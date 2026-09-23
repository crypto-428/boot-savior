#!/usr/bin/env python3
"""Removes the leftover Windows folders from the packed NTFS template.

The reference volume the template was captured from had been mounted by
Windows, so its root directory contained "$RECYCLE.BIN" and
"System Volume Information".  Every volume written from the template
inherited them.  This tool rewrites the packed image in place:

  * those two entries are deleted from the root directory index
    (the NTFS metadata entries in the root stay untouched)
  * the MFT records of those folders and their children are freed
  * the $MFT bitmap bits for the freed records are cleared

Usage: clean-ntfs-template.py <template.bin.gz>
"""
import gzip
import struct
import sys

RECORD = 1024
JUNK_RECORDS = (34, 35, 36, 37, 38)   # SVI, WPSettings.dat, $RECYCLE.BIN, SID, desktop.ini
ROOT_RECORD = 5


def u16(b, o):
    return struct.unpack_from('<H', b, o)[0]


def u32(b, o):
    return struct.unpack_from('<I', b, o)[0]


def u64(b, o):
    return struct.unpack_from('<Q', b, o)[0]


def unprotect(rec, bps):
    """Applies the update-sequence array, returning a plain record."""
    rec = bytearray(rec)
    off = u16(rec, 4)
    count = u16(rec, 6)
    for i in range(count - 1):
        end = (i + 1) * bps - 2
        rec[end:end + 2] = rec[off + 2 + i * 2:off + 4 + i * 2]
    return rec


def protect(rec, bps):
    """Stamps the update sequence number back into every sector end."""
    rec = bytearray(rec)
    off = u16(rec, 4)
    count = u16(rec, 6)
    usn = (u16(rec, off) + 1) & 0xFFFF
    if usn == 0:
        usn = 1
    struct.pack_into('<H', rec, off, usn)
    for i in range(count - 1):
        end = (i + 1) * bps - 2
        rec[off + 2 + i * 2:off + 4 + i * 2] = rec[end:end + 2]
        struct.pack_into('<H', rec, end, usn)
    return rec


def attributes(rec):
    out = []
    off = u16(rec, 20)
    while off + 8 <= len(rec):
        t = u32(rec, off)
        if t == 0xFFFFFFFF:
            break
        length = u32(rec, off + 4)
        if length <= 0 or off + length > len(rec):
            break
        out.append((t, off, length))
        off += length
    return out


JUNK_NAMES = ('$RECYCLE.BIN', 'System Volume Information')


def strip_index_block(block, bps):
    """Removes the junk directory entries from one INDX block."""
    b = unprotect(block, bps)
    entries_off = u32(b, 24)
    index_len = u32(b, 28)
    pos = 24 + entries_off
    kept = bytearray()
    removed = []
    while pos < 24 + index_len:
        length = u16(b, pos + 8)
        key_len = u16(b, pos + 10)
        flags = u16(b, pos + 12)
        if length == 0:
            break
        name = ''
        if key_len:
            nl = b[pos + 16 + 64]
            name = b[pos + 16 + 66:pos + 16 + 66 + nl * 2].decode('utf-16le')
        if name in JUNK_NAMES and not (flags & 0x02):
            removed.append(name)
        else:
            kept += b[pos:pos + length]
        pos += length
    if not removed:
        return None, removed
    out = bytearray(b)
    at = 24 + entries_off
    out[at:at + index_len - entries_off] = b'\0' * (index_len - entries_off)
    out[at:at + len(kept)] = kept
    struct.pack_into('<I', out, 28, entries_off + len(kept))
    return protect(out, bps), removed


def free_record(rec):
    struct.pack_into('<H', rec, 22, 0)                             # not in use
    first = u16(rec, 20)
    struct.pack_into('<I', rec, first, 0xFFFFFFFF)
    struct.pack_into('<I', rec, first + 4, 0)
    struct.pack_into('<I', rec, 24, first + 8)
    for i in range(first + 8, len(rec)):
        rec[i] = 0
    return rec


def decode_runs(attr):
    off = u16(attr, 32)
    runs = []
    lcn = 0
    pos = off
    while pos < len(attr):
        header = attr[pos]
        if header == 0:
            break
        lsz, osz = header & 0xF, header >> 4
        length = int.from_bytes(attr[pos + 1:pos + 1 + lsz], 'little')
        delta = int.from_bytes(attr[pos + 1 + lsz:pos + 1 + lsz + osz], 'little', signed=True)
        lcn += delta
        runs.append((lcn, length))
        pos += 1 + lsz + osz
    return runs


def main(path):
    raw = bytearray(gzip.open(path, 'rb').read())
    assert raw[:8] == b'NTFSTPL2', 'not an NTFSTPL2 template'
    bps = u16(raw, 8)
    spc = u16(raw, 10)
    mft_lcn = u64(raw, 20)
    run_count = u32(raw, 28)
    runs = []
    o = 32
    for _ in range(run_count):
        runs.append(struct.unpack_from('<QI', raw, o))
        o += 12
    cluster = bps * spc

    # map cluster -> offset inside the blob
    offsets = {}
    pos = o
    for lcn, length in runs:
        for i in range(length):
            offsets[lcn + i] = pos + i * cluster
        pos += length * cluster
    mft_off = offsets[mft_lcn]

    def read(i):
        return bytearray(raw[mft_off + i * RECORD:mft_off + (i + 1) * RECORD])

    def store(i, rec):
        raw[mft_off + i * RECORD:mft_off + (i + 1) * RECORD] = rec

    # 1. drop the junk entries from the root directory's index blocks
    root = unprotect(read(ROOT_RECORD), bps)
    alloc = None
    for t, off, length in attributes(root):
        if t == 0xA0:
            alloc = root[off:off + length]
    assert alloc is not None, 'the root directory has no $INDEX_ALLOCATION'
    removed = []
    for lcn, length in decode_runs(alloc):
        for i in range(length):
            at = offsets[lcn + i]
            block = raw[at:at + cluster]
            if block[:4] != b'INDX':
                continue
            patched, gone = strip_index_block(block, bps)
            if patched is not None:
                raw[at:at + cluster] = patched
                removed += gone

    # 2. free the leftover folders and their files
    for i in JUNK_RECORDS:
        rec = unprotect(read(i), bps)
        store(i, protect(free_record(rec), bps))

    # 3. clear their bits in the $MFT bitmap
    mft_rec = unprotect(read(0), bps)
    bitmap_attr = None
    for t, off, length in attributes(mft_rec):
        if t == 0xB0:
            bitmap_attr = mft_rec[off:off + length]
    assert bitmap_attr is not None, '$MFT has no $BITMAP'
    lcn = decode_runs(bitmap_attr)[0][0]
    bitmap_off = offsets[lcn]
    for i in JUNK_RECORDS:
        raw[bitmap_off + i // 8] &= ~(1 << (i % 8)) & 0xFF

    with gzip.open(path, 'wb', compresslevel=9) as out:
        out.write(bytes(raw))
    print(f'cleaned {path}: removed {removed}, freed MFT records {JUNK_RECORDS}')


if __name__ == '__main__':
    main(sys.argv[1])

#!/usr/bin/env python3
"""
Reference implementation of the size adapter that NtfsTemplate.kt ports to
Kotlin. Writes the packed NTFSTPL2 template into a volume of an arbitrary
sector count and recomputes the three size-dependent structures:

  * boot sector: total sectors, fresh serial, plus the backup copy at the end
  * $Bitmap (MFT record 6): one non-resident run sized for the real cluster count
  * $BadClus (MFT record 8): one sparse run covering the whole volume
  * $MFTMirr: the first four MFT records copied to cluster 2

Usage (produces a sparse raw image that can be checked with ntfsinfo/ntfsfix):
  python3 adapt-ntfs-template.py <template.bin.gz> <out.img> <sectors>
"""
import gzip
import os
import struct
import sys

SECTOR = 512
REC = 1024
BITMAP_REC = 6
BADCLUS_REC = 8
MIRR_LCN = 2


def load(path):
    with gzip.open(path, "rb") as g:
        b = g.read()
    if b[:8] != b"NTFSTPL2":
        raise SystemExit("not an NTFSTPL2 template")
    bps, spc, tot, mft = struct.unpack("<HHQQ", b[8:28])
    n = struct.unpack("<I", b[28:32])[0]
    runs, o = [], 32
    for _ in range(n):
        lcn, length = struct.unpack("<QI", b[o:o + 12])
        runs.append((lcn, length))
        o += 12
    return bps, spc, tot, mft, runs, b, o


def patch_len(rec, off, alen):
    struct.pack_into("<I", rec, off + 4, alen)


def nonresident_run(rec, attr_off, lcn, clusters, real_bytes):
    """Rewrites a non-resident attribute to hold exactly one run."""
    run_off = struct.unpack_from("<H", rec, attr_off + 32)[0]
    struct.pack_into("<Q", rec, attr_off + 16, 0)               # startVCN
    struct.pack_into("<Q", rec, attr_off + 24, clusters - 1)    # lastVCN
    struct.pack_into("<Q", rec, attr_off + 40, clusters * 4096) # allocated
    struct.pack_into("<Q", rec, attr_off + 48, real_bytes)      # real size
    struct.pack_into("<Q", rec, attr_off + 56, real_bytes)      # initialised
    run = bytearray()
    run.append(0x11 if clusters < 0x100 else (0x22 if clusters < 0x10000 else 0x44))
    lsize = run[0] & 0x0F
    run += clusters.to_bytes(lsize, "little")
    run += lcn.to_bytes(lsize, "little")
    run.append(0)
    end = attr_off + run_off + len(run)
    pad = (-end) % 8
    run += b"\x00" * pad
    rec[attr_off + run_off:attr_off + run_off + len(run)] = run
    patch_len(rec, attr_off, run_off + len(run))


def sparse_run(rec, attr_off, clusters, size_bytes):
    struct.pack_into("<Q", rec, attr_off + 16, 0)
    struct.pack_into("<Q", rec, attr_off + 24, clusters - 1)
    struct.pack_into("<Q", rec, attr_off + 40, clusters * 4096)
    struct.pack_into("<Q", rec, attr_off + 48, size_bytes)
    struct.pack_into("<Q", rec, attr_off + 56, 0)
    run_off = struct.unpack_from("<H", rec, attr_off + 32)[0]
    run = bytearray([0x04]) + clusters.to_bytes(4, "little") + b"\x00"
    run += b"\x00" * ((-(len(run))) % 8)
    rec[attr_off + run_off:attr_off + run_off + len(run)] = run
    patch_len(rec, attr_off, run_off + len(run))


def find_attr(rec, wanted):
    off = struct.unpack_from("<H", rec, 20)[0]
    while off + 8 < len(rec):
        atype = struct.unpack_from("<I", rec, off)[0]
        if atype == 0xFFFFFFFF:
            return None
        alen = struct.unpack_from("<I", rec, off + 4)[0]
        if alen <= 0:
            return None
        if atype == wanted:
            return off
        off += alen
    return None


def build(template, out, part_sectors):
    bps, spc, tot, mft_lcn, runs, blob, data_off = load(template)
    cluster = bps * spc
    spr = cluster // bps
    clusters = (part_sectors - 1) // spr   # the last sector holds the boot backup

    f = open(out, "wb")
    f.truncate(part_sectors * SECTOR)

    def w(lba, data):
        f.seek(lba * SECTOR)
        f.write(data)

    # 1. Metadata clusters, verbatim.
    o = data_off
    mft_bytes = None
    for lcn, length in runs:
        chunk = blob[o:o + length * cluster]
        o += length * cluster
        if lcn == mft_lcn:
            mft_bytes = bytearray(chunk)
        w(lcn * spr, chunk)

    # 2. Boot sector: real size + fresh serial, main copy and backup.
    boot = bytearray(blob[data_off:data_off + bps]) if runs[0][0] == 0 else None
    f.seek(0)
    boot = bytearray(open(out, "rb+").read(bps)) if boot is None else boot
    struct.pack_into("<Q", boot, 40, part_sectors - 1)
    serial = int.from_bytes(os.urandom(8), "little")
    struct.pack_into("<Q", boot, 72, serial)
    struct.pack_into("<Q", boot, 48, mft_lcn)
    struct.pack_into("<Q", boot, 56, MIRR_LCN)
    w(0, boot)
    w(part_sectors - 1, boot)

    # 3. $Bitmap and $BadClus in the MFT.
    bitmap_bytes = (clusters + 7) // 8
    bitmap_clusters = (bitmap_bytes + cluster - 1) // cluster
    bitmap_lcn = clusters - bitmap_clusters       # tail of the volume, always free
    rec = bytearray(mft_bytes[BITMAP_REC * REC:(BITMAP_REC + 1) * REC])
    a = find_attr(rec, 0x80)
    nonresident_run(rec, a, bitmap_lcn, bitmap_clusters, bitmap_bytes)
    mft_bytes[BITMAP_REC * REC:(BITMAP_REC + 1) * REC] = rec

    rec = bytearray(mft_bytes[BADCLUS_REC * REC:(BADCLUS_REC + 1) * REC])
    a = find_attr(rec, 0x80)
    sparse_run(rec, a, clusters, clusters * cluster)
    mft_bytes[BADCLUS_REC * REC:(BADCLUS_REC + 1) * REC] = rec

    w(mft_lcn * spr, mft_bytes)
    # 4. $MFTMirr: first four records.
    w(MIRR_LCN * spr, mft_bytes[:4 * REC])

    # 5. Cluster allocation bitmap: reserved template area + the bitmap itself.
    highest = max(lcn + length for lcn, length in runs)
    bits = bytearray(bitmap_clusters * cluster)
    def mark(c):
        bits[c >> 3] |= 1 << (c & 7)
    for lcn, length in runs:
        for c in range(lcn, lcn + length):
            mark(c)
    for c in range(mft_lcn, mft_lcn + 64):
        mark(c)
    for c in range(bitmap_lcn, bitmap_lcn + bitmap_clusters):
        mark(c)
    for c in range(clusters, bitmap_bytes * 8):
        mark(c)   # padding bits past the end of the volume must read as used
    w(bitmap_lcn * spr, bits)
    f.close()
    print(f"sectors={part_sectors} clusters={clusters} bitmap_lcn={bitmap_lcn} "
          f"bitmap_clusters={bitmap_clusters} highest_meta={highest}")


if __name__ == "__main__":
    build(sys.argv[1], sys.argv[2], int(sys.argv[3]))

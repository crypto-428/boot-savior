#!/usr/bin/env python3
"""
Packs a real Windows/mkntfs-formatted NTFS volume (inside a fixed VHD) into the
compact NTFSTPL2 template that NtfsTemplate.kt writes onto a drive.

Only the clusters that actually carry NTFS metadata are kept, so a 7 MiB volume
becomes a ~150 KB gzip blob. The size-dependent structures ($Bitmap contents,
$BadClus run, boot sector total-sectors/serial) are deliberately NOT stored:
they are recomputed for the real partition size at write time.

Usage:
  python3 pack-ntfs-template.py <disk.vhd> <out.bin.gz>
"""
import gzip
import struct
import sys

SECTOR = 512
REC = 1024  # MFT record size for 4 KiB clusters (2^10 via clustersPerMftRecord = -10)


def le(b, o, n):
    return int.from_bytes(b[o:o + n], "little")


def part_start(disk):
    with open(disk, "rb") as f:
        mbr = f.read(SECTOR)
    for slot in range(4):
        e = mbr[446 + slot * 16:446 + slot * 16 + 16]
        if e[4] in (0x07, 0x17):
            return le(e, 8, 4), le(e, 12, 4)
    raise SystemExit("no NTFS MBR partition found")


def runs_of(rec, cluster_bytes):
    """Yields (lcn, length) for every mapped, non-sparse run in the record."""
    off = le(rec, 20, 2)
    out = []
    while off + 8 < len(rec):
        atype = le(rec, off, 4)
        if atype == 0xFFFFFFFF:
            break
        alen = le(rec, off + 4, 4)
        if alen <= 0:
            break
        if rec[off + 8] == 1:  # non-resident
            rl = off + le(rec, off + 32, 2)
            lcn = 0
            while rl < len(rec) and rec[rl] != 0:
                header = rec[rl]
                lsize, osize = header & 0x0F, header >> 4
                length = le(rec, rl + 1, lsize)
                if osize:
                    delta = le(rec, rl + 1 + lsize, osize)
                    if delta >= (1 << (8 * osize - 1)):
                        delta -= 1 << (8 * osize)
                    lcn += delta
                    out.append((lcn, length))
                # osize == 0 means a sparse run: no clusters to copy
                rl += 1 + lsize + osize
        off += alen
    return out


def main():
    disk, out = sys.argv[1], sys.argv[2]
    start, _ = part_start(disk)
    f = open(disk, "rb")

    def read_sectors(lba, count):
        f.seek((start + lba) * SECTOR)
        return f.read(count * SECTOR)

    boot = read_sectors(0, 1)
    if boot[3:11] != b"NTFS    ":
        raise SystemExit("partition is not NTFS")
    bps = le(boot, 11, 2)
    spc = boot[13]
    cluster = bps * spc
    tot = le(boot, 40, 8)
    mft_lcn = le(boot, 48, 8)
    spr = cluster // bps

    reserved = [(0, 1)]  # $Boot's first cluster region is covered by the runs below
    keep = set()

    # Walk the 16 system records plus a few user records that mkntfs/Windows writes.
    for idx in range(27):
        f.seek((start + mft_lcn * spr) * SECTOR + idx * REC)
        rec = f.read(REC)
        if rec[0:4] != b"FILE":
            continue
        for lcn, length in runs_of(rec, cluster):
            # $Bitmap (record 6) contents depend on the volume size: skip it.
            if idx == 6:
                continue
            for c in range(lcn, lcn + length):
                keep.add(c)

    # $Boot needs its first 8 KiB (the boot code) verbatim.
    keep.update(range(0, 2))
    # The $MFT zone itself.
    keep.update(range(mft_lcn, mft_lcn + 64))

    clusters = sorted(keep)
    # Coalesce into runs.
    data_runs = []
    for c in clusters:
        if data_runs and data_runs[-1][0] + data_runs[-1][1] == c:
            data_runs[-1][1] += 1
        else:
            data_runs.append([c, 1])

    body = bytearray()
    body += b"NTFSTPL2"
    body += struct.pack("<HHQQ", bps, spc, tot, mft_lcn)
    body += struct.pack("<I", len(data_runs))
    for lcn, length in data_runs:
        body += struct.pack("<QI", lcn, length)
    for lcn, length in data_runs:
        f.seek((start + lcn * spr) * SECTOR)
        body += f.read(length * cluster)

    with gzip.open(out, "wb", compresslevel=9) as g:
        g.write(bytes(body))

    highest = clusters[-1]
    print(f"bps={bps} spc={spc} tot={tot} mft={mft_lcn}")
    print(f"runs={len(data_runs)} clusters={len(clusters)} highest={highest}")
    print(f"raw={len(body)} bytes -> packed {__import__('os').path.getsize(out)} bytes")


if __name__ == "__main__":
    main()

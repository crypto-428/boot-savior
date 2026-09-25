#!/usr/bin/env bash
# Runs the partition-repair unit tests without the Android SDK.
#
# The repair engine only needs org.json plus the JDK, so the tests can be
# compiled and run directly with kotlinc/java. The single Android dependency
# (PartitionRepair.withDrive and its Context/UsbManager wrappers) is stripped
# out of a temporary copy, and UsbBulkStorageDevice is replaced with a stub.
#
# Usage:  bash USBooter_2.0/tools/run-repair-tests.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/app/src/main/java/com/yourapp/USBooter/util"
TEST="$ROOT/app/src/test/java/com/yourapp/USBooter/util"
WORK="${WORK:-/tmp/usbooter-repair-tests}"
LIBS="$WORK/libs"

mkdir -p "$LIBS" "$WORK/src"
rm -f "$WORK"/src/*.kt

fetch() { [ -f "$LIBS/$(basename "$1")" ] || curl -sSL -o "$LIBS/$(basename "$1")" "$1"; }
fetch https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
fetch https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
fetch https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar

for f in BlockDevice BlockWriter NtfsFormatter Mbr Gpt Fat32Formatter Fat32Writer \
         ExfatFormatter ExfatWriter PartitionConfig LayoutMath FormatError \
         MbrBootCode NtfsCapability NtfsTemplate NtfsResize FatShrink FatLegacyFormatter LinuxFs Ext2Formatter; do
  cp "$MAIN/$f.kt" "$WORK/src/"
done
for f in DriveImages FakeBlockDevice NtfsRebuildTest PartitionRepairTest \
         RepairTranslationCoverageTest PartitionManagerGptTest NtfsShrinkTest FatShrinkTest ExfatShrinkTest LinuxFsTest FatLegacyTest; do
  cp "$TEST/$f.kt" "$WORK/src/"
done

# Strip the Android-only surface of PartitionRepair.kt: the imports, the
# Context-based scan/repair/destructiveRebuild wrappers and withDrive itself.
python3 - "$MAIN/PartitionRepair.kt" "$WORK/src/PartitionRepair.kt" <<'PY'
import re, sys
src, dst = sys.argv[1], sys.argv[2]
lines = open(src).read().split('\n')
out, depth, skipping = [], 0, False
for line in lines:
    if not skipping and re.match(r'\s*(fun (scan|repair|destructiveRebuild)\(|internal fun withDrive\()', line):
        nxt = line
        skipping, depth = True, 0
    if skipping:
        depth += line.count('(') + line.count('{') - line.count(')') - line.count('}')
        if depth <= 0 and (line.rstrip().endswith('}') or line.rstrip().endswith(')')):
            skipping = False
        continue
    if line.startswith('import android'):
        continue
    out.append(line)
open(dst, 'w').write('\n'.join(out))
PY

# PartitionManager without its Context-based wrappers.
python3 - "$MAIN/PartitionManager.kt" "$WORK/src/PartitionManager.kt" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
a = s.index('    fun list(context'); b = s.index('    /** Moving a partition')
s = s[:a] + s[b:]
s = s.replace('import android.content.Context\n', '')
open(sys.argv[2], 'w').write(s)
PY

cat > "$WORK/src/UsbStub.kt" <<'KT'
package com.yourapp.USBooter.util

/** Test-harness stand-in for the Android USB drive class. */
class UsbBulkStorageDevice : BlockDevice {
    override val blockSize: Int get() = 512
    override val totalBlocks: Long get() = 0
    override fun readBlocks(lba: Long, count: Int): ByteArray = ByteArray(count * blockSize)
    override fun writeBlocks(lba: Long, data: ByteArray) {}
    override fun synchronizeCache() {}
    fun close() {}
}

fun UsbBulkStorageDevice.writeZeroBlocks(lba: Long, count: Int) {}
KT

JARS="$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:$LIBS/json-20240303.jar"
# The packed NTFS template is loaded as a classpath resource.
RES="$ROOT/app/src/main/resources"
kotlinc -nowarn -cp "$JARS" "$WORK"/src/*.kt -d "$WORK/out"
STDLIB="$(dirname "$(readlink -f "$(command -v kotlinc)")")/../lib/kotlin-stdlib.jar"
# The translation-coverage test locates assets/i18n.js relative to the CWD.
cd "$ROOT/.."
java -cp "$WORK/out:$RES:$JARS:$STDLIB" org.junit.runner.JUnitCore \
  com.yourapp.USBooter.util.PartitionRepairTest \
  com.yourapp.USBooter.util.NtfsRebuildTest \
  com.yourapp.USBooter.util.RepairTranslationCoverageTest \
  com.yourapp.USBooter.util.PartitionManagerGptTest \
  com.yourapp.USBooter.util.NtfsShrinkTest \
  com.yourapp.USBooter.util.FatShrinkTest \
  com.yourapp.USBooter.util.ExfatShrinkTest \
  com.yourapp.USBooter.util.FatLegacyTest \
  com.yourapp.USBooter.util.LinuxFsTest

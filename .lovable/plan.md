# Restore reliable NTFS partition rebuilding

## Goal
Make the repairer rebuild a corrupted NTFS partition at its existing full partition-table size, using the same NTFS formatter path that previously produced a mountable small NTFS volume, without recreating the erroneous FAT/exFAT/extra NTFS entries.

## Changes
- Replace the current broad “repair everything” destructive action with a targeted NTFS rebuild tied to a specific detected partition.
- Preserve the selected partition’s start sector and full table length; format only that range with the existing `NtfsFormatter`, then restore the matching NTFS table type.
- Return the target partition identity in repair findings and let the interface invoke only that destructive finding, rather than also applying unrelated risky repairs.
- Re-scan after rebuilding and report success only if the new NTFS boot sector, backup sector, and file-table records validate.
- Keep safe repairs non-destructive and prevent full-scan filesystem traces from creating extra partitions during an NTFS rebuild.
- Finish all five-language text for the targeted rebuild and ensure errors remain visible.
- Add regression tests proving one corrupted partition becomes one full-size NTFS partition, no extra entries are created, destructive rebuilding requires explicit approval, and failed verification is reported.
- Compile and run the focused Android unit tests, fixing any current `PartitionRepair.kt` or partition-editor compilation failures encountered.

## Safety behavior
The targeted rebuild creates a new empty NTFS filesystem and erases files only inside the chosen corrupted partition. It does not resize that partition or alter other partition entries.

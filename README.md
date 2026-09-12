# Boot Savior

code i made with lovable, there is some work to finish. Please upload the whole code so i download it when i finish instead of packaging a zip.
Here is what i asked:

Please make sure all text here is translated. Also correcr UI so that error messages are visible (check example).
After that, consider this text:
The Kotlin engine generates a large amount of user-visible prose that the web layer only translates for 14 known progress strings via NATIVE_KEYS. Everything else falls through in English:
ISO analysis notes/warnings from BootConfig.kt (mode recommendations, FAT32 4 GB explanations, hybrid-MBR advice)
Preflight and verification check names/details from BootFileVerifier.kt, Preflight.kt
Progress/diagnostic lines from BootableWriter.kt, CopyDiagnostics.kt
That's roughly 600 English strings living in Kotlin. Two ways to close it: expand NATIVE_KEYS to cover the messages that actually reach the UI (maybe 60-100 strings, no Kotlin changes), or refactor Kotlin to emit stable message codes + parameters that the web layer translates properly (cleaner, but touches the engine). Tell me which and I'll do it.
Choose what you think is best, and even better chooses what reduces complexity and space occupied
Also correct the automatic program so that it recognizes the iso and chooses the correct method. Add a partition repair function that repairs partitions possibly without losing files, otherwise just partition repair warning for potential loss of files.
Instead of repackaging the zip just upload and modify the whole code, so after you finish i can download the codebase. Keep going from where you left with previous job.

Here is what lovable did and remains to end:

I had to stop partway through — here's where things stand and what's left.
Decision (your question): expand the web-layer translation table, not refactor Kotlin. Reason: message codes would touch every engine file and add a parameter-encoding layer for no user-visible gain, while the web layer already funnels every native string through I18N.tNative() (ISO warnings, preflight/verify check names and details, progress lines, diagnostics). So the whole gap closes in one file.
To keep it small I designed it as pattern rules instead of ~600 literal strings:
NATIVE_RULES: [regex, {it, es, pt, zh}], with {1}, {2}… for captured numbers/paths/labels.
No English entries at all — tNative() returns the source text unchanged when the language is English, so the table is 4 languages wide instead of 5, and ~120 patterns cover the ~600 strings because values are captured rather than duplicated.
Done so far (drafted, not yet merged into the app): rule blocks for copy diagnostics, ISO analysis notes / mode warnings / FAT32 4 GB explanations / hybrid-MBR advice, all preflight check names, details and progress lines, and boot-file verification messages.
Remaining work:
Third rule block: FormatEngine/FormatService progress and error text, WindowsMediaVerifier messages, WebAppInterface responses, IoMonitor read/write/verified/failed lines.
Splice the blocks into assets/i18n.js and update tNative() to try NATIVE_RULES after the existing NATIVE_KEYS exact/prefix matches (keeping NATIVE_KEYS and the Error: prefix fallback).
UI fix for the error card (your screenshot): the modal has no max-height/scroll, so long failure reports overflow off-screen and the buttons become unreachable, and .modal { text-align:center } centres the bullet lists and makes them hard to read. Fix: max-height:88vh; overflow-y:auto; overscroll-behavior:contain on .modal, plus left-aligned body text and lists for the error/technical report.
I had to stop partway — here's the state of play.
Done this turn
Re-extracted the project and re-read the pieces that matter (i18n.js, index.html, BootConfig.kt, ImageProfile.kt, DriveInspector.kt, WebAppInterface.kt, Mbr.kt/Gpt.kt, UsbBulkStorageDevice.kt).
Wrote the new util/PartitionRepair.kt — a full non-destructive repair engine:
scan-only and apply modes, each finding tagged safe vs risky plus a plain-language reason;
GPT: header CRC validation, restore primary from backup or rebuild backup from primary, rewrite a missing 0xEE protective MBR;
MBR: missing active flag, partition type byte that disagrees with the filesystem actually present, table rebuilt around a filesystem found at a usual offset (2048/63/1/34/8192/4096) when the table is gone;
FAT32: restore boot sector from the backup at +6, rebuild the mirror FAT from the primary;
exFAT: restore the 12-sector boot region from the backup;
NTFS: restore the boot sector from the copy at the end of the partition;
anything with no spare copy on disk is reported as risky/unrepairable with an explicit "files will be lost if you reformat" message, never applied unless allowRisky = true.
Remaining (not yet written)
BootConfig.resolveMode: honour profileMode from ImageProfile (currently ignored), so AUTO picks Windows-split, Universal, raw-clone-only or UEFI-copy per detected image family, with feasibility fallbacks (canExtract → Universal, isHybrid → raw clone, EFI-only → file copy).
WebAppInterface: scanPartitions(deviceName) / repairPartitions(deviceName, allowRisky) running on a thread, posting onRepairScan(...), onRepairProgress(...), onRepairResult(...), guarded by FormatService.isRunning.
index.html: repair card + buttons and result rendering with the loss-of-files confirmation for risky repairs; plus the error-modal fix (max-height:88vh; overflow-y:auto; overscroll-behavior:contain, left-aligned error body and diagnostic lists).
i18n.js: the NATIVE_RULES pattern table (it/es/pt/zh, {1}-style captures) wired into tNative() after NATIVE_KEYS, covering the ~600 engine strings, plus new UI keys for the repair screen.
Repackage the zip into /mnt/documents and sanity-check the rules against sample engine strings.
Nothing has been repackaged yet, so the uploaded zip is unchanged; the new Kotlin file lives only in my working copy.
Repackage as a zip in /mnt/documents and verify the rules against sample engine strings.
Here's where things stand — the code now lives in the project at USBooter_2.0/, so it's part of the downloadable codebase (no zip repackaging).
Done this turn
Extracted the full app source into USBooter_2.0/ in the project.
util/BootConfig.kt → resolveMode now honours profileMode from ImageProfile: AUTO picks the mode the detected image family boots best with (Windows installer → Universal/split, rescue suites and WinPE → Universal, firmware tools → their recommendation), then a feasible() step downgrades anything the image can't support (no extractable tree → raw clone if isohybrid, else EFI file copy; raw clone only when a real isohybrid MBR exists; EFI copy only when an EFI loader is present).
Re-created util/PartitionRepair.kt — non-destructive scan + repair: GPT header CRC validation and restore from the backup (or rebuild the backup), missing protective MBR, missing active flag, wrong MBR type byte, MBR rebuilt around a filesystem found at a usual offset (flagged risky), FAT32 boot-sector restore from +6 and FAT mirror resync, exFAT boot-region restore and boot-checksum recompute, NTFS boot-sector restore from the end-of-partition copy. Every finding is tagged safe / risky / info with a plain-language reason; risky ones are applied only with allowRisky = true, and unrecoverable cases say explicitly that files will be lost if reformatted.
Remaining
WebAppInterface.kt: scanPartitions(deviceName) / repairPartitions(deviceName, allowRisky) on a background thread, posting onRepairScan / onRepairProgress / onRepairResult, refused while FormatService.isRunning.
assets/index.html: repair card + buttons and findings rendering with the risky-repair confirmation; plus the error-modal fix (max-height:88vh; overflow-y:auto; overscroll-behavior:contain, left-aligned error body and diagnostics list).
assets/i18n.js: the NATIVE_RULES regex/template table (it/es/pt/zh, {1}-style captures) wired into tNative() after NATIVE_KEYS, covering the ~600 engine strings, plus new UI keys for the repair screen.
Credits ran out mid-way — say the word when you're topped up and I'll pick up at the WebAppInterface hooks.

Please finish the job

This project was built with [Lovable](https://lovable.dev).

## Build with Lovable

Continue developing this project in the [Lovable editor](https://lovable.dev/projects/8b7cc44d-d682-444f-a0f7-e7c0dcd3cf15).

- **Ship faster**: describe what you want to build and Lovable handles the code.
- **Stay in sync**: every change made in Lovable is committed straight to this repository.
- **Full ownership**: this code is yours. Push to `main` on GitHub and your changes sync back into Lovable, ready for your next prompt.

## Development

Prefer working locally? You need Node.js and npm — [install with nvm](https://github.com/nvm-sh/nvm#installing-and-updating).

```sh
git clone <this-repository-url>
cd <repository-name>
npm i
npm run dev
```

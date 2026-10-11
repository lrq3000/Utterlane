# JVM deletion investigation — 2026-10-11

**Final verification: 648 passed, 0 failed, 0 skipped. The intermittent failure's
exact root cause remains unresolved; this is not a production-fix claim.**

Worktree: `C:\git\TranSlander\.worktrees\progressive-archival`.
This investigation supplements `progressive-compression-native-2026-10-11.md`.
Only JVM tests and host diagnostics were run. No APK build/install, adb operation,
device change, commit, push, cache clean, test retry mechanism, or scanner setting
change was performed.

## Reproduction and verification

All Gradle runs used the normal temporary directory and these options:

```powershell
.\gradlew.bat :app:testDebugUnitTest "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

| Run | Result |
|---|---|
| Initial `--tests "io.github.lrq3000.utterlane.archive.RollingHistoryTest"` | 4 passed |
| Archive/history suites plus `ImportedAudioCopyTest` | 235 tests, 2 failures |
| RollingHistory + ManagedHistory after diagnostic assertion change | 17 passed |
| First full run | 647/648 passed; `HomeCaptureCompletionTest.allocatedIdDeletedAfterRecorderOpenFailureCannotReleasePriorUnsavedWork`, line 20 |
| Final full run after making the diagnostic snapshot preserve immediate assertion timing | **648/648 passed**, 0 skipped, 29.740 s test time, Gradle successful in 32 s |

The 235-test run reproduced:

- `RollingHistoryTest.badOptionalCodecMetadataCannotStrandTheCaptureOwnerAtFinish`,
  original line 19: directory still exists immediately after `history.delete`.
- `ManagedHistoryTest.archival journal failure cannot undo a complete text history publication`,
  line 136: fixture `root.deleteRecursively()` returned false before fault injection.

The first full-run failure was another deletion boundary: empty uncompressed
capture finalization left the deferred entry addressable by `history.get`, so its
existing `assertThrows` failed. It does not exercise AAC or rolling metadata.

Final evidence is in `app/build/reports/tests/testDebugUnitTest/index.html` and
`app/build/test-results/testDebugUnitTest/`. The final RollingHistory XML has
timestamp `2026-10-11T02:48:06.059Z`, 4 tests, 0 failures/errors/skips. Later runs
overwrite these generated reports; the earlier failures above were read before
they were overwritten.

## Resource ownership checked

- The named rolling-history fixture constructs `RecordingHistory` **without an
  `ArchivalJobStore`**, starts no worker, and opens no history reader. Job-store
  directory cursors therefore cannot own this fixture's handles.
- `RollingPcmJournal.closeCapture()` calls the WAV writer's `close`, clears its
  reference in `finally`, then publishes the closed capture metadata.
  `WavFile.close()` closes its `RandomAccessFile` in `finally` even if header/sync
  fails. The successful source-count assertion precedes deletion in the failure.
- `Recording.finish` removes the active owner and releases its capture lease in
  its metadata-save `finally`. The test now explicitly asserts that no lease
  remains before deletion.
- `HistoryMetadata.read` and `ArchiveDisk.read/write` scope input/output streams
  with `use`; the synced output closes before atomic rename. `AudioMetadata`
  does not open a directory `FileChannel` on Windows. No mapped-file use was
  found in these source paths.
- `ArchiveDisk.visit`/`first` close cursors with `use`. The job store intentionally
  retains bounded maintenance cursors and closes them on exhaustion, store close,
  and `changing`'s exception path. Some test fixtures omit final store closure;
  that is not evidence that they caused these failures in separate temporary
  directories. The failing ManagedHistory fault-injection case only initializes
  its journal and has not opened persistent maintenance cursors.
- `RecordingHistory.remove` retains the deferred marker/index if physical
  recursive deletion returns false. This explains why the Home test can observe
  an entry after attempted deletion; removing its index anyway would conceal
  failed cleanup and is not an appropriate fix.

## App-independent reproducer

Local standalone diagnostic:

```powershell
java.exe C:\Users\33632\AppData\Local\Temp\opencode\MetadataIoProbe.java
```

Runtime: Oracle Java 21.0.5+9-LTS-239, 64-bit Windows HotSpot. This program loads
no Utterlane or Gradle classes. For each fresh directory it performs five
closed-scope `FileOutputStream` writes of a small `Properties` record, `fd.sync`,
atomic replacement, and closed-scope `Files.newInputStream` reads. It then deletes
the file and its parent. It stops at the first error; it does not retry deletion.

Two diagnostic executions failed:

```text
pid=7924
root=C:\Users\33632\AppData\Local\Temp\utterlane-metadata-probe-15647496974459959923
FAIL elapsedMs=2381 ...\entry-194
java.nio.file.DirectoryNotEmptyException
  at sun.nio.fs.WindowsFileSystemProvider.implDelete(WindowsFileSystemProvider.java:270)
  at sun.nio.fs.AbstractFileSystemProvider.delete(AbstractFileSystemProvider.java:104)
  at java.nio.file.Files.delete(Files.java:1152)
  at MetadataIoProbe.main(MetadataIoProbe.java:31)

pid=40340
root=C:\Users\33632\AppData\Local\Temp\opencode\utterlane-metadata-probe-16527672622813845247
FAIL elapsedMs=12618 ...\entry-940
same DirectoryNotEmptyException at parent-directory deletion
```

In each case the immediately preceding `Files.delete(recording.properties)` had
returned successfully. The source has no retained stream, directory stream,
mapping, background task or retry. The second execution changed only the probe's
temporary parent to the approved `opencode` diagnostics directory. A subsequent
`handle64.exe -nobanner <probe-root>` snapshot found no matching handles. That
later snapshot **cannot rule out a short-lived external handle at failure time**.

This establishes that an analogous failure is reproducible outside the app. It
does **not** establish the responsible process or prove all scattered historical
`AccessDeniedException` failures have the same cause.

## Counter-evidence and remaining blocker

Direct-Win32 comparisons were also made, using explicit `CreateFileW`,
`WriteFile`, `FlushFileBuffers`, successful `CloseHandle`, `MoveFileExW`,
`ReadFile`, `DeleteFileW`, and `RemoveDirectoryW`, with no outstanding owned file
handles at deletion:

- Python/ctypes: 1000 lifecycles passed (13.485 s); an earlier 10000-lifecycle
  version without reads hit the diagnostic command's 120-second timeout and
  supplies no completion result.
- C#/PInvoke: 5000 lifecycles passed (89.937 s).
- Java 21 preview FFM direct Win32 calls: 5000 lifecycles passed (54.076 s).
- FFM with actual `Properties.store` bytes: 5000 passed (52.396 s).

These are timing/API comparisons, not replacements for app tests and not proof
that Win32 or a filesystem filter is innocent. They prevent overclaiming a
uniform kernel failure or naming antivirus from the Java failure alone. Scripts
are local to `C:\Users\33632\AppData\Local\Temp\opencode`:
`metadata_io_probe.py`, `NativeMetadataProbe.cs`, `NativeJavaMetadataProbe.java`.

**Exact ownership remains the blocker.** This shell is not elevated; `fltmc
filters` returned access denied (`0x80070005`). An elevated, narrowly filtered
Process Monitor capture of the standalone Java reproducer is the next useful
step: correlate create/share modes, rename, cleanup/close and failed parent
deletion with process identity and filesystem-filter stacks. No such capture was
obtained here. Installed/running security software is not sufficient attribution.

## Changes from this investigation

- `RollingHistoryTest.kt`: stronger capture-lease assertion, plus remaining-path
  diagnostics. The immediate existence result is captured **before** enumerating
  diagnostic filenames, so diagnostic work cannot turn a failing observation
  into a pass. No sleeps, retries, weakened assertions or skipped cases.
- This evidence note. Production behavior is unchanged.

The final full-suite green is verified, but repeatability and the exact
host/JDK/filesystem cause are still open. There is no demonstrated production
resource-leak fix or red/green fix regression to claim.

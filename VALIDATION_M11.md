# Milestone 11 — Portable Project Export

Version **0.11.0**, versionCode **13**. Work is uncommitted on
`feature/portable-project-export`, based on **e028763**, the verified 0.10.1
video-framing correction. No reset, force switch, cherry-pick, commit or push.
Validation date: **4 November 2026**. Device: Moto G54 5G `ZY22KJQLTV`, API 35.

## Recovery and scope

The interrupted branch already pointed to the framing-fix commit and contained no
partial M11 implementation. It was resumed in place. README, M10 validation,
framing validation, recording/session codecs, export journals, Gallery and player
were inspected first. M11 adds a separate streaming archive layer. No camera
binding, preview geometry, orientation policy, encoder, Transformer composition,
MP4 publication algorithm or **100 ms A/V verification limit** was changed.
CameraX 1.5.3, Media3 1.11.1, min SDK 26 and compile/target 36 remain unchanged.
No production dependency or permission was added.

## User workflow

1. Finish a recording normally. STOP still assembles/verifies/publishes the MP4,
   returns automatically to the live camera and updates Gallery.
2. In Gallery select that completed SkitCam video. Its player has **Video options
   → Export Project** only when one matching valid completed session is available.
3. Finished MP4 is mandatory. Kept originals default On; discarded originals
   default Off and require explicit consent. Originals contain whole takes,
   including trimmed-out/private footage. Excluding originals still preserves
   non-content edit metadata for all segment IDs.
4. Choose the ZIP destination using Android's system create-document picker.
   A local provider keeps it offline; any cloud-backed destination is an explicit
   user/provider choice. SkitCam has no network permission or account integration.
5. The copy job shows phase/processed bytes and cancellation. Back returns to the
   camera; its notification or Gallery's **Project exports** opens the latest job. A final
   local success/failure notification is posted when notifications are allowed;
   each notification opens its own stable job identity.
6. Success follows full readback, final rename and a final hash check. Open archive
   delegates to a compatible installed viewer. Locate saved archive verifies the
   selected file before updating the receipt. Export another copy is explicit.

Ordinary photography/filming does not display a project-export screen. Archive
failure/cancellation does not change the automatic MP4 result or session metadata.

## Architecture and preservation

- `ProjectSnapshot` validates a frozen copy of an already completed M6 journal:
  session identity, state, saved playback order, retained cap, integer trim bounds,
  retained source hashes and verified finished-video hash. A later session/UI edit
  cannot mutate that snapshot.
- `CompletedProjectCatalog` reads existing committed journal bytes without
  `AtomicFile.openRead`, which could otherwise recover/rewrite an old file. A
  legacy `.bak` is treated as committed when present. Matching uses the actual
  MediaStore volume/video ID. Invalid/unrelated sessions are not offered.
- Selected original paths are canonicalised beneath the exact session directory.
  Missing/unreadable/empty originals, absent audio/video samples, invalid media
  duration, changed retained-source hashes and stale explicit trims reject export
  with the recording segment number. The finished MP4 must match M6's verified
  SHA-256; an unchanged private verified candidate can supply it if the gallery
  URI is inaccessible. No broken selected source is silently omitted.
- Finished and retained-source hashes reuse existing successful M6 evidence.
  Discarded originals do not have an old M6 export hash: their current readable
  container/tracks/duration and newly computed hash are checked, then identical
  bytes must reach the archive. This does not prove every discarded video frame
  can decode or establish when historic corruption occurred.
- `ProjectJobs` uses its own AtomicFile journal under
  `files/project-exports/<job-id>/job.json`. It never writes old session/export
  metadata. Jobs store options, frozen snapshot, destination, attempt count,
  phase, entry hashes/lengths/CRCs and whole-archive receipt.
- `ProjectExportService` is an independent user-started **dataSync** foreground
  service. No WorkManager/new scheduler dependency. It owns no camera, player,
  encoder or microphone. A single background worker, bounded buffers and timed
  partial wake lock support Activity backgrounding. Android timeout handling
  retains recoverable job state; there is no claimed automatic ZIP-position resume.
- Read-only runtime hints pause archive checkpoints while capture commands,
  active recording/finalisation, photo saving or automatic MP4 export are busy.
  Normal START/PAUSE/RESUME/STOP engine transitions and export busy state are not
  modified. An in-flight provider I/O call is not pre-empted; checkpoints yield
  between bounded reads/writes. Provider stalls can still delay cancellation.

Official platform contracts consulted:
[SAF documents](https://developer.android.com/training/data-storage/shared/documents-files),
[foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout),
[DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract).
Installed API 36 DocumentsProvider source was also inspected during fault-provider
validation; its unchanged-ID rename contract requires a null provider return.
API 26 libcore ZipOutputStream source was checked for ZIP64 support.

## Portable archive schema 1

```text
Finished_Video.mp4
Original_Segments/segment_001.mp4
Original_Segments/segment_003.mp4
session.json
checksums.sha256
```

Original entry filenames follow **recording chronology**, with gaps when omitted.
They are generated ASCII relative paths, never source filenames/absolute paths.
No traversal, absolute paths, duplicate entries or unsafe separators are allowed.
The outer filename may be Unicode and includes the unique job ID. Its UTF-8
length is bounded without splitting code points; Windows-reserved punctuation
and control characters are removed before writing.

`session.json` is a whitelist conversion, not the raw Android session JSON:

| Field | Meaning |
|---|---|
| `format`, `schemaVersion` | `skitcam-portable-project`, 1 |
| `sessionId`, `exportId`, `createdAtMs` | Stable recording/export identity and time |
| `sessionOrientationDegrees`, `lens`, `captureProfile` | Orientation and non-identifying profile facts/settings |
| `finishedVideo` | Relative MP4 path and verified SHA-256 |
| `includeOriginals`, `includeDiscardedOriginals` | Explicit archive options |
| `playbackOrder` | Retained stable IDs in saved user order |
| `segments` | All saved IDs, recording order, Keep/Discard, durations, source facts, orientation/track facts, optional relative original path/hash |
| `segments[].savedTrim` | Exact saved sourceDuration/start/end, or null for legacy unknown |
| `finishedTimeline[].resolvedRange` | Final assembly's resolved duration/start/end in playback order |

All times are integer **milliseconds**; start inclusive, end exclusive. Source
recorded duration, authoritative saved trim and resolved export range remain
separate. Null means unknown/omitted, never a fabricated duration. Legacy session
schemas supported by the existing codec and M6 journal versions 1/2 are read without
migration writes. Discard/restore and arrangement decisions remain independent of
original naming. Camera IDs, Android private paths and content URIs are omitted.
No account identifiers or unnecessary device data are copied.

`checksums.sha256` uses conventional `hex  relative/path` UTF-8 lines. It covers
every media entry and session.json, not itself. Its own bytes are covered by the
persisted whole-ZIP receipt. Checksums detect transfer changes; they are not
cryptographic signatures authenticating an untrusted archive.

## Streaming, ZIP64 and verification

MP4 payloads use native ZIP **STORED** entries. A streaming pre-pass computes
SHA-256, CRC32 and Long byte length; writing reopens each source and checks the
same bytes. No video is loaded wholly into memory and no full local project copy
is staged. Buffers are 64 KiB; only bounded metadata, entry facts and a small tail
buffer are retained. Size arithmetic uses checked Long operations.

Readback verifies ordered entry names/count, sizes, CRC and SHA-256; drains and
hashes the central directory/footer too; and validates the writer's comment-free
EOCD/ZIP64 footer. It compares every raw archive byte with the write receipt.
After rename the final URI is persisted and its whole-file digest/length are
checked again. A local write alone is never reported as success.

ZIP64 removes the classic ZIP entry/offset 4 GiB boundary, not provider filesystem
limits or Android execution budgets. A FAT32 destination, provider quota, stalled
remote provider or revoked URI permission can fail. Readback/rename/persistent
access are required; unsupported destinations fail with a clear message rather
than claiming unverified success. No generic arbitrary-ZIP importer is implemented.

Media-size UI estimates are approximate and exclude metadata/headers. MP4s are
not recompressed. Phone storage retains a reserve; destination-space reporting
is provider dependent, so actual write/read/close failures remain authoritative.
Completed source journals are bounded at 4 MiB and new archive journals at 8 MiB;
oversized metadata is rejected before replacing an existing atomic receipt. The
portable manifest is bounded at 4 MiB. These are explicit metadata limits, not
video-size limits.

## Failure and recovery rules

- New documents remain `.partial.zip` until fully verified. Source files are never
  written. Provider write failures retain a partial job for retry.
- A write without a durable receipt is restarted/truncated at the same owned
  destination. A completed receipt is read/verified again, without reopening or
  rewriting original footage. Repeated completion launches are ignored.
- Process death changes an active journal to Interrupted on recovery. Identity,
  snapshot, options, URI and receipt survive atomic recovery. Retry is explicit.
- Cancellation can remove only the job's own identifiable partial document. It
  cannot delete an original, gallery MP4, completed ZIP or user-located archive.
  Deletion failure leaves a partial file rather than erasing unrelated content.
- Final rename is a non-cancellable critical step. The new URI is persisted before
  permission retention/final verification. If a provider moves the document but
  loses the response, the job remains Interrupted; Locate saved archive verifies
  the known receipt and does not rename/rewrite the user's selected document.
- A new-copy request is explicit and uses a new UUID/SAF create-document result.
  Retry never blindly creates another document. A provider crash exactly between
  moving a document and returning its new URI needs user location; this cannot be
  made transactionally atomic across arbitrary remote providers.
- The latest-job shortcut is a recovery convenience, not a full archive-history
  browser. All journals remain in app storage; completed-session selection remains
  through the saved video's player. Arbitrary external/non-SkitCam videos cannot
  reconstruct missing app-private sessions.

## Automated and hardware results

Results below come from actual command logs; simulations and real footage are
distinguished. Evidence is retained in ignored `.validation/m11/` and the isolated
`.m11validation` app. Pre-existing failure fixtures remain intact.

- JVM: **183 tests passed**, zero failures/errors/skips (162 baseline + 21 project
  cases). Includes portable metadata, ordering, consent/filtering, legacy unknown
  facts, zero/single/100-retained cases, stale/non-integer trims, path safety,
  streaming archive roundtrip, source mutation, I/O/close failure, cancellation,
  overflow, Unicode/collision-safe filenames, oversized metadata, corrupted
  payload/footer, guaranteed destination closure after a broken entry, and
  state transitions.
- **89 distinct Android feature/regression cases passed**, including **19 project
  cases**, with zero failures/skips in the selected final batches. Repeated
  attempts and preparation cases are not counted again. One additional successful
  utility invocation retired only verified generated ZIP64 test documents.
- Production `testDebugUnitTest assembleDebug lint --offline` succeeded. Lint:
  **0 errors, 9 warnings**, all existing: 5 UsableSpace, 2 checked SharedPreferences
  UseKtx suggestions, 1 AGP-version notice, 1 JSON dependency-version notice. New
  archive code does not suppress these warnings or add dependencies.
- Real Android Downloads picker: a dedicated 3-segment CameraX/audio session was
  trimmed, segment 2 discarded and retained order changed to 3 → 1. Default ZIP
  has 5 entries. SHA-256 matches originals and the verified finished MP4; trim,
  discard and order metadata match. A subsequent new recording/automatic export
  returned to a genuinely streaming camera with a distinct session ID.
- Video-options integration and explicit discarded-take consent are tested through
  actual Compose accessibility actions. Options survive Activity recreation.
- Atomic project-journal interruption and invalid-state persistence were tested
  on Android using only newly created archive journals.
- Fault-provider tests execute production copy code against an isolated test-only
  DocumentsProvider: full originals/finished-only, write-full retry, corrupted
  readback, unsupported rename, slow cancellation, lost rename response/location,
  missing/corrupt/changed sources, recording priority and simulated Android timeout.
  These faults are **simulations on physical Android**, not failures of the real
  Downloads provider or an actually exhausted disk.
- Actual process-death test: begin Writing, force-stop only `.m11validation`, then
  cold recovery and same-job retry passed. No source cleanup or data clear.
- Actual ZIP64 test through the system Downloads picker streamed **4 GiB + 1 MiB**
  of generated binary data, verified every entry and raw archive bytes, renamed
  and rehashed. Two runs produced the identical **4,296,016,594-byte** archive,
  SHA-256 `b59c774783dcb68e82e0b3623243674ac7d3ae1460766ad64702e36f1b5cf5f0`.
  Ubuntu Python ZipFile/SHA-256 and `unzip -t` independently passed; Android
  `sha256sum` independently matched both files. This is a large-format/provider
  test, **not a >4 GiB camera take**. The two newly generated binary test documents
  were rehashed and removed through SAF to reclaim phone space; the verified
  desktop copy, receipts and all recordings/failure artifacts remain retained.

### Device case accounting and independent media inspection

| Selected batch | Distinct cases |
|---|---:|
| Photo/camera and Gallery | 12 |
| STOP-to-camera UX and process recovery | 8 |
| Recording, review and arrangement | 14 |
| Trim and explicit closure of its owned checkpoint | 5 |
| Audio and video profiles | 8 |
| Locked-profile process checkpoint/recovery | 2 |
| M6 journals, leading-gap processor, STOP workflow, finished playback, scene fixture | 12 |
| Framing/zoom/focus | 8 |
| Production timing fixture case (15 renders) | 1 |
| M11 project cases | 19 |
| **Total** | **89** |

The 19 project cases comprise two UI/workflow cases, three atomic-journal cases,
ten simulated-provider/source/priority cases, one actual picker bootstrap, two
actual process-death phases and one native ZIP64 case. The final subset has no
assumption skips; opt-in fixtures are supplied explicitly. Tests use real CameraX,
MediaStore, SAF and Media3 on the Moto, with known poses injected into model commands
where needed. This is not a claim of manually pressing every shutter/sensor gesture.

Three real project variants independently passed the desktop verifier:

| Archive | Entries | Sum of uncompressed payload bytes |
|---|---:|---:|
| Kept originals (default) | 5 | 14,217,633 |
| All originals, including explicitly selected discarded take | 6 | 19,071,663 |
| Finished MP4 and edit metadata only | 3 | 4,318,559 |

`unzip -t`, Python ZipFile CRC/SHA and extracted `sha256sum -c` checks passed. The
finished video and both default included originals decoded independently through
FFmpeg without errors: H.264, 1920×1080 stored dimensions, portrait display matrix,
AAC audio. Finished track durations were video **3.575033 s** / audio **3.572000 s**.
Archive bytes match the original verified sources; decoding alone is not evidence
of perceptual lip-sync or exact cut accuracy.

A separate, newly copied synthetic four-source fixture exercised the production
transformer on the Moto: kept order **yellow → red → blue**, fourth source discarded,
350–1750 ms trims, 4.2 s expected video. Independent 20 fps colour sampling confirmed
joins at 1.4/2.8 s and flashes at 0.75/2.15/3.55 s. Independent 10 ms audio FFT windows
located chirps at 0.780/2.190/3.595 s: 30/40/45 ms offsets at this sampling resolution.
Output H.264 1920×1080 video was 4.200000 s and AAC/48 kHz/stereo audio 4.254708 s;
independent decode and unchanged four source hashes passed. These measurements do
not establish frame accuracy or human dialogue lip-sync. All **15** bounded renders
of the five existing timing fixtures were accepted by the unchanged production
verifier. The **100 ms** limit remains intact.

Independent JPEG decoding/EXIF inspection passed for rear **4096×3072** portrait
and landscape photos and front **2302×1740** photos; EXIF rotations produced the
expected portrait/landscape display orientation. This is a regression check, not a
new subjective image-quality claim. Captured portrait and landscape project-option
screens were visually inspected at the device's existing 1.3× font scale; landscape
content remains vertically scrollable. No system font setting was changed.

Reproduction commands (connected-device serial may differ):

```bash
./gradlew testDebugUnitTest assembleDebug lint --offline
./gradlew -PvalidationSuffix=.m11validation assembleDebug assembleDebugAndroidTest --offline
adb -s ZY22KJQLTV shell am instrument -w -r -e class \
  com.calvinmazhindu.skitcam.project.ProjectDeviceTest \
  com.calvinmazhindu.skitcam.m11validation.test/androidx.test.runner.AndroidJUnitRunner
python3 tools/verify_portable_project.py archive.zip
unzip -t archive.zip
# Inside the extracted archive:
sha256sum -c checksums.sha256
```

The simulated provider must first be authorised through the real system picker by
`ProjectFailureDeviceTest#bootstrapSimulatedProviderThroughSystemPicker`. The
process checkpoint, recovery and ZIP64 methods need their explicit
`projectCheckpoint=true`, `projectRecovery=true` and `zip64=true` instrumentation
arguments. Force-stop only the isolated app between checkpoint/recovery. Fixture-only
M6 scene/playback tests require their `fixture` / `videoUri` arguments. Detailed
selected-case logs/counts, fixture reports, hashes and archive evidence are under
`.validation/m11/`; they are ignored by Git and not shipped as product assets.

### Errors found and corrected

An early repeated-completion launch exposed a foreground-service startup timeout:
completed/duplicate jobs now avoid starting the service, and onStart publishes its
notification before any job I/O. A rapid-retry lifecycle race was fixed by finishing
service teardown on the main thread before exposing retry readiness; an old
onDestroy cannot interrupt a new instance. Attempt counts make recovery auditable.

The test-only provider initially lacked an SAF grant, then its standalone Kotlin
Activity lacked the test runner's target classloader. It now bootstraps through the
real system picker using pure Java standalone components. Returning an unchanged
document ID instead of null incorrectly revoked its own grants; the simulated
provider was corrected to the Android contract. Tests now await service
acknowledgement before judging a retry and use unique failure-copy filenames,
preserving earlier evidence. A baseline model-only export test initially ran in
Photo after profile recovery deliberately preserved that ordinary preference; the
harness now explicitly selects Video first. Fixture-only tests are supplied their
required video/session arguments rather than counting assumption skips as passes.
These harness failures are not counted as passes. A final I/O review also found
that ZIP finalisation failure could bypass closure of the underlying destination;
its closure is now guaranteed and covered by a deterministic regression test.

## Source integrity and remaining verification

The before/after SHA-256 audit covers **1,507 pre-existing session files** across
the production app (772) and older isolated validation apps (229/268/238), plus
**325 pre-existing public gallery files**: **zero missing and zero changed**.
Production still has exactly 772 session files; public gallery count grew to 382
because of dedicated test media. No pre-existing file was removed or overwritten.
The audit covers accessible Android user 0, not inaccessible other user profiles.
New footage/archives are not substituted for the baseline. Selected M11 fixture
source hashes and session JSON were also checked before/after every project case.

The main **0.11.0** APK was installed with `adb install -r` and cold-launched. Its
live viewfinder was inspected and `dumpsys media.camera` identified the main app as
an active rear-camera client. A pre-existing main-app video was opened from Gallery;
Video options → Export Project showed the expected options/size estimate, and Back
returned through player/Gallery to the camera. No destination was chosen and no
main-app archive job was created during this read-only smoke test. Full archive
writes/cancellation/fault tests use the isolated application. One CLI uiautomator
attempt returned a stale XML after an idle timeout in PlayerView; screenshots and
foreground Activity inspection were used for the main-app menu check instead.

The new test-only APK was disabled after testing so its simulated storage root is
absent from ordinary destination selection; its app data/fixtures were preserved.
Component-level shell disable was rejected by the device; disabling that entire
new test APK for user 0 succeeded. Future tests can re-enable it using
`adb shell pm enable --user 0 com.calvinmazhindu.skitcam.m11validation.test`.
No existing application was uninstalled and no broad OS setting was changed.

Remaining platform/manual checks: Android 8/9 device execution, API 36 physical
execution, additional third-party/removable/cloud providers, a real >4 GiB camera
project, physically exhausted destinations and revoked-provider permissions under
load. Current-device accessibility actions/screenshots do not establish a full
TalkBack audit or human visual/photo-quality review.

## Desktop workflow and future work

Transfer the ZIP from its chosen folder over USB file transfer or removable media.
Ubuntu: `python3 tools/verify_portable_project.py archive.zip`, `unzip -t archive.zip`,
then extract and run `sha256sum -c checksums.sha256` inside the project. Windows:
use a current ZIP64-capable extractor (for example 7-Zip); compare SHA-256 with
PowerShell `Get-FileHash`. Paths/names inside the ZIP are portable ASCII even when
the outer filename is Unicode.

Original MP4s can be imported into a normal desktop editor such as DaVinci Resolve.
Use session.json for manual reconstruction of kept playback order and exclusive
trim boundaries. This is **not** a native Resolve timeline, a desktop companion,
a cloud backup or a promise that every desktop codec installation accepts every
file. A future timeline adapter/importer must validate this schema, hashes,
selected-source availability, orientation and track facts explicitly.

## Changed files

- New production package `project/`: `PortableProject.kt`, `ArchiveIO.kt`,
  `ProjectState.kt`, `ProjectStore.kt`, `ProjectExportService.kt`,
  `ProjectExportActivity.kt`.
- Integration: `export/ExportScreen.kt`, `gallery/SavedMediaActivity.kt`,
  `camera/CameraIcons.kt`, read-only priority hints in `PhotoModel.kt` and
  `RecordingViewModel.kt`, AndroidManifest and version in `app/build.gradle.kts`.
- Tests: `PortableProjectTest.kt`; project UI, persistence/failure/provider tests
  and test-only manifest/components; existing isolated-test package guards were
  extended to `.m11validation`. Production contains no simulated provider.
- `tools/verify_portable_project.py`, README and this validation record.

The recording engine, SessionCodec, camera binding/framing implementation and
ExportMedia algorithms are unchanged from e028763. No commit or push was made.

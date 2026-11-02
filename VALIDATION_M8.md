# Milestone 8 — Photo mode and camera-first interface

Implementation branch: `feature/camera-ui-photo-mode`, based on `eff822b`.
No commit, push, reset or branch switch is performed by this milestone.

## Architecture and safety

Two use-case configurations share one lifecycle-owned PreviewView: Preview plus
existing VideoCapture/Recorder for Skit Video; Preview plus ImageCapture for Photo.
Mode/lens transitions happen only when idle, after recovery. A fresh PreviewView is created only when changing the idle mode/lens; this avoids
accepting a prior binding's cached STREAMING event. Readiness requires the current
camera to be OPEN, the current view to be STREAMING and the Activity to be RESUMED.
Startup reads the recovered runtime snapshot directly after the recovery gate,
so independently scheduled UI collectors cannot choose Photo over an unfinished
session. The Photo shutter also repeats the live recording/export eligibility
check. Resume reconciles current stream state after translucent viewers/choosers
that do not stop the camera. Confirmed binding precedes preference commits. Failed transitions
restore the previous choice. Preference writes are serialized; a failed commit
reports that the selection could not be remembered. First launch defaults to
rear Skit Video. An unfinished session overrides saved mode/lens preferences.

Session schema 5 adds a lens enum. Versions 1–4 read as Rear without a migration
write. The selected lens is immutable across recording segments and recovery.
Unknown schema/lens values fail safely. Segment IDs, trim format (exclusive end),
chronology, Keep/Discard, arrangement, recording transitions and export inputs
retain their existing semantics. START has a synchronous command guard to prevent
an immediate mode-switch race before the worker publishes Starting.

The existing Recorder quality preference, audio monitor, export service, M7.1
leading-gap correction and **100000 us A/V verification threshold are unchanged**.
VideoCapture explicitly requests unmirrored output for front and rear lenses.
No Camera2 rewrite, image analysis pipeline or new timeline library was introduced.
AndroidX ExifInterface 1.4.2 is explicitly declared for reliable JPEG orientation
reading; all other dependency versions are retained.

The camera UI uses original Compose controls and drawing. It provides Photo /
Skit Video, shutter, lens switching, a bounded latest-media thumbnail, compact
status, Quick controls and a separate idle-only settings screen. PAUSE strips and
existing editors retain their portrait-left / landscape-bottom arrangement.
Gestures and quick adjustments are disabled beneath editors and during critical
transitions. Controls use platform touch targets and accessibility descriptions;
the Moto's existing 1.3 font scale was not changed.

## Photography and storage

ImageCapture uses JPEG quality 100 and MAXIMIZE_QUALITY. ResolutionSelector prefers
high-resolution supported configurations, with standard resolution fallback if
binding fails. The photo preview uses a fitted viewport matching its capture
framing, without stretching. Target rotation is captured for the shutter request;
selfie preview mirroring is separate from saved-image orientation.

A single photo job captures to `files/photos/<uuid>/source.jpg`. An AtomicFile
journal records Capturing, Validating, Publishing, Completed or Interrupted.
Capture callbacks authorize validation; a partial file left in Capturing is never
automatically published. JPEG markers, bounds, orientation and a sampled decode
are checked. Missing/undefined EXIF orientation means display the stored pixels;
invalid orientation values are rejected without rewriting the JPEG.

Android 10+ publishes pending images to Pictures/SkitCam. Unique job markers make
retry idempotent. Copy SHA-256 and a sampled decode are checked before clearing
pending. Copy failure removes only this job's newly created incomplete gallery
entry and retains staging for retry. Publication interruption can reuse the same
row. After a completed journal is durable, only its byte-identical staging copy
is removed. Original session MP4s and metadata are outside this code path.
Android 8/9 use legacy storage permission, hidden staging, rename and media scan.

The latest photo/video shortcut is timestamp-ordered across restart. It accesses
only owned media and grants URI read access when launching an image viewer.
A recorded video uses the existing finished-video player. No broad modern media
library permission is added. Camera input and photo save execute asynchronously;
thumbnail and validation decodes are bounded rather than full-resolution bitmaps.

## Observed hardware results

Moto G54 5G, serial ZY22KJQLTV, Android API 35. Independent host Pillow full JPEG
decoding confirmed the following real CameraX captures (not generated fixtures):

| Capture | JPEG dimensions | EXIF orientation | Display orientation |
|---|---|---|---|
| Rear portrait | 4096 × 3072 | 6 | Portrait |
| Front portrait | 2302 × 1740 | 8 | Portrait |
| Rear landscape | 4096 × 3072 | 1 | Landscape |

These are measured output configurations, not a claim about the sensor's maximum
or subjective image quality. EXIF values contain no mirror transform; visual
text-marker verification of selfie mirroring remains a manual follow-up.

An independently inspected two-segment front-camera recording after several photos exported
successfully (`ee6961fa-86a0-48ab-9e0e-9b5d8f2b3889`). Independent FFmpeg full
decode passed: H.264 1280 × 720 with rotation 90° (720 × 1280 display), AAC
48000 Hz stereo; video 3.521278 s, audio 3.550708 s. Independent source/output PCM
correlation at two interior windows in each clip measured about +9.33 ms for
clip 1 and +9.25 ms for clip 2, with correlations 0.982–0.990. This supports
continuity across the join, not a subjective lip-sync guarantee.

All selected gallery JPEG copies also match the completed capture journal SHA-256.
Independent marker checks measured zero-start +5/+5 ms and delayed-start +5/+15 ms.
The reordered/non-keyframe trim checker passed yellow → red → blue, flashes at
0.75/2.15/3.55 seconds and chirps at 0.76/2.16/3.57 seconds. These measurements do
not promise frame-accurate preview/export or perceptual sync for every real scene.

## Validation log

- JVM suite: 110 tests, zero failures/errors/skips, including storage reserve/overflow cases.
- Production `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest` and
  `lint`: BUILD SUCCESSFUL. The final invocation reported 1h 43m 17s elapsed
  across the interrupted session; the preceding full run took 1m 27s. This is
  wall time, not an isolated compile-performance measurement. App version 0.8.0 (9), min 26,
  compile/target 36.
- Photo/mode suite: **OK (11 tests)**, 66.899 seconds in the final guard rerun, no failures/skips. This
  includes rear/front/landscape JPEGs, recreation and preferences, focus/zoom and
  actual injected pinch gestures, timer cancellation, Photo → front video → two
  segments → recovery → STOP/export, corrupt JPEG rejection, atomic journal
  recovery/idempotency, simulated low space, settings and gallery round-trip.
- Existing recording/export regression suite: **OK (29 tests)**, 448.356 seconds,
  no failures/skips: timing fixtures (1), PCM forwarding/backpressure (4), export
  journals (4), recording (6), finished workflow (1), audio monitoring (1), PAUSE
  review (4), arrangement (4) and trimming/recovery (4).
- The timing test contains **15 private renders**, not 15 additional test methods:
  three each for failed-source copies, passing copies, zero-start markers, delayed
  markers and reordered/non-keyframe trims. All accepted under the unchanged limit.
- **40 distinct instrumentation test methods passed** across the full photo and
  regression runs.
  Earlier failed/aborted attempts and repeated checkpoint methods are excluded
  from this total. After the final layout correction, a focused **15-case** run
  passed again (177.488 seconds): all 11 photo cases, the finished Play/Share/New
  Recording workflow, and landscape PAUSE, arrangement and trim. These are repeat
  methods, not 15 additional unique tests.
- Additional real-process checks: saved Photo restored after force-stop/cold
  launch; an actual shutter capture saved with RECORD_AUDIO revoked; CAMERA denial
  disabled the shutter, followed by grant/restart recovery. These operated only
  on the isolated validation app.
- Static comparison confirms ExportMedia, ExportService, LeadingAudioGapFactory,
  AudioMonitor and AudioModel match committed M7.1 byte-for-byte.
- `git diff --check`: passed. No commit or push.
- Preservation audit: **740 pre-existing session files**, **zero changed or
  missing**, checked before the main-app update and again after installation and
  launch, then again after acknowledging New Recording without starting capture.
  This includes MP4s, session metadata, export journals and candidates.
  Main app updated in place to 0.8.0; timing-control and validation apps retain
  their separate storage. No file repair, reset or rollback was used.
- The first audit invocation had an ADB quoting error, so it was rejected as an
  invalid hash collection. The corrected audit validates SHA-256 record format
  before comparison; the valid before/after records match.

Early development findings were fixed rather than bypassed: an observable startup
readiness flag, a Kotlin declaration clash, a typed rotation fallback caught by
lint, and root text contrast. Interrupted USB/test runs are not counted as passes.
A repeated first-launch test originally ignored an existing Photo preference; its
assertion now checks persisted/default behaviour correctly. Device tests also
exposed a stale-stream mode transition and readiness after a translucent image
viewer; these are fixed using per-binding views and lifecycle reconciliation,
without bypassing the START readiness guard. Superseded/aborted runs are not counted
as completed passes. A generated JPEG
publication test exposed undefined EXIF orientation; interpretation was corrected
to normal display, without changing original bytes.

Screenshot inspection additionally exposed landscape clipping at font scale 1.3.
The final UI stacks landscape mode choices, uses fixed 48 dp gallery/lens icons,
reserves more rail space and scrolls lengthy photo feedback. A device assertion
now checks the complete landscape switch target bounds. After New Recording,
the Ready presentation shows a fresh zero timer/counter; the completed session
remains unchanged until START creates a separate session.

## Build commands and evidence

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint --offline --no-daemon
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PvalidationSuffix=.m8validation --offline --no-daemon
adb shell am instrument -w -r -e class com.calvinmazhindu.skitcam.camera.CameraDeviceTest com.calvinmazhindu.skitcam.m8validation.test/androidx.test.runner.AndroidJUnitRunner
```

The 29-case regression invocation selects TimingInvestigationDeviceTest,
LeadingGapConsumerDeviceTest, ExportJournalDeviceTest, RecordingDeviceTest,
ExportDeviceTest#stopAutomaticallyExportsAndPublishesTwoSegments, AudioDeviceTest,
PauseEnvironmentDeviceTest, ArrangementDeviceTest and TrimDeviceTest, with
`-e kinds failed,passed,zero,offset,trimmed`. Its runner is the same isolated package.
Argument-specific standalone fixture/player tests are not counted as runs.

Final logs: `/tmp/skitcam-m8-final-guard-tests.log`,
`/tmp/skitcam-m8-regression-verified.log`, `/tmp/skitcam-m8-layout-verified.log`,
`/tmp/skitcam-m8-final-delivery.log`.
Post-restart/permission screenshots, UI dumps and copied media are collected under
`/tmp/skitcam-m8-final-evidence/`. These temporary host paths are evidence locations,
not checked-in user recordings. The exact final production-install audit is in
`/tmp/skitcam-m8-final-guard-install.log`; its three comparisons all matched the
original 740 entries. The main app is left at the camera, without an active new
recording. Both diagnostic apps remain installed to preserve their test data.

Lint: **zero errors, 25 warnings**. Categories: 2 existing dependency-update
suggestions, 1 existing backup/data-extraction advisory, 1 existing player-layout
advisory, 5 free-space advisories (3 existing export/session and 2 photo call-site
reports), and 16 KTX convenience suggestions across existing/new code. New photo
preflights deliberately reserve actual usable space; they do not reclaim caches.
The explicit preference commit Boolean is checked for failure feedback. KTX/URI
suggestions are stylistic. No new lint error is suppressed and verification
thresholds are not relaxed. M7.1 documented 22 warnings; the current set is not an
unchanged-warning claim.

## Changed files

- Camera presentation: MainActivity, CameraPreview, RecordingControls and strings.
- New camera package: CameraModel, CameraPolicy, CameraTools and MediaShortcut.
- New photo package: PhotoModel, PhotoPolicy and PhotoStore.
- Recording integration: RecordingEngine, RecordingViewModel, SessionCodec and
  CameraRecordingPort (lens, startup guard and explicit unmirrored output).
- Configuration: app/build.gradle.kts and AndroidManifest.xml (version, isolated
  validation identity/label and EXIF dependency; no new permission).
- Tests: CameraPolicyTest, PhotoPolicyTest, CameraDeviceTest and the existing
  SegmentDecisionsTest schema-version assertion.
- Documentation: README.md and VALIDATION_M8.md.

## Limitations and manual follow-ups

- Real photo quality, low-light behaviour, autofocus accuracy and visual selfie
  mirroring need controlled scenes and human review; dimensions/decode alone are
  insufficient. No stock-camera quality equivalence or 50 MP claim is made.
- Orientation configurations were requested programmatically on the real phone;
  touch gestures were injected through Android into the actual preview. Human
  handheld rotation, TalkBack and gesture-navigation checks remain manual.
- Android 8/9 legacy publication, other camera hardware and storage exhaustion need
  further hardware validation. Gallery-provider write failures and actual storage exhaustion
  still need manual fault testing; injected zero-space cases are simulations. No
  unrelated OS storage/display settings were changed.
- Photo publication uses a process-owned bounded IO worker, not a foreground photo
  service. Process interruption offers recovery/retry; it does not guarantee that
  an unfinished camera exposure completes after process death.
- JPEG validation includes bounded device decode plus independent full decode of
  selected real outputs, not a full-resolution on-device memory allocation.
- External microphone hardware remains pending as documented in M7. No routing
  selection, advanced manual exposure, RAW, HDR or FPS profiles are added.

## Milestone 9 recommendations

Keep capability-driven controls and the two-mode binding strategy. Measure photo
quality and camera-specific exposure/focus behaviour before adding manual controls.
Retain schema compatibility, session lens/orientation guards and timing fixtures.
Do not widen verification thresholds or modify original segments to accommodate UI
features. Add advanced capabilities only when both binding and output are verified.

Official references: [CameraX image capture](https://developer.android.com/media/camera/camerax/take-photo),
[CameraX configuration](https://developer.android.com/media/camera/camerax/configuration),
[MediaStore storage](https://developer.android.com/training/data-storage/shared/media),
[ExifInterface releases](https://developer.android.com/jetpack/androidx/releases/exifinterface).

# Milestone 10 — camera-first UX and seamless save

Implementation on `feature/m10-camera-ux`, based on committed Milestone 9
`cdf8c8c`. Version **0.10.0**, versionCode **11**. Validation date: 3 November 2026.
Changes remain uncommitted. No push, uninstall, data clearing or source cleanup.

## Architecture and scope

The old success page required Play/Share/New Recording before returning to the
camera. Success now follows the existing finalisation → assembly → verification →
pending MediaStore publication pipeline, then automatically restores the live Skit
Video viewfinder. Failures, cancellations, empty sessions and Retry retain their
explicit recovery presentation. There is no premature success based on Transformer
completion alone, and no new encoding or media-assembly algorithm.

`ExportJournal.busyState` makes the existing service-busy flag observable. A
`SaveReturnPolicy` requires the current session to be Completed, resolved, published
and no longer busy. The UI reads the matching completed journal on IO and rechecks
session/result identity after the read. A version-1 `SaveReceipt` in the existing
`camera_preferences` stores job/session IDs, final URI, creation time and a handled
flag. It is independent of recording-session and export-job metadata. Recent-media,
receipt and acknowledgement writes share a serialized, checked preference commit;
mode preferences are written only by successful explicit camera transitions.

The return is handled only after CameraX reports OPEN, PreviewView STREAMING and
the video profile is ready. An already handled receipt ignores repeated completion
callbacks. This UI path does not publish, create a session UUID, rewrite metadata
or reset the recording engine. The next START creates a distinct session through
the existing engine. The prior Completed session, source files, edits and content
URI remain retained. A pending unseen completion opens Skit Video once even when
Photo is the saved ordinary mode; a subsequent ordinary launch restores Photo.
Unfinished recording recovery continues to take priority.

A short dark **Video saved** snackbar offers View, above measured camera controls.
The recent-media thumbnail opens a saved-media browser. Selecting a video opens
its finished player, whose header provides Back to Gallery and Android Share.
The snackbar View action opens that specific newly saved video directly. Share grants access to the selected content URI; no share
is automatically sent. Media3 releases on Stop and restores position/play state.
The snackbar is best effort across process death, not an exactly-once notification.
A crash before committing the return receipt can repeat the safe camera return,
but cannot create a recording session or a gallery publication.

CameraX **1.5.3**, Media3 **1.11.1**, min SDK **26**, compile/target **36**, and existing
production dependencies are unchanged. The recording state machine, capture
profiles, session schema **6**, export schemas **1/2**, trim conventions, leading-
audio-gap correction and **100 ms A/V verification limit** are unchanged.

## Interface decisions

- Original geometry-based Settings, quick-settings, microphone and capture icons;
  compact bound-quality/FPS request label with an accessible lens description.
- No idle Ready/duration/zero-kept clutter. Active sessions retain duration/count;
  PAUSE retains explicit RESUME/STOP, Arrange and the existing segment strip.
- Portrait capture uses gallery / shutter / camera-switch placement. Landscape
  stacks these controls in a side rail, enlarged for larger fonts. This avoids
  clipping two 48 dp controls beside each other in a narrow inset-adjusted rail.
- Quick settings have a scrollable height capped at 45% of the window. Quality/FPS
  chips have short single-line labels and horizontal scrolling. Longer capability
  information stays in full settings; session-profile locks remain enforced.
- Full settings: Camera, Photo, Video, Audio, Preferences and expandable Advanced /
  Technical Information. They are available idle and PAUSED; the camera stays
  bound. Background controls are hidden from accessibility while full settings
  covers them. Back and Close dismiss settings.
- Photo preview remains FIT with its source aspect ratio, positioned inside space
  between toolbar and capture controls. No saved-image crop, resolution strategy,
  JPEG quality or orientation policy changed. Photo acknowledgement is transient;
  failures and Retry Save remain visible.
- Audio overview groups built-in/other Android endpoint categories for presentation
  only. Distinct USB/wired/Bluetooth IDs remain distinct even with matching names.
  Diagnostics retains every raw ID, type, name, address and unverified observation.
  A connected endpoint is never represented as confirmed active or selectable.
- Icon targets are at least 48 dp, shutters 80 dp; labels/semantics and safe insets
  are retained. The existing phone configuration was 1.3× font scale; no font or
  display-size OS setting was changed for testing.

## Automated and hardware validation

The final selected batches passed **61 distinct Android cases**, with no failures
or skips in those final batches: 52 recording/media/camera cases and nine new M10
cases. Repeated attempts and the final focused reruns are not
added to that count. Initial failed attempts and fixture corrections are described
below; their logs and recordings remain retained.

- JVM: **153 tests passed**, zero failures/errors/skips (132 existing, 21 new).
  New cases cover completion eligibility, busy/finalisation restrictions, unrelated
  and duplicate events, receipt restart, invalid receipt data, one-time Video
  recovery without changing Photo preference, pending-session priority, concise
  bound labels, identity-preserving audio presentation and gallery ordering,
  filters, deduplication and preservation of older entries.
- Eight new M10 Android cases have passed across the selected batches: real STOP
  while paused plus another distinct recording, repeated success/recreation,
  PAUSED settings/RESUME, portrait/landscape controls, explicit failure UI,
  background-publication checkpoint, real process restart and microphone overview/
  raw-diagnostics presentation.
- These are automated physical-device tests using real CameraX/audio/MediaStore on
  Moto G54 5G `ZY22KJQLTV`, API **35**. Known pose values are injected into model
  commands; this is not a claim of manual shutter/sensor-gesture validation. The
  camera actually records again after the return, rather than only changing UI
  navigation state. A flat phone correctly leaves the shutter orientation guard
  disabled; that protection was not bypassed in production UI.
- Repeated completion deliveries are injected UI events for an actually published
  job. The failure-presentation case injects an unacknowledged failed UI result
  without changing the journal. It is not a real codec/publishing failure test.
- The recovery checkpoint uses a real background export and a pending UI receipt
  with a conflicting Photo preference, then force-stops only `.m10validation` and
  verifies cold recovery, same session identity and the persisted handled receipt.
  It does not simulate intermediate-position encoder resume.

### Final device case accounting

| Suite | Distinct passing cases |
| --- | ---: |
| Camera/photo | 11 |
| Recording | 6 |
| PAUSE/review | 4 |
| Arrangement | 4 |
| Trim | 4 |
| Profile recovery, separate processes | 2 |
| Video profiles, including longer landscape | 7 |
| Audio recording/meter/pause/resume/export | 1 |
| Export journal/recovery | 5 |
| Leading-audio-gap consumer | 4 |
| STOP → camera → thumbnail → Play/Share | 1 |
| Timing fixtures, three renders per five inputs | 1 |
| Ordered, trimmed, discarded-scene export | 1 |
| Finished player prepare/play/release/restart | 1 |
| New M10 UX/recovery/microphone cases | 8 |
| Gallery older-video selection/playback/recreation | 1 |
| **Total** | **61** |

The **15 production timing renders** are inside the timing-fixture case, not 15
additional Android cases. The final polish batch also reran post-save recording,
landscape targets, microphone summary/diagnostics and photo gallery return: four
passes. The source recording and session transitions use the same engine.

### Independent inspection

- FFmpeg fully decoded **13 MP4 copies** without errors: ten native gallery
  outputs, one CameraX probe, one post-return output and the controlled scene
  output. ffprobe confirmed H.264/AVC plus AAC, actual SD/HD/FHD dimensions,
  portrait display matrices (±90° as reported by ffprobe) and 0° landscape.
- The new controlled, synthetic-input export was performed on the Moto. It kept
  yellow → red → blue, excluded green, and applied each [350,1750) ms selection.
  Independent decoded-frame analysis found joins at **1.4 s / 2.8 s**, flashes at
  **0.75 / 2.15 / 3.55 s**, and audio chirps at **0.76 / 2.16 / 3.57 s**. Video
  duration was **4.200000 s**, AAC **4.254708 s**, at 1920×1080 / 60 fps with
  48 kHz stereo. This verifies this fixture's order/cuts/markers, not universal
  frame-accurate trimming or perceptual speech synchronisation.
- **101 frozen export-source hash comparisons matched** after export; all four
  generated colour-source files, including discarded green, also matched their
  host-generation hashes.
- A new recording made after returning to the camera had nonzero decoded audio:
  first-second mono PCM RMS **4.329×10^-5**, peak **2.095×10^-4**. These quiet
  ambient samples establish decoded signal presence, not intelligibility or
  independent confirmation of the active physical microphone.
- Pillow fully decoded **six newly captured gallery JPEG copies**. Rear output
  was **4096×3072**, EXIF 6 portrait / 1 landscape; front **2302×1740**, EXIF 8.
  Display dimensions followed orientation. Manual sharpness/colour evaluation
  was not performed.

### Build and lint

Production and isolated `assembleDebug`, Android-test assembly, JVM tests and
`lint` completed successfully. Production lint: **0 errors, 9 warnings**, compared
with M9's 8 warnings:

- Two existing tool/dependency-update advisories (`AndroidGradlePluginVersion`,
  `NewerVersionAvailable`); versions were intentionally preserved.
- Five existing `UsableSpace` advisories in recording/export/photo storage code.
- One existing checked-commit `UseKtx` advisory and one new checked-commit
  `UseKtx` advisory for serialized recent-media/return state. `commit()` returns
  the Boolean needed for failure feedback; the suggested KTX editor returns Unit.

No warning/error was suppressed. The isolated label build has one additional
`UnusedResources` warning for `app_name`, which its literal validation label does
not reference; production uses that resource. New icon ModifierParameter warnings
were fixed. No new API-level lint errors were introduced.

Git blob comparisons confirm `RecordingEngine`, `SessionCodec`, `SessionFileStore`,
`CameraRecordingPort`, `VideoController`, `PhotoStore`, `ExportModel`, `ExportMedia`
and `ExportService` remain byte-for-byte identical to the committed M9 versions.
Only export busy-state observation and presentation/prefs were extended.

## Gallery follow-up from the physical test

The user reported that Gallery showed only the latest video. The active screen
was `FinishedPlaybackActivity`: the original thumbnail route passed one URI
straight to that player. The older files were still present and hash-identical.

The thumbnail now opens `gallery/SavedMediaActivity`, a minimal read-only browser
with All / Videos / Photos filters, newest-first metadata ordering, and lazy bounded
thumbnails. A gallery row opens the existing player or compatible image viewer;
Back returns to the browser, then camera. Older entries are not replaced by a
recent-media pointer. Missing/deleted media produces feedback and a refresh.
Gallery opening is blocked during sessions, queued recording commands, profile
changes, photo capture and export. Starting recording dismisses the prior save
message; its View action also checks for new capture work before navigating.

`SavedMediaStore` queries published, nonempty MediaStore items in Movies/SkitCam
and Pictures/SkitCam on IO, using volume-specific content URIs. It never reads
videos into RAM, writes session metadata, deletes files or changes publication.
Thumbnails are small and concurrency is bounded at two. Modern Android exposes
accessible/app-owned media without a new broad media permission. Android 8/9 use
the existing legacy read permission when needed. **Browse phone** uses Android's
picker for other apps/installations' media without requesting full-library access.
Reference: [Android shared-media access](https://developer.android.com/training/data-storage/shared/media)
and [Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker).

Six new JVM cases and a new physical older-video playback/recreation case pass.
The affected STOP → Gallery → Play/Share, photo gallery return, distinct-session
recording continuity and landscape cases also passed again: **five selected device
cases**, no failures/skips. This adds one distinct case to the earlier 60. Full
153-case JVM tests, both APK assemblies and production lint passed after this fix;
production remains at 0 errors / 9 classified warnings. The capture/assembly/timing
implementation was unchanged, so the already completed 15 fixture renders were
not rerun solely for this browser addition.

The production app was updated again in place. Its actual camera thumbnail opened
the new Gallery, which reported **61 videos and 2 photos saved with SkitCam**.
Older rows were visible, and the Videos filter was selected on the phone. The
remaining public files include other validation/installations' media; Android's
picker is provided for that broader collection. Picker fallback and older-platform
permission UX still need manual physical checks. No account or cloud integration
was added to SkitCam.

## Errors found during validation

- The first new test helper accepted the preceding session's Completed export
  before the current STOP was processed. It now waits for current session identity,
  finalised recording state and the matching completed job. The recordings from
  those attempts were retained. This was a test-harness race, not an A/V failure.
- Completion handling now also keys on recording state/unresolved changes, so
  collector delivery order cannot omit its final eligibility check.
- The first landscape test retained the test's portrait display lock. It now sets
  and restores a matching landscape test rotation. Production orientation rules
  were not changed.
- Screenshot review found a clipped landscape switch glyph despite accessible
  bounds appearing sufficient. Vertical shortcuts and a wider/font-aware rail
  correct the actual layout. Snapshots are captured after UI idle, not immediately
  after dispatching a click.
- The failure UI fixture initially used an already acknowledged successful session;
  the app correctly did not show an unhandled failure for it. The fixture now
  explicitly marks its synthetic presentation unacknowledged, restoring it after.
- The wider landscape viewport fits six thumbnail cards without scrolling. The
  existing scroll test now records eight bounded fixture clips to exercise a real
  overflow; the six-clip attempted fixture was completed through its enabled STOP
  control and retained. No production strip width was reduced to satisfy a test.
- A standalone profile-recovery case assumed an earlier suite had created its
  diagnostic directory. It now creates that directory explicitly before saving
  its checkpoint. This was a fixture-order dependency, not a recording failure.
- New ModifierParameter warnings were fixed. Boolean commit results remain checked;
  replacing them with the Unit-returning KTX editor would lose failure reporting.

## Preservation

The fresh M10 baseline covers **995** accessible pre-existing session files:
production **766**, M9 validation **229**. SHA-256 manifests and audit outputs are
under ignored `.validation/m10/`. The older `.m71control` and `.m8validation` app
records are absent for user 0, and run-as reports their data unavailable at the
start of this run. They were not uninstalled by this work and cannot be included
in this run's verified-file count. Prior M9's 1,047-file audit is historical.

New tests use the hidden-launcher `.m10validation` application, preserve every
recording/candidate, and copy timing inputs into its private validation directory.
No original fixture is modified. Tests compare previous session JSON and source
MP4 hashes after starting/finishing a new session.

Before deployment, all **995** baseline session files matched their hashes. The
main app was updated in place to 0.10.0, preserving its paused one-segment session.
During the final check the user pressed STOP (explicitly confirmed in conversation).
That session completed and published a verified MP4. The final audit found **994
files unchanged**, zero missing files, and exactly one expected metadata change:
`Paused` → `Completed` in that session. Replacing only that state string reconstructs
the original SHA-256 exactly, proving all other metadata bytes remained identical.
Every original MP4, including that segment, remains byte-for-byte unchanged. The
new export job/output are additional files. Existing audit-failure logs are retained;
the user-authorised transition is explicitly reconciled, not hidden by resetting
baseline manifests.

The public-media audit matched all **266** files in the pre-upgrade Movies/SkitCam
and Pictures/SkitCam manifest, with zero missing/changed hashes. There are now **271**
files: the user's newly finished video and four media files from focused validation
were added. This public-media baseline was taken after the earlier isolated tests,
before upgrading the main app; it does not claim to predate all M10 test captures.
`final-preservation-reconciled.json` records the final accounting.

## Commands and evidence

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint -PvalidationSuffix=.m10validation --offline --no-daemon
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint --offline --no-daemon
adb -s ZY22KJQLTV install --no-streaming -r <isolated-debug.apk>
adb -s ZY22KJQLTV install --no-streaming -r <isolated-androidTest.apk>
adb -s ZY22KJQLTV shell am instrument -w -r -e class <selected-case-or-class> com.calvinmazhindu.skitcam.m10validation.test/androidx.test.runner.AndroidJUnitRunner
```

Build, lint, instrumentation logs, copied screenshots, hash manifests, generated
fixture inputs and independent analysis live in `.validation/m10/`. Production
and isolated APKs are kept separately there. No production session is used as a
writable instrumentation fixture. Test rotation overrides are restored.

## Remaining manual checks and next steps

- Manual usability in bright/changing scenes, TalkBack traversal/announcements,
  2× fonts/display scaling, and devices other than this Moto remain to be checked.
  Automated accessibility-node bounds and screenshots do not prove all of these.
- Below API 29, the recent-video shortcut retains the existing generic gallery
  placeholder instead of a decoded video thumbnail. Opening the video remains
  supported; older-platform physical validation is pending.
- A human should check perceived speech/lip-sync and comparative photo quality;
  decoder success and track timing do not establish either. External microphone
  hardware coverage remains limited to the prior milestones' actual evidence.
- Abrupt device power loss, storage exhaustion and process death mid-encode still
  rely on existing checkpoint/retry protections; this milestone does not add
  resumable encoder positions or broaden codec guarantees.
- A future portable project bundle should copy retained originals plus versioned
  session/edit/order/profile and export manifests, translate paths without changing
  stable IDs, verify hashes and byte counts, and finish its destination atomically.
  Include clear missing-file handling and an explicit user-controlled retention
  policy. No bundles, account/network service or automatic cleanup are implemented.

## Changed files

- `MainActivity.kt`, `CameraPreview.kt`, `recording/RecordingControls.kt`: camera
  layout, binding-derived short labels, finishing/return orchestration and capture
  controls. Binding/capture policy itself is preserved.
- `camera/CameraModel.kt`, new `SaveReturn.kt`: durable UI receipts, mode-safe
  automatic return and serialized recent-media persistence.
- New `camera/CameraIcons.kt`, `CameraTools.kt`, `MediaShortcut.kt`: original icons,
  contextual/full settings and a bounded thumbnail with a visible dark-scene edge.
- `audio/AudioModel.kt`, `AudioMonitor.kt`, `AudioIndicator.kt`: presentation groups,
  raw diagnostic fields and compact status/dialogs; capture/routing policy unchanged.
- `export/ExportJournal.kt`, `ExportScreen.kt`: observable busy flag, finishing-only
  success transition and finished-player Back/Share controls.
- `photo/PhotoModel.kt`: transient save acknowledgement state, without capture or
  publication changes.
- `app/build.gradle.kts`: 0.10.0/versionCode 11 and isolated validation label.
- New `gallery/SavedMedia.kt`, `SavedMediaStore.kt`, `SavedMediaActivity.kt`,
  `SavedMediaPolicyTest`, `GalleryDeviceTest`; a private activity registration in
  `AndroidManifest.xml` and reuse of bounded thumbnail rendering.
- New `CameraUxDeviceTest`, `SaveReturnTest`, `AudioPresentationTest`; updates to
  `CameraDeviceTest`, `ExportDeviceTest`, `ExportTestSupport`,
  `PauseEnvironmentDeviceTest`, `ProfileRecoveryDeviceTest`, `VideoDeviceTest` and
  `tools/make_profile_fixture.py` for M10 isolation, workflows and robust fixtures.
- `README.md` and this report.

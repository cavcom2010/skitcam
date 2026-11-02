# Milestone 9 — video profiles and capture controls

Implemented on `feature/pro-camera-controls`, based on committed M8 `71e519e`.
Validation date: 2 November 2026. Version: **0.9.0**, versionCode **10**.
No commit, push, uninstall, data clearing or recording cleanup was performed.

## Results

- **132 JVM tests passed**, zero failures/errors/skips: 110 existing and 22 added.
- **51 distinct Android device cases passed across the final batches**, including
  all existing regression cases, profile controls, actual process restart, longer
  landscape capture and the controlled export fixture. Repeated attempts are not
  added to this distinct-case count. Initial failures/skips are described below.
- **15 production timing-fixture renders passed**, three each for failed, passed,
  zero-offset, positive-offset and trimmed fixtures. These renders belong to one
  of the 51 Android cases, rather than 15 additional cases.
- Production `assembleDebug`, Android-test assembly and `lint`: **BUILD SUCCESSFUL**.
  Lint: **0 errors, 8 warnings**. No timing threshold or lint error was suppressed.
- All **1047 pre-existing session files** matched their original SHA-256 hashes
  in the completed integrity audit: production 740, M7.1 control 28, M8 validation
  279. New files do not replace this original-file comparison.
- The isolated `.m9validation` app was installed and exercised on the Moto G54.
  Its launcher shortcut is hidden. The regular production app was updated
  in place after confirming its session was Completed with no pending
  segment. Package version 0.9.0 and a successful cold launch were confirmed. The
  production camera reached its ready 1920×1080 / Auto FPS preview. The post-launch
  audit again matched all 1047 original files; no old app was uninstalled.

## Architecture

CameraX remains **1.5.3**, Media3 **1.11.1**; Kotlin/AGP/Gradle and dependencies are
unchanged. Min SDK 26, compile/target SDK 36; physical device Android API 35.
No custom Camera2 capture session, AudioRecord/muxing replacement, additional
permission or heavy dependency was introduced.

Preview binds with VideoCapture in Skit Video and ImageCapture in Photo. Profile
changes create a new Recorder/VideoCapture only while an idle video camera is
streaming, recovery is complete, and no mode/lens transition, photo save, recording
command or export is in flight. START closes synchronously before a profile rebind;
the serial engine also checks availability. Disposal unbinds the exact owned use
cases, rather than whichever capture happens to be in a mutable port later.

`video/VideoController` discovers SDR qualities and configuration-specific FPS
ranges, restores per-camera preferences and owns only Camera2 AE_LOCK/AWB_MODE
interop options. CameraX retains ownership of focus, zoom, EV, EIS and frame-rate
requests. AE is labelled locked only after a capture result confirms it. AF hold
uses CameraX metering with auto-cancel disabled and requires a successful focus
result. Failed holds restore Auto, and request tokens prevent stale focus futures
from overriding a subsequent tap/background event. AE/AF holds clear on background
or rebind; white balance is frozen as part of the session profile.

Preferences commit only after successful binding/streaming and requested WB
confirmation. An applied-binding revision prevents the previous stream's callback
from acknowledging a newly requested profile. The Android regression explicitly
injects that old-stream callback before Compose can rebind. Failed changes roll
back; checked commit failures are reported. The bounded writer closes with its
ViewModel. UI labels distinguish current requests from the last finalised clip's
measured cadence, including variable timing.

Official API documentation was checked against actual public methods in the
pinned AAR:

- [Configuration-specific FPS ranges](https://developer.android.com/reference/androidx/camera/core/CameraInfo#getSupportedFrameRateRanges(androidx.camera.core.SessionConfig))
- [Recorder.Builder](https://developer.android.com/reference/androidx/camera/video/Recorder.Builder)
- [VideoCapabilities](https://developer.android.com/reference/androidx/camera/video/VideoCapabilities)
- [Camera2CameraControl interop](https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2CameraControl)

## Moto G54 capabilities and supported choices

Device `ZY22KJQLTV`; rear camera ID 0, front ID 1. Rear sensor orientation 90°;
front 270°. CameraX reports the following SDR profiles for both cameras:

| Camera / quality | Verified encoded dimensions | Offered FPS requests |
| --- | --- | --- |
| Rear FHD | 1920×1080 | Auto, 30 target, 60 target |
| Rear HD | 1280×720 | Auto, 30 target |
| Rear SD | 640×480 | Auto, 30 target |
| Front FHD | 1920×1080 | Auto, 30 target |
| Front HD | 1280×720 | Auto, 30 target |
| Front SD | 640×480 | Auto, 30 target |

Auto captures at each quality and front FHD/30 were recorded/exported with audio.
Rear FHD/30 with EIS/Daylight and rear FHD/60 segmented capture/export were also
physically exercised. Capability-reported choices do not imply every possible
quality/EIS/WB combination received an independent physical recording test.

UHD is absent from CameraX's SDR list. Vendor profile XML files disagree about
UHD/high-speed support, so they are not UI authority. Fixed 24/25 are absent;
a reported 24–30 range does not establish fixed 24 or 25 support. A configuration-
specific 60–60 range appears for rear FHD, not HD/SD/front. No slow motion is offered.

Rear zoom is 1–8x; front 1–4x. Both report EV -24..24 at 1/6 EV steps (±4 EV),
AE lock, EIS and Auto/Daylight/Cloudy/Incandescent/Fluorescent WB. Rear supports
AF; front is fixed focus and has no AF-hold button. Rear AE, front AE, background
AE reset, rear AF hold and Daylight WB confirmation were exercised through actual
CameraX/Camera2 results. Other WB presets are capability-gated and require runtime
confirmation, but colour-chart testing is pending. EIS On was camera-confirmed
for rear FHD/30; motion effectiveness and field-of-view cropping were not measured.
Optical stabilisation remains device-managed. EIS is restricted to Auto/at-most-30
and SD/HD/FHD, with a clear reason when unavailable.

## Requested versus measured FPS, bitrate and quality

Default: **FHD, Auto FPS, Auto bitrate, SDR, Auto WB, EIS Off**. Saved unsupported
idle settings fall back FHD -> HD -> SD -> UHD; an unfinished session never silently
falls back to a different profile.

A bounded rear FHD/60 probe measured 217 frames over a 3.598377 s PTS span:
about **60.03 fps**, with 16.655–16.767 ms intervals. A two-segment 60-target session
measured **57.89 / 59.60 fps**. However, another recovered 60-target session measured
**51.82 / 48.35 fps**, including intervals up to about 150 ms. The longer landscape
sample contained **764 frames**, source duration **15.461 s**, mean **49.41 fps**,
with 16.566–116.633 ms intervals. Its last camera result still reported [60,60] and
16.675 ms sensor frame duration. The cause of the missing/variable recorded cadence
was not independently isolated; it must not be attributed to heat, light or an
encoder bottleneck without further evidence.

Consequently, **60 is explicitly a best-effort target**, with an on-screen warning
that drops can occur and Auto/30 is recommended for dialogue. The app does not
claim reliable CFR 60. Measured properties remain separate from requested values;
no FPS assertion was substituted for the unchanged A/V export verification gate.

Mean source cadence is `(sampleCount-1)/(maxPTS-minPTS)`; interval extrema and
reordered indication are retained separately. A nominal 30 request can also contain
a doubled interval. Video payload bitrate is sample bytes / reported video track
duration, excluding container overhead. For the initial short rear FHD/HD/SD Auto
clips it was approximately 2.50/1.31/0.44 Mbps; the FHD/60 probe was about 39.8 Mbps.
These are observations affected by content/clip length, not promised targets.

Recorder 1.5.3 has a bitrate request API but no public MIME-selection setter.
Capture uses Auto bitrate/SDR and independently requires finalised AVC/AAC tracks.
Unexpected codec/dimensions reject registration, preserve the candidate and retain
earlier clips. Manual bitrate, HEVC/HDR, manual ISO/shutter and high-speed capture
remain out of scope. More pixels or bitrate alone do not establish better visual
quality.

## Metadata, recovery and export contract

Session schema **6** retains all existing fields and adds nullable `captureProfile`
(version 1) and per-segment `media`. A capture profile freezes camera ID, quality,
FPS target/null Auto, requested EIS, WB, bound dimensions and legacy-output policy.
Version 1 implies SDR, automatic capture bitrate and AVC/AAC validation; alternative
codec/dynamic-range policies require an explicit future format change.

Each finalised segment records encoded dimensions, rotation, video/audio MIME,
sample rate/channels, video sample count, first/last PTS, interval extrema/reordered
indication, video bytes and reported track duration. Integers reject fractional,
string and overflowing values. A streaming extractor scan retains no decoded
frames or complete video. API 28+ uses sample sizes; API 26/27 reads a reusable
compressed-sample buffer bounded at 16 MiB. Oversized/unreadable input fails safely.

Versions 1–5 remain readable without a startup rewrite. Legacy paused recovery
inspects a readable source and requires the same bound dimensions before RESUME.
An explicit RESUME can journal the inferred Auto-FPS legacy profile. Malformed
profile JSON is preserved and blocks unsafe continuation/export. IDs, paths,
chronology, playback order, Keep/Discard and exclusive-end integer trims remain
independent. All saves use the existing AtomicFile repository.

The physical restart test saved a paused FHD/60 session, deliberately stored
conflicting HD/30 and Photo preferences, force-stopped only the isolated validation
app, then restored the exact original metadata bytes/profile, resumed and exported.
A new session explicitly clears inherited legacy-output policy even when reusing
an existing legacy binding; a JVM regression covers this separate edge case.

New export jobs are **version 2** and freeze the completed capture profile. Ordered,
trimmed Media3 compositions retain source dimensions and source cadence, with no
new-session 720p/30 cap. AVC bitrate is automatic; output AAC is 128 kbps, 48 kHz
stereo. Verification checks the saved profile dimensions and source display geometry,
including orientation. Space preflight scales with duration, dimensions and FPS.

Legacy sessions/version 1 jobs keep their original at-most-720p/30-cap/4 Mbps
policy, including retry. No existing job silently changes policy. M7.1 leading-
audio-gap handling, full decode, publication protection and **100 ms** duration/
start/end A/V rejection limits are unchanged. Originals and completed session
metadata are not cleaned up. Profile changes are blocked throughout an unfinished
session; the UI explanation follows live session state, rather than bind-time state.

## Independent media and photo checks

Ten copied native-camera finished outputs and two camera probe sources decoded
fully with FFmpeg, without reported decode errors. ffprobe independently confirmed
AVC/AAC, dimensions, rotation/display orientation, cadence and audio properties.
Untrimmed 60-target source/output frame counts matched exactly: 278/278 for the
two-segment session and 764/764 for the longer landscape sample. The controlled
fixture contained 252 frames at 60 fps. Output portrait FHD retains a 90° display matrix; the longer landscape export is
1920×1080 landscape. Native AAC was 48 kHz stereo with nonzero PCM, but ambient
samples were very quiet (roughly 10^-5–10^-3 RMS in first-second probes). This is
not a human speech intelligibility or perceptual lip-sync validation.

A separate **synthetic-input, real-device export** used four NEW 1080p/60 sources:
red, blue, yellow and discarded green. Saved order was yellow/red/blue, each trimmed
[350,1750) ms. Independent `check_export_fixture.py` verified:

- AVC 1920×1080, 60 fps, 4.200 s video; AAC stereo 48 kHz, 4.254708 s audio.
- Yellow -> red at 1.4 s -> blue at 2.8 s; discarded green absent.
- White flashes at 0.75, 2.15, 3.55 s; chirps at 0.76, 2.16, 3.57 s.
- Output flash/chirp separation 10–20 ms in this fixture. The source chirp was
  intentionally 20 ms before its flash; this is not a zero-offset accuracy claim.
- All four source MP4 SHA-256 hashes unchanged after export.

This checks scene order, non-keyframe cuts and bounded marker alignment separately
from decoder success. It does not establish universal frame-accurate export.
`tools/make_profile_fixture.py` reproduces the dedicated generated inputs without
overwriting existing files.

Thirteen copied JPEGs decoded independently with Pillow. Observed rear output was
4096×3072 (EXIF 6 portrait / 1 landscape); front output was 2302×1740, EXIF 8.
Device tests also check non-mirrored EXIF orientations, display orientation,
MediaStore pending status and opening/returning from the gallery. These checks do
not certify manual image sharpness or comparative photo quality.

## Test commands and final case accounting

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint --offline --no-daemon
# Isolated hardware build; never substitute the production package for destructive test setup.
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PvalidationSuffix=.m9validation --offline --no-daemon
adb -s ZY22KJQLTV install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s ZY22KJQLTV install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s ZY22KJQLTV shell am instrument -w -r -e class com.calvinmazhindu.skitcam.video.VideoDeviceTest com.calvinmazhindu.skitcam.m9validation.test/androidx.test.runner.AndroidJUnitRunner
```

| Final passing Android cases | Count |
| --- | ---: |
| VideoDeviceTest (qualities, lock/trim/order, front, preferences/race, 60 segments, longer landscape, probe) | 7 |
| ProfileRecoveryDeviceTest (separate processes) | 2 |
| CameraDeviceTest (clean final complete suite) | 11 |
| RecordingDeviceTest | 6 |
| PauseEnvironmentDeviceTest | 4 |
| ArrangementDeviceTest | 4 |
| TrimDeviceTest | 4 |
| AudioDeviceTest | 1 |
| LeadingGapConsumerDeviceTest | 4 |
| ExportJournalDeviceTest | 5 |
| TimingInvestigationDeviceTest (15 renders) | 1 |
| ExportDeviceTest (STOP/Play/Share/New Recording + controlled fixture) | 2 |
| **Total distinct cases** | **51** |

JVM additions cover defaults, supported combinations, malformed/numeric metadata,
requested/observed separation, VFR/reordering, codecs/dimensions, schema/legacy
round trips, immutable contracts, profile restrictions/recovery, source-mismatch
preservation, decisions/order interaction, storage overflow and new-session legacy
policy isolation. Existing 100-kept limits, audio, orientation, trimming, atomic
persistence, failure, duplicate/stale events and export tests still pass.

## Errors found and resolutions

- Kotlin experimental SessionConfig opt-in was required by the pinned API; added
  the proper Kotlin opt-in, without inventing a different API.
- Lint found API 28 `sampleSize` on min SDK 26; added the bounded older-API path.
- A front-capability test read the previous rear binding too early; it now waits
  for streamed front-camera readiness and mode transition completion.
- The new recovery test incorrectly assumed a 60 target guarantees >=55 measured
  FPS. Actual variable recordings disproved that assumption. Recovery now checks
  exact saved/applied profile and valid measured source facts, records the cadence,
  and the UI warns/displays it. A/V verification was not changed.
- The previous preview callback could acknowledge the prior settings during a new
  selection; binding revisions now guard preference commit/readiness. A regression
  deliberately delivers that callback before rebind. The recovery test also waits
  for idle mode-preference completion before selecting a profile.
- The first longer-landscape test injected 270° before its display pose was stable,
  causing Activity interruption and CameraX finalisation error 8. Its candidate
  remains in isolated session `64b00843-5f97-4cdf-a2bc-a3d4a7c60b41`. The fixture now
  sets/restores a temporary display rotation consistently with the injected pose;
  the 15-second run passed. The recording orientation policy was not modified.
  Subsequent runs that inherited that settled test Error were invalid prerequisites;
  setup now closes only settled errors in the isolated M9 package through STOP.
- Initial photo landscape/touch cases failed once; both passed individually and
  all 11 passed in the final clean suite. The initial transient cause was not
  established; failure screen/tree diagnostics were added. No verified photo
  implementation defect was inferred from those first failures alone.
- The older audio test also inherited the paused fixture. Its standalone attempt
  waited for video readiness without explicitly selecting Skit Video after Photo
  mode. Setup now waits for recovery and selects that mode; the original audio
  assertions passed in a separate final run (23.42 s).
- The finished-actions test initially skipped a deliberately paused restart fixture;
  it passed after normal completion of that isolated fixture. It genuinely opened
  the player, advanced playback, opened/dismissed Android Share and returned through
  New Recording to a ready camera. No share was sent to another person/app.
- USB repeatedly disconnected or became unauthorised. Kernel logs later confirmed
  repeated descriptor/address failures with error -71, below the ADB layer. Interrupted installs/runs
  are not counted as passes. A host /tmp quota also truncated an evidence archive;
  evidence was moved intact to project storage and the archive was retransferred
  and validated before independent analysis. This was not a phone-media failure.

## Lint investigation

M8 reported 25 warnings; M7.1 reported 22. Source-line comparison against `eff822b`
identifies 11 M8 call sites absent in M7.1: two photo usable-space reports, eight
camera/photo URI suggestions and one checked preference commit. Other old UI call
sites disappeared, so net count is not a new-warning classification.

M9 fixes URI/bitmap KTX convenience calls, unchecked permission preference edits,
a redundant player wrapper (using merge), and explicit no-backup/no-transfer rules
consistent with the existing allowBackup=false policy. Production now has **8**:

- Five UsableSpace advisories: three existing export/session and two M8 photo
  reports. Actual free-space checks deliberately avoid assuming cache reclamation.
- Two existing pinned Gradle/test-JSON dependency update suggestions.
- One M8 checked preference commit KTX suggestion. Its Boolean is required for
  failure feedback; KTX edit returns Unit. It was retained deliberately.

No new emitted warning remains relative to M8, and nothing is suppressed. The
isolated-label build additionally flags the production app-name resource as unused;
that warning is absent from the production build.

## Preservation, changed files and handoff

The completed SHA audit covered production 740, M7.1 control 28 and all 279 original
M8 validation files. M8 had six additional files when audited; none of its original
files changed. All M9 capture tests used a separate application ID. Failed candidates,
including the orientation-interruption fixture, were retained. Four generated scene
sources and 14 earlier immutable export-input hashes also matched after processing.

Durable local evidence is under **`.validation/m9/`**, ignored by Git: manifests,
logs, camera capabilities, source/output copies, independent reports and build logs.
`/tmp/skitcam-m9` is a convenience symlink. No source cleanup was performed.

Changed implementation areas:

- New `video/VideoProfile.kt`, `VideoJson.kt`, `VideoInspector.kt`, `VideoController.kt`.
- CameraPreview, MainActivity, CameraModel/CameraTools and recording port/runtime/
  engine/store/codec for safe binding, immutable profiles and measured facts.
- ExportModel/Journal/Media/Service for immutable native output policy and geometry.
- Build/version/manifest; backup rules and merge-based player controls; safe KTX
  changes in media shortcuts, photo store, export/recording controls and thumbnails.
- New JVM/video/recovery tests, existing codec/export/device regressions, fixture
  generator, README, this report and ignored evidence-directory rule.

For Milestone 10: retain the versioned profile/job contract, the 100 ms timing gate,
leading-gap handling and original-file protections. Prioritise sustained/high-load
FPS investigation and additional hardware matrices before expanding codec/manual
controls. Manual scene checks remain for EIS crop/motion effectiveness, AF sharpness,
AE response under changing light, WB colour charts, large-font usability and spoken
speech/lip-sync. API 26/27 compatibility passed lint, but those devices were not
physically tested. There is no CFR-60, universal frame-accuracy or subjective quality
certification. The regular-app upgrade, ready preview and post-launch audit
completed successfully after the USB connection stabilised. All isolated hardware cases above completed.

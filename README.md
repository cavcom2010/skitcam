# SkitCam

A native Android photo and segment-video camera built with Kotlin, Jetpack Compose and CameraX.
Tap the Photo ratio in the header to choose **1:1, 3:4, 9:16, Full** in portrait
(or **1:1, 4:3, 16:9, Full** in landscape). Ratios flip with orientation: 3:4
becomes 4:3 and 9:16 becomes 16:9. Portrait 9:16 uses the full screen width, and
**Full is exactly 100% of the current screen on any device** rather than a fixed
ratio. Saved JPEGs match the chosen framing. See
[VALIDATION_PHOTO_ASPECT_RATIOS.md](VALIDATION_PHOTO_ASPECT_RATIOS.md) for device
tests, production installation, measured bounds and preservation results.

Landscape **Photo** now uses the safe-area height behind the gradient header and
mode controls while retaining its default 4:3 fitted frame and separate shutter
dock. See [VALIDATION_PHOTO_LANDSCAPE_VIEWFINDER.md](VALIDATION_PHOTO_LANDSCAPE_VIEWFINDER.md)
for before/after bounds, touch tests, JPEG comparisons and unexecuted checks.

Version **0.11.2** lets the camera screen follow the phone's physical orientation
in Skit Video and Photo even when Android Auto-rotate is off, while an active
recording stays locked to the orientation it started in. Landscape video also
uses the full safe height behind the gradient toolbar and mode buttons, removing
the previous narrow central band. See
[VALIDATION_CAMERA_ROTATION.md](VALIDATION_CAMERA_ROTATION.md) for the root cause,
measured geometry, preview-versus-MP4 comparisons and limits.
Version **0.11.1** gives portrait Skit Video a full-width, aspect-correct viewfinder
where safe-area geometry permits, extending beneath the gradient-backed controls.
Default 1.0× zoom, recording profiles and Photo layout are preserved. See
[VALIDATION_IMMERSIVE_VIEWFINDER.md](VALIDATION_IMMERSIVE_VIEWFINDER.md) for measured
geometry, preview-versus-MP4 comparisons and control-overlap limitations.

Version **0.11.0** adds optional portable project ZIP export from a selected saved
SkitCam video. Normal STOP → verified MP4 → live-camera return remains unchanged.
See [VALIDATION_M11.md](VALIDATION_M11.md) for archive format, validation and limits.

Version **0.10.1** corrects video viewfinder cropping on both cameras and explicitly
applies the default 1.0× zoom on mode/lens changes. See
[VALIDATION_VIDEO_FRAMING.md](VALIDATION_VIDEO_FRAMING.md) for preview-versus-MP4 evidence.
Milestone 10 streamlines the viewfinder, settings and microphone information. After
a verified STOP/export it returns automatically to the camera, with a brief
**Video saved · View** message and an updated recent-media shortcut. See
[VALIDATION_M10.md](VALIDATION_M10.md) for validation and remaining checks.
Milestone 9 adds camera-specific video quality, FPS targets, session profile locking,
confirmed AE/WB controls and optional electronic stabilisation. See
[VALIDATION_M9.md](VALIDATION_M9.md) for measured output and remaining checks.
Milestone 8 adds Photo / Skit Video modes, JPEG capture, camera switching, focus,
zoom and a camera-first interface. See [VALIDATION_M8.md](VALIDATION_M8.md) for
measured capabilities, validation results and remaining checks.
Milestone 7 adds truthful microphone discovery, recording audio-level feedback
and safe pauses on external-input loss. Milestone 6 provides automatic MP4 finishing after STOP. The export pipeline respects
Keep/Discard, saved playback order and non-destructive trim boundaries. Original
recordings and session metadata remain in app-specific storage.

STOP finalises the active segment, then shows a minimal finishing view while the
existing pipeline assembles, verifies and publishes to **Movies/SkitCam**. Success
returns automatically to the live **Skit Video** camera. A brief **Video saved**
message offers **View** for that video; the recent-media thumbnail opens **Gallery**,
where older saved videos/photos can be browsed and selected for playback and Share. The next START creates a different session ID; the completed
session, original files, edits and export record remain intact. Failures/cancellation
retain explicit Retry / New Recording recovery actions and never show false success. Finishing uses one foreground
job at a time; Android may show a processing notification. No accounts, backend or
network permission are required.

The recording/export workflow has been exercised on the Moto G54, including Play
and the standard Share chooser. Reordered non-keyframe trims also passed independent
scene/audio measurements. See [VALIDATION_M6.md](VALIDATION_M6.md) for actual test
results, preservation evidence and remaining platform/stress checks.

## Optional portable project export (0.11.0)

Open **Gallery**, select a completed SkitCam video, then **Video options → Export
Project**. The finished video is always included. Original segments are included
by default; **discarded takes are excluded unless you explicitly select them**.
Originals contain the entire take, including footage outside saved trims. The
portable metadata retains Keep/Discard and edit decisions even when a source is
omitted. This action exports only the selected session, never the whole gallery.

Choose a destination with Android's system file picker. Choose a local folder for
an offline archive; another provider you explicitly choose may sync using its own
service. No account, network permission or broad filesystem permission is added.

```text
SkitCam_Project_<date>_<job-id>.zip
├── Finished_Video.mp4
├── Original_Segments/segment_001.mp4  # selected originals, recording chronology
├── session.json                     # portable schema 1, UTF-8
└── checksums.sha256                  # SHA-256 for every other entry
```

Segment filenames are relative and generated; stable IDs, recording chronology
and saved playback order are separate. Saved trims use integer milliseconds,
start inclusive/end exclusive. `savedTrim` preserves the exact saved decision;
`finishedTimeline[].resolvedRange` describes what the finished MP4 used. Older
unknown source facts remain null. No private Android paths, content URIs or camera
IDs are required by the manifest. This is an archive format, **not a DaVinci Resolve
timeline import format**.

A separate foreground copy job streams uncompressed ZIP entries with bounded
buffers. It never owns the camera, microphone or encoder and waits while recording,
photo saving or automatic MP4 finishing is busy. You can return to the camera; its
notification or Gallery's **Project exports** action opens the latest job. A job
snapshot and recovery receipt are atomic and separate from recording metadata.
Retry uses the existing destination; a completed write is verified again rather
than copied twice. An interrupted write restarts from the beginning, not a claimed
mid-file resume. Android may stop long-running work; retry remains explicit.

The destination is named `.partial.zip` until every entry and the entire ZIP have
passed readback checks, including its closing directory. Final rename and another
whole-file hash check precede success. Providers must support persistent access,
readback and safe rename. Unsupported destinations fail with an explanation;
publication is never inferred merely from a successful write. If a provider loses
its rename response, **Locate saved archive** verifies the existing document
before accepting it. Cancellation removes only this job's identifiable partial
document when the provider allows it. Unremovable partials remain clearly named;
original footage, finished MP4s and session metadata are never cleaned up.

ZIP64 supports entries/archives above 4 GiB with compatible tools/providers. It
does not remove a destination filesystem's limits (for example FAT32), provider
quotas or Android processing limits. The media-size estimate excludes small ZIP
headers/metadata; MP4s are stored without compression, so allow additional room.
There is no full extra local copy of the project. Verification requires additional
streaming reads, so large archives take time.

Copy the ZIP from the selected folder to Ubuntu/Windows using USB file transfer,
a removable drive or your chosen file-transfer method. On Ubuntu, independently
check it with:

```bash
python3 tools/verify_portable_project.py /path/to/SkitCam_Project_....zip
unzip -t /path/to/SkitCam_Project_....zip
```

Extract with a current ZIP64-capable tool. From inside the extracted folder,
`sha256sum -c checksums.sha256` verifies payloads and metadata. On Windows, 7-Zip
can test/extract the archive and PowerShell `Get-FileHash -Algorithm SHA256` can
check individual files against the manifest. Import the original MP4s into a
normal desktop editor such as DaVinci Resolve; reconstruct saved order/trims
manually using `session.json` if needed. Native timeline generation is future work.

## Save return and recent media (0.10.0)

A small versioned receipt in the existing camera preferences remembers the completed
export job, session, published URI and whether its camera return was handled. This
is UI state, separate from immutable session/export metadata. Duplicate completion
callbacks cannot create a session or publish again. The camera is considered back
only after CameraX is open, the preview is streaming and the video profile is ready.
START still requires microphone permission and the existing valid orientation.

An unseen publication recovered on startup opens Skit Video once. The saved ordinary
mode preference is unchanged; a later ordinary launch can still restore Photo.
An interrupted recording session always takes priority over that preference. The
brief save message is best effort, rather than an exactly-once notification across
process death. A committed return receipt prevents repeated automatic navigation;
the published video remains accessible through its retained content URI and Gallery.

The toolbar uses labelled-accessibility icons for Settings, microphone details and
Quick settings. Idle duration/count clutter is hidden; recording and PAUSE retain
their useful duration/count and explicit RESUME/STOP controls. Full settings are
grouped into Camera, Photo, Video, Audio, Preferences and expandable Advanced
information. The microphone summary groups built-in/other endpoint categories for
presentation only, preserves every raw device ID/type in Diagnostics, and does not
claim that a connected receiver is the confirmed recording input.

## Photo and camera controls

The first launch opens **Skit Video**. The app remembers the last successfully
bound mode and each mode's lens. Startup waits for preferences and recording/export
recovery; an unfinished session takes priority over a Photo preference. Mode and
lens switching are disabled throughout an unfinished session, including PAUSE,
and while finishing or saving a photo. Switching never silently stops a session.

Choose **Photo** and press the white shutter. Photos use CameraX JPEG quality
priority and the highest suitable supported resolution, with a standard-resolution
binding fallback. Output goes to **Pictures/SkitCam** after validation. Resolution
is reported from actual camera/output facts; more pixels do not guarantee better
image quality. Only camera permission is needed for Photo on modern Android.
Android 8/9 also require the existing legacy storage permission to publish.

The **camera-switch icon** switches front/rear before recording or while Photo is idle. The selfie
preview is mirrored; saved photos and videos request unmirrored output. A video
session locks its lens and orientation for all segments and recovery.

Tap the unobstructed preview to focus. Pinch to zoom, or open **Quick settings** for a
precise zoom slider, a grid, supported exposure compensation and a Photo timer
(Off / 3 / 10 seconds). Unsupported adjustments are hidden. Focus, zoom and
exposure reset when changing cameras; grid and timer preferences persist.
The timer cancels when the app leaves the foreground. **Settings** opens the full
settings screen while idle or PAUSED, leaving the camera bound underneath. Capture
profile controls remain locked during an unfinished session. Quick settings can
close without stopping video; its scrollable panel uses less than half the display.

The gallery thumbnail previews the most recent saved media and opens a **Gallery**
list with **All / Videos / Photos** filters. Older finished videos and photos remain
browsable; selecting a video opens Play/Share, and Back returns to Gallery. The
list shows accessible SkitCam media from Movies/SkitCam and Pictures/SkitCam.
**Browse phone** opens Android's picker for media from other apps or installations,
without new broad media permissions. Missing media and absent viewers show feedback. Photo publication uses
private staging, an atomic recovery journal, verified MediaStore copies and pending
rows on Android 10+. Failed publication retains the private capture for **Retry
Save**; interrupted captures are not mistaken for completed photos. Only a new
job's verified temporary JPEG is removed after its identical gallery copy succeeds.
Original video segments and session metadata are never cleaned up automatically.

Preview binds with ImageCapture in Photo and the existing Recorder in Skit Video.
The app does not require all three use cases simultaneously. Camera adjustments
never rebind an actively recording camera. Idle profile changes rebind safely. The video indicator shows bound resolution and
a short label such as **1080p · Auto** or **1080p · 30 target**, without claiming a
measured capture frame rate. Detailed dimensions, cadence, codec and session facts
remain in the expandable Advanced / Technical Information section.

New session metadata is version **6**, retaining the v5 `lens` and adding an optional
immutable `captureProfile` plus observed per-segment `media` facts. Versions 1–5
remain readable without a startup rewrite (versions 1–4 default to Rear). Existing v4 integer trim boundaries,
segment IDs, chronology, Keep status and playback order are unchanged. A normal
subsequent user edit saves through the same atomic repository in the latest format.


## Video capture settings

Before START, open **Quick settings** or **Settings** to select a quality reported by the
active camera's CameraX SDR capabilities. The Moto G54 reports FHD/HD/SD; it does
not expose UHD through this pipeline. Front/rear preferences are separate. A
profile preference is committed only after binding, streaming and any requested
white-balance confirmation succeed. Unsupported saved preferences cannot force
an unsupported binding. Capture uses CameraX automatic bitrate/encoder selection;
every completed segment must independently verify as AVC video plus AAC audio.

FPS choices are labelled **targets**, not measured constant-rate guarantees.
Configuration-specific CameraX ranges determine availability. Auto remains the
default. The Moto G54 offers a verified rear FHD 60 target and 30 targets for
reported profiles; fixed 24/25 targets are absent. Front/HD/SD do not offer 60. Actual sample counts,
presentation timestamp span, cadence variation, dimensions, codecs, audio format
and measured video payload bitrate are saved after successful finalisation.
A nominal 30 fps request may still contain dropped/variable intervals. Sustained
60-target output measured about 49 fps in one device run even with a 60 sensor
request, so the UI warns and recommends Auto/30 for dialogue. This is not CFR 60.

The full capture profile freezes at START and remains unchanged through PAUSE,
review, trimming, arrangement, RESUME and recovery. Quality, FPS, electronic
stabilisation and white balance cannot be reconfigured mid-session. Unsupported
recovery profiles leave the live preview available where feasible and block
RESUME; STOP preserves the files. Legacy paused sessions infer a conservative
Auto-FPS profile from an actual readable source and require the same dimensions
before resuming. They are not rewritten merely by opening the app.

AE lock is distinct from EV compensation and is labelled locked only after a
Camera2 capture result confirms it. **Hold focus** uses CameraX AF metering with
auto-cancel disabled; a successful focus result is required. It returns to Auto
when released, backgrounded or rebound. Fixed-focus cameras show no AF hold.
White-balance presets use only reported modes and require capture-result
confirmation. They are session settings. These narrow Camera2 options never
force sensor frame rates, ISO, shutter speed or custom AF modes.

Electronic stabilisation is offered only when reported by CameraX, with supported
SD/HD/FHD, at-most-30 targets. Its status distinguishes a request from an observed
camera result. Optical stabilisation remains device-managed. Cropping and visual
effectiveness need scene/motion validation; a reported capability alone is not an
image-quality claim. HDR, HEVC, manual bitrate/ISO/shutter and slow motion remain
out of scope. The existing **100 ms** export timing gate is unchanged.

## Recording

- **START** creates a session and records its first segment. Allow microphone
  permission when requested, then press START again. Audio is mandatory.
- **PAUSE** stops the recording and waits for CameraX finalisation, audio/video
  sample validation and metadata persistence before registering the segment.
- **RESUME** records a new MP4 in the same session. Paused time is excluded from
  the duration. Up to 100 kept segments are supported per session; discarded
  segments stay in the history without using retained capacity.
- **STOP** finalises an active segment and completes the session; while paused,
  it completes the session without creating another segment. Finishing begins
  automatically. Verified publication returns to the camera automatically; the
  next START begins a separate session and preserves all earlier files.

Hold the phone upright and steady before START. The physical orientation must
match the preview. UI and video orientation stay locked for the entire session;
RESUME is blocked until the phone returns to the exact original direction,
including which edge is up. Flat or uncertain sensor readings cannot enable
recording. New sessions default to FHD (1080p), Auto FPS, SDR, Auto bitrate and
electronic stabilisation Off. Unavailable saved qualities fall back in the order
FHD → HD → SD → UHD before a new session; interrupted sessions never silently
fall back to a different profile.

Leaving the foreground stops/finalises the active segment and pauses on success.
Returning never resumes automatically. Errors retain confirmed segments and any
unfinished candidate file; press STOP to close a settled error before START.
An unresolved finalisation blocks new recordings, including after closing and
reopening the activity. A 30-second watchdog reports a stuck operation.

## Microphones and audio status

Audio routing remains **Automatic — managed by Android**. CameraX 1.5.3 does not
expose supported per-device input selection, so SkitCam has a read-only audio
input panel rather than a misleading selector. It lists recording endpoints,
including built-in, USB, wired and exposed Bluetooth inputs. Connected receivers
are not reported as active merely because they are present.

Tap the compact microphone indicator to inspect connected inputs and Android's
reported camcorder routes. CameraX does not expose its audio session identifier,
so those observations remain explicitly **unverified for SkitCam**. No hidden APIs,
Bluetooth SCO forcing, communication-mode changes or parallel AudioRecord capture
are used. The meter shows CameraX's sampled relative peak during recording only;
it is neither calibrated loudness nor proof of microphone identity. Zero level
alone is not an audio error. Muted, silenced, failed and unavailable audio have
separate labels, and stale samples stop displaying as live.

If an external recording endpoint disappears, SkitCam conservatively finalises
the current segment and pauses, even if that endpoint's use was unverified.
It also pauses on a reported route change for the same observed capture session.
Check the input and press RESUME explicitly; reconnecting does not resume capture.
For phone-microphone fallback, disconnect external audio inputs, resume and check
a test recording. SkitCam cannot force built-in routing through this CameraX API.
Original files, saved edits and export behavior are preserved.

Only built-in hardware is currently available for Milestone 7 validation.
External USB/wired/Bluetooth routing tests remain pending. Milestone 7.1 corrects
a reproduced leading-audio-gap composition problem while retaining the existing
verification limits; see [VALIDATION_M7_1.md](VALIDATION_M7_1.md). See
[VALIDATION_M7.md](VALIDATION_M7.md) for actual results and limitations.

## PAUSE environment

Successfully finalised segments appear in a scrollable strip on the **left in
portrait** and at the **bottom in landscape**, alongside the live preview.
Thumbnails load lazily off the main thread, with one extraction at a time and a
2 MiB memory cache. Pending recordings and failed candidates are never shown.

Tap a thumbnail to open review. Playback starts paused: use Play and the seek
bar to review video with sound. A kept segment shows **Discard**, **Trim** and
**Close**; a discarded segment shows **Restore**, **Trim** and **Close**. Close
or Back releases playback without changing the decision. Each shelf card also
has a **More actions** menu for direct Discard/Restore, with Undo after a
discard. Review is optional; RESUME releases playback and audio focus before
recording. Backgrounding or recreating the activity closes review. Player
selection and playback position are not persisted.

**Play skit** plays every kept segment in saved playback order without creating
an MP4. **Play from here** sets that kept segment as the loop anchor; Loop ON
repeats from that anchor through the last kept segment, while Loop OFF stops at
the end. Discarded segments are skipped, saved trims are respected, and the
current clip is shown. Playback never edits session metadata. It closes and
releases Media3 before RESUME starts a new segment.

Every successful segment starts as **Kept**. **Discard** excludes it from the
counter and session duration while preserving its MP4. Discarded items remain
labelled in the strip and can be restored, subject to the 100-kept limit.
Missing/corrupt media displays a placeholder or playback error without silently
changing its status. Decisions are accepted only while Paused and published
after atomic persistence. See
[VALIDATION_M12A.md](VALIDATION_M12A.md) for shelf actions, loop-anchor
semantics, 20-clip measurements, process/reboot recovery and preservation results.

## Arrangement

Arrange is enabled while Paused with at least two kept segments. It opens an
overlay in the same activity; the camera stays bound underneath. Rows show
playback position, original segment number, thumbnail, duration and a drag handle.
Hold a handle and drag to reorder; holding near an edge scrolls the list. Move
up/down buttons and accessibility actions provide alternatives to dragging.

Moves are tentative until **Done** saves atomically. **Cancel** or Back leaves
the saved order unchanged. The draft survives backgrounding and activity
recreation in memory; a process restart returns to the last saved order.
Preview opens the existing player without autoplay or Keep/Discard controls;
Back to Arrange preserves the draft. Playback is released when leaving preview,
backgrounding, closing Arrange or starting another recording.

The PAUSE strip stays in recording chronology. Arrangement only changes a
separate playback-order list. Discard removes a segment from that list; restoring
it appends it to the end. New recordings also append. Existing arranged items
keep their relative order, original filenames and recording numbers. STOP preserves
the chosen order and applies it during verified MP4 assembly.

## Trim

While Paused, open a segment's review and choose **Trim**. Drag the start/end
boundaries over the frame timeline, or use 100 ms adjustments and integer
millisecond entry. Zoom in/out and the position slider support long segments;
Show start/end brings either handle back into view. The shaded areas are excluded.
Play previews only the selected interval, including audio; seeking stays within it.

**Save** atomically stores the boundaries. **Cancel** or Back writes nothing.
**Reset Trim** selects the full verified source in the draft; press Save to retain
that reset. Save and Cancel return to the PAUSE environment. Reopen review to see
the saved result and its Trimmed indicator. Arrangement previews also respect
saved intervals. Discard/Restore and reordering preserve every trim.

Drafts survive backgrounding and activity recreation in memory; playback is
released and the source is rechecked on return. Process death drops unsaved edits.
Saved edits survive restart and STOP. The camera remains bound behind the editor;
recording always releases review/trim playback first.

Preview uses Media3 clipping and exact-seek requests. Decoder preroll, sample
boundaries and device output timing still apply: **frame-accurate preview or export
is not claimed**. Timeline thumbnails use approximate sync-frame sampling. No
source MP4 is rewritten, rendered, combined or deleted by trimming.

### Trim metadata and time convention

Version 4 adds a nullable `trim` object to each segment:

```json
"trim": {
  "sourceDurationMs": 4000,
  "trimStartMs": 300,
  "trimEndMs": 3500
}
```

These are integer Long milliseconds in the normalized source presentation
timeline. The end is exclusive; effective duration is `trimEndMs - trimStartMs`.
The minimum is 500 ms. A verified shorter source may retain only its full interval.
Historic CameraX `durationMs` remains separate and is never overwritten by edits.
New recordings default to a full range using native post-finalisation inspection.
Trim resolves the unclipped, seekable Media3 duration and captures native file
facts; Save rechecks file identity and media validation on the recording worker.
Untrimmed full-source intent adapts only in the draft. Duration discrepancies
require explicit review/Save; saved out-of-bounds user cuts
are not silently clamped. Reset can select the newly verified full duration.

Versions 1–3 load without trim decisions (`trim: null`) and resolve media lazily.
Legacy recorded durations are explicitly estimates until verified through Trim.
Malformed v4 trim values round-trip during unrelated saves, display a warning,
and require a verified Reset followed by Save to repair. Missing/corrupt sources
cannot be saved. Unknown effective durations are not fabricated; totals containing
unresolved entries are marked estimated. MP4 identity, history, Keep status and
playback order remain independent of trim metadata.

## Build

Requires JDK 17 and an Android SDK containing platform 36 and build tools 35.0.0.
Android Studio, an emulator and a globally installed Gradle are unnecessary.

Set `ANDROID_HOME` to your SDK directory, or create an ignored `local.properties`:

```properties
sdk.dir=/home/cmazh/Android/Sdk
```

```sh
./gradlew :app:assembleDebug :app:lintDebug --no-daemon
```

The first build downloads dependencies from Google Maven, Maven Central and
the Gradle Plugin Portal. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.
Build concurrency is capped at two workers with a 1536 MiB Gradle heap and
in-process Kotlin compilation to suit a machine with approximately 6.5 GiB RAM.

## Install and launch

Enable USB debugging and authorize the computer on the phone. Select the device
explicitly if more than one is attached:

```sh
~/Android/Sdk/platform-tools/adb devices -l
~/Android/Sdk/platform-tools/adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb -s DEVICE_SERIAL shell am start -W -n com.calvinmazhindu.skitcam/.MainActivity
```

Allow camera access at first launch. Denial shows a retry or app-settings action;
returning from settings checks permission again. Preview follows the activity
lifecycle, releases its use case on disposal, and reports camera startup errors.
Both rear and front cameras are supported. Video requests a preview aspect matching
the CameraX video profile, then fits the actual capture frame between the controls
without stretching or full-screen center cropping. Photo keeps its fitted 4:3
capture viewport. Normal 4:3/16:9 sensor and stabilisation differences remain;
front preview is mirrored while saved video is unmirrored. Saved media keeps its
independent orientation. Startup and mode/lens changes explicitly apply 1.0× zoom;
deliberate user zoom survives same-choice rebinding and PAUSE/RESUME.

## Files and recovery

Internal app-specific storage contains:

```text
files/sessions/<session-id>/
├── session.json
├── segment_001.mp4
├── segment_002.mp4
└── export/
    ├── job.json
    └── output.mp4
```

Metadata records session state, timestamps, ordered segments, recording duration,
physical orientation, encoded video rotation, absolute file paths, errors and
the pending attempt, lens, immutable capture profile and observed source properties.
Version 6 retains immutable segment IDs and a separate
`playbackOrder` list of kept IDs. Versions 1/2 load deterministic IDs derived from
session ID and recording number, with chronological playback order; version 1
defaults every successful segment to Keep. Legacy metadata upgrades only on a
subsequent save. Writes use Android AtomicFile on one
worker. MP4s are never overwritten or automatically deleted by SkitCam. New
segment numbers use the full history, including discarded items, so freeing
capacity cannot reuse an existing filename. Failed candidates remain
unregistered, even when CameraX produced a playable partial file.

At least 100 MiB free is required before a segment starts. Recording requests a
stop below a 50 MiB reserve. A storage failure can still prevent finalisation;
previously confirmed files remain retained. On process restart, safely paused
sessions are restored. An interrupted pending attempt restores as Error, without
silently certifying its MP4.

Original segment files are private to the app. Verified finished exports are
published to Movies/SkitCam. Debug builds
can inspect them through `adb shell run-as com.calvinmazhindu.skitcam ls files/sessions`.
Uninstalling the app or clearing its data removes app-specific storage; use
`adb install -r` to upgrade while preserving recordings.

## Tests

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
~/Android/Sdk/platform-tools/adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
~/Android/Sdk/platform-tools/adb -s DEVICE_SERIAL shell am instrument -w -r -e class com.calvinmazhindu.skitcam.recording.RecordingDeviceTest,com.calvinmazhindu.skitcam.review.PauseEnvironmentDeviceTest,com.calvinmazhindu.skitcam.arrange.ArrangementDeviceTest,com.calvinmazhindu.skitcam.trim.TrimDeviceTest com.calvinmazhindu.skitcam.test/androidx.test.runner.AndroidJUnitRunner
```

JVM tests exercise transitions, duplicate/stale events, finalisation, failure
preservation, storage guards, the 100-kept limit, timeouts and orientation. Codec
tests cover legacy metadata, decisions, restart restoration and write failures.
Device tests grant camera/microphone permission and create real audio/video MP4s.
They retain their test sessions and leave the last one paused for restart testing;
they skip recording if an existing open session is detected. Do not run the
hardware suite during a session you want to continue recording. Review tests play
real files and record afterward, exercise both strip layouts and scrolling, and
preserve their diagnostic fixtures. Arrangement tests inject real long-press touch
gestures, exercise edge auto-scroll, cancellation, preview, stale saves and
recording afterward. Temporary rotation locks are restored afterward.

## Versions

| Component | Version |
| --- | --- |
| Gradle Wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose compiler plugin | 2.3.21 |
| Compose BOM | 2025.12.00 |
| Activity Compose | 1.12.1 |
| Lifecycle | 2.10.0 |
| CameraX camera2 / lifecycle / view / video | 1.5.3 |
| Media3 ExoPlayer / UI / Transformer | 1.11.1 |
| Minimum / compile / target SDK | 26 / 36 / 36 |

The genuine Wrapper was generated by Gradle's `wrapper` task. Its JAR SHA-256 is
`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`,
verified against the official Gradle checksum. Wrapper properties also pin the
official distribution SHA-256. Wrapper files must be retained in source control.

## Structure

```text
.
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/                 # Genuine wrapper JAR and properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/calvinmazhindu/skitcam/
        │   ├── MainActivity.kt     # Camera permission and lifecycle entry point
        │   ├── CameraPreview.kt    # Mode-specific preview/use-case binding
        │   ├── camera/            # Modes, camera controls and preferences
        │   ├── video/             # Capture profiles, capabilities and source facts
        │   ├── photo/             # JPEG capture, validation and publication
        │   ├── audio/             # Truthful input discovery and recording health
        │   ├── export/            # Composition, verification and gallery publishing
        │   ├── recording/         # State machine, CameraX adapter, metadata,
        │   │                      # orientation, ViewModel and controls
        │   ├── review/            # Lazy thumbnails, review UI and player ownership
        │   ├── trim/              # Validated ranges, transient editor and timeline
        │   └── arrange/           # In-memory drafts and touch arrangement overlay
        └── res/                   # App name, theme and vector launcher icon
```

## Device checks

Check camera/microphone permissions, START/PAUSE/RESUME/STOP, orientation locks,
background/foreground behavior and retained MP4s. Physical sensor behavior needs
the phone to be held and rotated; the device engine tests inject a known pose
while using real CameraX, microphone and storage. See `VALIDATION_M5.md` for the
current results. Earlier validation reports preserve the Milestone 1 and 2 results.

## Automatic MP4 finishing

New profiled sessions re-encode to SDR H.264/AVC at their saved source dimensions
and preserve source presentation cadence, with AAC-LC 48 kHz stereo audio. Capture
and export bitrates are automatic; measured source bitrate is recorded separately.
Legacy sessions/jobs retain the previous at-most-720p, 30 fps cap and 4 Mbps export
policy. Smaller sources are not enlarged. Incompatible aspect ratios, HDR, missing tracks or broken retained
sources fail explicitly. There is no silent skipping, cropping, stretching or
source cleanup. Arbitrary frame rates and encoder properties require device
validation; export is not advertised as frame accurate.

Each session has an atomic `export/job.json` sidecar containing its immutable
recording snapshot, ordered clip boundaries, source hashes, processing state,
verification result and final MediaStore URI. Export job version 2 also freezes
the session capture profile; legacy version 1 jobs keep their original output policy.
After interruption, Retry restarts encoding or reuses a verified output checkpoint;
it does not resume an encoder at its previous frame. Publication uses a unique
job marker and pending MediaStore row to avoid duplicate gallery videos.

Original segments and the private verified export remain after success. This is
conservative and uses extra storage. There is no automatic deletion policy or
completed-session browser yet. Android 8/9 may request storage permission on Retry
for gallery publication; Android 10+ uses app-owned scoped MediaStore entries.

See [VALIDATION_M6.md](VALIDATION_M6.md) for the export architecture, test evidence
and remaining limitations. Final output verification includes streaming full-track
decoding and timestamp checks; those checks alone cannot prove every scene or
subjective lip-sync result.

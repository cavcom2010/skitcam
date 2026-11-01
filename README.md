# SkitCam

A native Android segment camera built with Kotlin, Jetpack Compose and CameraX.
Milestone 4 adds touch arrangement with independent playback order, preserving
the recording engine, live preview, thumbnails and Keep/Discard decisions.

**STOP retains separate MP4 segments and marks the session completed. It does
not combine them into a final video.** There is no trimming, export,
authentication, backend or network functionality.

## Recording

- **START** creates a session and records its first segment. Allow microphone
  permission when requested, then press START again. Audio is mandatory.
- **PAUSE** stops the recording and waits for CameraX finalisation, audio/video
  sample validation and metadata persistence before registering the segment.
- **RESUME** records a new MP4 in the same session. Paused time is excluded from
  the duration. Up to 100 kept segments are supported per session; discarded
  segments stay in the history without using retained capacity.
- **STOP** finalises an active segment and completes the session; while paused,
  it completes the session without creating another file. START after completion
  begins a separate session and preserves all earlier files.

Hold the phone upright and steady before START. The physical orientation must
match the preview. UI and video orientation stay locked for the entire session;
RESUME is blocked until the phone returns to the exact original direction,
including which edge is up. Flat or uncertain sensor readings cannot enable
recording. The recording quality defaults to HD (720p), with CameraX-supported
fallback when necessary.

Leaving the foreground stops/finalises the active segment and pauses on success.
Returning never resumes automatically. Errors retain confirmed segments and any
unfinished candidate file; press STOP to close a settled error before START.
An unresolved finalisation blocks new recordings, including after closing and
reopening the activity. A 30-second watchdog reports a stuck operation.

## PAUSE environment

Successfully finalised segments appear in a scrollable strip on the **left in
portrait** and at the **bottom in landscape**, alongside the live preview.
Thumbnails load lazily off the main thread, with one extraction at a time and a
2 MiB memory cache. Pending recordings and failed candidates are never shown.

Tap a thumbnail to open review. Playback starts paused: use Play and the seek
bar to review video with sound. Keep/Discard saves the decision immediately and
leaves review open. Close or Back releases playback without changing the decision.
Review is optional; RESUME releases playback and audio focus before recording.
Backgrounding or recreating the activity closes review. Player selection and
playback position are not persisted.

Every successful segment starts as **Keep**. **Discard** excludes it from the
counter and session duration while preserving its MP4. Discarded items remain
labelled in the strip and can be restored with Keep, subject to the 100-kept
limit. Missing/corrupt media displays a placeholder or playback error without
silently changing its status. Decisions are accepted only while Paused and
published after atomic persistence.

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
keep their relative order, original filenames and recording numbers. STOP keeps
the chosen order for future assembly, which is not implemented yet.

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
Only the rear camera is used; the preview fills the screen by center cropping.

## Files and recovery

Internal app-specific storage contains:

```text
files/sessions/<session-id>/
├── session.json
├── segment_001.mp4
└── segment_002.mp4
```

Metadata records session state, timestamps, ordered segments, recording duration,
physical orientation, encoded video rotation, absolute file paths, errors and
the pending attempt. Version 3 includes immutable segment IDs and a separate
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

Files are private to the app rather than published to the gallery. Debug builds
can inspect them through `adb shell run-as com.calvinmazhindu.skitcam ls files/sessions`.
Uninstalling the app or clearing its data removes app-specific storage; use
`adb install -r` to upgrade while preserving recordings.

## Tests

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
~/Android/Sdk/platform-tools/adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
~/Android/Sdk/platform-tools/adb -s DEVICE_SERIAL shell am instrument -w -r -e class com.calvinmazhindu.skitcam.recording.RecordingDeviceTest,com.calvinmazhindu.skitcam.review.PauseEnvironmentDeviceTest,com.calvinmazhindu.skitcam.arrange.ArrangementDeviceTest com.calvinmazhindu.skitcam.test/androidx.test.runner.AndroidJUnitRunner
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
| Media3 ExoPlayer / UI | 1.9.4 |
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
        │   ├── CameraPreview.kt    # Preview + video use-case binding
        │   ├── recording/         # State machine, CameraX adapter, metadata,
        │   │                      # orientation, ViewModel and controls
        │   ├── review/            # Lazy thumbnails, review UI and player ownership
        │   └── arrange/           # In-memory drafts and touch arrangement overlay
        └── res/                   # App name, theme and vector launcher icon
```

## Device checks

Check camera/microphone permissions, START/PAUSE/RESUME/STOP, orientation locks,
background/foreground behavior and retained MP4s. Physical sensor behavior needs
the phone to be held and rotated; the device engine tests inject a known pose
while using real CameraX, microphone and storage. See `VALIDATION_M4.md` for the
current results. Earlier validation reports preserve the Milestone 1 and 2 results.

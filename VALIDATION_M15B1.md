# M15B.1 — Recording resume reliability

Branch: `feature/m15b1-recording-resume`. Fix and test commit: `eb9e07cc54e39151668dc62a535d89fbbea2b436`. Baseline: `5e1818e64673f452c22c5bf965bbd0ce77b2dfb7` (merged M15A), with a [passing post-merge Android quality run](https://github.com/cavcom2010/skitcam/actions/runs/45168819834). The approved M14.2 UI, production application ID, release settings, recording engine, and media validation rules are unchanged.

## Reproduction and cause

On the Moto G54 (Android 15, API 35), the unchanged `RecordingDeviceTest#c_backgroundFinalizesAndExplicitResumeWorks` failed on a fresh `.m15b1baseline` installation. With the phone held upright, the recording finalised into one 1,604 ms clip, the camera became ready after foregrounding, and the test then timed out waiting for a stable portrait pose. No Resume was requested. The session stayed Paused with no pending segment; the clip remained on disk.

A second unmodified application, `.m15b1probe`, used a test-only observation after foreground return. The display and preview were both at rotation `0`, the camera was ready, and the app still had raw pose `-1` and no filtered pose. An independent, freshly registered `OrientationEventListener` reported `3°` while the app remained stuck. Resume was disabled and the source hash was unchanged. That physical run did not capture the requested nudge before timeout.

The cause is a mismatch in lifecycle state: SkitCam resets its pose on pause, but [Android's `OrientationEventListener`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/OrientationEventListener.java) retains the last reported angle across `disable()` / `enable()` and does not notify for identical angles. The G54 probe established that a new listener could get a reading while the existing listener did not report one. This was not a camera-readiness or failed-finalisation block in the reproduced scenario.

The controlled API 35 emulator case compared both conditions on the unchanged app: unchanged acceleration left the app at raw `-1` with a ready camera; a slight simulated tilt produced raw `6°` and stable portrait `0°`. It recovered without recreating the activity. The corrected build recovered from stationary portrait and landscape, then detected a different pose supplied while backgrounded and recovered again on return to the original pose. These emulator cases do not record media.

## Correction

`OrientationTracker` now registers a fresh platform listener per foreground entry. A valid first reading can seed the existing 500 ms stability filter even if the phone has not moved. Stop still clears both raw and stable pose, cancels the timer, and unregisters the listener; callbacks from a retired listener cannot update the active pose. No pose is inferred from the locked window or saved session. If the sensor has no valid reading, Resume stays unavailable.

The display/preview check, original session orientation, camera/lens/profile lock, and battery and permission gates are unchanged. The change does not issue a recording command or touch segment finalisation, journaling, or media files.

## Files changed

| File | Change |
|---|---|
| `app/src/main/java/com/calvinmazhindu/skitcam/recording/OrientationTracker.kt` | Lifecycle-scoped platform listener; same pose filter. |
| `app/src/test/java/com/calvinmazhindu/skitcam/recording/OrientationFilterTest.kt` | Same-angle restabilisation after stop, and real pose changes not replaced by the previous pose. |
| `app/src/androidTest/java/com/calvinmazhindu/skitcam/recording/RecordingResumeDeviceTest.kt` | Isolated lifecycle and media-preservation cases, reproducible sensor probe and host-driven rotation. |
| `VALIDATION_M15B1.md` | Evidence, test results and limitations. |

## Local quality and validation APKs

```sh
./gradlew --console=plain --continue -PvalidationSuffix=.m15b1validation \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

**Passed:** 296 JVM tests (0 failures, errors or skips), Android lint (0 errors, 46 warnings, no new findings versus M15A), and both APK builds. The Moto validation APKs are debug-signed; version `0.13.0` / code `17`.

| Local artifact | Application ID | SHA-256 |
|---|---|---|
| `.validation/m15b1/validation.apk` | `com.calvinmazhindu.skitcam.m15b1validation` | `448ba699e43e28745d929a8b4dfedbfef2b52a0882ff21a4e5cffe0709301a3f` |
| `.validation/m15b1/validation-test.apk` | `com.calvinmazhindu.skitcam.m15b1validation.test` | `400b782009f2e5689deb0488cba2f30f0838dd001623d30efd238767155d747e` |

Install on the intended test device only. Use a fresh `-PvalidationSuffix=.m15b1…` for each independent recording fixture. Do not install over SkitCam or clear an already-used app ID. For example, the original regression is selected with:

```sh
pkg=com.calvinmazhindu.skitcam.m15b1validation
adb -s "$SERIAL" shell am instrument --user 0 -w -r \
  -e class 'com.calvinmazhindu.skitcam.recording.RecordingDeviceTest#c_backgroundFinalizesAndExplicitResumeWorks' \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
```

For the new cases, select one `com.calvinmazhindu.skitcam.recording.RecordingResumeDeviceTest#<method>` per invocation. `recordingReturnsWithoutMotion` defaults to upright portrait; use `-e m15b1Pose 270` with a phone held in the corresponding landscape orientation. `rotatedInBackgroundBlocksResumeUntilOriginalPoseReturns` needs `-e m15b1Rotate true` and an operator or host to act on the `m15b1Phase` rotation prompts. For `checkpointForProcessDeath` → `explicitResumeAfterProcessDeath`, force-stop **only** the new validation package between steps. Check for `OK (1 test)` rather than relying on the ADB exit status.

## Moto G54 device results

| Case | Result |
|---|---|
| Unchanged background-recording test | **Passed** on the correction (failed before the fix). A stationary portrait session had one confirmed clip after backgrounding and completed with two valid clips only after explicit Resume. |
| Stationary portrait, camera release/reacquisition | **Passed.** CameraManager observed the camera become available in the background and in use again after return. Raw pose `4°` stabilised at `0°`; the original clip hash was unchanged and the Resume button appended exactly one valid clip at a different path. |
| Paused and Review returns, repeated cycles, low battery | **Passed.** Three lifecycle cycles left the session, source hash and MP4 count unchanged. Review released on background as before. An injected 5% battery reading blocked both the Resume button and the recording runtime. After a normal reading, the button appended exactly one valid, distinct MP4. No device-wide battery setting was changed. |
| Paused-session process death | **Passed** on the fresh `.m15b1cold` package, in separate instrumentation processes with a validation-only force-stop between them. The first recovery attempt in `.m15b1process` failed in the test checkpoint reader, which treated pretty-printed JSON as a single line. After fixing the test reader, the fresh two-stage rerun restored the same session and clip hash, then appended exactly one new clip after explicit Resume. |
| Stationary landscape recording and physical rotation during an interruption | **Not run on the Moto.** These require the operator to reposition the phone. The corrected emulator covered both stationary poses and during-background rotation at the sensor/lifecycle level. JVM coverage and the existing engine orientation checks cover mismatch rejection; that is not a substitute for the Moto recording cases. |

All on-device activity used separate debug validation application IDs. No production package, recording, or session was opened, replaced, cleared, or deleted. Both baseline clips were re-hashed after testing and matched their initial SHA-256 values. The original regression finished one disposable validation skit and therefore also produced a test export. No APKs, device logs or recorded media are included in Git; local evidence is in `.validation/m15b1/`.

## Emulator boundaries

A clean API 35 AOSP x86_64 emulator with an emulated rear camera could open and record, but the unchanged regression failed before the pose check: the retained virtual-camera candidate used AMR-NB audio, not the required AAC (`ffprobe` confirmed H.264 video and `amr_nb` audio at 8 kHz). No media validation rule was changed. The emulator sensor/lifecycle test (`controlledSensorForegroundTransitions`, `-e m15b1ControlledSensor true`) is independent of that media constraint. It verifies repeatable poses with `adb emu sensor set acceleration`, not on-device capture quality. Screen-size compatibility and a hosted emulator CI gate are separate work.

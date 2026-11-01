# Milestone 2 validation — 2026-11-01

## Result

Implemented on `feature/segment-recording`, based on Milestone 1 commit
`e8bb0c2`. Built and installed version 0.2.0 (2) on Motorola Moto G54 5G,
serial `ZY22KJQLTV`, Android API 35. No app data was cleared, no recorded
segments were deleted and no system configuration changes were made.

**STOP completes a session and retains its individual MP4s. Combining/export
is not implemented.** The installed app explicitly displays this limitation.

## Checks performed

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon`
  completed successfully.
- **24 JVM tests passed**: 22 recording-engine tests and 2 orientation-filter
  tests, with no failures or skipped tests.
- Android lint: **0 errors, 15 warnings**. These are pinned-version update
  suggestions, existing backup configuration guidance, conservative usable-space
  guidance and Kotlin extension style suggestions. No lint error suppression or
  baseline was introduced.
- Debug APK and test APK installed using `adb install -r`.
- `adb shell am instrument -w -r com.calvinmazhindu.skitcam.test/androidx.test.runner.AndroidJUnitRunner`
  reported **OK (6 tests)** in 28.959 seconds, with no skipped tests.
- APK signature verified with `apksigner` (v2).
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- APK SHA-256: `616e5d491660f91660335a952edd318e554335ed2eb1d4fa28aa8c2237d8163c`.

## Hardware scenarios

| Test | Result |
| --- | --- |
| START → PAUSE → RESUME → STOP | Two ordered, independently finalised audio/video MP4s |
| Wrong orientation supplied to RESUME | Remained paused; no additional segment |
| STOP while paused | Completed without creating another MP4 |
| Background during recording | Successfully finalised and paused; explicit resume recorded the next segment |
| Camera source forcibly unbound during Segment 2 | Error code 4; Segment 1 preserved; candidate Segment 2 retained but not registered |
| Atomic metadata round-trip and empty media validation | State persisted; empty media rejected |
| Activity recreated while paused | Same session/segment restored; preview returned |

The hardware engine tests use a known orientation input and real CameraX,
microphone, storage and activity lifecycle. Physical sensor filtering is tested
separately on the JVM. Tests require camera/microphone grants and preserve
their recorded fixtures.

## Independent storage/media verification

Five real recording sessions were retained under `files/sessions/`: seven
confirmed segments plus one unregistered camera-interruption candidate, eight
MP4s total. Each session has its own `session.json`; the failed candidate path
and error remain in its metadata. No automatic deletion or replacement occurs.

Copied those files to `/tmp` solely for verification. `ffprobe` identified H.264
1280×720 video and AAC audio in all eight files, with approximately 1.3–1.4
seconds of media per file and consistent portrait rotation. All eight files
also decoded through `ffmpeg -v error -i FILE -f null -` without errors.
The source-loss candidate was deliberately not registered despite being playable,
because CameraX reported unsuccessful finalisation.

After the suite, force-stopped the app and cold-launched it. It restored the
last confirmed paused session with one segment, its saved duration and locked
orientation. The live UI blocked RESUME while the sensor pose was unsuitable
and displayed a clear original-orientation warning. Tapping the actual STOP
button changed metadata to Completed, kept the count at one, created no extra
file, and displayed the separate-MP4 limitation. The app was left on that
Completed screen with preview available and every recording retained.

## Safety and remaining limits

- A single engine per process serializes commands, CameraX callbacks, metadata
  and storage checks. Activity recreation or reopening cannot bypass an
  unresolved recording. State and metadata survive through confirmed boundaries.
- Tests cover duplicate/invalid commands, stale callbacks, delayed finalisation,
  start/finalisation timeouts, metadata failures, audio errors, storage thresholds,
  earlier-segment preservation, interrupted-session recovery and the 100-segment cap.
- Low storage, stuck encoders, abrupt death during an active recording and the
  100-segment boundary were simulated in engine tests; the phone was not filled
  or deliberately crashed during an active recording. An abruptly killed active
  MP4 may be incomplete and is never automatically certified on restart.
- The full physical hold/rotate/return sequence, all four physical directions,
  microphone denial/settings UI, long recordings and API 26 runtime remain
  manual follow-up checks. Hardware tests do verify actual audio capture and
  reject wrong orientation at the engine boundary.
- No background service or automatic recording resume is implemented. Leaving
  the foreground requests stop; if CameraX nevertheless reports an error, the
  candidate stays unregistered and earlier segments remain available.
- A finalisation timeout fails closed. Late successful finalisation may still
  register its correlated file, but does not silently resume recording. If the
  encoder never responds, a process restart is required; uncertain files remain.
- App-specific files survive upgrades with `install -r`, but Android removes
  them on uninstall or Clear storage. No in-app deletion/export/gallery UI exists.

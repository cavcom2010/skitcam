# M15B.2 — MediaCodec playback reliability

Branch: `feature/m15b2-playback-reliability`. Baseline: `f0dbb794f6a9defa84aea5410ac809c90d408643` (M15B.1 merged; [Android quality passed](https://github.com/cavcom2010/skitcam/actions/runs/45189843834)). Baseline test commit: `66481b9`; startup-seek follow-up: `c4870e3`. This report is a separate documentation commit.

**Result:** The earlier MediaCodec failure was not reproduced. The Moto G54 passed 299/300 matrix attempts and 60/60 focused follow-ups. One same-turn seek stalled, and the subsequent close timed out. The cause is unresolved, so no production fix was applied.

## Build and quality gates

The default CI command and both isolated validation quality runs passed: 296 JVM tests (no failures, errors or skips), lint (0 errors, 46 warnings, the same count as M15B.1), debug APK, and instrumentation APK. Both APKs also built for `.m15b2capture`.

## Changes

There are no production code, dependency, or UI changes. `PlaybackReliabilityDeviceTest.kt` runs the bounded stress matrix and records allowlisted state and timing observations. `SegmentNavigationDeviceTest.kt` allows the isolated capture package and adds a real append-after-playback check. `PlaybackFailureDeviceTest.kt` allows the fresh validation package without changing its negative-control assertions. `tools/m15b2_playback_fixtures.sh` generates and decodes the disposable inputs.

## Original problem and source review

`VALIDATION_M14_2.md` records two Play Skit starts that failed with a device MediaCodec error on a **pre-M14.2** build on the Moto G54. Later starts passed. The paused-seek screenshot that showed a black frame does not establish a decoder failure. M15A added structured failure diagnostics; it did not establish the cause of the older failures.

| | Play Skit | Immersive Review |
|---|---|---|
| Source and seek | Sets the saved-order playlist, applies each saved clip range, selects exact seeks, prepares immediately, and requests autoplay. Rail navigation seeks within the same player. | Opens one full source paused and uses source-relative trim preview positions. Trim playback can replace the item on the same player to enforce the saved/draft range. The non-full-source controller path uses a clipped item if needed and prepares immediately. |
| Output | Compose attaches a `TextureView`-backed `PlayerView` after the controller calls `prepare()`. | Uses a `SurfaceView` with the Compose sync workaround. The initial full-source prepare waits for a positive surface-size callback. Review reuses the view and retains content while replacing the player when the selected segment changes. |
| Release | The controller clears its reference and calls `release()` on close or fatal error. The view detaches on removal. | Also releases on close, error and before replacing the player. The Play Skit entry points close Review before starting Play Skit. The activity closes both on pause and destroy. |

A prepare call before view attachment is a confirmed ordering difference, not proof that the hardware decoder initialized without a surface. A `surface_available` field on a failure is the last observed callback state, not a timestamp for preparation. A returned `release()` call is also not a measurement of when the vendor codec stopped using all resources.

`MainActivity` can keep the foreground camera preview bound behind the immersive playback UI. A successful recording after close proves the capture path is usable; it does not imply a camera unbind/rebind on every entry and exit.

## Reproduction protocol

- Run on the Moto G54 with Android 15 / API 35 and Media3 1.11.1. Use a fresh isolated debug package; never open an existing recording session, clear app data, or install over production.
- Use `tools/m15b2_playback_fixtures.sh` to generate three fully decoded test inputs (3.6, 4.4 and 2.8 s; 720×1280, AVC High at 25 fps, AAC-LC). The clips use distinguishable colours before and after the one-second mark and distinct audio tones. The third clip has a saved 1.04–2.40 s range.
- The device tests use the real activity, controllers, and surfaces. Controller-level actions pin down stress timing; the camera-backed navigation regression uses actual UI actions. Each stress row has a bounded verification deadline per step. The separate cold case starts in a freshly stopped validation process, not after a device reboot. Count each scheduled case once; do not retry it within the measurement.
- Record controller state, first-frame callbacks, sampled composited frame colours, and validation-only timing observations. From Logcat retain only parsed `SkitCamPlayback` `playback_failure` JSON: error/codec code, decoder and MIME where available, phase, clip ordinal and position, player state, surface and first-frame observations, and device/Android version. Never save or share a full Logcat dump. A missing field means unknown. Intentional corrupt/unavailable sources are controls and do not count as reproductions.

## Moto G54 stress results

Moto G54 5G, Android 15 / API 35, build `6766b4-a2b693`. The one-attempt-per-scenario smoke run passed and is excluded from the matrix.

| Scenario | Attempts | Failures | Dark / transition samples |
|---|---:|---:|---:|
| Cold playback, fresh validation process | 30 | 0 | — |
| Repeated entry/exit | 30 | 0 | — |
| Rapid segment navigation, same playlist and player | 30 | 0 | 15/57 |
| Forward/backward seek, paused and playing | 30 | 0 | 0/272 |
| Same-turn startup seek | 30 | 1 | — |
| Review segment switching, same view | 30 | 0 | — |
| Pause/resume | 30 | 0 | — |
| Last-to-first loop and Replay | 30 | 0 | 28/549 |
| Review close → Play Skit | 30 | 0 | — |
| Activity stop → return → Play Skit | 30 | 0 | — |

Dark means a near-black pixel at the centre of the composited video bounds during a sampled transition. This shows dark samples already exist on the unchanged baseline; it neither measures flash duration nor verifies that all flashes would be caught. Every sampled navigation/loop case eventually displayed the expected frame.

The initial matrix failed its instrumentation assertion and retains that result. On immediate-seek attempt 28, Play Skit entered buffering and observed a positive surface callback at 79 ms but had no decoder-init or first-frame callback. No playback error was observed before the 4 s frame deadline. The subsequent close took 506 ms and the test listener received `1003` (`ERROR_CODE_TIMEOUT`), consistent with Media3's release timeout. That is not a `4001`/`4003` decoder initialization/decoding error. Missing decoder details are unknown, not proof that no codec was allocated.

### Timing and diagnostic comparison

| Successful player starts | First-frame observations | Median | 95th percentile |
|---|---:|---:|---:|
| Play Skit, fresh process | 30 | 751 ms | 831 ms |
| Play Skit, in-process matrix | 269 | 314 ms | 492 ms |
| Review, in-process matrix | 120 | 327 ms | 450 ms |

Timing is from the test's player open call to `onRenderedFirstFrame`, not a user-input or display latency benchmark. All 419 successful starts used `c2.mtk.avc.decoder`. Play Skit entered buffering before a positive surface-size observation for all 299 successful starts; the Media3 video-decoder initialization timestamp preceded that observation in 140/299. Review entered buffering at or after that observation in all 120 starts; no decoder initialization timestamp preceded it. This difference is real, but it was common in **successful** Play Skit runs and does not explain the single stall. Media3's observation of surface dimensions is not a platform-level timestamp for the actual output hand-off.

The successful rapid replacement runs observed video-decoder release callbacks for 60/60 replaced Review players and 30/30 Review-to-Play-Skit transitions; the enclosing close/replacement calls took at most 94 ms. This shows the measured paths completed, not that vendor resources are always released synchronously. There is no evidence of persistent contention in those scenarios.

No structured `SkitCamPlayback` playback failures were emitted by the known-good matrix. The existing negative controls passed (2 tests) and emitted exactly three structured events in preparation: `3003` (`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`) for Play Skit and Review and `2005` (`ERROR_CODE_IO_FILE_NOT_FOUND`) for Saved. None described a MediaCodec failure. The release timeout above came from the validation listener, not an M15A structured event.

The focused follow-up had **0/30 failures** for same-turn seeks and **0/30 failures** for seeks after the first positive surface callback. All 60 seeks were issued before a first-frame callback. Median seek times from the controller call were 12 ms and 187 ms, respectively. No structured failures were emitted. The comparison changes seek timing only; it did not test changing when the controller calls `prepare()` or establish that waiting for a surface prevents the stall. Same-turn controller injection is earlier than a user can normally reach the Compose seek control.

The original decoder failure remains unresolved. The synthetic sources decode independently and played successfully across the same skit, so the evidence does not point to corrupt or unsupported input for the observed stall. The old pre-M14.2 incident's media and diagnostic details are not established. A device/Media3 stall, a seek-order race, and contention with other media resources are hypotheses, not confirmed causes. Do not infer a vendor fault or add a retry, a second player, or a surface-sequencing change from this evidence alone.

### Regression checks

- The matrix verified correct startup frames, segment changes and rail selection, pause intent, forward/backward seeking, looping, Replay, Review switching and returning from activity stop, with the one timeout noted above.
- **Private trim/export check passed:** the saved trim opened at the expected frame, a paused trim seek showed the expected frame, and trim playback ended at the edited boundary. The private export's clip sample frames were in the expected order; video and audio both decoded end to end. Container duration was 9,375 ms versus an expected 9,360 ms; the last decoded presentation timestamps were 33.3 ms apart. Listening remains a human acceptance check.
- **Camera-recorded navigation and export passed:** on a ten-clip disposable session, Play Skit followed kept and saved order, the rail followed playback, and the anchored playlist and Replay were correct. Export completed with both tracks, and all ten input SHA-256 values matched. The first invocation lost the USB transport before a JUnit verdict; its ten-clip session was retained. The verified invocation recorded a separate session.
- **Camera append after playback passed:** after the two-clip skit was played and closed in the UI, the player was released and the camera was ready. The explicit resume command appended one valid MP4 at a new path. The first two segments' metadata and hashes were unchanged. No structured playback failures were emitted in the camera regressions.

### Media preservation and privacy

Staged fixture SHA-256 values matched the host inputs. The instrumentation compared all three session source hashes and persisted session metadata at setup and teardown, including in the matrix with the timeout. The trim/export regression used a new file in private validation storage. No production app data was opened, cleared or replaced; no production media was read for checksums. No APK, clip, recording path, unique device identifier, or raw Logcat is included in the branch.

## Re-running the tests

```sh
./gradlew --console=plain --continue -PvalidationSuffix=.m15b2validation \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
bash tools/m15b2_playback_fixtures.sh .validation/m15b2/inputs
```

After verifying the device and that neither package exists, install the two debug APKs as the intended user, granting only the isolated app's runtime permissions. Stage the three generated files in `files/m15b2/` under the validation app using `run-as`. The instrumentation test refuses other package names and will not overwrite its original sources. For example:

```sh
pkg=com.calvinmazhindu.skitcam.m15b2validation
testclass=com.calvinmazhindu.skitcam.playback.PlaybackReliabilityDeviceTest
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
adb -s "$SERIAL" shell run-as "$pkg" mkdir -p files/m15b2
for name in first second third; do
  adb -s "$SERIAL" shell -T "run-as $pkg sh -c 'set -C; cat > files/m15b2/$name.mp4'" \
    < ".validation/m15b2/inputs/$name.mp4"
done
adb -s "$SERIAL" shell am instrument --user 0 -w -r -e class "$testclass#playbackStress" "$runner"
adb -s "$SERIAL" shell am instrument --user 0 -w -r -e class "$testclass#trimPreviewAndPrivateExport" "$runner"
for i in $(seq 1 30); do
  adb -s "$SERIAL" shell am force-stop --user 0 "$pkg"
  adb -s "$SERIAL" shell am instrument --user 0 -w -r -e class "$testclass#firstPlaybackInFreshProcess" "$runner"
done
adb -s "$SERIAL" shell run-as "$pkg" cat files/m15b2/attempts.jsonl
adb -s "$SERIAL" shell am instrument --user 0 -w -r \
  -e class com.calvinmazhindu.skitcam.playback.PlaybackFailureDeviceTest "$runner"
```

The focused follow-up uses a separate `.m15b2validation2` build with the same fixtures. Invoke `PlaybackReliabilityDeviceTest#startupSeekTimingProbe` to compare 30 seeks in the same controller turn as `play()` with 30 after the first positive surface callback. This changes when the test requests the seek, not when production prepares the player. The probe also records the pre-cleanup state and distinguishes Media3 release timeout from an error during playback.

Inspect instrumentation assertions and `OK` counts, not just ADB exit status. Use a separate, fresh `.m15b2capture` build of both APKs for `SegmentNavigationDeviceTest`: run `verifyFreshSession` before `cameraCanAppendAfterClosingPlayback`, with the phone upright in portrait. The first case completes its disposable session so that the second can start a new one without altering old files. Fixtures and evidence stay local and are not versioned.

To capture failure events, use a device-time start marker for each batch and collect the positive cases and negative controls separately. An example on a device whose `logcat -v brief` uses the standard tag prefix:

```sh
adb -s "$SERIAL" logcat -b main -d -T "$START" -v brief 'SkitCamPlayback:W' '*:S' |
  sed -nE 's/^W\/SkitCamPlayback\([[:space:]]*[0-9]+\): (\{.*\})$/\1/p'
```

Inspect the JSON against the allowed fields above before retaining any rows. Do not clear the shared log buffer.

## Human acceptance

These visual and listening checks are not signed off by the automated run. Use a phone with a different chipset as a comparison; the measurements above are from one Moto G54 5G and do not cover all media profiles, orientations, or device states.

- On the G54 and a phone with a different chipset, open a disposable camera-recorded skit in portrait and landscape. Confirm the first visible frame matches the chosen clip and watch for flashes at clip boundaries, after early and backward seeks, on looping, and after returning to the app.
- Verify the rail follows the current clip, paused seeks stay paused, active seeks stay active, Replay starts at the anchor, and closing playback allows an explicit Resume to append a clip.
- In Review, switch between segments; scrub, drag trim handles, Save and Reset. Check that the retained frame and audio are correct and that no existing media changed.
- Listen to the disposable export at startup, joins and the end in Gallery or a second player. Full-track decoding and timestamp checks do not replace checking audible sync or listening for gaps.

If the stall returns through the real UI or a structured decoder failure recurs, capture its error code and phase alongside frame, surface and release observations. Repeat on the same known-good and native camera fixtures before testing a change to preparation order. Keep the same surface type when comparing preparation timing so the two variables are not conflated.

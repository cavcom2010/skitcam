# M15B.3 — Recording, audio and export field validation

Branch: `feature/m15b3-field-validation`. Tests and diagnostics: `2876412`, `0a04988`. Baseline: `c24250095b901f08d15395c2cc1df012c67a350e` (M15B.2 merged; [Android quality passed](https://github.com/cavcom2010/skitcam/actions/runs/45267331392)).

**Scope:** validation only. The M14.2 UI, recording format, segment model and export semantics are unchanged. Synthetic fixture and fault-injection evidence is not interchangeable with real recording or human acceptance.

## Test environment

- Motorola Moto G54 5G, Android 15/API 35, incremental build `6766b4-a2b693`. FFmpeg/FFprobe 8.0.1 on the host. No second hardware device or pre-Android 10 Gallery path tested.
- App version `0.13.0` / code `17`. The instrumentation manifest has no separately declared version. APK checksums are recorded per installed validation pair.
- Only disposable material may be captured or published. Fixtures and field recordings use separate app IDs. Do not open, modify, clear, uninstall, or hash the production installation's data. Keep MP4s, raw test output, session metadata, APKs, paths and identifiers out of Git.

| Validation app suffix | App APK SHA-256 | Instrumentation APK SHA-256 |
|---|---|---|
| `.m15b3field` | `8f3abd3b1b1a44e62b4e464090511d6d64656facfc0d20c07f87ea09f28a34d6` | `2be19a97cff03a4397f1547b9c40b6976772f7a57981d1dc495b8f51fa1f3b54` |
| `.m15b3fixtures` (initial) | `e82b8f4384cae28eb4204101e80c0c83b721a9defef8e4c69ae43d04beeb87f0` | `5934d64fe4e9d6d46b10d24e502952e51b6ba2bf1d475c387edd36470a4a5fc7` |
| `.m15b3fixtures.m12bvalidation` (corrected) | `22c6326f5a87b81d73e7dcee27a6a30e6cbec0512aa1116b5f9af51dabe05f40` | `f1e9190df47b47fb23f1246f780e52ee2315d42fb9a3eb66610ba2e92deec9af` |
| `.m15b1m15b3` (lifecycle) | `4c87f419fae9270afc36e211201b008f8906d4763b1b6dc3118f0cc54ccc42ea` | `5802e5a7de37b9ffa59b5719e1b08f2cee7d46204e8410183144baf0252b6b30` |
| `.m15b1m15b3battery` | `72f7c014e7a9f32b27e67b9d9f7e1f52e403c1b45e972a196bf90dd660d87204` | `b9eff9b48a24024ab499b6b473819c65619e366b5ebf30c938a29e80d902809c` |
| `.m15b1m15b3cold` | `4ab43962e578afcd419a320f420c7c791b339f09319d930cd5b52a73aeab90b5` | `474748ae018be472c6f35ce6e17406aaf13e7f358dcf449a75d3db0211c8f6f6` |
| `.m15b1m15b3batteryprobe` | `f8ce583de7bfcb0d36353d235c198be40eac50a5e12c7bb54d3ed690f330fd25` | `b4a59a4f9dcdac5fac6b119c56851b027a766b06249d21dec710db0f1745b61d` |
| `.m15b3audiocheck` (temporary diagnostic) | `d48127d60cdd02997fa2fcdb36385d704131b5ffc8c840cdc18586ecbaaf69a7` | `10d31bdcdd9c5f4b9b632e7d83035797ceccb611db4fe8d3ec773a00596b3d9f` |

## Local quality gates

The final CI-equivalent command passed JVM tests (296 tests, no failures, errors or skips), Android lint (zero errors, 46 warnings), debug APK and instrumentation APK. All six host tool tests passed, including the three sync-tool tests. Both isolated APKs built for each ID above. The recording engine's JVM cases include simulated low storage before startup and during a take, battery-triggered pause, and failed battery finalisation. Those simulations do not measure actual battery, camera or microphone release. The GitHub HTTPS push preflight failed for lack of credentials; hosted CI for this branch is not verified.

```sh
./gradlew --no-daemon --console=plain --continue \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

## Evidence matrix

| Scenario | Machine evidence | Physical or human result |
|---|---|---|
| 1080p portrait: three short-take sessions, Pause/Resume, discard/restore, trim, reorder, Review, Play Skit and append | **PASS** for capture, keep/discard state, saved order/trim and append in the first session. All eleven originals matched after export. **BLOCKED** for remaining repetitions. | The operator reported no issues in the editing/playback-and-append pass. The first ten takes were 11.7–25.2 s, not the planned 2–5 s. |
| 1080p landscape: same workflow in independent sessions | **BLOCKED** — no field originals in evidence. | **BLOCKED** — physical landscape run pending. |
| Two 3–5 minute takes per orientation; late markers and long export | **BLOCKED** — long capture, drift and thermal/cadence evidence pending. | **BLOCKED** — record, view and listen to long takes. |
| 4K, both orientations | **BLOCKED** — selected camera/settings capability not established. Use **N/A** only once unsupported is established. | **BLOCKED** — conditional on capability. |
| Built-in mic, speech levels, dialogue and laughter | **PASS** for non-silent audio in all ten decoded originals with the operator reporting internal mic use. The separate instrumentation run **FAILS** its rotation sentinel after completing capture/export; see below. **BLOCKED** for full speech-level and input-origin checks. | Operator accepted the portrait export's picture and sound. Controlled speech levels, individual join checks and source isolation were not signed off. |
| USB-C and wireless mic, connect/disconnect/reconnect | **BLOCKED** — no external inputs confirmed available. | **BLOCKED** — hardware and operator checks required. |
| A/V offset at starts, near joins, through saved trims, late in takes and in published MP4 | **PASS** for short synthetic cues only; **BLOCKED** for reliable recorded cues and drift. | Operator reported that the portrait export looked and sounded good. Explicit speech sync at each join/endpoint is not signed off. |
| Ordinary and trimmed/reordered publication | **PASS** for short landscape fixtures and for the portrait trimmed/reordered export on retry. Both tracks decoded completely, portrait order/orientation matched, and the camera became ready. **BLOCKED** for an ordinary unedited field export. | Picture and sound accepted for the portrait export. Which player(s) and second-player verification were not confirmed. |
| Legacy Gallery publication (Android API 26–28) | **N/A** on the Android 15 Moto; no legacy device available for this run. | **N/A** on this device. |
| Cancellation, retry, export failure and no duplicate publication | **PASS** for the portrait job: no row after cancellation, exactly one owned non-pending row after retry, matching URI and bytes. **PASS** for fixture cancellation, injected low-space failure and repeated launch of a completed job without duplication. | Operator exercised cancellation and retry through the real UI. |
| Export wait | 140.2 s from successful retry service start to camera ready for 152.1 s of kept video (one measurement). | **FAIL** — operator described the wait as major friction. No numeric acceptance budget set yet. |
| Background recording, resource release/reacquisition and repeated playback-to-recording transitions | **PASS** for stationary upright recording finalisation, camera release/reacquisition and explicit append. **BLOCKED** for moved return and ten playback-to-recording cycles. | The operator positioned the phone; automated camera-resource observations are not human acceptance. |
| Recovery and storage | **PASS** for twelve on-device file-store/export-journal fixture checks, and both stages of paused-session force-stop/recovery. **BLOCKED** for the in-flight unconfirmed take scenario. | **BLOCKED** — user-facing recovery alternatives not walked through. |
| Injected low battery refusal/recovery and low storage | **PASS** for export low-space injection on Moto. Recording low-space and battery policy passed JVM simulation. Two battery JUnit runs **FAIL** at the disabled-Resume assertion; on-device refusal/recovery evidence is **BLOCKED** because the injected value did not persist. No shared-storage fill or OS battery override. | **BLOCKED** — physical resource-interruption acceptance pending. |
| Original playback decoder risk and M15B.2 immediate-seek stall | No structured playback error during the portrait edit/append pass. Neither existing issue is closed; repeatability/stress coverage is **BLOCKED**. | The operator reported no problem in the first field pass; remaining sessions pending. |

## Fixture evidence

- Moto MediaStore readbacks for the ordinary, trimmed and retried jobs fully decoded with `ffmpeg -xerror` on both tracks. They were 1280×720 landscape with H.264/AAC, 48 kHz stereo. Ordinary: 30 fps, 6.025 s, red → blue; trimmed and retry: approximately 29.82 fps average, 2.847 s. Colour samples on both sides of the initial trimmed cut established blue → red; the corrected retry had the same marker times and output profile. These fixtures do not establish microphone or field acceptance.
- Synthetic cue offsets below are **audio minus video** bounds using one frame interval and 5 ms padding. The unmodified synthetic sources' independent flash/chirp check reported 0 ms at both markers in its 5 ms bins. No physical clap or long-take drift is claimed.

| Published fixture | Flash PTS | Audio-minus-video bounds |
|---|---:|---:|
| Ordinary | 1.000 s, 4.000 s | +4.4 to +47.7 ms, +4.4 to +47.7 ms |
| Trimmed/reordered | 0.650 s, 2.050 s | +25.7 to +69.1 ms, +33.7 to +77.1 ms |

- The first low-space case **failed its test assertion**: the original `.m15b3fixtures` app ID did not activate the existing `.m12bvalidation`-guarded injection, so the retry completed normally. This is an invalid simulation, not evidence that SkitCam ignored a low-space condition. Kept that fixture and its publication intact. The corrected app ID is `.m15b3fixtures.m12bvalidation`; no production code was changed for the hook.
- On the corrected ID, both export fixture tests and all twelve file-store/export-journal cases passed. The retry remained under the same job through cancellation and injected low space, published one non-pending result, and retained it on repeated launch. The two playback negative controls passed; intentional corrupt sources emitted preparation code `3003` for Play Skit and Review. These controls are not recurrences of the field decoder issue.
- `tools/measure_field_sync.py` and its host tests add marker evidence without changing app timing or media processing. The control spans an aligned cue, deliberately late audio, a missing cue, a window-edge cue, and a change in offset. The on-device test additions use the existing real export service, journal and MediaStore publisher.

## Export performance follow-up

The 140 s wait was investigated in [M15B.3.1](VALIDATION_M15B3_EXPORT_PERFORMANCE.md). Saved trims forced the renderer; equivalent untrimmed bytes selected AVC passthrough with audio render. Removing an unnecessary eligibility scan saved about 3 s, but required rendering, audio processing and full verification dominate. Intermittent vendor-decoder failures during long exports were also observed and remain open. The earlier performance concern is therefore not resolved.

## Portrait capture evidence

- First operator-driven validation session: rear camera, requested FHD, Auto FPS, stabilisation off. All ten sources were H.264 in a 1920×1080 raster with an FFprobe display matrix rotation of −90° (1080×1920 displayed), AAC stereo at 48 kHz, around 29.922–29.924 fps average. Both streams decoded end to end in all ten. All local copies match device SHA-256 values taken before edits.
- The decoded AAC tracks had non-zero signal and distinct transients. The ten files' overall audio RMS levels ranged from approximately −53 to −30 dBFS. Several loud transients decoded above nominal full scale; this is not by itself proof of microphone clipping or unintelligible speech. The operator's reported input was internal; Android observation alone cannot certify source pickup, failover, or loud/quiet speech quality.
- Early and late audio transients alone are not valid sync markers. Several sampled transients lack a clearly visible contact frame. Do not infer sync from those transients, use matching stream durations as a substitute, or report a field PASS without an identifiable paired cue.
- After the append, saved kept order was `3, 1, 2, 4, 5, 6, 7, 8, 10, 11` (take 9 discarded; take 3 retained). Take 2 used 1353–10461 ms; take 3 used 2739–13066 ms. The first ten original SHA-256 values still matched the pre-edit checkpoint, including the discarded and trimmed takes. All eleven originals were hashed before export. The first export attempt was cancelled in the UI; the journal had not reached publication and the scoped row check found no pending or visible row.
- Retry completed through `RENDER` and produced one visible Gallery item. The shared MP4 matched the verified journal SHA-256, and all eleven source hashes matched the pre-export checkpoint. The 152.052 s MP4 was 1920×1080 with −90° display rotation, H.264 at about 29.92 fps and AAC stereo at 48 kHz. The saved ranges summed to 152.030 s. Visual sequence matching of the decoded MP4 identified all ten kept clips in saved order, with each best alignment within 66 ms of its expected start on an 8 fps analysis grid. Isolated audio-snippet matching was ambiguous for some repeated snaps and was not used to infer order or spoken A/V sync.
- A default FFmpeg null-output run warned about a repeated output DTS. A repeat preserving the demux time base and passing through frame timestamps decoded both streams cleanly. FFprobe found strictly increasing packet DTS and decoded frame PTS on both tracks (4,549 video frames and 7,128 audio frames). The shortest video interval was 1.22 ms at a join; both frames survive with native timestamps. The coarser null-output timestamp warning is not evidence of a file decode failure. The operator said the export looked good with sound, but did not identify the second player or confirm each speech-sync checkpoint separately.

### Recorded A/V estimates (inconclusive)

Four visible finger motions in take 10 could be matched to the same decoded export frames. Because contact itself was not clearly visible, first visible motion is only a proxy; the bounds include the roughly 33 ms frame interval and an additional ±40 ms allowance. Changing the PCM threshold from 0.15 to 0.30 moved the source crossings by less than 0.2 ms. Values are signed **audio minus video**.

| Approximate time in source / export | Source offset bounds | Export offset bounds |
|---|---:|---:|
| 2.941 s / 125.083 s | +6.8 to +120.2 ms | +19.6 to +133.0 ms |
| 13.502 s / 135.643 s | +8.6 to +121.9 ms | +20.7 to +134.0 ms |
| 14.203 s / 136.345 s | −8.9 to +104.4 ms | +3.3 to +116.6 ms |
| 18.314 s / 140.455 s | −3.1 to +110.3 ms | +9.1 to +122.5 ms |

Under this proxy the export estimates shift about 12–13 ms later in audio than the corresponding source estimates. The first-to-last offset-change bounds are approximately −123 to +104 ms in the source and −124 to +103 ms in the export. The absolute intervals straddle the 100 ms tolerance. They cannot establish reliable sync or drift, especially at trim edges, joins or the ends of long takes.

### Export performance finding

The operator reported **major friction** from the export wait. The existing monotonic device trace puts the successful retry at **139.4 s** from export service start to publication and **140.2 s** to camera ready for **152.1 s** of video. This excludes the earlier cancelled attempt and the operator's time before Retry. Saved trims selected the existing full renderer. This is one G54 run, not a performance distribution or a comparison with a compatible passthrough job.

| Phase | Elapsed |
|---|---:|
| Media3 transform | 74.3 s |
| Full candidate verification | 60.5 s (37.4 s video decode, 22.7 s audio decode; included in the phase total) |
| Gallery publication | 1.3 s |
| Source checks, setup, handoff and other time | about 4.1 s |

Treat performance as a release-readiness concern separate from media correctness. Define an acceptable wait with the operator, then measure paired untrimmed and trimmed runs at the same profile and kept duration, including a long take. Use the evidence to decide whether to optimize verification, rendering or both; do not skip integrity checks or force a faster path for trims without equivalence and regression evidence.

## Lifecycle and resource regressions

- `recordingReturnsWithoutMotion`: the first invocation failed at the initial sensor prerequisite with the camera ready and zero clips recorded. After the phone was propped upright, the unchanged test passed on the same untouched validation store. `CameraManager` observed camera release and reacquisition; the backgrounded take finalized once and its hash stayed unchanged after an explicit Resume appended one new valid clip.
- `checkpointForProcessDeath` → package-specific force-stop → `explicitResumeAfterProcessDeath`: both passed on a separate validation ID. The previously confirmed paused clip and its hash were restored; Resume appended exactly one distinct, valid clip. This is not evidence about an unconfirmed take in flight.
- `pausedAndReviewSurviveRepeatedReturnsWithoutBypassingBattery`: record and pause once, cycle Paused/Review across three background/foreground returns, verify original hash, then inject 5%. Both invocations timed out at the disabled-Resume UI assertion before exercising runtime refusal/recovery. The follow-up verified that the model initially received 5%, then observed it replaced by the live USB-charging reading (71%) without a missing Resume node. Resume was seen enabled during at least one sample, but the probe never demonstrated a settled disabled state under a sustained 5% reading. This is an instrumentation limitation and does not establish whether sustained critical battery caused a UI or runtime defect; use a controlled battery source in a follow-up.
- `builtinDiscoveryLevelPauseResumeAndExport`: launch fresh isolated camera store, record and pause in portrait, check meter releases, recreate, resume, and finish. The first invocation left two confirmed clips and a completed export, with active CameraX capture metering observed. Two original JUnit runs failed at the final rotation sentinel (`lock 1` before, `lock 0` immediately after closing). A temporary test-only probe also saw `user_rotation` switch from `1` to `0` before instrumentation exited, while host reads after instrumentation showed it back at `1`. Android's [`UiAutomationConnection`](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/UiAutomationConnection.java) saves and restores rotation on connection teardown, but this does not identify the cause of the intermediate value on this phone. The temporary probe is not retained in the branch. The regression verdict remains **FAIL**, not an audio-track failure or a proven production rotation write. No device-wide rotation command was used in this batch.

## Reproduce fixture checks

Build the instrumentation pair with `-PvalidationSuffix=.m15b3fixtures.m12bvalidation`, and install on a fresh test target where neither ID already exists. That exact isolated ID activates the existing storage stub and is required by the new fixture tests. Do not clear an old validation installation to reuse it.

```sh
python3 -m unittest discover -s tools -p test_measure_field_sync.py -v
python3 tools/make_timing_probe.py .validation/m15b3/timing-inputs
pkg=com.calvinmazhindu.skitcam.m15b3fixtures.m12bvalidation
base=com.calvinmazhindu.skitcam
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
fixture_dir=files/validation-m12b-inputs/zero
adb -s "$SERIAL" shell run-as "$pkg" mkdir -p "$fixture_dir"
for name in segment_001.mp4 segment_002.mp4; do
  adb -s "$SERIAL" shell -T "run-as $pkg sh -c 'set -C; cat > $fixture_dir/$name'" \
    < ".validation/m15b3/timing-inputs/zero/$name"
done
paths="$base.export.ExportPathsDeviceTest"
adb -s "$SERIAL" shell am instrument --user 0 -w -r \
  -e class "$paths#fieldFixturesPublishOrdinaryAndTrimmedReorderedResults,$paths#fieldFixtureCancelledThenLowSpaceThenRetryPublishesExactlyOnce" "$runner"
adb -s "$SERIAL" shell am instrument --user 0 -w -r \
  -e class "$base.recording.SessionFileStoreRecoveryDeviceTest,$base.export.ExportJournalDeviceTest" "$runner"
```

The ordinary, trimmed and retried copies are in the validation app's `files/m15b3-published/`. They are MediaStore readbacks, not prepublication working files. Pull only those explicit files; decode with `ffmpeg -xerror` and review marker bounds with the host tools. The separate `PlaybackFailureDeviceTest` is an intentional corrupt/missing-media control and does not simulate the original decoder issue.

## Open risks and readiness

Not ready to recommend M15C. The portrait retry preserved media and passed publication checks, but the operator considers its wait unacceptable, and important physical coverage is outstanding. Complete the Moto matrix, get reliable markers and targeted human visual/listening sign-off, follow up on the injection and rotation-sentinel regressions, and review the export-wait budget. No production integrity defect was confirmed in the exercised paths. Loss or alteration of confirmed media, incorrect or silent capture, duplicate or unrecoverable publication, reliable A/V offsets outside tolerance, or a reproducible stall that prevents ordinary playback/recording require review before release. No speculative retry, player change, or production fix is proposed.

M15B.2's single immediate-seek stall with a Media3 release timeout remains distinct from the unresolved earlier vendor decoder error. If either recurs, record the exact UI actions, phase, clip ordinal, media profile and privacy-safe error fields, then compare with known-good synthetic and native camera sources before proposing a correction.

## Recording and listening protocol

1. Verify the device with `adb devices -l`; always select it explicitly with `adb -s "$SERIAL"`. Install only a new validation ID after checking both app IDs are absent and the APK manifests match. The field app (`com.calvinmazhindu.skitcam.m15b3field`) has no normal launcher icon. Open it with `adb -s "$SERIAL" shell am start --user 0 -n "$pkg/com.calvinmazhindu.skitcam.MainActivity"`, with `pkg` set to the field ID. Grant only the validation app's needed permissions.
2. Set Skit Video to 1080p and record requested FPS (including Auto if used), lens and stabilization. Keep the phone in a single orientation per session. Make three ten-take batches per orientation, using 2–5 s takes and an identifiable visual slate for each. Clap visibly about half a second in and before pausing; use a quiet target and avoid other transients near the claps. After the tenth confirmed take, hash the originals. In edit batches, discard two and restore one, save a non-destructive trim that retains an identifiable marker, then reorder. Use Review and Play Skit, close playback, and append a newly numbered take where scheduled. Record the expected **kept order and source trim ranges** before export.
3. Make two 3–5 min takes per orientation with markers near the beginning and end. Leave an uninterrupted baseline take and exercise background/foreground behaviour in a separate disposable interruption case; compare a stationary return and a gentle nudge. Try 4K only if offered and supported for the exact selected lens/settings; record support evidence. For each profile report encoded dimensions, display rotation/dimensions, codecs, video cadence, and audio sample rate/channels.
4. Built-in mic: first confirm no external capture input for that batch. For USB-C and wireless when available, separate handset from transmitter/mic and put distinct known sounds near each. Compare pickup and decoded signal at the expected times; do not use Android's route label as proof of source. Use normal and quiet speech, two-person dialogue, loud speech/laughter; connect first, disconnect during a disposable take, then reconnect between segments. Record whether interruption safely paused or rejected the take, whether the audio went silent or fell back, and what the next segment captured. Unavailable or unconfirmed hardware is not a pass.
5. Cancel a disposable export before publication, record resulting state and row count, retry, return to camera, and reopen the **published** file in Gallery and a second player. Check number/order, crop and orientation visually. The operator must listen to starts, cuts, trim edges and ends and report missing or distorted speech and whether lip sync sounds right. FFmpeg decode, metering and marker analysis are not human acceptance.
6. During recording, Review and Play Skit, record the exact steps for any problem, including whether a new segment can be started after returning and whether the previous take was confirmed. Run only validation-scoped process recovery and resource injections. Never fill physical shared storage. On a playback fault, retain only allowlisted `SkitCamPlayback` JSON (`playback_failure`) since the device-time start for that batch, plus the observed steps. Do not retain full Logcat, IDs, URIs, paths, or raw exception text; do not clear shared logs.

### Machine evidence for a field export

After confirming the exact disposable session UUID with the operator, read only that validation directory. For example, after pausing and again after export, compare `run-as "$pkg" sha256sum files/sessions/$sid/segment_*.mp4` via `adb -s "$SERIAL" shell` with the glob evaluated **inside** `run-as ... sh -c`. Cross-reference the basenames with committed `session.json`; label any unconfirmed candidates separately. Hash before edits and again immediately before export if new takes were appended. Copy only named, confirmed validation sources into a protected, Git-ignored location for decoding; do not copy all files on a device.

When a job has settled and no recording is active, the readback test starts a separate instrumentation invocation. It takes an explicit session UUID, checks only that validation job's owned MediaStore rows including pending rows, and reads the actual published item. It refuses to overwrite a named local copy:

```sh
pkg=com.calvinmazhindu.skitcam.m15b3field
adb -s "$SERIAL" shell am instrument --user 0 -w -r \
  -e class com.calvinmazhindu.skitcam.export.FieldPublicationDeviceTest \
  -e fieldSession "$sid" -e fieldState Completed \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
adb -s "$SERIAL" exec-out run-as "$pkg" cat "files/m15b3-published/$sid.mp4" > "$local_copy"
ffmpeg -nostdin -v error -xerror -i "$local_copy" -map 0:v:0 -map 0:a:0 \
  -fps_mode passthrough -enc_time_base:v demux -f null -
```

Check the instrumentation verdict (`OK (1 test)`), not just ADB exit status. Use `fieldState Cancelled` to check there is **no** row, without making a copy. Resume the isolated app explicitly after instrumentation. Compare actual slate numbers and marker locations with the trim/order manifest. Use `ffprobe -show_streams -show_frames` with selected fields to record codec, dimensions, rotation side data, and presentation cadence (observed frame PTS, not only the nominal frame rate).

### Measuring real cues

Frame-step the decoded video in presentation order, and inspect the decoded audio waveform before annotating any window. Use the zero-based index of the first frame that shows contact (or a visible flash), immediately after a clear pre-contact frame. Use `ffprobe` frame PTS or `ffmpeg -vf showinfo -fps_mode passthrough` to map this to the decoded order; do not infer PTS from nominal FPS. Choose a narrow window around the **first unambiguous** sound of that cue, with quiet audio before it. Reject obscured contact, ambiguous onset, clipped/transient-confounded cues, or windows with timestamp gaps around the cue. Keep contact distance stable and account for sound travel (~3 ms/m), threshold choice, exposure/rolling shutter and apparatus uncertainty in the padding. Do not use 5 ms as a default for field claps: estimate padding for the actual setup.

Example annotation for a synthetic cue:

```json
{"cues":[{"label":"first","first_contact_frame":30,"audio_window_s":[0.94,1.08],"threshold":0.2,"padding_ms":5}],"drift_pairs":[]}
```

Run `python3 tools/measure_field_sync.py "$local_copy" "$annotations"`. The tool uses decoded video PTS and an audio sample bracket tied to the decoded audio-frame PTS, then reports **audio minus video** bounds (positive = audio late). The result is a pass only when the entire interval is within ±100 ms; a boundary-straddling or ambiguous result is inconclusive (report **BLOCKED** if no reliable measurement can be obtained). Use `drift_pairs` like `[["early","late"]]` for long takes; report change in offset bounds, not stream-duration difference. Measure matched markers in source and export and surviving markers on both sides of joins and trims. Re-run with plausible threshold choices and widen uncertainty or reject the cue if the result is sensitive. `tools/measure_timing_markers.py` is an independent synthetic flash/chirp check, not an automatic clap detector.

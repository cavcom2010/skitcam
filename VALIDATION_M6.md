# SkitCam Milestone 6 — automatic MP4 assembly

Status: core Milestone 6 workflow implemented and verified on the Moto G54.
Platform, interruption and stress-test limitations are listed below.
No changes have been committed or pushed. Branch: `feature/automatic-mp4-export`,
from committed Milestone 5 (`9108330`). Minimum SDK 26, compile/target SDK 36.

## Architecture and storage

The existing recording state machine and version 4 session format remain intact.
STOP creates one durable export request, waits for recording finalisation, then
freezes the session. STOP while paused creates no additional segment. The export
specification contains only retained stable segment IDs, in saved playback order,
with original paths and integer-millisecond `[start, end)` boundaries. An empty
selection produces a clear Empty result, never a fake video. Invalid order, pending
or failed finalisation, missing sources, invalid cuts and incompatible orientation
are rejected; retained clips are never silently skipped.

`export/job.json`, next to the original `session.json`, uses Android AtomicFile.
It stores a UUID, session snapshot, source hashes, ordered export specification,
state, verification checkpoint and publication URI. Each session has one export
job. Source recording metadata and MP4s are never modified by export. Legacy
untrimmed intent resolves to the probed full source duration; saved user cuts stay
exact. A saved cut whose source duration changed by more than 1 ms is rejected.

Execution uses a direct foreground service, independent of Activity recreation:
`mediaProcessing` on API 35+, `dataSync` on API 29–34, ordinary foreground service
on API 26–28. This avoids Android 16 long-running WorkManager quota coupling.
Notification permission is optional on API 33+; the foreground-service obligation
still applies. The service holds a bounded wake lock and handles cancellation,
platform time limits and low space. Only one export runs at a time. Camera use is
released before hardware transformation; a fresh recording waits for export.

States: Preparing → Exporting → Verifying → Publishing → Completed, with Failed,
Cancelled and Empty outcomes. Retry reuses the same job and frozen inputs. Process
interruption is reported on recovery; encoding restarts from the beginning unless
a verified private output checkpoint is intact. No mid-encoder resume is claimed.

Media3 Transformer/Composition 1.11.1 builds one ordered EditedMediaItemSequence.
Each MediaItem carries saved clipping boundaries. Both tracks are decoded and
re-encoded; single-clip jobs are also forced through the encoder factory.

Supported output profile:

- MP4 container; H.264/AVC SDR video, requested 4 Mb/s.
- Display dimensions fit HD, at most 1280 × 720 (portrait equivalent permitted),
  preserving aspect ratio and avoiding upscaling. Incompatible aspect ratios fail.
- AAC-LC, 48 kHz, stereo, requested 128 kb/s; mono sources are mapped to stereo.
- Inputs require exactly one audio and one video track. HDR is rejected. Actual
  codec availability is device dependent; no silent encoder fallback is enabled.
- EditedMediaItem.setFrameRate(30) limits video frame rate in the decoder path.
  Variable/high-rate inputs still need broader validation.
- Media3 controls relative track offsets and composition timing. This is a streaming
  pipeline, not a whole-video in-memory edit. Source orientation metadata is read
  and display aspect ratios reconciled; portrait encoding may use rotation metadata.

Output is initially private `export/output.mp4`. Native MediaExtractor/MediaCodec
fully decode video and audio separately without speaker playback or frame retention.
Verification requires expected codecs, samples, monotonically presented timestamps,
duration within 100 ms of the edited sum and first/last track timestamps within
100 ms. AAC priming may validly precede zero. These checks do not prove scene order,
cut precision or subjective sync; separate fixture measurements are required.

On API 29+, publication uses MediaStore in Movies/SkitCam with IS_PENDING=1.
A unique job DESCRIPTION marker recovers insertion-before-journal crashes without
creating another row. The copy is fsynced, SHA-256 checked and reopened through the
content URI before making it visible. Already published content is never overwritten.
API 26–28 uses legacy read/write permission, a hidden staging file, verified rename
and media scanning. This fallback has not yet been exercised on hardware.
Verified private output remains on publication failure and after success. Retry
must not erase it merely because gallery copying failed. Original segments are
never candidates for automatic cleanup.

## Actual hardware observations so far

Motorola Moto G54 5G, serial ZY22KJQLTV, Android API 35. Real USB ADB device.

The user's real 12-segment session `b68ff22f-f65b-4c0e-b8d4-7cb50023a6f3`
completed after the user pressed STOP. Native full decoding and publication passed:

- MediaStore URI: `content://media/external_primary/video/media/1000137076`.
- Verified duration: 67,700 ms.
- Verified SHA-256: `84f1a75b9f47d0a7e92f294a7266b3b52d4caf61b3792e59dd86983ade30c769`.
- Sources and session metadata retained. This proves one real untrimmed multi-clip
  job; it does not prove subjective sync, trim precision or every UI action.

A separate generated fixture contains red/green/blue/yellow scenes and distinct
sine tones. Green is discarded; saved order is yellow, red, blue. Each retained
clip selects [350,1750) ms from a 3-second source (total expected 4,200 ms).
A white flash and simultaneous audio chirp provide timing landmarks. Fixtures are
new dedicated files, not edits to user recordings.

Initial trimmed output failed verification and was not published. Independent
ffprobe/ffmpeg inspection found video 4.116667 s versus audio 4.254708 s, with all
frames decodable. Visible order was yellow/red/blue, with flashes at 0.783333,
2.183333 and 3.583333 s; loud chirp windows began near 0.76, 2.16 and 3.57 s.
The joins' cues were close, but the video tail ended too early. This was a real
correctness issue; verification tolerance was not widened.

Upgrading all Media3 modules together to stable 1.11.1 corrected this fixture.
The physical retry passed and published a verified 4,255 ms MP4, SHA-256
`b66436a3486e596a6d85751cb6ab8890aa82a7d0665f9caf02871ad42a7f8ab1`.
Independent inspection with `tools/check_export_fixture.py` passed: video duration
4.216667 s, audio 4.254708 s, AVC 1280×720 and AAC 48 kHz stereo. Scene order was
yellow/red/blue; flashes were exactly 0.75, 2.15 and 3.55 s, with chirp windows
starting 0.76, 2.16 and 3.57 s. Scene joins occurred at 1.416667 and 2.816667 s.
These measured tolerances apply to this fixture, not a frame-accuracy guarantee.

Earlier physical attempts exposed and corrected a Media3 default frame-drop
texture-pool failure and a verifier error that treated valid negative AAC priming
PTS as timestamp regression. Failed private candidates and test recordings remain.

## Validation results

Final local build 15 passed:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint --no-daemon
```

- 77 JVM tests, zero failures or errors (62 existing + 15 export-domain cases).
- Debug APK and instrumentation APK built successfully.
- Full lint passed: zero errors, 22 warnings.
- Build 16 rebuilt the corrected PAUSE instrumentation setup successfully.
- Four dedicated export-journal cases passed on the physical phone.
- The dedicated reordered/trimmed fixture passed on Media3 1.11.1, including
  native full decode, publication and independent host scene/audio checks.
- The 19-case physical regression run passed 15 cases: automatic two-segment
  export (including repeated STOP and same-job re-launch), six recording cases,
  four arrangement cases and four trim cases.
- Four PAUSE cases failed in setup because their harness still waited for the
  camera behind the Finished screen. The setup now uses the real New Recording
  action, as the other suites do. The corrected test APK built successfully;
  the corrected four-case PAUSE retry subsequently passed in 69.713 seconds.

The 19-case physical run command was:

```sh
adb shell am instrument -w -r -e class com.calvinmazhindu.skitcam.export.ExportDeviceTest#stopAutomaticallyExportsAndPublishesTwoSegments,com.calvinmazhindu.skitcam.recording.RecordingDeviceTest,com.calvinmazhindu.skitcam.review.PauseEnvironmentDeviceTest,com.calvinmazhindu.skitcam.arrange.ArrangementDeviceTest,com.calvinmazhindu.skitcam.trim.TrimDeviceTest com.calvinmazhindu.skitcam.test/androidx.test.runner.AndroidJUnitRunner
```

The generated landscape fixture used `ExportDeviceTest#exportDedicatedSceneFixture`
with instrumentation argument `fixture=980e0519-ce34-4278-b609-4403867ca078`.
Host measurement command:

```sh
python3 tools/check_export_fixture.py /tmp/skitcam-m6-scene-output4.mp4
```

This physical run used injected UI/touch actions and actual CameraX/Media3 on the
Moto G54. It was not an emulator run or human finger/listening assessment.

SHA-256 comparison passed for all 295 pre-change session files, with zero changed
or missing entries. The second inventory after the user's recording completed
also passed for all 352 files, including the new user recording. Host diagnostics and copies stay under `/tmp`.

## Remaining validation and limitations

- Synthetic non-keyframe trim regression passed after the Media3 update; broaden
  testing to unusual timestamp offsets and high/variable frame rates.
- All 19 distinct recording/export regression cases passed across the initial
  run and corrected four-case PAUSE retry.
- Play, Share chooser and New Recording passed in the final combined workflow.
  Broaden physical tests for cancellation, backgrounding, real encoder-process death
  and publication failure; checkpoint recovery has simulated test coverage.
- API 26/28 storage fallback, Android 16 foreground restrictions, long/100-segment
  hardware stress, high/variable frame rates, storage exhaustion, external gallery
  behavior, audio listening and human lip-sync checks remain manual follow-ups.
- Private verified exports consume an additional copy. No automatic source cleanup.
- No promise of frame-accurate cutting: frame cadence, AAC packet boundaries,
  decoder preroll and container edit-list behavior need measured tolerances.
- No completed-session library. Previous sessions remain on disk for audit/recovery.

## References used for implementation

Official tagged API sources, rather than newer-version examples:
[Transformer 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/transformer/src/main/java/androidx/media3/transformer/Transformer.java),
[Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3),
[mediaProcessing foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#media-processing),
[shared media storage](https://developer.android.com/training/data-storage/shared/media).

Current measured checks: 77 JVM tests passed on build 14. Four physical journal
tests passed on build 13, covering duplicate requests, frozen snapshots, interrupted
atomic writes, process-recovery simulation and publication checkpoints. The first
1.11.1 lint run found a stale AppCompatImageButton reference; it was restored to the
native ImageButton because that dependency is no longer transitively present.
Build 15 passed `:app:testDebugUnitTest :app:assembleDebug
:app:assembleDebugAndroidTest :app:lint --no-daemon`: 77 JVM tests, zero failures,
zero lint errors (22 warnings). Four journal hardware cases passed, and the
19-case recording/export regression run passed 15 cases with four PAUSE setup
failures, as detailed above.

Independent inspection of the user's 12-segment export found AVC 1280×720 with
-90-degree display rotation (portrait), AAC 48 kHz stereo, audio duration 67.700 s
and video duration 67.656211 s. FFmpeg's default null-output time base produced
rounding-related duplicate-DTS warnings; a decode with source time base and
passthrough frame timing is used to distinguish these from decoder errors.

The source-time-base FFmpeg decode of the 12-segment output exited successfully
with no errors. The initial generic null-muxer warnings were not decoder failures.

## Latest hardware continuation

The Motorola reconnected. The previously paused test fixture
`8bd32e92-029e-45ad-a5f2-d754804c493a` had successfully completed export to
`content://media/external_primary/video/media/1000137091`.

The four corrected PAUSE cases passed. Together with the earlier 15 successes,
all 19 distinct recording/export regression cases passed across the two runs.
`FinishedPlaybackDeviceTest#publishedVideoPreparesPlaysAndReleases` also passed
on that published URI in 4.976 seconds: READY, advancing position without a player
error, release on Stop and Activity return. This is real playback, not subjective
listening. A first manual Play probe returned to Finished; the focused test then
verified player operation independently. No playback exception was logged.

Build 17 passed full JVM/build/lint after adding playback diagnostics and its test.
Build 18 passed the expanded instrumentation build and full lint. JVM count remains
77, zero failures; lint has zero errors and 22 warnings.

A subsequent expanded STOP/export/Play/Share/New Recording test created session
`81ce9523-9af0-4fd7-81bc-8881dcb5d552` and successfully exported to
`content://media/external_primary/video/media/1000137193`. Other phone UI obscured
SkitCam, including an open notification panel. The test did not reach a confirmed
terminal assertion and was intentionally aborted by force-stopping only SkitCam,
after verifying Completed export status. The runner reported process termination;
this test is **not counted as passed**. SkitCam was cold-launched afterward.

New Recording was exercised by successful recording/arrangement/trim regressions.
The final combined Finished-screen workflow subsequently passed, as recorded
below. No share was sent. Cancellation, real process death during
encoding and gallery-copy failure remain untested on hardware; journal recovery
is covered by simulated checkpoint tests. These remain limits on hardware coverage of the requested Milestone 6 edge cases.

After the corrected PAUSE and focused playback runs, SHA-256 comparison again
confirmed all 295 original inventory entries and all 352 later inventory entries
unchanged, with zero missing entries. New test sessions and gallery outputs remain.

## Changed implementation surfaces

- `export/ExportModel.kt`: immutable selection, duration reconciliation, state guards.
- `export/ExportJournal.kt`: atomic job identity, snapshot and recovery checkpoints.
- `export/ExportMedia.kt`: bounded Media3 composition, source checks and native decode.
- `export/ExportService.kt`: foreground execution, progress, cancellation and retry.
- `export/GalleryPublisher.kt`: pending gallery copy, hash validation and idempotency.
- `export/ExportScreen.kt`: finishing/results UI and released-on-stop playback Activity.
- RecordingViewModel and MainActivity: STOP/finalisation integration and camera release.
- Manifest/build configuration: service/storage permissions and uniform Media3 1.11.1.
- Export JVM/device tests and camera regression setup; host fixture checker.
- README and this report. Original recording state transitions remain unchanged.

## Final Finished-screen workflow result

After reconnecting the phone, the expanded physical workflow reached the actual
Android Share chooser. The harness initially expected package `android`; this
Moto uses `com.android.intentresolver`. Correcting that assertion produced a
successful complete run in **15.503 seconds**, with no application behavior change:

1. START/PAUSE/RESUME created two real CameraX MP4s with audio.
2. STOP (including a repeated tap) finalised recording and automatically exported.
3. Native full decoding, gallery copy validation and publication completed.
4. Re-launching the same completed job preserved its final URI.
5. The real Play button opened Media3; READY and advancing playback were asserted.
6. Share opened Android's chooser. Back dismissed it without sending anything.
7. New Recording returned to an available rear-camera preview.

Final test session: `5a8ddcca-57aa-4682-98f8-55d2584e2f30`.
Published URI: `content://media/external_primary/video/media/1000137215`.
Native verified duration: **3,593 ms**.
SHA-256: `699f0a93882e8bbb9c21c259af78dba749abe667520bbb6a763b324ec28c8fef`.

Final test command:

```sh
adb shell am instrument -w -r -e class com.calvinmazhindu.skitcam.export.ExportDeviceTest#stopAutomaticallyExportsAndPublishesTwoSegments com.calvinmazhindu.skitcam.test/androidx.test.runner.AndroidJUnitRunner
```

Build 19 passed instrumentation assembly and full lint after correcting the
chooser assertion. The final application APK is build 17; build 18/19 changed
only tests. Local JVM count is **77 passed**, build and lint have **zero errors**
(**22 lint warnings**). Physical coverage totals **25 distinct passing cases**:
19 recording/export regressions, four journal cases, one dedicated scene/trim
fixture and one finished-player lifecycle case. Reruns and abandoned attempts
are described above and are not counted as additional successes.

The core recording-to-gallery success criterion is verified, including the
reordered/trimmed synthetic fixture and both portrait and landscape recording
regressions. This is not a guarantee for every codec, device or interruption mode.
Original recordings and metadata remain retained, and no commit or push was made.

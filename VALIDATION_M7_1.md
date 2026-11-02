# Milestone 7.1 — export timing investigation

Branch: `feature/external-microphone-reliability`. Existing uncommitted M7 changes
were preserved. No branch switch, reset, commit or push. Application version
0.7.1 (8). Original failed job and candidate were not retried or overwritten.

## Conclusion and evidence

The preserved failure contains real **additional displacement across a join**,
not merely an endpoint-calculation artifact. The failure reproduces in committed
M6's capture/export code without the M7 microphone monitor. Media3's handling of
positive initial audio timestamps together with per-item silence-duration filling
is implicated; a bounded adapter restoring the source-declared leading gap in
the PCM representation corrects the measured cases. Verification remains strict.

This establishes a mechanism present in M6 code. It does not date the first
historical occurrence, prove physical lip-sync of the source recordings, or prove
that every possible codec/timestamp pattern is supported.

## Preserved failure timestamps

Session: `769f2f6a-ae12-4159-bfe8-1029bf834bac`.
All values below are seconds; endpoints include the final decoded sample/frame.

| Artifact | Audio first PTS | Audio last PTS | Audio presentation end | Video last PTS | Video presentation end |
| --- | ---: | ---: | ---: | ---: | ---: |
| Source 1 | .066104 | 1.774583 | 1.795916 | 1.771389 | 1.804722 |
| Source 2 | .009396 | 1.630729 | 1.652062 | 1.604222 | 1.637544 |
| Rejected output | 0* | 3.529333 | 3.550666 | 3.408922 | 3.442244 |

*FFmpeg edit-adjusted first audio frame starts at zero and has 448 samples.
Android's decoder reports a -12,000 us first PTS for its first returned buffer.
The final Android PCM endpoint agrees with independent FFmpeg measurement.

The last-PTS difference is **120.411 ms**. The presentation-end difference is
approximately **108.422 ms**. Video's last frame lasts about 33.322 ms; audio's
last block contains 1024 samples at 48 kHz, about 21.333 ms. Comparing their starts
therefore overstates the endpoint difference by about 11.989 ms. Even the corrected
endpoint difference exceeds 100 ms, so this observation alone cannot justify
accepting the original output.

Sources use video timescale 90,000, audio 48,000 and movie 10,000. Source audio
edit lists include empty leading edits: 661 movie ticks (66.1 ms) for source 1,
and 94 ticks (9.4 ms) for source 2. The output audio edit list starts media at
1600 audio ticks (33.333 ms). Its raw audio media duration is 172032 ticks;
edit-adjusted presentation duration is approximately 3.550708 s. Header duration,
track span, last PTS and decoded presentation end are not interchangeable.

## Reproduction and controls

All re-renders used private copies under `files/validation-m71/`, unique output
paths and no MediaStore publication. Production failure metadata was untouched.

| Experiment | Result |
| --- | --- |
| Current pre-fix pipeline, preserved failure, three repetitions | Rejected 3/3; identical 120411 us last-PTS gap |
| Current pre-fix pipeline, passing fixture, three repetitions | Accepted 3/3; 68778 us last-PTS gap |
| Isolated committed M6, same failure copies | Rejected 3/3 |
| Isolated committed M6, same passing copies | Accepted 3/3 |
| Fresh M6 capture/control tests | One recording/export passed; another fresh two-segment export reproduced timing rejection |
| Remove application audio processors, copied failure | Still rejected 3/3 with 120411 us gap |
| Zero-start generated audiovisual marker control | Cues within 5 ms before correction |
| Generated positive-start-offset marker probe | Second chirp about 135 ms late before correction |

The isolated M6 build was made from `git archive b119ae0` under `/tmp`, with a
separate application ID `com.calvinmazhindu.skitcam.m71control` and identifying
label. Only its application identity and diagnostic tests were added; its capture
and transformation implementation remained the committed baseline. The primary
app was not downgraded or replaced with that control. Control recordings remain
in the separate app. Initial control setup skipped because its private files
directory had not yet been initialised; that attempt is not counted as a render.

Fresh M6 failure: `ae197e38-c8e1-4a09-9d03-81dcb699386c`; its first source has a
69.604 ms audio start offset. Fresh successful M6 session:
`8a526c64-c8ad-47d6-9202-5ee120618198`, first audio offset 14.104 ms.
These observations rule out M7 monitoring as a necessary cause of the failure.
They do not prove that monitoring can never affect scheduling on any device.

## Join/content measurements

Cross-correlating two independent 250 ms PCM windows in each preserved source
against the rejected export produced correlations approximately .985–.993:

| Source content | Displacement from intended source position |
| --- | ---: |
| Clip 1, windows at .3 s and .9 s | +9.333 ms |
| Clip 2, windows at .3 s and .9 s | +65.750 ms |

The additional displacement after the join is about 56.417 ms. The passing fixture
also had a smaller interior error: +9.333 ms for clip 1 versus -25.458 ms for clip 2,
with correlations .965–.994. Passing endpoint checks alone did not prove join sync.

Generated red/blue scenes have calibrated white flashes and audio chirps. Source
cues align within a 5 ms analysis window. Before correction, exported flashes
occurred at 1.0 and 4.0 s, while offset-probe chirps occurred at 1.005 and 4.135 s.
The zero-offset control remained at 1.005 and 4.005 s. This independently
demonstrates join displacement without relying on the quiet real recordings.

## Focused correction

`LeadingAudioGapFactory` decorates Media3's normal asset loader. It reads the
source audio's first sample timestamp and compares it with the selected clip
start. Only an actual source-declared positive gap is eligible. When the first
raw decoded PCM buffer arrives, the adapter represents that existing gap with
zero PCM frames before the original samples. It restores no more than the source
gap or the first decoded PTS and preserves any remaining timestamp component.

Media3 can then count the gap within the clip's expected duration, rather than
counting a full PCM duration and also offsetting the stream. Original PCM bytes
are preserved. This is not a tail cut, arbitrary padding, source rewrite,
independent retiming of video, or custom AudioRecord/muxing replacement.

The adapter is bounded to a two-second gap, mono/stereo signed 16-bit or float
PCM, sample rates up to 192 kHz and a known longer clip duration. Unsupported
cases fail closed with originals retained. Allocation is bounded to one leading
buffer per item; there is no whole-video memory load. Queue backpressure cannot
insert the gap twice, and EOS is forwarded without fabricated samples.

An early diagnostic adapter needed explicit forwarding of Java's default
`SampleConsumer.getInputBuffer` method; it failed before timing verification and
was never enabled in production. An ungated prototype also moved zero-start AAC
cues by about one AAC packet. Restricting it to source-declared gaps restored the
zero control's original 5 ms cue alignment. These failed experiments remain in
private diagnostic artifacts and are not counted as successful corrections.

The gated prototype accepted all measured real and generated cases. The failed
real fixture's first/second content offsets became approximately +9.333/+9.042 ms,
removing the join displacement to within one millisecond in those windows. Its
output duration fell from 3551 to 3487 ms under the unchanged verifier. The offset
marker probe's second cue improved from about 135 ms late to at most 15 ms in the
initial prototype; the gated/final implementation is checked separately below.

## Verifier semantics and compatibility

The existing 100 ms duration and first/last-sample coverage guards remain intact.
No threshold was increased, bypassed or removed. Diagnostics now distinguish
first PTS, last PTS, decoded PCM frame counts, PCM exclusive end and reported
track duration. Video presentation end is not guessed from nominal frame rate.
Host inspection obtains it from sample durations and edit-adjusted timestamps.
These diagnostics do not equate codec padding with audible content.

New jobs atomically retain `timingDiagnostic` alongside existing export metadata,
including on timing rejection. Existing job/session schemas remain compatible;
no recording chronology, trim, Keep/Discard or arrangement data is migrated.
Existing failed jobs are preserved. Tests and normal exports use the same strict
verification; experimental controls never publish gallery videos.

## Validation status

Production build 7 passed **93 JVM tests**, debug APK assembly, instrumentation
assembly and full lint. Build 8 rebuilt the final diagnostic test expansion.
The 22 existing lint warnings remain; final warning comparison and hardware
regressions are being collected. Six timing-arithmetic JVM tests extend M7's 87.

The running final suite has 25 cases: fifteen private renders in one bounded
fixture test, four PCM consumer tests, six recording tests, the complete Finished
workflow, the M7 audio workflow, four PAUSE, four arrangement and four trim cases.
Final results and preservation checks are recorded below.

## Tools and reproducibility

- `tools/inspect_mp4_timing.py`: read-only moov/sample/edit-list and ffprobe analysis;
  moov input is bounded to 16 MiB.
- `tools/make_timing_probe.py`: creates only NEW generated fixture directories.
- `tools/measure_timing_markers.py`: flash/chirp times; uses host numpy and FFmpeg.
- `tools/correlate_audio_join.py`: source/output PCM correlation with confidence.

No dependencies were added to the Android app. Host numpy/FFmpeg were already
available. Fixture copies and result archives remain under `/tmp/skitcam-m71-*`.
Media3 1.11.1's AudioGraphInput and SilenceAppendingAudioProcessor were inspected
alongside the exact cached APIs. Source code and measurements jointly support
the mechanism; endpoint differences alone would not.

## Remaining limits and Milestone 8 recommendation

Human listening and physical lip-sync were not measured; the real recordings are
quiet. Synthetic cues and strong PCM correlations establish content-timing
behavior, not general perceptual quality. No frame-accuracy guarantee is made.
External microphones remain untested, as documented in M7. Older Android versions,
very long/100-item sessions, unusual codecs/edit lists and large initial gaps
need further coverage. Verification remains protective for unsupported patterns.

**Recommendation: safe to start Milestone 8 development**, retaining the strict
verification and regression fixtures. The reproduced failure is explained and the
focused correction has passed final production tests. This is a development
recommendation, not unrestricted production qualification across all devices,
codecs, microphone hardware or long sessions.

## Checkpoint — user requested continuation later

The user requested stopping further work near the account usage limit. No further
scope expansion is planned. The final production run's private fixture outputs are
under `files/validation-m71/run-14bf38ed-632d-484c-8666-15a5a7154362` on the primary
app and copied under `/tmp/skitcam-m71-production-results/`.

All **15 production fixture renders accepted** under the unchanged verification
thresholds: three each for the original failed sources, passing sources, zero-start
markers, delayed-start markers and reordered/trimmed M6 markers. Final measured
marker errors: zero-start +5/+5 ms; delayed-start +5/+15 ms. The reordered trim
checker passed with yellow/red/blue order and flashes at .75/2.15/3.55 s. Production
last-PTS gaps were 56411 us (failed-source copies), 26111 us (passing copies),
37334 us (zero/delayed markers), and 50000 us (ordered trims).

The full 25-case device suite was still running at checkpoint collection. Its log
is `/data/local/tmp/skitcam-m71-regressions.log` on the Motorola. It had passed the
fixture test, four PCM-consumer tests, recording tests, Finished workflow, M7 audio,
PAUSE and early arrangement cases. Do not claim the entire suite passed without
reading its final result. It may finish independently after the agent turn ends.

Next continuation:
1. Read the final device log and the `/tmp/skitcam-m71-after.sha256` comparison.
2. Confirm remaining arrangement/trim cases, and record exact final test counts.
3. Finish the Milestone 8 recommendation and add concise README/M7 cross-links.
4. Check git diff and preserve all uncommitted work; do not commit or push.

Build 7 passed JVM/build/lint; build 8 changed only instrumentation tests. The
primary app is 0.7.1 (8). The separate M6 control app and all diagnostic recordings
remain installed/retained. The original failed export remains unchanged and
unpublished; only private copies were corrected.


## Final collection — 2026-11-02

The device suite finished independently after the saved checkpoint. Its complete
log ends **OK (25 tests)**, **410.466 seconds**, with no failures or skips. This
includes the 15 private production renders (three each of five fixture types),
four PCM-buffer forwarding/backpressure cases, six recording cases, STOP/export/
Play/Share chooser/New Recording, the M7 microphone workflow, four PAUSE cases,
four arrangement cases and four trimming/recovery cases. The 15 renders are
subcases of one test and are not counted as 15 additional test methods.

All production fixture outputs passed the original verifier. Last-PTS gaps:
failed-source copies 56411 us; passing copies 26111 us; zero/delayed markers
37334 us; ordered trims 50000 us. None required increasing the 100000 us limit.
Final source-gap markers measured +5/+15 ms versus +5/+135 ms before correction;
zero-start controls stayed +5/+5 ms. Ordered non-keyframe trims retained yellow →
red → blue, flashes at .75/2.15/3.55 s and chirps near .76/2.16/3.57 s. The
independent trim checker passed. These are measured fixture tolerances, not a
frame-accuracy or subjective lip-sync guarantee.

The full suite includes fresh real CameraX multi-segment recordings and actual
Android Play and Share-chooser interactions. Nothing was shared to another app
or person. The final restart fixture intentionally remains retained in Paused
state; it is test-owned and can be completed through STOP without deleting it.

The exact final commands were:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint --no-daemon
adb shell am instrument -w -r -e class com.calvinmazhindu.skitcam.export.TimingInvestigationDeviceTest,com.calvinmazhindu.skitcam.export.LeadingGapConsumerDeviceTest,com.calvinmazhindu.skitcam.recording.RecordingDeviceTest,com.calvinmazhindu.skitcam.export.ExportDeviceTest#stopAutomaticallyExportsAndPublishesTwoSegments,com.calvinmazhindu.skitcam.audio.AudioDeviceTest,com.calvinmazhindu.skitcam.review.PauseEnvironmentDeviceTest,com.calvinmazhindu.skitcam.arrange.ArrangementDeviceTest,com.calvinmazhindu.skitcam.trim.TrimDeviceTest -e kinds failed,passed,zero,offset,trimmed com.calvinmazhindu.skitcam.test/androidx.test.runner.AndroidJUnitRunner
```

Primary changed code: `LeadingAudioGapFactory.kt`, `TimingMeasurement.kt`,
`ExportMedia.kt` and export-job diagnostic persistence in `ExportService.kt`.
The surrounding uncommitted M7 implementation was preserved. There are no new
Android dependencies, permissions, backend services or source-file migrations.
The isolated M6 control app and its recordings remain retained alongside the
primary app; it can be distinguished by its M6 timing-control label.

Official implementation references:
[Media3 1.11.1 AudioGraphInput](https://github.com/androidx/media/blob/1.11.1/libraries/transformer/src/main/java/androidx/media3/transformer/AudioGraphInput.java),
[SilenceAppendingAudioProcessor](https://github.com/androidx/media/blob/1.11.1/libraries/transformer/src/main/java/androidx/media3/transformer/SilenceAppendingAudioProcessor.java),
[MediaCodec buffer timestamp semantics](https://developer.android.com/reference/android/media/MediaCodec.BufferInfo).


Final local rerun (2026-11-02): **BUILD SUCCESSFUL**, 50 seconds; **93 JVM tests**,
zero failures/errors; debug and instrumentation APKs assembled; full lint passed
with **22 existing warnings and zero errors**. `git diff --check` passed.

Final post-suite SHA-256 audit compared **628 pre-existing device session files**:
**zero changed or missing entries**. This includes original MP4s, metadata,
export journals and candidates. Local original failure MP4 hashes also passed.
The original rejected output/job remain untouched and unpublished.

An independent FFmpeg full decode of the final corrected failure-copy output
completed without errors. ffprobe reports H.264 1280×720, duration 3.442244 s,
and AAC 48 kHz stereo, duration 3.486708 s. The remaining track-end difference
is about 44.464 ms, compared with approximately 108.464 ms in the original.
Interior content/marker evidence, rather than that endpoint reduction alone,
supports the correction.

No commit, push, reset or branch switch occurred. All original M7 work remains
uncommitted together with the focused M7.1 changes. Final application APK:
`app/build/outputs/apk/debug/app-debug.apk`, version 0.7.1 (8).

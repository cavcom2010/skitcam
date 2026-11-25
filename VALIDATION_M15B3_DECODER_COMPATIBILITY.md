# M15B.3.1B — Decoder compatibility and export reliability

Baseline: `d065a27` on `feature/m15b3-field-validation`. Device: Moto G54 5G
(Android 15/API 35, `V1TDS35H.83-20-5-8-9`). All device tests ran under the
isolated `com.calvinmazhindu.skitcam.m15b31b.m12bvalidation` package. Inputs
were synthetic or staged copies of retained validation evidence; the
production package and original recordings were not opened or changed.

## Failure being investigated

M15B.3.1A observed a MediaTek AVC decoder failure during Media3 rendering
and another during full verification. The latter failed again without
re-rendering. Both were released-state failures; an earlier run had also
reported `0x80000000`. In the retained M15B.3.1 log, the last reported output
PTS was 23.444 s. The component then returned `0xe`, Android reported
`0x80000000/UNKNOWN_ERROR` while the codec was `STARTED`, and the next
`dequeueOutputBuffer` saw `Released`. This is a mid-stream failure, not a
failure to select or configure the decoder. It identifies the component
and failure sequence, not the root cause inside the vendor service.
Injected self-release tests recovery, not the vendor fault itself.

The two selection points are independent:

| Stage | Existing behavior | Controlled change |
|---|---|---|
| Verification | `MediaCodec.createDecoderByType`, synchronous, no output surface | Name a single AVC decoder; keep the same sequential full video and audio verifier, duration/PTS checks and SHA-256 |
| Rendering | Media3 1.11.1 `DefaultDecoderFactory` via `LeadingAudioGapFactory`, video decoded to a surface | Filter the factory's selector to one AVC name; keep the default AAC decoder list, audio-gap handling, effects, trims and encoder settings; verify with the default verifier |

The Media3 builder has decoder fallback off by default. Enabling setup
fallback is not a solution to a mid-stream decoder release. The trial
does not turn it on, and neither trial automatically retries.

## Runtime AVC inventory

On both the 6 s 1080p High-profile synthetic input and the rotated 1080p
field candidate, the app's `MediaCodecList` gave the same result:

| Canonical decoder | Runtime aliases | Classification | Available for these non-secure formats |
|---|---|---|---|
| `c2.mtk.avc.decoder` | `OMX.MTK.VIDEO.DECODER.AVC` | MediaTek hardware | Yes; format default and observed stage default |
| `c2.android.avc.decoder` | `OMX.google.h264.decoder` | Android software | Yes; distinct alternative |
| `c2.mtk.avc.decoder.secure` | `OMX.MTK.VIDEO.DECODER.AVC.secure` | MediaTek hardware, secure-only | No; absent from regular list and not format-supported |

Aliases were not tested as alternative implementations. No second regular
hardware implementation was exposed. Audio opened `c2.android.aac.decoder`
in the targeted short trials on both paths.

## Targeted comparisons

Each trial was run in a fresh instrumentation app process. This resets
app-owned codec objects, not the vendor codec service. The retained long
candidate was compared on identical bytes against the original frozen
152,030 ms spec, not against a duration inferred from the candidate.
Media3's field comparison used only the first three retained items in
field order `3, 1, 2`, with both saved trims (31,132 ms total). This
exercises multiple decoder opens and real source metadata without
rerunning the full eleven-source export benchmark. Files were hashed
before and after each trial.

| Short targeted test | Default hardware | Explicit software |
|---|---:|---:|
| Full verification of the same 6 s input | Pass, 3.27 s | Pass, 4.37 s |
| Media3 transform of same 2.50 s trim (transform only) | Pass, 2.68 s | Pass, 2.50 s |

The short verification reports agreed on all decoded sample counts and
timing (180 video frames, 282 audio buffers and 28.0 ms last-PTS gap).
Both short renders decoded at the expected geometry with 75 video
frames, 119 audio buffers and a 38.67 ms last-PTS gap. Timing is
observational, not a throughput estimate from one short run.

**Long verification (hardware, software, software, hardware):** all four
attempts passed. Hardware took 78.54 s and 67.73 s; software took
113.35 s and 115.22 s (about 56% slower on the pair medians). Every run
reported 4,549 video frames, 7,128 audio buffers, 7,298,496 decoded PCM
frames, a 35.71 ms last-PTS gap and identical candidate SHA-256. Thermal
status was `NONE` at the sampled start and end. The same bytes that had
failed verification in M15B.3.1 decoded with either implementation;
the prior fault did not reproduce in these trials. This supports
compatibility, not a reliability improvement or a diagnosis of the
vendor-service fault.

**Media3 field renders (hardware, software, software, hardware):** all
four transforms and their default-verifier passes completed. Transform
time was 18.80 s and 19.34 s for hardware, versus 26.42 s and 25.96 s for
software (about 37% slower on the pair medians). The initialized decoder
was the requested AVC implementation for all three items of each trial;
AAC selection did not change. Every output was 1080 × 1920 in display
orientation and had the same digests over AVC and AAC sample bytes,
presentation timestamps and flags. All four had 932 decoded video
frames, the same 31,164 ms container duration, a 4.39 ms last-PTS gap,
and thermal status `NONE` at the sampled start and end.

**Decision: keep both production defaults.** Software was compatible on
this Moto and both paths, but no hardware failure reproduced in either
comparison and software was slower on representative media. There is
no measured reliability gain to justify a selection policy, including
on Retry. No full benchmark batch was run. The intermittent MediaTek
fault remains open; these samples do not establish its rate on this
device or elsewhere, or readiness for unattended export.

## Retry and integrity

After a verifier decoder failure, the error now says Retry can re-check
unchanged bytes without re-encoding only while the candidate's allowance
remains. Once the durable allowance has been consumed, the message says
Retry rebuilds from the original clips. Process-death restoration
distinguishes the same cases. A failed re-check records its own failure
class instead of retaining the previous one. Cancellation and preparation
errors continue to show their own messages.

The export still verifies complete video and audio sequentially, checks
geometry, duration, timestamps and the 100 ms A/V bounds, hashes the
candidate and frozen source files, and checks the Gallery copy by readback
before completion. No new dependency, encoder/settings change, retry
loop or publication path was added.

## Regressions

The first Gallery run returned to a live camera and published the exact
verified bytes, but the accessibility tree showed an unrelated **Recover
a skit** offer covering the camera shortcut. The navigation test now
defers that offer with **Later** when present, without opening or editing
the offered draft. This is a test fix, not an app UI change.

- **Export paths: 14/14 passed.** The suite covered direct-copy byte
  equality, both audio-origin fixtures, the trimmed/reordered renderer,
  B-frame/different-SPS/VFR inputs, same-job fallback, low space,
  cancellation, and resuming publication through the original pending row.
- **Camera → Gallery → exact export playback → camera: passed.**
  Two freshly recorded segments exported automatically. The test checked
  source digests, one owned non-pending row and SHA-256 readback, then
  opened the published URI through the visible shortcut and Gallery row.
  Video advanced, the share chooser opened, and navigation returned
  through Gallery to the streaming camera.
- **Gallery older-video navigation: passed.** An older row opened its
  own URI, played, and remained visible when returning to Gallery and
  recreating the Gallery activity.
- **Process death: 3/3 recovery pairs passed.** All three first phases
  were deliberately killed. Retry rebuilt when killed before the
  checkpoint (2 production attempts, 1 verification), reused when killed
  after the checkpoint (1 production, 1 verification), and rebuilt after
  death during the allowed re-check (2 production, 3 verifications).
  Each phase-two run preserved the source and unrelated file, published
  one non-pending row, and matched the published digest to `verifiedSha`.
- **Follow-up on the delivered APKs:** five journal tests, focused
  low-space and pending-row checks, and the persistent-failure Retry
  test passed. The persistent-failure test confirmed that the message
  changes to “Retry rebuilds” after the one re-check, that another Retry
  actually rebuilds, and that nothing is published until full verification.
  The injected codec self-release test passed separately: one production
  attempt, one failed verification followed by a complete re-check, and
  one exact published copy.

## Quality gates and artifacts

- CI-equivalent `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and
  `assembleDebugAndroidTest` passed: 299 JVM tests, no failures or skips;
  zero lint errors and 46 warnings (unchanged from M15B.3.1A).
- The validation runner passed `bash -n`; `git diff --check` passed.

| Isolated build | App APK SHA-256 | Test APK SHA-256 |
|---|---|---|
| Short/long comparison and initial path suite | `c9d8a225ee658224f9ae481a3bbf28c14fa79660d147db98b4002aa9162ff91e` | `83ed0a67218125a7d238403e6e170dd0f07421742e10509f6a1609655e311f07` |
| Field renders | `697f54783d6d9695c7462b78a142c873101298a2205ba6df3007287e89eacc20` | `ee99bd0cc4e1bd2232d5bf7272b4fa288ffdd1ee1b4d1ea49d51f1bcbd014d1f` |
| Delivered validations | `697f54783d6d9695c7462b78a142c873101298a2205ba6df3007287e89eacc20` | `2b67282315ad61054dbadec5f340f26f86baa5c6fe7615890147d6eb9b5c4544` |

## Reproduction

Use `SERIAL=<validation-device> bash tools/m15b31b_decoder.sh` with `build`,
`install`, and `stage-short`. `stage-long` accepts the retained M15B.3.1
candidate; its SHA-256 is
`bd1343e877c35524522feee935f75ee0936a3821d51db2bfecef2bc544e829da`.
`stage-field` takes the retained first three source files (override input
directory with `FIELD_INPUTS`). The runner verifies the SHA-256 of every
staged file and refuses to overwrite an existing different copy.

```bash
export SERIAL='your-adb-serial'
bash tools/m15b31b_decoder.sh trial inventory-long inventory -e candidate long.mp4
bash tools/m15b31b_decoder.sh trial verify-soft fullVerification \
  -e candidate long.mp4 -e expectedMs 152030 -e decoder c2.android.avc.decoder
bash tools/m15b31b_decoder.sh trial render-soft fieldPrefixRender \
  -e decoder c2.android.avc.decoder
```

`stage-paths` stages the original zero and offset marker fixtures, a
retained M15B.3 readback with the full direct-copy contract
(`d8f21de3d7598be01a3c26a64d3e6192b9c1461bd2ea64c7d73cbadd69d40da3`),
and derived B-frame, different-SPS and VFR fixtures. The old M12B
baseline app's private golden was not available; the replacement is
explicitly staged and also has AAC-LC stereo at 48 kHz/128 kb/s. Fixture
generation uses the existing host FFmpeg, not an app dependency.

```bash
bash tools/m15b31b_decoder.sh stage-paths
bash tools/m15b31b_decoder.sh instrument paths ExportPathsDeviceTest
bash tools/m15b31b_decoder.sh death
```

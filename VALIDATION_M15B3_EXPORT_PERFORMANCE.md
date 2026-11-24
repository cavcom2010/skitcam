# M15B.3.1 — Export performance and reliability

Branch: `feature/m15b3-field-validation`. Starting point: `94e9c48796767b9ead4a2c7b62c1a8e313157ca9` (M15B.3). Benchmarks used disposable M15B.3 source copies and a generated fixture only. No production recording, production app data or original media was opened or changed. No merge or M15C work.

**Result:** the measured 140 s export was dominated by required work. Skipping an unnecessary eligibility scan saved about 3 s on realistic jobs, but did not materially reduce the wait. More importantly, **5 of 12 realistic long export attempts failed intermittently in the phone's vendor H.264 decoder during verification**. Every failure stopped before Gallery publication and retained the originals. The same class of failure occurred on the untouched build. It remains an open reliability risk.

## Baseline

The M15B.3 retry rendered 152.052 s of 1080p portrait H.264/AAC: 140.187 s from service entry to camera ready.

| Stage | Seconds |
|---|---:|
| Media3 transform | 74.335 |
| Candidate verification | 60.486 |
| ↳ video decode (included) | 37.359 |
| ↳ audio decode (included) | 22.688 |
| ↳ candidate SHA-256 (included) | 0.256 |
| Eligibility sample scan | 2.410 |
| Source checkpoint hash recheck | 0.477 |
| Gallery copy, sync and readback | 1.293 |
| Publication → camera ready | 0.820 |

Rendering and verification were 96% of the wait. Gallery work was not a bottleneck.

## Export path selection

The M15B.3 job recorded:

```
selectedPath: RENDER
pathReason: Saved trim requires the established renderer
```

Two takes had saved trims. `ExportPathPolicy` rejects saved trims before examining codec data, so the scan could not have selected passthrough.

Replaying the same staged source bytes on the untouched build without trims selected `VIDEO_PASSTHROUGH_AUDIO_RENDER`:

```
Compatible whole AVC clips; preserve video and render/correct audio
```

The trimmed control on the same build selected `RENDER`. Reordering changed only the kept sequence; discarding did not force a render. The single-clip case was passthrough with audio render, not `DIRECT_COPY`: the field AAC track averages about 256 kb/s, while direct copy requires the stricter 128 kb/s contract, and changing the exported audio rate would be an undisclosed quality change. No codec-configuration or timestamp-ordering mismatch was observed among the staged sources.

Therefore the measured slowness was **mainly a policy consequence of trimming**, not an accidental codec rejection. Long untrimmed exports still take about 127 s because audio rendering and full verification, not video encoding, dominate.

## Confirmed profile

| Work item | Evidence | Assessment |
|---|---|---|
| Video encode for trimmed jobs | transform 74–85 s | Required by the established renderer |
| Audio render, including passthrough jobs | transform 56–65 s with video passthrough | Largest remaining fast-path cost |
| Full video decode verification | 32–38 s | Required integrity check |
| Full audio decode verification | 20–23 s | Required integrity check; runs after video |
| Source checkpoint hashing | 0.5–0.6 s | Required; differs from the initial snapshot |
| Candidate hash + Gallery copy + readback | 1.5–2.5 s + hash | Required; protects different copies |
| Eligibility sample scan | 0.23 s single; 0.7–0.8 s three clips; 2.9–4.2 s long/realistic | Avoidable only when the path is already fixed to render |
| Metadata re-inspection | 4 `inspect()` calls, about 0.25 s total on a long job | Real but below measurement noise |
| Decoder waits | roughly 3–4 s input and 3–4 s output per track on a 152 s file | Work is genuinely in codec scheduling, not one obvious redundant loop |

The verifier's video and audio passes protect different streams and are not duplicate decoding. Its private hash and the Gallery readback hash protect different bytes. No evidence supports removing them.

## Implemented change

`ExportPathPolicy.renderWithoutProbe()` returns the established render decision for a legacy profile or any saved trim. `ExportProcessor` now calls it before creating `ExportInputProbe`, recording the decision under `pathSelection` and only running `eligibilityProbe` when the decision is still open. Safety is unchanged:

- every candidate still passes source-path checks, the frozen source hash snapshot and the pre-processing recheck;
- the renderer still validates tracks, geometry and the immutable profile;
- every output still undergoes full video and audio decode, duration/order checks, the existing 100 ms A/V bounds and SHA-256 verification;
- cancellation, retry, exactly-once MediaStore publication and recovery transitions are unchanged.

Additional instrumentation separates `pathSelection`, `verifyGeometry`, `verifyInspectBefore`, `verifyInspectAfter`, decoder setup, decoder wait/read totals and checkpoint work. These are passive timings.

## Benchmark results

Fresh debug IDs on the Moto G54 5G, Android 15/API 35, build `6766b4-a2b693`. Thermal status was `NONE` before and after every successful run. Each job used new session and export IDs over byte-identical staged sources. Sources were re-hashed after each successful run; publication produced one owned, non-pending row whose bytes matched `verifiedSha`.

| Case | Baseline path and time | Candidate path and time |
|---|---|---|
| Single untrimmed | passthrough + audio, 11.09 s | passthrough + audio, 11.01 s |
| Three untrimmed | passthrough + audio, 33.46 s | passthrough + audio, 32.65 s |
| Reordered | passthrough + audio, 32.25 s | passthrough + audio, 34.63 s |
| Discarded | passthrough + audio, 32.77 s | passthrough + audio, 32.66 s |
| Trimmed, 3 clips | render, 35.33 / 33.41 / 33.34 s (median 33.41) | render, 36.22 / 33.26 / 32.95 s (median 33.26) |
| Long generated, 180 s | passthrough + audio, 112.83 s | passthrough + audio, 113.87 s |
| Realistic, untrimmed | passthrough + audio, 127.21 / 134.42 s | passthrough + audio, 123.56 / 127.34 s |
| Realistic, original trims | render, 148.06 s (one success) | render, 135.59 / 144.08 s (median 139.84) |

The only deterministic saving is the removed scan:

| Case | Baseline `eligibilityProbe` | Candidate `pathSelection` |
|---|---:|---:|
| Three trimmed clips | 0.74–0.79 s | 0 s |
| Realistic trimmed | 3.17 s | 0 s |
| Long generated | 4.20 s | 4.39 s (no trim; scan still required) |
| Realistic untrimmed | 2.92 / 3.19 s | 2.92 / 3.05 s (no trim; scan still required) |

On the trimmed realistic job the fixed ~3.2 s saving is about 2% of the wait. Run-to-run transform variance is larger: candidate successes ranged from 135.59 to 144.08 s, and untrimmed cases varied by 7 s. **This change is correct cleanup, not a solution to the friction.** Short and long case differences otherwise sit inside variance.

## Intermittent export failure (release risk)

Five realistic long jobs failed before publication. Two further candidate runs were invalidated by a test-order mistake (a cancellation test running in the same instrumentation method selection) and are excluded:

| Build | Case | Outcome |
|---|---|---|
| Untouched baseline | realistic, trimmed | Media3 decoder exception, `c2.mtk.avc.decoder`, during transform |
| Candidate | realistic, untrimmed | passthrough attempt failed in verification, fallback render also failed |
| Candidate | realistic, trimmed | failed during verification |
| Candidate | realistic, trimmed | failed during verification |
| Candidate | realistic, trimmed | failed during verification |

The captured stack on the candidate:

```
E MediaCodec: Codec reported err 0x80000000, actionCode 0, while in state 6
D MediaCodec: [...] setState: 0
E MediaCodec: Invalid to call at Released state; only valid in executing state
    at android.media.MediaCodec.native_dequeueOutputBuffer
    at com.calvinmazhindu.skitcam.export.ExportMediaKt.verify(ExportMedia.kt:157)
```

The platform decoder reported an undefined vendor error and released itself mid-decode; the next verifier dequeue threw. This is the same family of vendor-decoder instability carried forward from M15B.2, now observed in the **export verifier**, not Play Skit or Review. It is intermittent, not tied to the path-selection change: the untouched build failed too.

Safety behaviour was correct:

- the job ended `Failed`, never `Completed`;
- `publicationStarted` was false and the scoped MediaStore query found no row;
- sources remained unchanged;
- the next retry created a new job attempt through the existing flow.

One failed candidate (the trimmed realistic job) was retained. A host copy of that 152.052 s, 167,500,857-byte MP4 fully decoded with FFmpeg (`-xerror`, passthrough timestamps); packet PTS and DTS were strictly increasing on both tracks (4,549 video and 7,129 audio packets). This supports a transient device-decoder failure rather than a corrupt output, but one file cannot prove that for every failure.

No retry of verification or codec fallback was added. A bounded re-verification of a completed, unverified candidate during **Retry** is the most promising follow-up: it would avoid re-rendering and would still require the complete decode and hash before publication. It needs its own deterministic failure injection and equivalence tests before implementation. Running video and audio verification concurrently could save up to ~20 s, but would increase decoder contention precisely where the vendor decoder already fails, so it is not proposed without evidence.

**This reliability finding blocks a performance sign-off**: an export that has to be retried at random is worse friction than a predictable render.

## Integrity and regression checks

- Candidate fixture cancellation → retry passed on the final test APK: same job, zero rows after cancellation, exactly one owned row after retry, unchanged source digest.
- Candidate journal and file-store recovery suite: 12 device tests passed.
- Every successful benchmark asserted one non-pending owned row, published bytes matching `verifiedSha`, and unchanged copies of all staged sources.
- Existing JVM policy tests cover direct copy, passthrough, trim, legacy, mismatched AVC configuration, rotation and geometry, B-frames/origins, HDR/encryption, unknown audio and duration changes. Two new assertions cover the probe-free decision.
- Battery harness: the debug-only pinned reading is accepted only for `com.calvinmazhindu.skitcam.m15b1m15b31battery` on the main thread. A live 71% charging update can no longer replace a pinned 5%, clearing restores the live value, and the camera-backed test passes: Resume disabled, runtime refuses, healthy value appended exactly one distinct clip, originals unchanged.
- Microphone regression passed on a fresh isolated build. The opt-in rotation probe reported the same `wm`, `user_rotation` and `accelerometer_rotation` values before launch, after pause, recreation, export and activity close. The earlier M15B.3 `lock 1` → `lock 0` result did not reproduce; no evidence of an app rotation write was found.
- Local gates: 296 JVM tests (0 failures/errors/skips), Android lint (0 errors, 46 warnings), both debug APK builds, six host tests including the sync-marker tool tests.

## Artifacts

| Validation ID | App APK SHA-256 | Test APK SHA-256 |
|---|---|---|
| `.m15b31base.m12bvalidation` | `709a290fb38d7fce36cd533831f2af16dc8b50d0816474af25e631df6d91498c` | `bb558b3907a5f8fb4184efe41fae229b1f37f4e3218913752019ce5a19ec59b3` |
| `.m15b31candidate.m12bvalidation` | `2624c1da0eff44f69b271d31e59a1064976ea95acd04017d771bf16408c5940d` | `8eb37040fb33e2868fe0acd1faae10f91cd1d60b245cc1442c0bac25bae3a5f5` |
| `.m15b1m15b31battery` | `223e05f3d4bb73bbc26688671f0ac0d27d3db65d1722de2bdcd9164d354fa6f4` | `7854bdf8a82e3c7d02214006ade92d400e3f6b3efe13048bc49f1e8833e18842` |
| `.m15b3audioprobe` | `dad19fe2525b7d5bea6b2f8e33dd358bb7c131732b67c0ff025a0ce8b64d1bd5` | `b1c83bdbb268b32d2e2c38f059773a3436beb72d6d9e4cfcb14cc0f31bae3412` |

## Reproduce

Build the baseline from `94e9c48` plus the benchmark harness (no candidate app edits) and the candidate from the delivered commit:

```sh
./gradlew --console=plain -PvalidationSuffix=.m15b31base.m12bvalidation \
  :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew --console=plain -PvalidationSuffix=.m15b31candidate.m12bvalidation \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Copy each pair to `.validation/m15b31/{base,candidate}/` as `app.apk` and `test.apk`. Stage the eleven M15B.3 portrait sources and the generated `segment_012.mp4` as described by `tools/m15b31_export_bench.sh`; then:

```sh
SERIAL=your_validation_device bash tools/m15b31_export_bench.sh base \
  real:1 realwhole:1 single:1 multi:1 order:1 discard:1 trim:1 long:1 trim:2 trim:3
SERIAL=your_validation_device bash tools/m15b31_export_bench.sh candidate \
  real:1 realwhole:1 single:1 multi:1 order:1 discard:1 trim:1 long:1 trim:2 trim:3
```

The driver selects `FieldExportBenchmarkDeviceTest#exportFixture`, uses a fresh case/run pair, refuses an existing result file, checks staged SHA-256 values, and pulls only the named local result. Result files remain ignored evidence.

## Remaining limitations and readiness

- One Moto G54; no second chipset. Decoder failure frequency may differ elsewhere.
- The 180 s long control is generated, low-entropy media, not a camera long take.
- Benchmarks measure request → service idle; the historical camera-return reference is not a fresh Finish-to-camera comparison.
- Audio transmux was not attempted. It is the largest remaining fast-path cost but must preserve per-item AAC priming and the 100 ms A/V bounds.
- Concurrent or lighter verification was not attempted; it needs corrupt/truncated/wrong-order equivalence and must not raise vendor-decoder pressure.
- The intermittent decoder failure is unresolved. A bounded re-verification of a completed candidate on Retry is the next evidence-led option.
- Landscape field recording, real long takes, external microphones, physical sync markers, second-player acceptance and cross-device testing remain outstanding.
- The M15B.2 Play Skit decoder/release risk remains open.

**Recommendation:** do not treat M15B.3.1 as an export-performance fix. Keep the safe path-selection cleanup, but address the intermittent vendor-decoder failure before promising export reliability or starting M15C.

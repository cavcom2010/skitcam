# M15B.3.1A — Export verification recovery

Branch: `feature/m15b3-field-validation`. Starting point: `effdae4` (M15B.3.1). All device work used isolated debug packages on the Moto G54 5G, Android 15/API 35, build `6766b4-a2b693`. Production app data and the M15B.3 field recordings were not opened or changed. Nothing was merged and M15C was not started.

**Result:** verification failures no longer discard a completed export. A finished, unverified candidate is now recorded durably and can be re-verified once on an explicit Retry with fresh decoder resources, without rendering again. Full decoding, timestamp/order/duration checking, source hashing, the 100 ms A/V bounds and publication readback are unchanged. Deterministic fault injection passes every case. The underlying vendor decoder fault is **not fixed**; it also still interrupts rendering, where a Retry must render again.

On twelve natural long exports, ten completed first time. One verification failure took the reuse path without rendering, but the platform decoder failed the second verification too; one render failure could not be recovered by its retry. Neither failure published anything, and every original stayed intact.

## Why this changed

The previous retry path only reused an output whose bytes matched `verifiedSha`. A candidate that had been rendered but failed verification had no durable identity, so Retry deleted it and started the full 74–85 s render again. The measured M15B.3.1 failure was exactly this case.

A second problem made a fast-path failure worse: any exception during production or verification could trigger the immediate render fallback. A transient decoder fault therefore doubled the work on the same decoder instead of preserving the finished bytes for a later re-check.

## Confirmed findings at `effdae4`

| Question | Finding |
|---|---|
| Did a verification failure trigger unnecessary re-rendering? | Yes. `ExportProcessor` caught production and verification failures together and could start an immediate render fallback. On an explicit Retry the service always deleted a non-verified `output.mp4` and processed again. |
| Did completed but unverified candidates survive? | The file usually survived on disk, but the journal did not bind those bytes to the job, specification and source hashes, so Retry could not safely use it. |
| Were decoder resources always released? | Each track created a fresh extractor and decoder. However `codec.release()` and `extractor.release()` shared one `finally` block; a release exception could skip extractor release and replace the original failure. |
| Did the journal distinguish rendering completion from verification completion? | No. It recorded `state=Verifying` and later `verifiedSha`; there was no durable record of "these exact completed bytes belong to this job". |
| Could earlier decoder failures affect later attempts? | A previous decode failure did not leave a codec object that was reused; every attempt built fresh resources. Vendor-service state outside the app is not observable and cannot be reset by the app except by process restart. |

## Failure classification

Two typed failures separate platform decoder trouble from media that contradicts the export contract:

- `ExportDecoderException` — raised only from `MediaCodec` calls (`configure`, `start`, queue/dequeue, format, `releaseOutputBuffer`, `release`). Carries the platform error code and diagnostic text when available.
- `ExportIntegrityException` — our own checks: missing/unreadable candidate, wrong codec or audio format, no decoded samples, non-monotonic decoded timestamps, duration mismatch, or audio/video drift over 100 ms.

Everything else remains `other`. Only `decoder` and an interrupted attempt (no class recorded) are eligible for candidate re-verification. `integrity` and `other` retire the candidate through the existing rebuild path. A generic codec exception is never treated as proof that the media is valid, and a decoder exception is never treated as proof that the media is invalid.

The automatic render fallback now applies only to integrity failures on a fast path. A decoder failure, I/O failure, cancellation or low space fails safely and leaves the choice to the user. A render-path integrity failure still fails, as before.

## Candidate identity and bounded reuse

After production returns, before any decoder opens, the service fsyncs the candidate and journals:

```
candidate.attempt      fresh UUID for these bytes
candidate.bytes        length
candidate.sha256       SHA-256 of the finished file
candidate.specSha256   session, orientation, profile, ordered clip ids/paths, durations and trims
candidate.sourcesSha256 ordered frozen source digests
candidate.completedAt
```

The checkpoint is separate from `verifiedSha`. It proves identity, not safety.

On an explicit Retry, re-verification proceeds only when all of these hold:

1. the retry intent was carried into the service (automatic completion never reuses);
2. a structurally valid checkpoint exists;
3. this candidate has not already been re-checked once;
4. no verified digest, publication marker, pending URI or `publicationStarted` exists;
5. the previous failure is a decoder failure or an interrupted attempt — not integrity/other;
6. the candidate is a regular non-symlink file directly inside this job's export directory, with unchanged length;
7. the specification fingerprint and frozen source list still match;
8. every source still matches its frozen SHA-256 and free space still allows verification and the Gallery copy;
9. the candidate's full SHA-256 still matches the checkpoint.

The one-time allowance is written durably **before** the decoder opens, so a crash or second failure cannot reset it. Re-verification runs the same geometry check, both full decodes and the final hash, and additionally requires the returned digest to equal the checkpoint. Only then is `verifiedSha` committed and the checkpoint removed. Otherwise the existing path rebuilds a new candidate. There is no loop: one reuse per finished candidate, then a re-render is required.

A process death after rendering but before verification leaves the checkpoint with no consumed allowance, so Retry reuses it. A process death during re-verification consumes the allowance; the next Retry rebuilds, which is bounded and safe.

## What did not change

- Full video decode, full audio decode, decoded-order checks, container duration, first/last A/V bounds, geometry, profile and source hashes.
- Candidate SHA-256 and Gallery copy/readback SHA-256.
- Exactly-once publication via the owned pending row and persisted URI.
- Audio/video verification remains sequential; no parallel decoding was added.
- No encoder, bitrate, resolution, audio or trimming change.
- Original recordings are only read.

## Deterministic fault injection

`ExportFaults` is internal, debug-only and armed solely by the isolated package `com.calvinmazhindu.skitcam.m15b31a.m12bvalidation`. Every hook is a no-op otherwise. Each case ran in its own process against a 6 s 1080p H.264/AAC fixture with a saved trim, so the renderer path is deterministic.

| Fault | Injection | Expected | Result |
|---|---|---|---|
| Video init failure | before decoder creation | Failed, class decoder, candidate retained; Retry verifies | Pass |
| Video mid-decode | after 10 decoded samples | same | Pass |
| Vendor self-release | real `codec.release()` mid-decode | real `IllegalStateException`/released-state error becomes decoder class; Retry verifies | Pass |
| Audio mid-decode | after 10 audio samples | same | Pass |
| Persistent video failure | every verification attempt | one reuse, then rebuild; no publication; disarm → reuse of rebuilt candidate | Pass |
| Corrupt candidate | mutate a byte after checkpoint | damaged bytes never reused/published; rebuild completes | Pass |
| Truncated candidate | shorten after checkpoint | rebuild completes; truncated bytes never published | Pass |
| Missing candidate | delete after checkpoint | rebuild completes | Pass |
| Duration mismatch | verify with a wrong expected duration | `ExportIntegrityException`, never decoder class | Pass |
| Cancellation | cancel before production | zero rows, originals unchanged, Retry publishes exactly one | Pass |
| No retry intent | launch a completed job again | no-op, one row remains | Pass |

Each recovery case asserted one production attempt, two verification attempts, one owned non-pending Gallery row, published bytes matching `verifiedSha`, unchanged source hashes, and an unrelated file in the export directory left untouched.

One run of the truncation case needed one further Retry after the rebuild: deliberately decoding a truncated file left the vendor decoder service unstable for the next attempt. The test now records that explicitly rather than assuming a single attempt always succeeds. It remains a real residual risk.

## Process death

| Phase | Observation |
|---|---|
| Killed after checkpoint, before verification | Instrumentation reported `Process crashed`; phase two restored the journal to Failed. Retry completed with `productionAttempts=1`, `verificationAttempts=1`, one row. The candidate was reused. |
| Killed before checkpoint | Phase two found no candidate identity, rebuilt (`productionAttempts=2`), and published exactly one row. |

Both phase-two runs passed with the source hash and the unrelated decoy file unchanged.

## Natural device runs

`RecoveryBenchmarkDeviceTest` replayed the realistic eleven-source job (kept order `3,1,2,4,5,6,7,8,10,11`, trims on 2 and 3, RENDER path) once per fresh session and, if the first attempt failed, performed one explicit Retry. Injected faults were not armed. Sources are the retained M15B.3 copies; each run's disposable copy is removed after its report is written.

| Run | First attempt | First duration | Stage / class | Retry | Retry duration | Final | Renders | Verifications | Reused |
|---:|---|---:|---|---|---:|---|---|---:|---:|---|
| 1 | Completed | 140.51 s | — | — | — | Completed | 1 | 1 | no |
| 2 | Completed | 142.21 s | — | — | — | Completed | 1 | 1 | no |
| 3 | Failed | 37.10 s | render | yes | 40.82 s | Failed | 2 | 0 | no |
| 5 | Completed | 169.60 s | — | — | — | Completed | 1 | 1 | no |
| 6 | Completed | 188.89 s | — | — | — | Completed | 1 | 1 | no |
| 7 | Failed | 347.22 s | decoder | yes | 16.32 s | Failed | 1 | 2 | yes |
| 8 | Completed | 162.02 s | — | — | — | Completed | 1 | 1 | no |
| 9 | Completed | 149.75 s | — | — | — | Completed | 1 | 1 | no |
| 10 | Completed | 143.08 s | — | — | — | Completed | 1 | 1 | no |
| 11 | Completed | 151.18 s | — | — | — | Completed | 1 | 1 | no |
| 12 | Completed | 142.59 s | — | — | — | Completed | 1 | 1 | no |
| 13 | Completed | 169.24 s | — | — | — | Completed | 1 | 1 | no |

Run 4's instrumentation process ended without a result and produced no report; it is excluded and replaced by run 13. The twelve valid attempts used the same sources, order, trims, profile and RENDER path. Thermal status was `NONE` (0) at start and peak for every run. Successful outputs were 167,499,963–167,524,427 bytes.

- **First-attempt success: 10/12. Final success after at most one Retry: 10/12.** Successful runs took a median 150.46 s (range 140.51–188.89 s), consistent with M15B.3.1's 140–148 s.
- The natural exceptions named `c2.mtk.avc.decoder` and released-state text; Media3 exposed no numeric code in this batch. The earlier M15B.3.1 run reported `0x80000000`.
- **Render failure (run 3).** The first attempt failed after 37.10 s in Media3's video decoder (`c2.mtk.avc.decoder`). No candidate existed, so the automatic Retry correctly rendered again (40.82 s) and also failed. Two rows were not created; sources were unchanged. Candidate recovery cannot help a production failure.
- **Verification failure with reuse (run 7).** The first attempt took 347.22 s, including a 298.5 s transform under post-boot load and 34.1 s of video verification before the decoder released itself. The Retry took 16.32 s, **reused the checkpoint without rendering** (`productionAttempts` stayed 1, `verificationAttempts` became 2), and the second verification failed with the same released-state error. No row was published. The one-recheck bound then requires a rebuild on any further Retry.
- **Recovery rate.** One natural verification failure was eligible and was re-checked exactly as designed, but did not recover within that single attempt (`0/1`). Deterministic tests show that a fresh verifier succeeds when the platform decoder call completes: 10/10 single-fault recoveries, plus the persistent-failure case recovering after the bounded rebuild. The natural sample is too small to estimate a recovery rate, and the platform fault is not eliminated.
- **Media preservation.** Every valid run asserted that all eleven source hashes were unchanged. Successful runs asserted exactly one owned non-pending row and a published SHA-256 readback equal to `verifiedSha`; failed runs asserted zero rows.

An earlier exploratory batch was run immediately after the fault suite, which deliberately releases codecs mid-stream. Three of its six attempts succeeded first time; two failed both initial and retry in the renderer, suggesting degraded vendor codec-service state. The device was rebooted and that batch is retained under ignored local evidence but excluded from the table above. It is a reminder that process restart, not a new `MediaCodec` object, is the only reliable platform reset the app can trigger indirectly by the user.

## Artifacts

| Validation package | App APK SHA-256 | Test APK SHA-256 |
|---|---|---|
| `.m15b31a.m12bvalidation` (delivered fault/return build) | `313e2b2ce723b0011c42ae4ffa093ad200887693c2269b2f429efc281ae5267d` | `87fe8ade8e5c76cfbc5193394a7c299b0ae92785659e5ea80f68dc7e6052624f` |
| `.m15b31candidate.m12bvalidation` (delivered benchmark build) | `970dfb9b2f13fc7f3de0639ae70dc98a99dc5676c396cc808ebdf29962c22504` | `cbb0840f44003b453afb8663f54fcc519015491258d2e5b1997e979fb7a58167` |
| `.m15b31candidate.m12bvalidation` (twelve-attempt table) | `744bb55225fc356f1bc2542035bc4e2dfef26249e568d432d6431272ecc7b35d` | `2fdba26777caf77cfbf80c23b7aaadb307b4f0432eef482910928840ffbd6004` |

The delivered app extracts the camera-release wait so the candidate-reuse path also waits before opening decoders. The benchmark package has no recording runtime, so that wait returns immediately. One confirmation run (14) on the delivered benchmark APK completed in 176.34 s with one render, one verification and one published row.

## Regressions

- JVM: 299 tests, 0 failures/errors/skips.
- Lint: 0 errors, 46 warnings.
- Debug and instrumentation APKs build.
- Deterministic recovery suite: 11/11 methods passed in separate processes, including a repeat on the delivered APKs.
- Process-death phases: 2/2 recovery runs passed.
- Journal/file-store recovery and path/publication regressions: - Journal/file-store recovery, including a persisted publication checkpoint and same-job Retry: 12 device tests passed.
- Focused camera-return check (`ExportReturnDeviceTest`): an automatic export returned to a live streaming camera with the new URI exposed, one owned row, an exact readback and unchanged originals. Passed.
- The heavier `ExportDeviceTest` end-to-end flow reached the live camera and the correct saved URI, but its later gallery-button accessibility wait timed out on this device. That step is unrelated to export recovery and is not counted as a pass. The test's camera-return wait was tightened to require the published URI, which removes an earlier read-before-update race.

The fault suite was repeated on the delivered `.m15b31a` APKs: 11/11 passed. The focused camera-return test passed again on that build. Run 14 confirmed the delivered benchmark APK before delivery.
- `ExportPathsDeviceTest` was not rerun: its M12B golden baseline is not staged in the available validation packages. Exactly-once publication, cancellation, readback and source preservation are covered by the recovery suite and the twelve benchmarks. The low-space branch itself was last exercised in M15B.3.1 and was not changed except that the candidate-reuse path now performs the same space check before verifying..
- Production originals were not modified.

## Remaining risks

- The precise vendor decoder cause is unknown. The same `0x80000000`/released-state family occurs in the renderer and the verifier, so candidate reuse only removes the re-render for verification failures. Render failures still cost a full retry.
- Decoding deliberately damaged media can leave the vendor decoder service unstable; the next fresh codec instance may fail. The app bounds this with one reuse and then a rebuild, but cannot reset a platform service.
- Process death during re-verification consumes the single allowance; the next Retry renders again.
- Verification remains sequential and still costs roughly 50–60 s on the realistic job.
- One device and one codec family. The fault rate elsewhere is unknown.
- The M15B.2 playback decoder/release risk remains open.
- Landscape, real long field takes, external microphones, physical sync markers, second-player acceptance and cross-device testing remain outstanding.

## Release recommendation

Keep candidate recovery: it preserves a completed render, avoids a known unnecessary render, strictly adds checks around reuse, and never publishes without the full verifier. Do **not** claim the decoder instability is eliminated. The intermittent platform failure should remain a release-blocking reliability item for "finishing always succeeds unattended", even though media and originals are preserved and explicit Retry recovers.

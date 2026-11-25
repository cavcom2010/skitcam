# M15B.3.3 — Field acceptance

**Field acceptance remains open.** Production baseline: `209232b7ce892b176267ba235824ecea47db746b` on `feature/m15b3-field-validation`. This pass adds test-only instrumentation and an evidence tool; it does not change the recording, decoder, verification, Gallery, or retention policies. Nothing has been merged.

## Independent audit of M15B.3.2

No new media-safety blocker was found for isolated Moto testing. This does **not** clear known reliability risks or constitute release sign-off. The audit traced the committed paths and independently recounted the [delivery evidence](VALIDATION_M15B3_2_EXPORT_HARDENING.md): 64 passing device tests, 15 deliberate process deaths, no failing delivery verdicts. These runs were reused, not repeated. The publication findings below concern the modern MediaStore path; the separate legacy scanner was tested on API 28. API 29's special pending-query branch remains **NOT RUN** in this pass.

| Prior concern | Audit result |
|---|---|
| Destructive recovery of a visible video | A proven published row is read and hashed without opening it for write. A matching row can complete without the private candidate; a mismatching row is neither repaired nor replaced. |
| Unsafe pending-row copy | A complete pending copy is checked read-only. For an incomplete copy, compare the exact existing prefix, recheck pending ownership/identity, append without truncation, and sync and hash before visibility. A missing backing file on a confirmed pending row is not permission to insert a new row. |
| Ownership and duplicates | One serialized publisher uses volume, deterministic name, path, package ownership, row identity and visibility; discovery without a saved URI also requires the job marker. Ambiguous results stop. An exact published digest cannot be substituted by rebuilding after publication starts. |
| Repeated incompatible passthrough | A documented runtime incompatibility or fast-path integrity failure can persist `RENDER` before the single fallback. Decoder, unrelated I/O and cancellation are not fallback signals. |
| Verification failure classification | Codec-creation `IOException` is classified only at the codec factory. Extractor/file I/O remains `other`; explicit contract violations such as wrong duration are `integrity`. The same failure-recording path covers initial, fallback and Retry verification. |
| Candidate re-check and storage | The allowance is consumed durably before opening decoders. Reuse checks frozen source hashes and candidate identity, then performs the entire verifier before publication. It reserves candidate length plus 100 MiB for one Gallery copy; rebuilding still requires the full estimate. |
| Ambiguous insert intent | Empty lookup after durable intent does not prove the provider never inserted. Retry may remain blocked with the verified output and originals preserved. MediaStore and the private journal are not transactional; this is a known fail-closed recovery limit. |

The intermittent MediaTek AVC failure remains **unresolved**. Earlier natural long exports completed first time in 10/12 valid runs; the other two still failed after one Retry (one in rendering, one in verification). [Targeted hardware/software comparisons](VALIDATION_M15B3_DECODER_COMPATIBILITY.md) did not reproduce the vendor fault and did not demonstrate a reliability gain. Production decoder selection remains unchanged. App-process death injection is not vendor-fault or power-loss reproduction.

## Devices and 4K capability

- **Moto G54 5G:** Android 15 / API 35, build `V1TDS35H.83-20-5-8-9`. Verified fresh install of `com.calvinmazhindu.skitcam.m15b33field` and its instrumentation APK; a different fresh, isolated package, `com.calvinmazhindu.skitcam.m15b33bench.m12bvalidation`, is used for staged benchmarks. Both pairs’ APK manifest identities and installed hashes were checked. The production app and previous validation installations were not updated, cleared, or inspected for media.
- **Comparison handset:** **BLOCKED** — no Android 14–16 phone with a different chipset and a confirmed 4K profile was available. An emulator cannot stand in for this device.
- **Emulators:** disposable AOSP x86_64 API 35 (`AE3A.240806.019`, build `12368160`) and API 28 (`PSR1.180720.012`, build `4923214`), using the isolated `com.calvinmazhindu.skitcam.m15b1m15b33emu` ID. The API 28 result is an emulator check of the legacy branch, not evidence about another physical handset.

The Moto capability probe passed using the same CameraX selectors and SDR `Recorder` profile list that SkitCam uses. Camera2 was checked separately to distinguish a raw stream-size advertisement from an app recording profile.

| Moto lens | CameraX SDR profiles | Camera2 `MediaRecorder` inventory | 3840×2160 in SkitCam |
|---|---|---|---|
| Rear, ID 0 | 1920×1080, 1280×720, 640×480 | Reports sizes including 4096×3072 and 4096×2304, but not 3840×2160 | **NOT APPLICABLE** |
| Front, ID 1 | 1920×1080, 1280×720, 640×480 | Largest reported width 2320; no 3840×2160 | **NOT APPLICABLE** |

No selectable lens advertised UHD. That makes UHD unsupported in the current SDR app path in either orientation, before choosing FPS/stabilization; it does not imply the rear sensor cannot output larger buffers, or say what another app could record. No 4K recording was attempted and none is counted as a pass. The prior rear-camera portrait recordings were actual AVC 1920×1080 with portrait display rotation and 48 kHz stereo AAC. The new machine probe does not count as a new physical capture.

| APK pair | App SHA-256 | Instrumentation SHA-256 |
|---|---|---|
| Moto field | `effe654bb53edff6906a99e837fc89ac80f92d2db566b24042804a6252c4db69` | `701747b19e2bdbb2868cf74a6ef8aa3a87f471aa58e0e435f1084aaa647ec426` |
| Moto benchmark | `b3672ef2f2f7b50333c72d575ab76bbc955581feda6433c9d34145314d3cfc84` | `bf1db45d04ec092def203a8decf6fcbf20327e94618e209c7dc91a55c7cec0f8` |
| AOSP API 35 | `c998e2ac08e60088c241143327026cc90c50354d15ac80a7ca33d9875a6def06` | `b49c8477396de38ce6be07d7265186392e8c7728ac63672a409d815e488a5cd5` |
| AOSP API 28 legacy retest | `c998e2ac08e60088c241143327026cc90c50354d15ac80a7ca33d9875a6def06` | `b72f6a4723a9d4e9fe7a5267277d4214679c147f678ad5d9afccf1cc6cf5c6d6` |

## A1–A7: machine-checked evidence

Statuses refer to the stated case and device. `NOT RUN` means the exact case has not been executed; `BLOCKED` identifies a missing prerequisite. Earlier milestone evidence is labelled separately and not credited as a new run.

New host gate: **307 JVM tests** with no failures, errors or skips; **0 lint errors, 46 warnings**; both APKs build; six Python host tests and `git diff --check` passed. This reran the host build gate for the new test sources, not the prior physical regression suites.

| ID | Status | Reused evidence and remaining work |
|---|---|---|
| A1 | **PASS** (Moto, carried forward) | [M15A](VALIDATION_M15A.md) verified Review frames, rail centring/manual swipe, summary states, corrupt-source negative controls and AtomicFile recovery. [M15B.3.2](VALIDATION_M15B3_2_EXPORT_HARDENING.md) passed 307 JVM tests, lint with zero errors, and journal/store regressions at this production baseline. No production change warrants repeating those device suites. |
| A2 | **NOT RUN** (exact Moto batches) | Prior portrait machine evidence: ten kept clips from eleven originals, two trims, saved order, append, one exact Gallery readback and all eleven hashes unchanged. The first ten takes were 11.7–25.2 s, not 2–5 s. The new operator batch below also exceeds that duration. Three qualifying short batches per orientation remain, plus one ordinary unedited field export (use a long-take session rather than making an extra Gallery item). |
| A3 | **NOT RUN** (Moto 1080p); **NOT APPLICABLE** (Moto 4K) | No physical 3–5 minute takes with reliable cues, thermal observations and cadence. Capture two takes per orientation, with early/late markers, and measure the sources and named Gallery readbacks. Synthetic long renders do not cover this requirement. |
| A4 | **NOT RUN** (full Moto sequence) | Prior stationary portrait background/reacquire and explicit append passed; Paused/Review foreground cycles are documented in [M15B.1](VALIDATION_M15B1.md). Starting, moved return, physical landscape, lock/unlock and the complete ten playback → close → recording-resume cycles with ownership observations are not collectively established on Moto. Emulator results are separate below. |
| A5 | **PASS** (Moto, carried forward) | [M14 exact interrupted recording](VALIDATION_M14.md#automated-verification) covered two confirmed and a third pending, Later, continuing to segment 4, and exporting only confirmed media. Both committed hashes and the unfinished file were preserved. M15B.3.2 reran relevant store/journal and export process-death regressions. No need to kill another recording just to restate A5. |
| A6 | **PASS** (simulated cases, carried forward) | Recording reserve/write and battery threshold/refusal/finalization coverage is in JVM policy tests; [pinned battery on Moto](VALIDATION_M15B3_EXPORT_PERFORMANCE.md#integrity-and-regression-checks) fixed the earlier unstable test injection. M15B.3.2 checked cancellation and same-job retry, storage before copy and one verified publication, with source hashes preserved. Physical shared-volume fill is excluded; real emulator `ENOSPC` was optional and **NOT RUN**. |
| A7 | **FAIL** (historical Moto immediate-seek case); **BLOCKED** (comparison handset) | [M15B.2](VALIDATION_M15B2.md) passed 299/300 playback attempts, including 30 cold entries, then 60/60 focused follow-ups. The one stall ended with a release timeout; it did not reproduce the pre-M14.2 MediaCodec incident. Current native playbacks and targeted comparison are still outstanding. Keep structured playback diagnostics separate from export MediaTek errors and from intentional corrupt-file controls. |

### Emulator results (machine evidence only)

- **A4 controlled stationary and moved return — PASS (API 35).** Both portrait and landscape stabilized after returning without movement, detected a changed simulated pose on return, and then recovered the original pose. No media was recorded.
- **A4 playback-close and screen-return coverage — PASS (API 35).** The staged draft made **10/10** playback → close → fresh playback cycles; cycles 4 and 8 also slept and woke the screen. The player and camera-ready flag cleared on screen-off; after wake the paused draft and camera readiness returned. All fixture hashes and the draft matched before/after. This checks app lifecycle and software-decoder behavior, not physical camera/mic ownership, secure lock-screen behavior, or starting a new recording in those cycles.
- **Legacy storage permission and Gallery scanner — PASS (API 28 emulator); NOT APPLICABLE on Moto.** First Finish with permissions denied retained a verified candidate and no public file. After granting only the legacy test app’s permissions, the same job retried without new production or verification, scanned exactly one path to one matching URI, and matched the verified digest through both file and URI readback. Original SHA-256 was unchanged. The first test invocation had incorrectly required verified output to be byte-identical to the input: on this emulator the direct candidate failed the 100 ms verification gate and the exporter fell back to `RENDER`. That failure was retained in the logs. The corrected test checks the verified candidate digest and unchanged original separately; the test-only update changed no production code, permission rule or integrity bound.

### New Moto operator batch (in progress)

The operator paused after ten confirmed rear-camera portrait clips at 1920×1080, Auto FPS, stabilization off. All ten original SHA-256 values were recorded before editing. The committed video-track durations were **7.72–14.57 s**, outside A2's 2–5 s window. Continue the export and listening checks on this material rather than discarding it; do not count it as a qualifying short batch.

The operator then reported completing the edit, playback and append steps. The pre-Finish machine checkpoint confirms 11 distinct committed clips, clip 2 discarded, clip 5 kept again, clip 3 moved first with a saved **2.508–7.623 s** trim, and the new clip 11 appended. Kept order is `3, 1, 4, 6, 7, 8, 9, 10, 5, 11`. All ten earlier hashes remained unchanged; all eleven were sealed before Finish.

The operator reported a successful export and that it “took long as Usual.” The job reached `Completed` on its first attempt via `RENDER`; no Retry or fallback. On-device trace measured **97.655 s to publication and 98.153 s to live camera** from confirming Finish. The job included **103.966 s** of source video, **99.390 s** after trim, in ten portrait 1080p clips. The verifier reported **99.423 s** output duration. Production took **51.247 s**, full verification **43.458 s** (24.708 s video and 18.443 s audio decode), and publication **1.114 s**. The reported wait remains a usability concern; no new approved budget.

**Machine readback PASS for this job:** the frozen order and trim matched the pre-Finish checkpoint, all eleven original hashes still matched, and exactly one owned non-pending Gallery URI matched the verified SHA-256 through both the device and host readbacks. The host fully decoded all eleven originals and the actual published copy. The published copy contains **2,974 decoded AVC frames** at 1920×1080 with −90° display rotation (portrait), and **4,661 decoded AAC frames** at 48 kHz stereo. Decoded frame PTS and packet DTS increased strictly on each track. These checks do not establish audible or visible sync.

Decoded content independently corroborated the kept order: brightness-sensitive raw image matching was ambiguous on similar scenes, but normalized edge-feature matching compared against **all eleven** sources and identified all ten expected clips, with alignments within **56 ms** on an 8 fps sampling grid. Decoded audio waveform matching identified nine immediately; a repeated waveform near the start of clip 10 was ambiguous, but three later windows matched clip 10. These are source-identity/order controls, not paired physical marker measurements.

**Operator listening PASS for this VLC playback:** the operator said it was “sounding ok, all sync well” after the exact published file was opened in VLC and they were asked to listen across cuts. No audio issue was reported. They had already found the export in Gallery and reported general playback success; named cue-by-cue Gallery observations were not obtained. This does not sign off every join or quantify physical sync or drift.

**Landscape checkpoint:** the operator paused a confirmed rear-camera landscape setup clip. Its source SHA-256 was recorded. Committed media metadata reports 1920×1080 at rotation 0, 240 AVC frames, an **8.020 s** video track, and 48 kHz stereo AAC. The second clip saved at **4.912 s (147 AVC frames)**, inside the 2–5 s window. Both originals were hashed and clip 1 still matched its initial checkpoint. The app's timer is cumulative for the skit, so use a separate count for later takes. Keep clip 1 as a preserved setup take rather than counting it among the short clips. No landscape item has been published.

At the operator’s request, the physical run stopped here and work moved to an emulator. The named Moto draft and both captures were left in place, paused; no additional phone actions or Gallery publications were performed.

**Short-landscape emulator follow-up — BLOCKED.** All eleven distinct **3.2 s** 1920×1080 AVC/AAC fixtures decoded on the host and had distinct colour and tone fingerprints. An initial staging attempt supplied only one input and was preserved as a setup failure. The complete-fixture preview run timed out waiting for the virtual camera with the staged locked profile. The separate fixture-only run then hung **before the first edit** and was stopped after approximately 30 minutes; the exact emulator draft was still paused with ten kept clips, no saved trim or export directory, and all ten staged source hashes unchanged. No publication was attempted. The cause of the hang was not established and is not evidence of the Moto MediaTek failure. The experimental harness and APKs were retained under Git-ignored `.validation/m15b33/emulator-a2/`, not added to the regression suite. The earlier passing API 35 and API 28 checks above are unaffected.

## H1–H4: human checks

| ID | Status | Established vs. still needed |
|---|---|---|
| H1 | **BLOCKED** | Prior operator reported no issue in a portrait editing/playback pass; complete both directions of scrubbing, loop/replay, Review, drag/Save/Reset, standard and large text, and TalkBack counts and pill state in both orientations. Automated colour/frame and accessibility-tree checks do not establish visual or spoken acceptance. |
| H2 | **BLOCKED** | Prior Moto recordings contained decoded built-in audio. Controlled pickup, quiet/normal/loud speech, disconnect/fallback and reconnect listening are not signed off. External microphones have not been confirmed available; do not call them unsupported. |
| H3 | **PASS** (new portrait export, VLC listening); **BLOCKED** (full 1080p matrix); **NOT APPLICABLE** (Moto 4K) | The operator found the new 1:39 portrait export in Gallery and reported general success. VLC took a long time opening the folder; direct launch of the exact verified URI bypassed it. The operator then confirmed it sounded fine and in sync in VLC. Full Gallery cue coverage, landscape and long-take listening, and reliable paired physical markers are still open. Earlier physical clap bounds straddled ±100 ms and are inconclusive. |
| H4 | **BLOCKED** | The prior operator exercised UI cancellation and Retry. Clarity of the recovery and low-resource messages, and the complete manual walkthrough, remain unconfirmed. |

## Export timing and acceptance thresholds

The [original field retry](VALIDATION_M15B3.md#export-performance-finding) took **139.367 s service start to publication** and **140.187 s to streaming camera** for **152.030 s kept** from ten 1080p clips with two saved trims (152.052 s output). The transform took **74.335 s** and full verification **60.486 s**, including **37.359 s** video decode and **22.688 s** audio decode. Publication took **1.293 s**. Transform plus verification were **96.2%** of the wait. The old 2.410 s eligibility probe was unnecessary with saved trims; the current path skips it. The operator called the wait major friction (**FAIL**); a comparable runtime is not an acceptance target.

### New Moto measurements

Four single-shot jobs ran on the fresh benchmark app. Full source duration below sums included clips before trims; the discarded eleventh file was staged and hashed but excluded from that sum. Resolution is displayed. Total is request-to-service-idle (Retry-to-idle for re-check); this staged benchmark does not measure the physical camera UI handoff. All four runs sampled thermal status `NONE` at start and peak. No natural decoder failure occurred in these four observations; this is not a failure-rate estimate.

| Path / workload | Included source / kept / output (s) | Resolution | Clips | Production (s) | Full verify (s) | MediaStore publication (s) | Total (s) |
|---|---:|---|---:|---:|---:|---:|---:|
| `DIRECT_COPY`, synthetic contract fixture | 6.025 / 6.025 / 6.025 | 1280×720 landscape | 1 | 0.004 | 2.725 | 0.494 | **3.706** |
| `VIDEO_PASSTHROUGH_AUDIO_RENDER`, whole field copies | 159.872 / 159.872 / 159.881 | 1080×1920 portrait | 10 | 69.096 | 90.835 | 2.153 | **166.432** |
| `RENDER`, same field copies with two trims | 159.872 / 152.030 / 152.052 | 1080×1920 portrait | 10 | 88.764 | 79.322 | 1.378 | **171.804** |
| Candidate re-check (`RENDER` bytes, no new production) | 159.872 / 152.030 / 152.052 | 1080×1920 portrait | 10 | — | 77.755 | 1.097 | **80.017** |

- The direct copy is byte-identical to the 48 kHz/128 kb/s AAC fixture. Camera originals in the replayed field set were around 256 kb/s AAC. Passing video through still processes audio; neither changing recording quality nor forcing direct copy is warranted.
- The candidate re-check used an **interrupted-checkpoint fixture**, seeded from the completed render in this same batch and bound to the new job’s identical frozen sources and trim spec. It started without `verifiedSha`, consumed the one durable allowance, ran one full verification, and published with zero production attempts. The measured Retry excludes test seeding/prior attempt time. The re-check’s verifier took **77.755 s**; from re-check start through service idle took **78.940 s**. This is not a measured recovery from a natural MediaTek fault.
- The long job's full verifier cost was **79.3–90.8 s** on the normal attempts. Its sequential tracks cost 48.9 s video / 30.1 s audio for render, and 55.5 s / 34.8 s for passthrough. Compared with the historical trimmed retry, the new render spent about 14.4 s longer transforming and 18.8 s longer verifying. The endpoints differ slightly, so this compares stages, not a controlled estimate of a code regression. Gallery copy, sync, hash and metadata work still took only **0.5–2.2 s** total publication. There is no evidence that dropping verification or changing media quality is justified.

### Proposed thresholds

These are **regression alerts**, not operator-approved wait limits. [Paired historical measurements](VALIDATION_M15B3_EXPORT_PERFORMANCE.md#benchmark-results) and the new samples support:

| Matched G54 workload | Proposed alert | New result |
|---|---:|---|
| Short 720p contract fixture, one direct-copy clip | >5 s request-to-idle (initial single-sample guardrail) | Below; not a general direct-copy budget |
| Untrimmed ten-clip 1080p job, about 160 s kept | >135 s request-to-idle (previous comparable successes 123.56–134.42 s) | **FAIL** (166.432 s) |
| Trimmed ten-clip 1080p job, about 152 s kept | >150 s request-to-idle (paired successes about 135.6–148.1 s; a separate natural batch had median 150.46 s and a long tail) | **FAIL** (171.804 s) |
| Normal publication for these file sizes, about 0.5–402 MB | >3 s for the MediaStore stage (historical copy/readback roughly 1.3–2.5 s on long jobs) | Below in all four |

Do not average failures away. Log path, duration, thermal state, timing endpoints, failure stage and cumulative time through any Retry. For matched 152 s renders and re-checks, full verification at or above ~80 s is a useful diagnostic flag (prior targeted hardware verification 67.73–78.54 s), not permission to bypass checks. Set a shorter usable **Finish-to-streaming-camera** budget with the operator after sampling short, edited and long native jobs. A job like the one already rejected at ~140 s cannot pass usability on timing alone; no numeric user acceptance budget has been signed off.

## Operator handoff

1. Use **SkitCam validation**, not production. In Skit Video select rear **1080p / Auto FPS / stabilization off**. Start with portrait: number ten disposable takes, hold the phone upright, and pause after **2–5 seconds** each. Use a visible hand clap or hard tap with a quiet background; put the contact in frame. Tell the validation host when paused at ten so the originals can be hashed **before** editing.
2. Discard two, restore one, save a trim that keeps an identifiable cue, then move one clip. Play Skit, scrub both ways, close it and append one numbered take. Note the kept order. Pause again for the pre-Finish hash. Then Finish. One short edited session can also cover Review, trim, loop/replay and TalkBack observations. Repeat to three short batches per orientation, varying font scale for the H1 checks.
3. Make two **3–5 minute** takes per orientation with unmistakeable markers near each beginning and end. Use at least one of those sessions for the **ordinary unedited** export; do not make an extra short job just to compare ordinary playback. Note warnings, glitches and thermal behaviour. Use the published 1080p exports to listen at starts, cuts and ends in Google Photos and VLC. Record player names, cues checked and any picture/audio issue; a report of “plays” is not join or sync sign-off.
4. Built-in mic first. If USB or wireless is available, identify the device and produce different known sounds close to it and to the handset. Listen to capture and export. Disconnect during a disposable take, reconnect between takes and explicitly resume. Report actual pickup, silence, fallback, clipping or drift as heard. Avoid private subjects and recordings.
5. Use a separate disposable session for starting/recording/paused/Review/Play Skit interruptions, stationary and gently moved returns and some screen lock/unlocks. Note camera ready and Resume available separately. Cancel before publication only on a planned job, let the host check zero rows, then Retry that job. Do not fill the phone or clear app data. Stop on altered originals, a duplicate or unrecoverable result, a crash loop, or capture with silent/wrong audio; preserve the test evidence.

Use [the physical cue protocol](VALIDATION_M15B3.md#measuring-real-cues) for annotated source and Gallery cues. Accept a cue only when its whole audio-minus-video interval fits within ±100 ms; a boundary-straddling or ambiguous result is inconclusive. Analyse early-to-late change for drift. A/V track lengths and decoder success do not replace either marker evidence or listening.

## Local evidence and Gallery protection

Retain URIs, job/session IDs, original digests, private paths and raw measurements only in the private, Git-ignored `.validation/m15b33/` ledger. For a completed job require one owned non-pending row matching its journal URI and readback SHA-256, not merely that an MP4 exists. For a planned pre-publication cancellation require zero job rows. The field readback pulls only the named URI to validation-private storage and runs a host full two-track decode; it does not create a second public item. No sweeping, deletion, uninstall, source-retention change or processing of genuine Gallery items is authorized.

This pass added **five** Moto Gallery items: four benchmark jobs and one operator job, one verified item per job. The exact benchmark URIs, pending states and readback SHA-256 values are in `.validation/m15b33/bench/*-1.json`; hashes of all underlying staged sources stayed unchanged. The operator job's exact receipt is in `field-jobs/portrait-short-01/completed-receipt.json` under the same evidence root. The API 35 emulator used only private media. The API 28 isolated emulator added one exact scanned item, recorded in `emulator/api28-legacy-receipt.json`. No item was deleted.

To gather machine evidence for an already identified **disposable** field session, use `python3 tools/m15b33_field_evidence.py --serial "$SERIAL" ACTION LABEL SESSION_UUID`, with `before-edits`, `before-finish`, then `completed` (or `cancelled` followed by `completed` for the same job). Use `--copy-sources` with `completed` for cue analysis. Instrumentation should be run only after the session is paused or the export has settled and no later capture is active. The directory is exclusive to the named job; a repeated action refuses to overwrite evidence. Record the operator’s observations separately. The helper was checked against the retained portrait metadata, journal and fixture MP4; no new manual job has exercised it yet.

For reproduction, build the app/test pair with `-PvalidationSuffix=.m15b33field`, `.m15b33bench.m12bvalidation` or `.m15b1m15b33emu` as appropriate. Validate both IDs and target only a verified device serial. `FieldCameraCapabilitiesDeviceTest#inventory` records the app’s lens profiles after the field package has camera permission. `FieldExportBenchmarkDeviceTest#exportFixture` takes `-e case direct|realwhole|real|recheck -e run 1` (one case per invocation). Stage the named, SHA-checked retained portrait clips in `files/m15b31-inputs/` of the benchmark app, plus the compatible short fixture as `segment_013.mp4`. Run `real` before `recheck` with the same run number. Do not repeat the batch merely to populate a ledger: each invocation completes a new public job.

The separate short-landscape emulator attempt did not become a reproducible passing test. Its setup, failed verdicts and experimental harness diff are kept in the local evidence directory; do not use that attempt to close A2.

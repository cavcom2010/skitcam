# Milestone 12B — fast video saving and export optimisation

Validation date: **7 November 2026**
Device: **Moto G54 5G `ZY22KJQLTV`**, Android 15 / API 35, 1080×2400.
Branch: **`feature/m12b-fast-export`**, created from `main` at `d6e2e74`.
Application version unchanged: **0.12.0 / versionCode 16**.

All destructive testing used the isolated package
**`com.calvinmazhindu.skitcam.m12bvalidation`**. The production package was not
reinstalled, cleared or used by the new export tests. No commit or push was made.

## Result

Compatible, untrimmed camera clips no longer re-encode their video. SkitCam now
copies a fully conforming single source when the whole output contract already
matches, or preserves the camera's compressed video packets while re-rendering
only audio through the existing 48 kHz stereo and leading-gap pipeline.

Five untrimmed clips, about 30 seconds total:

| Metric | Baseline renderer | M12B fast path | Change |
|---|---:|---:|---:|
| STOP → usable live camera, median | **33.49 s** | **29.45 s** | **−12.1%** |
| 10 s single clip, median | **12.12 s** | **10.71 s** | **−11.6%** |
| 20 short clips, single run | **36.43 s** | **30.51 s** | **−16.3%** |
| Reordered/discarded 5-clip run | **26.35 s** | **21.52 s** | **−18.3%** |
| Front HD single clip | **11.75 s** | **6.56 s** | **−44.2%** |
| Rear FHD 60-target single clip | **17.92 s** | **12.28 s** | **−31.5%** |
| Landscape rear FHD | **12.52 s** | **7.89 s** | **−37.0%** |
| Landscape front FHD | **12.66 s** | **11.04 s** | **−12.8%** |

These are real Moto measurements, not a universal guarantee. Ambient temperature
and single-run matrix variation are reported below. The fast path was selected
automatically; none of the baseline/optimised comparison runs used a prefilled
verification checkpoint.

## Measured bottleneck before changes

For five clips/31 s source, the baseline renderer spent roughly:

- Media3 transformation: **16.2 s**
- Full video + audio verification: **15.0 s**
- Source inspection/hash and publication: under 1 s combined
- Camera-ready after publication: roughly 0.5–0.7 s

The dominant cost was video re-encoding, with full verification second. The
optimisation therefore removed video re-encoding while preserving the complete
verification pass. Verification is now the largest remaining fixed cost.

## Export-path decision

The immutable export spec and frozen source hashes are still authoritative.

| Input | Path |
|---|---|
| One whole clip, exact profile geometry, SDR AVC, AAC-LC stereo, 48 kHz, 128 kb/s, safe timestamps | **Direct copy** |
| One or more whole clips with compatible AVC/SPS/PPS/colour/rotation, no trim/reorder timing hazards | **Video passthrough + audio render** |
| Trimmed clips | Renderer |
| B-frames, non-keyframe starts, nonzero/reordered video timestamps, differing AVC configuration, HDR, encryption, odd geometry | Renderer |
| Legacy jobs and legacy output policy | Renderer |
| Missing/corrupt sources or invalid spec | Fail; originals retained |

Direct copy is intentionally narrow. The Moto's real CameraX sources use AAC at
roughly **256 kb/s**, while the established output policy requests 128 kb/s.
Therefore real camera recordings normally select **video passthrough + audio
render**, not byte-for-byte whole-file copy. A generated source that already met
the 128 kb/s audio contract exercised the direct-copy path.

Full audio transmux for multi-clip jobs was deliberately not enabled: the M7.1
investigation proved that per-item AAC priming/edit-list behaviour can displace
audio at joins. The existing audio graph, including `LeadingAudioGapFactory`, is
retained.

## Correctness evidence

- **Video payload identity:** independent `ffprobe -show_data_hash sha256`
  comparison showed every compressed video packet was identical to the source
  packets. Single fixture: **317 packets**; five-clip fixture: **931 packets**.
  Both outputs independently decoded with FFmpeg using passthrough timing.
- **Audio markers:** generated flash/chirp fixtures passed.
  - Zero-start: +5 ms and +5 ms at the two cues.
  - Positive audio origin: +5 ms and +15 ms at the two cues.
  - All remain below the existing 100 ms sync gate.
- **Trimmed/reordered clips:** correctly stayed on the renderer; no lossless path
  was claimed for them.
- **VFR source:** selected video passthrough and completed full verification; it
  was not forced onto the constant-30 renderer.
- **B-frame and different SPS/PPS fixtures:** correctly selected the renderer.
- **Audio output:** AAC-LC, 48 kHz stereo, 128 kb/s (fast path re-renders audio).
- **Video output:** H.264 High, source dimensions/rotation and original payload.

### Output-size tradeoff

Preserving the camera's roughly 20 Mb/s video is larger than the prior roughly
8–9 Mb/s re-encode:

| Case | Renderer output | Fast-path output |
|---|---:|---:|
| 10 s single | ~11.8 MB | ~26.9 MB |
| 5 clips, ~30 s | ~34.5 MB | ~79 MB |
| 20 short clips | ~30.7 MB | ~71 MB |

This is the central product tradeoff. The fast path preserves the original camera
video exactly and therefore increases storage/transfer size. No quality downgrade
is introduced, but a future storage policy may need to expose this choice.

## Implementation summary

- `export/ExportPathPolicy.kt` — pure eligibility decisions and reasons.
- `export/ExportInputProbe.kt` — fresh AVC/AAC, timestamp, rotation, colour,
  codec-config, keyframe and duration evidence from the frozen sources.
- `export/ExportProcessor.kt` — direct copy or Media3 passthrough, unchanged
  geometry/verification, one bounded same-job renderer fallback.
- `export/ExportMedia.kt` — optional video transmux path; existing audio graph,
  audio-gap correction and full decode verification retained.
- `export/ExportService.kt` — stage trace, selected-path persistence, safe retry
  and same-job fallback.
- `export/ExportJournal.kt` — path policy/fallback metadata. Existing job version
  and state transitions remain compatible.
- `export/GalleryPublisher.kt` — publication uses the verified output digest and
  an ownership-checked checkpoint URI to prevent duplicate Gallery rows.
- `export/ExportTrace.kt` — monotonic per-job stage/byte measurements.
- `audio/AudioIndicator.kt` — reserved the meter row permanently; this removes
  the header-height movement seen when recording starts.
- `review/PausedEnvironment.kt` — landscape review now places video on the left
  and metadata/actions on the right; Close is pinned at the top of that column.

The production renderer remains the fallback authority. A failed fast candidate
is never published and is preserved as a rejected attempt where possible.

## Device test results

### Export matrix

| Case | Baseline | M12B |
|---|---:|---:|
| Single 10 s, rear FHD | 12.12 s median | 10.71 s median |
| Five untrimmed clips | 33.49 s median | 29.45 s median |
| Twenty short clips | 36.43 s | 30.51 s |
| Front HD | 11.75 s | 6.56 s |
| Rear HD/30 target | 11.82 s | 10.72 s |
| Front FHD/30 target | 12.51 s | 10.18 s |
| Rear FHD/60 target | 17.92 s | 12.28 s |
| Landscape rear FHD | 12.52 s | 7.89 s |
| Landscape front FHD | 12.66 s | 11.04 s |
| Trimmed five clips | 28.09 s | 30.45 s (both renderer; one-run variance) |
| Reordered/discarded five | 26.35 s | 21.52 s |

The final repeated single/five runs used the last build and selected
`VIDEO_PASSTHROUGH_AUDIO_RENDER` automatically. Five runs were at 26–27 °C versus
22–23 °C for the baseline; the measured improvement is therefore conservative.

### Recorded latencies

Final five-clip median:

- STOP → segment finalised: ~0.24 s
- STOP → session committed: ~0.55 s
- transform: ~10.8–11.5 s
- full verification: ~15.0–15.7 s
- publication: ~0.6–0.7 s
- STOP → live camera: **29.45 s** median

Final single-clip median:

- transform: ~2.9–3.9 s
- full verification: ~5.4–5.6 s
- STOP → live camera: **10.71 s** median

Peak sampled PSS was lower on the fast path (roughly 342–399 MB versus 544–617 MB
for the renderer), consistent with avoiding the video encoder/GL pipeline.

### Fast-path safety tests

All 17 export-journal/path tests passed:

- Direct-copy identity and idempotent re-launch.
- Marker/audio-gap and offset-source passthrough.
- Trim, reorder, B-frame, differing AVC configuration and VFR decisions.
- Low-space failure followed by same-job retry.
- Cancellation and partial-candidate retry.
- Rejected fast candidate falling back to the renderer under the same job and
  preserving the rejected file.
- Publication rejects a candidate changed after verification, then reuses exactly
  one pending Gallery row without duplicate publication.

A real force-stop/retry test reached the export-failure/recovery path and completed
the same job on retry. It did **not** prove a kill in the middle of an active
transform; journal recovery for active states remains covered by deterministic
tests, not a physical mid-encode kill.

### Existing regressions

- `ExportDeviceTest#stopAutomaticallyExportsAndPublishesTwoSegments` + `RecordingDeviceTest`: **OK (7 tests)**.
- Core portrait PAUSE/arrangement/trim block: **OK (9 tests)**.
- Core landscape PAUSE/arrangement/trim block: **OK (3 tests)**.
- Gallery DeviceTest: **OK (1 test)**.
- Project suite: main picker case passed; the companion case passed on retry after
  a pre-existing camera-pose setup race. No export failure was observed.
- Final JVM: **235 tests, 0 failures/errors/skips**.
- Final lint: **0 errors, 9 existing warnings** (4 `UsableSpace`, 2 `UseKtx`,
  AGP update, dependency update, Android 16 orientation warning).

### UI follow-up from testing

- The microphone meter used to add/remove a 3 dp row while recording, changing
  header height. The meter row is now permanently reserved. A 20-frame recording
  capture shows stable top controls.
- The first landscape review screenshot showed a collapsed player. The player now
  has an explicit bounded height in portrait and a left video/right metadata
  layout in landscape; Close is pinned in the metadata header. The real landscape
  review test passes and the captured result is
  `.validation/m12b/landscape-review-final.png`.

## Preservation audit

Baseline and closing production SHA-256 manifests were taken under
`.validation/m12b/`.

| Scope | Before | After | Existing changed/missing |
|---|---:|---:|---:|
| Production private session files | 914 | 960 | **0 / 0** |
| Public SkitCam media | 707 | 852 | **0 / 0** |

Forty-six new production private files and 145 new public media files appeared
during the wider validation window. They are additive; every pre-existing hash
was unchanged and no original was missing. The isolated validation app accounts
for many new public exports, but because the production app was also usable during
the run, the report does not claim that every new production-side file came from
the test harness.

Settings were restored to `wm user-rotation=free`, Auto-rotate on, font 1.3,
navigation mode 0.

## Remaining limitations and risks

1. **Larger output files.** The fast path preserves ~20 Mb/s camera video instead
   of the prior ~8–9 Mb/s re-encode. This is a deliberate quality-preserving
   tradeoff, not hidden compression.
2. **Direct copy is rare for camera sources.** The established 128 kb/s audio
   contract excludes the Moto’s ~256 kb/s AAC recordings; those get video
   passthrough with audio re-render.
3. **Audio is not fully transmuxed for multiple clips.** Full audio passthrough
   remains disabled because of the documented M7.1 leading-gap/join risk.
4. **Trims always render.** Non-keyframe cuts are not represented as a lossless
   fast path.
5. **Verification is now dominant.** It remains full-decode and is not bypassed.
6. **Physical mid-transform process death is not proven.** Force-stop/retry and
   journal recovery passed, but the observed kill was not a confirmed mid-encode
   event. Physical battery loss cannot be safely simulated.
7. **No early camera return.** The camera returns only after verified publication.
8. **Device coverage is one Moto G54/Android 15.** API 26/28, other encoders,
   foldables, split-screen and Android 16 remain unverified.
9. **Validation-only hooks.** Renderer-forcing and low-space injection are guarded
   to the `.m12bvalidation` package and inert in production.

## Files changed

- `export/ExportPathPolicy.kt`, `ExportInputProbe.kt`, `ExportProcessor.kt`,
  `ExportTrace.kt`
- `export/ExportMedia.kt`, `ExportService.kt`, `ExportJournal.kt`,
  `GalleryPublisher.kt`
- `audio/AudioIndicator.kt`, `review/PausedEnvironment.kt`
- `CameraPreview.kt`, `MainActivity.kt`, `recording/RecordingControls.kt`,
  `RecordingViewModel.kt`, `CameraRecordingPort.kt`
- New JVM/device tests for path eligibility, fast/fallback processing, marker
  timing, publication retry, and export benchmarking.
- `README.md` and this report.

No original source MP4 or production session metadata was migrated or rewritten.
Version remains **0.12.0 / 16**; nothing was committed or pushed.

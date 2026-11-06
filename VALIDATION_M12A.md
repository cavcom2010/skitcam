# Milestone 12A — shelf, loop playback and recovery

Validation date: **6 November 2026**
Device: **Moto G54 5G `ZY22KJQLTV`**, Android 15 / API 35, 1080×2400,
font scale 1.3, three-button navigation.
Suffix-isolated package: **`com.calvinmazhindu.skitcam.m12avalidation`**.
The production package was installed and inspected, but never replaced or used
for these tests.

Branch: **`feature/m12a-shelf-loop-recovery`** (already existed and was clean at
`04f5727`). No `AGENTS.md` is present. No commit, push, merge or version bump was
made.

Validation APK SHA-256:
`b3b0bf2ea89c8644afe5050b3bf50c3f3abef68dd8c812db17c000b8ee9c040f`.
Version remains 0.11.2 / code 15.

## Source audit and preservation baseline

The existing engine already provided atomic `setSegmentKept()`, append-on-restore,
validated playback-order permutations, trim clipping in Media3, a single-segment
review player, and AtomicFile metadata. The changes build on those rules rather
than replacing them.

Before testing, the production session store contained **868 files** across 190
sessions. Public SkitCam media contained **396 Movies/SkitCam files** and **263
Pictures/SkitCam files**. Every production SHA-256 was captured under
`.validation/m12a/`.

Closing audit:

| Scope | Before | After | Changed/missing | New |
|---|---:|---:|---:|---:|
| Production private session files | 868 | 868 | **0** | 0 |
| Movies/SkitCam public media | 396 | 437 | **0** | 41 validation exports |
| Pictures/SkitCam public media | 263 | 263 | **0** | 0 |

The 41 new public videos were created by the isolated validation package. No
production MP4, session JSON, trim decision or Gallery item was changed or
removed. Device settings were restored to Auto-rotate on, `wm user-rotation=free`,
font 1.3 and navigation mode 0.

## Implemented behavior

### Context-sensitive review

- Kept segment: **Discard**, **Trim**, **Close**; no redundant Keep.
- Discarded segment: **Restore**, **Trim**, **Close**; no redundant Discard.
- Close releases the player and never edits metadata.
- Decisions use the existing atomic store before success is reported.
- Action controls are disabled while a decision is pending.
- `Trim` is shown only for a readable source; invalid trim remains repairable.
- The card and review title show **Kept** or **Discarded** explicitly.

### Direct shelf actions

- Tapping a card opens review.
- A 48 dp **More actions** menu on every card provides:
  - **Discard segment N** when kept.
  - **Restore segment N** when discarded.
  - **Play from here** when kept.
- Discarded cards remain visible and are visually dimmed/labelled.
- A successful direct discard shows **Discarded · original file retained** with
  **Undo**.
- No swipe-only or long-press-only operation is required.

### Continuous playback and anchor rules

`Play skit` plays the kept segments from the selected anchor through the last kept
segment in saved playback order. Loop ON repeats only that suffix; Loop OFF stops
after its last item.

Verified pure rules:

| Saved kept order | Anchor | Loop ON sequence |
|---|---|---|
| 1,2,3,4,5 | 3 | 3,4,5,3,4,5… |
| 1,2,3,5 (4 discarded) | 3 | 3,5,3,5… |
| 5,1,3,2,4 | 3 | 3,2,4,3,2,4… |

- `Play skit` from the paused toolbar starts at the first kept segment.
- `Play from here` in review or the shelf menu establishes a new anchor.
- Ordinary playback seeking does not change the anchor.
- Trim boundaries are applied through `MediaItem.ClippingConfiguration`.
- Discarded clips are absent by construction.
- Invalid trim blocks playback with an instruction to repair it; it is not
  silently played as a full clip.
- A missing active source prevents start with a segment-specific error; a corrupt
  source stops with an error. Neither changes decisions or files.
- No kept clips disables Play and displays **No kept clips to play.**
- The current clip is shown as **Clip X of Y · Segment N**; per-clip Play/Pause,
  seek, elapsed/duration and Loop controls are explicit Compose controls.
- Closing playback releases the single ExoPlayer. RESUME also closes and releases
  it before the recording engine starts a new segment.

### Recovery

Three conditions are kept distinct:

| Condition | Handling |
|---|---|
| A — finalised committed segment | Restored with Keep/Discard, trim and saved order |
| B — interrupted active candidate | Pending MP4 retained; never registered or called recovered |
| C — interrupted export/project journal | Existing journal recovery unchanged |

`SessionFileStore.loadLatest()` was hardened to read the committed base before a
legacy `.bak`, instead of letting `AtomicFile.openRead()` rename a stale backup
over a newer valid base. If the base is corrupt, its own valid backup is tried;
otherwise the read fails without loading an unrelated older session and without
writing. `.new` files are never treated as committed sessions.

A restored paused session now reports **Recovered paused session · N confirmed
clips.** A Paused session containing a pending candidate is converted to Error,
retaining the candidate and explaining that it was not registered.

## Device test results

All tests below ran on the physical phone against the isolated package.

| Suite | Result |
|---|---|
| `SessionFileStoreRecoveryDeviceTest` | **OK (5 tests)** |
| `M12ADeviceTest` review + direct shelf | **OK** |
| `M12ADeviceTest` anchor playback + RESUME release | **OK** (repeated after final controls) |
| `M12ADeviceTest` portrait playback layout | **OK** |
| `M12ADeviceTest` landscape playback layout | **OK**, 15.716 s on the final build (pose 270) |
| `M12ADeviceTest` 20-clip playback | **OK**, 32.559 s |
| `M12ADeviceTest` anchored loop repeat | **OK** |
| `M12ARecoveryDeviceTest` process death | **OK** (checkpoint + force-stop + restore) |
| `M12ARecoveryDeviceTest` device reboot | **OK** (checkpoint + reboot + restore) |
| `RecordingDeviceTest` | **OK (6 tests)** |
| `PauseEnvironmentDeviceTest` portrait | **OK (3 tests)** |
| `PauseEnvironmentDeviceTest` landscape | **OK** |
| `ArrangementDeviceTest` portrait | **OK (3 tests)** |
| `ArrangementDeviceTest` landscape | **OK** |
| `TrimDeviceTest` portrait | **OK (3 tests)** |
| `TrimDeviceTest` landscape | **OK** |

JVM: **221 tests, 0 failures/errors/skips.**
Lint: **0 errors, 10 existing warnings** (`UsableSpace` ×5, `UseKtx` ×2,
AndroidGradlePluginVersion, NewerVersionAvailable, DiscouragedApi).

### Performance

The 20-clip fixture used short real MP4 copies and the real Media3 decoder; no
transcode or export was added. Loop OFF completed the full playlist.

- Tap Play → first playing state: **1,048 ms**.
- Total 20-clip playback: **28,440 ms**.
- Observed transition checkpoints (ms from start):
  `1048, 2079, 3472, 4852, 6245, 7596, 8974, 10373, 11754, 13177, 14561,
  15971, 17323, 18695, 20063, 21483, 22878, 24255, 25635, 26996`.
- One ExoPlayer instance; it was released on close and the anchor loop wrapped to
  clip 3 without playing clips 1–2.

These are measured device results, not a gapless or frame-perfect claim. Decoder
preroll and per-item transitions remain visible.

### Screenshots inspected

- `.validation/m12a/review-kept.png`
- `.validation/m12a/shelf-discarded.png`
- `.validation/m12a/playback-portrait.png`
- `.validation/m12a/playback-landscape.png`

They confirm the conditional review actions, direct shelf status/Undo, explicit
Play/Pause/seek controls and the paused dock remaining available beside playback.

## Retries and operational notes

- A first portrait/landscape test assumed the phone was upright after a prior
  physical rotation. It timed out on pose 270/0 until the phone was placed back
  upright; no product failure was observed.
- Landscape playback was first successful at pose 270 (21.943 s). After adding the
  explicit seek controls, the phone was rotated again and the final landscape test
  passed in 15.716 s and produced `playback-landscape.png`.
- Two pre-existing recording suites call `model.resume(0)` directly after
  backgrounding without waiting for the sensor pose. The harness now waits for a
  stable matching pose before those direct engine calls; this mirrors the real UI,
  which already disables RESUME until the pose matches.
- The `CameraUxDeviceTest`, Gallery, and project device suites have hard-coded
  legacy package checks (`.m10validation`, `.m11validation`) and were not run under
  `.m12avalidation`. Export/project code was not changed; the portable-project JVM
  tests remain part of the 221 passing tests.

## Known limitations and remaining risks

- Playback is transient. It does not auto-start after PAUSE, does not survive
  activity recreation, and the anchor/loop/position are not persisted.
- No sample-perfect or gapless looping is promised; measured transition gaps
  include Media3 decoder/renderer preroll.
- Physical battery loss during an MP4 write cannot be simulated safely. The tests
  prove process-death, force-stop and reboot recovery only. Android cannot
  guarantee a file being written at the instant power disappears.
- Corrupt or missing active media produces an explicit playback error; it is not
  silently skipped and the saved edit decisions are not altered.
- Invalid saved trim blocks continuous playback until repaired in Trim; this is
  deliberate and matches export’s conservative validation.
- The full production Play Store/UI transformation remains out of scope. This
  milestone does not redesign Photo, Settings or the broader camera interface.
- TalkBack traversal, extreme font sizes, split-screen, foldables and other
  hardware remain broader regression risks inherited from earlier milestones.

## Files changed

- `MainActivity.kt` — playback ownership, lifecycle/close hooks, paused integration.
- `review/PausedEnvironment.kt` — context review, shelf actions, Undo, playback entry.
- `review/SegmentReviewController.kt` — shared atomic decision path/pending state.
- `playback/SkitPlaybackPlan.kt` — pure anchor/suffix/trim plan.
- `playback/SkitPlaybackController.kt` — Media3 playlist, loop, error/stale-session handling.
- `playback/SkitPlaybackScreen.kt` — playback surface and explicit controls.
- `recording/SessionFileStore.kt` — base-before-backup recovery read.
- `recording/RecordingEngine.kt` — recovered-paused notice and pending invariant.
- Tests: `SkitPlaybackPlanTest`, `SessionFileStoreRecoveryDeviceTest`,
  `M12ADeviceTest`, `M12ARecoveryDeviceTest`, plus review-copy updates in the
  existing recording/pause/arrangement/trim/UX suites.
- `README.md` — PAUSE environment behavior.

No original media or production session metadata was migrated or rewritten.

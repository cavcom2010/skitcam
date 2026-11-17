# M13 — icon-first UI

Follow-up to the initial inspection build: [Review control simplification](VALIDATION_M13_REVIEW.md).
The results/artifacts below describe the initial M13 build; its validation app now
contains owner-recorded clips and is not used for follow-up fixture seeding.

Branch: `feature/m13-icon-first-ui`  
Baseline: clean `main` / `origin/main` at `3df2a50`  
Device: Moto G54 5G `ZY22KJQLTV`, Android 15  
Validation package: **`com.calvinmazhindu.skitcam.m13validation`**  
Production package: `com.calvinmazhindu.skitcam`

**Status: ready for owner visual inspection. Not merged; no production installation authorised.**

## Audit and implementation

The current implementation, rather than the older transformation plan, is authoritative.
The checkout is `/home/calvi/skitcam` (`~/projects/skitcam` is absent).

| Stage | Changes |
|---|---|
| Foundation | Shared tooltip-labelled 48dp icon actions, common glyphs, adaptive confirmation surface, corrected text-on-rose contrast. Charcoal, warm rose, green/check and orange/minus remain. |
| Capture | Centred portrait shutter; lens slot becomes Finish during recording/pause. Pause/Resume use icons. Compact landscape auxiliary pair avoids overflowing the rail. At large fonts, a short Ready label and time/check/count caption keep the rail readable, with full spoken status. Disabled Photo/Gallery remain unavailable; the unavailable lens switcher is hidden. |
| Paused shelf | Shorter cards, compact number/duration, separate review/overflow touch regions, smaller overflow scrim, distinct selection/drag outline. Empty viewport no longer darkens the live preview. |
| Review/Trim | Checkmark Save, reset arrow, trash, restore and X. Existing frame steps, exact time accessibility alternatives, trim/playhead separation, aspect-fit rendering and unsaved-change protection remain. |
| Playback/dialogs | Shared playback glyphs, icon loop/replay, stacked wrapping confirmation actions. Reordering still offers Done and continue, Cancel changes and Keep reordering. |
| Export/Gallery | Common colours/buttons, readable export state announcements, adaptive filters and notification actions, icon Refresh, shared saved-video transport with visible playback error/retry. Portable project actions retain explicit labels. |

No capture engine, session schema, codec, ordering, trim persistence, export processor,
publication or recovery-store implementation was changed. No Arrange screen was restored.

### Finish safety

Opening/cancelling the Finish dialog does not invoke pause, stop or export. Confirming
consumes a session-scoped, activity-recreation-safe presentation gate before invoking
the existing stop/export workflow. It rechecks recovery, unresolved recording, pending
commands, edits and export availability. The existing finalisation/journal barrier
remains responsible for safely registering the final segment before media processing.

An interrupted session retains its existing close-without-export behaviour. An unreadable
session without an ID can still acknowledge its recovery error; that case never offers
"Finish and export". Confirmation is not automatically restored/accepted after process death.

## Build and tests

```sh
./gradlew -PvalidationSuffix=.m13validation \
  :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug --offline
git diff --check
```

- JVM: **265 passed**, no failures/errors/skips (8 Finish-gate and 2 contrast tests added).
- App/instrumentation builds: **successful**, using cached dependencies.
- Lint: **0 errors, 43 warnings**; no new baseline or suppression.
- M13 UI matrix: **16 passed** at 1.15× and 2.0× fonts, portrait and landscape.
- Existing inline matrix: **23 passed in the final complete run**, including touch
  reordering/scrolling, accessibility, recreation, process death, resume and ordered export.
- Atomic session-store recovery: **5 passed**.
- Finished video prepares/plays/releases: **passed**.
- Legacy camera UX: **7 distinct cases passed**: six in the grouped run; the raw-audio
  diagnostics lookup failed there, then passed in isolation.
- Legacy portrait trim: **4 distinct cases passed**: `c` and `d` in the grouped run;
  `a` and `e` on isolated retries after a handle-tap timeout and sensor-pose timeout.
- Existing automatic two-segment export/publication regression: **passed**.

The grouped legacy runs were not clean runs; their original failures and isolated
results remain in the evidence. No production code was changed to make those retries
pass. These legacy checks preceded the final landscape-only status caption adjustment.
The final app passed the 16-case UI matrix and the complete 23-case inline matrix.
Together with the legacy checks, **57 distinct device cases passed**, with the
legacy retry qualifications above.

A final top-edge test initially pressed the off-viewport centre of a partially visible
card, so no second drag began. The injected start point now intersects the visible shelf,
and a new assertion verifies that the correct card is actively dragging. The original
auto-scroll distance assertion was retained. Both focused cases and all 23 inline cases
then passed; app reordering/gesture logic was not changed for this test correction.

The final inline rerun exposed a test setup error: after a drop, the selected compact
card can be partially below the viewport. The test pressed its off-viewport centre,
so the second gesture did not drag a card. It now presses the visible intersection
and additionally asserts ownership of the correct dragging ID. The original
auto-scroll distance assertion is unchanged. Both focused top-edge cases passed.

New UI regressions use the existing Android accessibility/instrumentation framework;
no Compose UI-test dependency was added. Assertions cover target bounds, actual font
scale/orientation, modal cancellation, recreation, trim actions, source preservation,
reordering prompts, progress/error states and real portrait CameraX capture/export.

```sh
python3 tools/validate_m13.py
python3 tools/validate_inline_reorder.py \
  --package com.calvinmazhindu.skitcam.m13validation \
  --output .validation/m13/inline
```

The inline runner's default remains the M12F validation package; its optional package
argument accepts only the two explicitly named validation IDs.

### Generated fixtures

Use separate one-second red/green/blue AVC/AAC files in the validation package's
`files/inline-inputs/`. Existing generated M12F host fixtures were copied into M13's
private directory; no production source was used. Each UI/inline invocation makes
its own new session and copies those fixtures.

## Isolation, artifacts and inspection

Before installation, `aapt dump badging` verifies the exact validation APK ID and
instrumentation package; the test manifest's target package is also checked.
No production launch, install, force-stop, clear or uninstall is part of validation.

- Evidence, logs and media inventories: `.validation/m13/`
- Screenshots: `.validation/m13/screenshots/`
- Owner APK: `.validation/m13/SkitCam-M13-validation.apk` (about 18 MiB).
- SHA-256: `5f3e442f666d464d9cf175ac09a2f90d15cc22635f886b0e60b1a1f1684bc5fb`.
- APK v2 signature verified; instrumentation APK and separate checksum files are alongside it.
- Installed validation APK SHA-256 matches the owner artifact.
- Validation app is left paused with three generated clips for inspection.

### Production integrity

| Scope | Before | After | Missing | Changed | Added |
|---|---:|---:|---:|---:|---:|
| Production private `files/**` | 1,557 | 1,557 | 0 | 0 | 0 |
| Public Movies/Pictures SkitCam files | 1,165 | 1,186 | 0 | 0 | 21 validation exports |

All **977 production private media files** and all pre-existing public media are
byte-identical. Production remains 0.13.0 / 17, first installed `2026-11-01 00:52:17`,
last updated `2026-11-08 18:18:55`. It was not launched, installed over, cleared,
uninstalled or force-stopped.

Original device settings were verified restored: 1080×2400, original 446 dpi override,
1.15× font, free user rotation and disabled fixed-to-user-rotation override.
Hashes/comparisons are in `.validation/m13/integrity-summary.json` and the accompanying
before/after inventories. Validation exports intentionally add files; they do not
replace any pre-existing public media.

The validation app intentionally has no launcher entry. Open it explicitly:

```sh
adb -s ZY22KJQLTV shell am start -W -n \
  com.calvinmazhindu.skitcam.m13validation/com.calvinmazhindu.skitcam.MainActivity
```

### Key screenshots

All captures are from the separate validation app. Coloured clips are generated
fixtures, not production recordings. Android's existing floating notification bubble
may appear over the app; notification contents/settings were not cleared or changed.

| Screen | Evidence |
|---|---|
| Video / Photo idle | [Video](.validation/m13/screenshots/portrait-1.15-idle-video.png), [Photo](.validation/m13/screenshots/portrait-1.15-idle-photo.png) |
| Recording / Finish | [Recording](.validation/m13/screenshots/portrait-1.15-recording.png), [confirmation](.validation/m13/screenshots/portrait-1.15-finish-active.png) |
| Paused shelf | [Paused](.validation/m13/screenshots/portrait-1.15-paused.png), [reordering](.validation/m13/screenshots/portrait-1.15-reorder-unlocked.png) |
| Review / Play Skit | [Review/Trim](.validation/m13/screenshots/portrait-1.15-review-trim.png), [Play Skit](.validation/m13/screenshots/portrait-1.15-play-skit.png) |
| Saved video | [Finished playback](.validation/m13/screenshots/finished-playback.png) |
| Export / Gallery | [Progress](.validation/m13/screenshots/portrait-1.15-export-exporting.png), [error](.validation/m13/screenshots/portrait-1.15-export-failed.png), [Gallery](.validation/m13/screenshots/portrait-1.15-gallery.png) |
| 200% landscape | [Idle](.validation/m13/screenshots/landscape-2.0-idle-video.png), [Review](.validation/m13/screenshots/landscape-2.0-review-trim.png), [three-action dialog](.validation/m13/screenshots/landscape-2.0-reorder-confirmation.png) |

## Limitations / approval

- The owner was unavailable for physical rotation. Software landscape fixtures validate
  layout, not the sensor-pose lock or physical landscape capture. Those safeguards were
  not bypassed; guided physical rotation remains pending.
- Manual TalkBack traversal, haptic quality and owner visual approval remain pending.
- Large-font compact dialogs scroll when necessary; actions retain complete labels and
  can be reached rather than being squeezed into a clipped horizontal dismiss slot.
- Android 15 on this phone is not a full API/device/RTL matrix.
- The full instrumentation suite, physical landscape capture/trim tests and long media
  benchmarks were not rerun. New fixture-based Review/aspect/layout checks cover both
  software orientations; real portrait capture/export and the listed legacy cases ran.
- No production release or merge into `main` until explicit visual approval.
- Changes are left uncommitted on the feature branch for inspection.

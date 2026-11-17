# M13 Review follow-up — stable Review and camera start

Status: **device validation complete; awaiting visual approval; not merged**.

## Requested changes

Round 1 (Review simplification):

- Removed the lower Play and Discard/Delete controls from Review.
- Discard, Restore and Play from here remain in each segment's labelled **⋮ menu**.
- Removed extra portrait preview padding; video stays aspect-fit (no crop, no stretch).
- Landscape thumbnail overflow menus sit outside the card's cleared semantics, so
  TalkBack still finds them. Menus are disabled while a trim or decision is saving.

Round 2 (trim interaction):

- **Save Trim and Reset Trim moved into the playback row** above the playhead, in fixed
  slots beside Jump and Play. Saving, resetting or editing only changes their enabled
  state; the preview, timeline and clip strip never move.
- **No drag jitter.** Dragging is handled by one full-width gesture surface that maps the
  finger's absolute x to time, instead of accumulating deltas against a grip that is
  moving under the finger. (The old delta also mixed dp with px.)
- **Playhead is anchored.** It is captured when a trim gesture starts using the same
  position source that draws it, so it no longer drifts along with the edge.
- **Snap to playhead.** A dragged edge within 10 dp of the marker locks onto it, with
  hysteresis (releases after ~20 dp) so it does not flicker.
- Saving and status messages overlay the preview instead of taking layout height.
- Saving twice in place (Save, Reset, Save) no longer fails with “The segment changed”;
  the trim view model refreshes its segment snapshot after a successful save.

Round 3 (camera start):

- **The preview no longer bounces when Record is pressed.** Audio warnings used to be
  part of the measured toolbar; the header's height positions the capture frame, so a
  transient warning resized the preview twice.
- Microphone startup now has a neutral **Preparing** state instead of reporting
  “Recording audio unavailable”. Genuine muted/silenced/failed warnings still appear.
- Microphone warnings now overlay the preview *below* the toolbar, so they can never
  change the measured toolbar height. The toolbar itself is unchanged.
- The loading spinner during START keeps the shutter's 80 dp footprint instead of
  shrinking the dock to 36 dp.

Round 4 (icon distinction and playback transport):

- **Play skit from beginning no longer looks like Record.** It now uses a dedicated
  play-from-start glyph (bar plus right-pointing triangle) in a neutral ink circle,
  while Record/Resume keep the rose disc with their own glyphs. Label, role and 48 dp
  target are unchanged.
- **Loop no longer floats over the video.** The top-left overlay is gone; Loop sits in
  the transport row immediately before Play/Pause, and Replay appears there when
  playback finishes. The video area stays clean.

Trim storage format, recording pipeline, export and file handling are unchanged; the
differences are interaction and presentation.

Measured on the previous build, the warning text appeared during START and the preview
moved; on this build no warning appears and every sampled preview rectangle is
identical from idle through recording.

## Isolation

Tests run on the owner's Moto G54 in a fresh package:
`com.calvinmazhindu.skitcam.m13reviewstablevalidation`.

The production package and the original `com.calvinmazhindu.skitcam.m13validation`
(now holding owner-recorded clips) were not installed over, cleared, uninstalled,
force-stopped or used as an instrumentation target. Fixture seeding is limited to the
fresh validation package.

## Validation

```sh
./gradlew -PvalidationSuffix=.m13reviewstablevalidation \
  :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug --offline

python3 tools/validate_m13.py --output .validation/m13-review-stable
```

- JVM: **266 passed**, 0 failures/errors/skips.
- Lint: **0 errors, 35 warnings**.
- **All 20 presentation cases passed**: idle, capture/finish/export, paused dialogs,
  Review trim and trim-stability, and gallery/export, in portrait and landscape at
  1.15× and 2.0× font scale.
- **Camera start**: the recording case samples the live `PreviewView` rectangle every
  20 ms from idle through `Starting` to `Recording`. Every sample equals the idle
  rectangle, and “Recording audio unavailable” never appears. The same test against the
  previous APK fails immediately with that warning visible.
- **Four legacy real-recording trim cases passed**:
  `a` (frame taps, drag, clipped playback, save/reset, source hashes),
  `c` (dirty close, keep editing, source fallback), `d` (discard/restore without
  silently saving), `e` (playhead preserved through handle steps and reset).
- Nine geometry snapshots per Review configuration — clean, snapped, reset, trim-in,
  drag, trim-out, saved, reset-saved, saved-full — are **byte-identical**. The portrait
  preview stays **967×1719 px** in every state; before the trim fix the dirty state
  shrank it to **892×1585 px** (a 134 px / 48 dp jump).
- Snap test: with the finger finishing 8 dp from the marker, the edge landed within
  20 ms of it, and a mid-drag read confirmed the marker had not moved.
- At 2.0× font, controls keep 48 dp targets and share one row. Landscape keeps the
  side rail; preview there stays **545×968 px**.
- Font scale **1.15**, free user rotation, fixed-to-user rotation disabled, and display
  size/density all match the pre-test settings.
- Landscape layouts were exercised with generated fixtures. Physical landscape
  recording and manual TalkBack traversal remain unverified; camera pose safeguards
  were not bypassed.

## Broader functional pass

Beyond the presentation matrix, the current build was exercised on the same isolated
package. Suites that keep sequential state were run method-by-method with a clean
session, which is how they are designed to run.

- **M13 UI matrix: 20/20** (idle, photo mode, capture/finish/export, paused dialogs,
  Review trim and stability, gallery/export; portrait/landscape at 1.15×/2.0×).
- **Camera UX: 10/10** — photo capture and preference retention, pause/settings with a
  bound camera, compact quick/landscape targets, failure presentation and journal
  recovery, microphone summary and raw diagnostics, portrait overlay targets, paused
  focus-ring retirement, background checkpoint, recovery checkpoint.
- **Inline reorder: 7/7 standalone cases** — tap/long-press/cancel/done/playback,
  resume after reorder, reordered MP4 contents, scroll-does-not-unlock, drag
  auto-scroll, accessible unlock/navigation save/discard, top-edge auto-scroll with 24
  clips. (The two process-death checkpoint cases are a pair and were not rerun.)
- **Pause/Review environment: 3/3 runnable cases** — portrait decisions and recording
  audio, missing/corrupt/thumbnail fallbacks, atomic status/restart. The landscape case
  needs the phone physically turned; `wm` rotation cannot supply sensor pose 270.
- **Recording: 6/6**; **session recovery: 5/5**; **audio monitor: 1/1**;
  **gallery playback: 1/1**; **Review layout: 1/1**; **export journal: 5/5**.
- **Trim: frame taps, drag, save/reset, dirty close, discard/restore and playhead
  preservation** all pass (landscape variant likewise needs physical rotation).
- Not run: project export through the system file picker, finished-video playback via
  an intent URI, and legacy milestone suites that require older package IDs. The
  modern paths for those features are covered by the suites above.

Whole-class runs of Camera UX, reorder and pause suites can fail on state left by the
previous method (an open session or a stale export) even though each method passes on a
clean start; these are sequence-coupled tests, not product failures.

## Preservation

- Production: **1,562 private files unchanged, no missing files**, including 979
  pre-existing media/image files.
- Original M13 app: **2,703 private files unchanged**, including 2,420 media/image
  files; its session remains **Paused, five segments, no pending recording**.
- The earlier follow-up copy (`…m13reviewvalidation`, generated fixtures only) gained a
  session update and export artifacts while old and new builds were compared. No
  pre-existing media there was changed or removed.
- Full inventories: `*-private-before.sha256` / `*-private-after.sha256` and
  `preservation-results.json` under `.validation/m13-review-stable/`.

## Inspection artifacts

- [Validation APK](.validation/m13-review-stable/SkitCam-M13-stable-review-validation.apk)
  (`com.calvinmazhindu.skitcam.m13reviewstablevalidation`, debug signed, v2 verified).
- SHA-256: `a33ffc29f592365d125910870a8aa7a6548e5f9ade33ca808c6ccac4902cd749`;
  the installed base APK matches this digest.
- [Play skit with Loop in the transport](.validation/m13-review-stable/screenshots/play-skit-loop-transport.png);
  the video area has no floating icon.
- [Paused screen icons](.validation/m13-review-stable/screenshots/paused-icons.png):
  neutral play-from-start beside the rose Resume/Finish discs.
- [Camera screen left ready on the phone](.validation/m13-review-stable/screenshots/camera-idle.png)
  for a manual Record press.
- [Review screen](.validation/m13-review-stable/screenshots/final-review-on-phone.png)
  (clean full range; Save/Reset disabled in place).
- [Snapped trim](.validation/m13-review-stable/screenshots/portrait-1.15-review-stable-snapped.png)
  and [mid-drag](.validation/m13-review-stable/screenshots/portrait-1.15-review-stable-drag.png).
- Results: `review-matrix-camera-start.log`, `paused-icons-matrix.log`,
  `camera-start-before-fix.log`, `camera-start-after-fix.log`, `legacy-trim.log`,
  `legacy-playhead-dirty.log`, `final-fixture-run.log`; broader functional logs in
  `functional-tests/`; measured geometry in `layouts/`.
- Earlier round evidence remains under `.validation/m13-review/`; the historical
  interface report is `VALIDATION_M13.md`.

Stop before merging. Await owner visual approval.

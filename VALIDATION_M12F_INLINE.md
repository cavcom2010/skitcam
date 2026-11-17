# M12F — inline segment reordering

Branch: `feature/m12f-inline-reordering`  
Device: Moto G54 5G `ZY22KJQLTV`, Android 15 / API 35  
Production package: `com.calvinmazhindu.skitcam` (never installed over, cleared or uninstalled)  
Validation package: `com.calvinmazhindu.skitcam.m12finlinevalidation`

## Design

- Reordering lives in the paused camera's existing shelf, vertical in both orientations.
- Normal taps open Review. The platform long-press timeout (approximately 500 ms)
  unlocks reordering with haptic feedback and an inline indication.
- A single viewport-owned gesture handler distinguishes taps, scrolling and dragging.
  Drag input survives lazy-item disposal. Only active dragging disables user scrolling.
- Animated neighbours, a faint placeholder/insertion line, a viewport-clamped floating
  thumbnail, edge auto-scroll, and accessible Unlock / Move up / Move down actions.
- Unlock persists until explicit Done or Cancel. Navigation offers Done and continue,
  Cancel changes, or Keep reordering. Failed saves leave the draft open.
- Recording, export, pending commands and unresolved recovery gate entry and saving.
- Only after the inline checks passed, the Arrange button, navigation wiring and
  standalone screen were removed. The obsolete standalone UI test was replaced by
  the inline device suite; existing arrangement JVM tests and atomic recovery code remain.

## Persistence and recovery

Session schema 7 adds optional `shelfOrder`, containing all permanent segment IDs.
`playbackOrder` remains the kept-only permutation consumed by playback and export.
Both orders commit through the existing atomic session store in one transaction.
Recording chronology, paths, trim metadata, media facts and disposition are not rewritten.

Versions 1–6 remain readable. Without an explicit shelf order, discarded chronological
slots are retained and kept slots follow the existing saved playback order. No migration
write occurs on read. Malformed explicit shelf orders fail rather than being normalised.
Discard removes an ID from playback only; restore retains the existing append-to-playback
contract and moves that restored shelf item to the end. Newly recorded segments append.

`files/inline-reorder-draft.json` is a separate, atomic recovery-only checkpoint. It is
never read by playback, export or ordinary session recovery. It stores the baseline and
last settled draft; an in-progress drag is not committed. Checkpoints are written on a
serialized background worker. On restart, only a matching, resolved paused session can
restore the draft. Stale/corrupt drafts are not applied. Done and Cancel delete it.

## Validation commands

```sh
./gradlew -PvalidationSuffix=.m12finlinevalidation \
  :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug

ADB="$HOME/Android/Sdk/platform-tools/adb"
"$ADB" -s ZY22KJQLTV install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s ZY22KJQLTV install -r \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

python3 tools/validate_inline_reorder.py

"$ADB" -s ZY22KJQLTV shell am start -W -n \
  com.calvinmazhindu.skitcam.m12finlinevalidation/com.calvinmazhindu.skitcam.MainActivity
```

Confirm the APK application ID is the validation ID before installation. The validation
app intentionally has no launcher entry; use the explicit activity command above.
The device-test matrix requires separate one-second red/green/blue AVC/AAC inputs at
`files/inline-inputs/{red,green,blue}.mp4` in this validation package. Each test creates
its own session and copies those inputs; no production files are used.

The matrix restores the original font scale; individual tests restore original rotation.
The user's original density override (446 dpi) is retained, not reset to 400 dpi.

## Results

| Check | Result |
|---|---|
| JVM suite | **255 passed**, 0 failed/errors/skipped; includes 15 new inline tests |
| Validation app and instrumentation APKs | **BUILD SUCCESSFUL**, installed using `adb install -r` |
| `lintDebug` | **0 errors, 44 warnings** (existing warning count; no new suppression/baseline) |
| Inline device matrix | **23 passed** across separate instrumentation invocations |
| `SessionFileStoreRecoveryDeviceTest` | **5 passed** |
| Portrait `TrimDeviceTest` regressions (`a`, `c`, `d`, `e`) | **4 passed**, with `e` passing on retry after a sensor-pose setup timeout |
| APK signing | **v2 signature verified** |
| Host MP4 inspection | Three outputs: H.264 + AAC, 1.652 s each; 16 sampled frames each are blue then red, never discarded green |

The 23 inline cases cover:

- One and three segments: actual short tap versus long-press, Cancel, Done, accessible
  Move down and Move up, persisted shelf order and whole-skit playback.
- Three, 20 and 100 segments: injected touchscreen drag, bottom-edge auto-scroll,
  selected floating thumbnail, navigation protection, recreation and atomic Done.
- 100 segments: normal touchscreen scrolling without unlocking.
- 20 segments: explicit top-edge auto-scroll back beyond the viewport, Cancel of both drags.
- Portrait and landscape at the user's 1.15× font; portrait and landscape at 2.0× font.
  Tests assert actual orientation and resource font scale, not just requested settings.
- Saved recovery checkpoint, real validation-package force-stop and cold-start recovery.
- Accessible unlock; navigation with both Cancel changes and Done and continue.
- Resume after saving: real CameraX recording with valid audio/video, appended ID/order.
- Reordered MP4: kept blue then red clips; discarded green omitted; trim and source-byte
  preservation checked for every fixture.

One earlier final-matrix attempt was intercepted by Android's notification shade
(active window was System UI). The **final complete 23-case matrix passed in one run**,
with intended font scales printed and asserted. A separate repeat of the three-clip
tap/Cancel/Done/playback case also passed to prepare the phone for inspection. No notifications were
cleared and no notification settings were changed. The old trim playhead regression's
first setup attempt timed out waiting for stable pose 0; its isolated retry passed.
An additional post-stress Review-readiness attempt reached the existing “Cannot play”
fallback; its isolated retry at 1.15× passed without app changes. A cleanup attempt also
caught font scale still at 2.0×; the runner now passes the intended scale explicitly
and avoids unnecessary scale changes when filtering cases.

The full unrelated instrumentation suite and landscape trim regression were not rerun.
The updated legacy pause-environment test expectations were compiled, but that full
legacy suite was not executed in this pass.

Final validation APK SHA-256:
`b7768014a46332b234c252ae63589b88f2a389bafa1a1952d75283285a301609`

### Production media integrity

Production private `files/**`: **1,557 before and after; 0 missing, 0 changed, 0 added**.
This includes **977 media files**. Version 0.13.0 / 17, first installation
`2026-11-01 00:52:17` and last update `2026-11-08 18:18:55` are unchanged.

| Scope | Before | After | Missing | Changed | Added |
|---|---:|---:|---:|---:|---:|
| Production private `files/**` | 1,557 | 1,557 | 0 | 0 | 0 |
| Public Movies/Pictures SkitCam media | 1,156 | 1,163 | 0 | 0 | 7 validation exports |

Every pre-existing inventoried file is byte-identical. Inventories and comparison JSON
are under `.validation/m12f-inline/`. The production app was not launched, installed
over, cleared, uninstalled or force-stopped.

The validation app is left paused with three generated clips for inspection. Device
settings were verified restored: 1080×2400, the original 446 dpi override, 1.15× font
and free user rotation. The installed validation APK hash matches the built APK.

## Changed files

Under `app/src/main/java/com/calvinmazhindu/skitcam/`:

- `MainActivity.kt`: inline ownership, recovery, navigation prompts; remove Arrange wiring.
- `arrange/InlineReorderDraft.kt`, `arrange/InlineReorderViewModel.kt`: draft transaction/checkpoint.
- `arrange/ArrangementScreen.kt`: removed after inline validation.
- `review/InlineSegmentShelf.kt`: viewport gestures, animations, auto-scroll, floating thumbnail.
- `review/PausedEnvironment.kt`: inline controls, shelf and accessibility actions.
- `recording/RecordingControls.kt`: navigation guard and Arrange button removal.
- `recording/RecordingEngine.kt`, `recording/RecordingViewModel.kt`, `recording/SessionCodec.kt`:
  validated atomic shelf/playback commit and backward-compatible schema 7.

Other files:

- `app/src/main/res/values/strings.xml`: remove unused Arrange label.
- `app/src/androidTest/java/com/calvinmazhindu/skitcam/arrange/InlineReorderDeviceTest.kt`: new suite.
- `app/src/androidTest/java/com/calvinmazhindu/skitcam/arrange/ArrangementDeviceTest.kt`: retired UI tests removed.
- `app/src/androidTest/java/com/calvinmazhindu/skitcam/review/PauseEnvironmentDeviceTest.kt`:
  update shelf orientation/position expectations.
- `app/src/test/java/com/calvinmazhindu/skitcam/recording/InlineReorderTest.kt`: 15 new JVM tests.
- `app/src/test/java/com/calvinmazhindu/skitcam/recording/SegmentDecisionsTest.kt`,
  `app/src/test/java/com/calvinmazhindu/skitcam/camera/CameraPolicyTest.kt`: update schema version assertions.
- `tools/validate_inline_reorder.py`: validation-only device matrix.
- `VALIDATION_M12F_INLINE.md`: this report.

## Evidence and limitations

Evidence is retained under `.validation/m12f-inline/` and the validation package's
`files/validation-inline/`. Generated media, logs and screenshots are not committed.

- Recovery preserves the last completed checkpoint, not an in-flight gesture or a
  write interrupted before becoming durable. The authoritative order remains atomic.
- Device scale fixtures use copied, distinguishable one-second sources, not 100 newly
  recorded takes. All fixture media bytes and original segment metadata are checked.
- Actual TalkBack exploration and tactile haptic quality still require manual review.
- Schema 7 sessions must not be opened with an older validation APK that supports only
  schemas 1–6. Production data is isolated under its unchanged package.

No merge or production installation is authorised by this report. Interface inspection
and explicit approval by the owner are required before merging into `main`.

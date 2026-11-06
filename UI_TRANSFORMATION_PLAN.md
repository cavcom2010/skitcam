# SkitCam UI transformation plan

Status: **proposed, not implemented**. Baseline: `main` at `4013175`, clean tree.
Work should happen on a new branch (for example `feat/ui-foundation`) so the
deployment history stays intact.

## Goal

Turn SkitCam from an engineering-style camera console into a simple, privacy-first
camera for recording clips, arranging and trimming them, and finishing one video.
The capture, recovery, orientation-lock, and export engines are not part of this
work and must stay unchanged.

## What can be verified without a phone

- Kotlin compilation and APK assembly.
- JVM unit tests and lint.
- Instrumentation-test compilation.
- `git diff --check`.

Not verifiable without a device or emulator: live preview layout, gestures,
permissions, real density and cutout behaviour, TalkBack, and media output. Those
are deferred to a device session and must not be claimed as validated before then.

## Gaps identified

| Priority | Gap | Evidence |
|---|---|---|
| P0 | Camera permission is requested immediately on first launch with no in-app rationale | `MainActivity.kt` |
| P0 | Notification permission is requested during finishing with no explanation, and the result is ignored | `export/ExportScreen.kt` |
| P0 | Modal overlays (Quick/full settings, Trim, Arrange, Finishing) lack consistent focus management, background isolation, and pane/heading semantics | `MainActivity.kt`, `camera/CameraTools.kt` |
| P0 | Most user-facing copy and accessibility labels are hardcoded; `strings.xml` holds only about 12 entries | `res/values/strings.xml`, multiple Kotlin files |
| P0 | No in-app privacy/storage explanation; private originals and discarded takes accumulate with no management UI | `README.md`, Gallery is read-only |
| P1 | Quick settings shows the Photo timer while in Video mode; some chip rows lack headings | `camera/CameraTools.kt` |
| P1 | Primary recording controls sit inside a scrollable column and can move under long messages or large text | `recording/RecordingControls.kt` |
| P1 | Pause and Stop are visually similar; Finish is a small icon near Pause | `recording/RecordingControls.kt` |
| P1 | Technical copy: “Hold upright”, “original 270° direction”, raw enum names such as `Finalising` | `recording/RecordingControls.kt` |
| P1 | Settings displays `SkitCam · 0.10.0`; the app is `0.11.2` | `camera/CameraTools.kt`, `app/build.gradle.kts` |
| P1 | Styling is duplicated across activities with separate colour literals and themes | `MainActivity.kt`, Gallery, Export, Project Export, `themes.xml` |
| P1 | Playback failure closes the screen instead of showing an inline Retry/Back state | `export/ExportScreen.kt` |
| P1 | Gallery header and filter rows are not resilient to narrow widths or large text | `gallery/SavedMediaActivity.kt` |
| P1 | Trim and Arrange discard unsaved edits on Back with no confirmation | `trim/TrimScreen.kt`, `arrange/ArrangementScreen.kt` |
| P2 | No adaptive launcher icon | `AndroidManifest.xml`, `res/drawable/ic_skitcam.xml` |
| P2 | No Compose UI-test framework is cached; no UI test dependency is present | `app/build.gradle.kts` |

Strengths to preserve: 48 dp touch targets, 80 dp shutters, safe-area handling,
Arrange's non-drag accessibility actions, Trim's numeric alternatives, recovery
flows, and the media-preservation tests.

## Phase 1 (proposed first slice): foundation and camera shell

### 1a. Design foundation

- Add `ui/theme/SkitCamTheme.kt` with semantic colours, typography, spacing,
  shapes, and scrim levels. Replace the repeated literals `#101014`, `#18181D`,
  `#854C55`, `#F2D4BC`, and the others found in the audit.
- Add shared components: top bar, primary and secondary actions, status banner,
  loading, empty, error, permission panel, and settings section heading.
- Apply the theme to Main, Gallery, finished playback, Project Export, and the
  two player layouts.
- Derive the displayed version from `BuildConfig` (enable `buildFeatures.buildConfig`).
- Move camera-shell copy and accessibility descriptions into `strings.xml`.

### 1b. Camera shell

- Split `MainActivity.CameraScreen` into presentation components only:
  `CameraHeader`, `CaptureDock`, `PhotoControls`, `VideoControls`, `CameraStatus`,
  `QuickControlsSheet`. Camera-binding and state logic stays where it is.
- Stable control zones: gallery, shutter, and lens in consistent thumb positions
  per orientation. Hierarchy and placement are consistent, not necessarily identical
  geometry in portrait and landscape.
- Mode selector: **Photo** / **Video**, with one short explanation that Video records clips.
- Contextual Quick controls:
  - Photo: ratio, timer, grid, zoom.
  - Video: quality, frame-rate target, stabilisation, zoom.
  - Advanced: white balance, AE/AF holds, microphone routes, technical measurements.
  - Fix the Photo timer appearing in Video mode.
- Accessible modal surface with a pinned title and Close action, background
  semantics cleared, focus moved in on open and restored on close, and predictable Back.
- Pin primary recording controls; scroll warnings and secondary content independently.
- Separate Pause (primary) from Finish (secondary, labelled, spatially distinct).
- Replace technical copy:
  - “Hold upright” → orientation-neutral guidance.
  - “Return to the original 270° direction” → “Rotate back to landscape, top edge left/right.”
  - Raw states such as `Finalising` → “Finishing current clip.”
- Rename **Full** → **Full screen**, with a one-time explanation that it crops the sensor to the display.
- Polite live-region announcements for countdown, photo save, recording transitions, and failures.

### 1c. Permission UX

- Brief in-app rationale before the first camera system prompt.
- Microphone rationale stating that audio is required for Video only.
- Keep the deliberate second Record press after microphone permission is granted.
- Explain notification permission before the finishing flow requests it; handle denial visibly.
- Consistent permanently-denied recovery with Open settings.

### 1d. Explicitly out of scope for Phase 1

- Completed-project editing and versioned re-export.
- Multiple drafts or projects; Library Media/Projects redesign.
- Private-original deletion and storage management.
- Any change to trim, arrangement, export, or recovery logic.
- Navigation Compose migration (the current activity/overlay model is adequate).
- Play assets, signing, privacy policy, Data Safety, and full localisation or RTL sign-off.

### 1e. Test and verification plan

- Instrumentation tests that match visible text will need updated expectations
  wherever copy changes. Keep those edits minimal and map each one to its old label.
- Offline checks:

  ```sh
  ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline
  git diff --check
  ```

- Do not add a Compose UI-test dependency without confirming network access.
- Device or emulator validation (later): camera permission flows, Photo and Video
  capture, Quick controls, orientation behaviour, pinned controls at 200% system font,
  TalkBack traversal of the modal, and the existing hardware suites.

## Later phases (not yet scheduled)

- **Phase 2 — Editing and session flow:** single full-screen clip editor with a
  consistent draft/save model, unsaved-change confirmation, Include/Exclude with undo,
  and a clip-first paused-session surface.
- **Phase 3 — Library and privacy:** Media/Projects library, internal photo viewer,
  completed-project reopening and re-export (if in scope), and Privacy & storage
  management. Deletion requires a retention decision first.
- **Phase 4 — Release readiness:** adaptive launcher icon, release signing and AAB
  configuration, hosted privacy policy, Data Safety answers, foreground-service
  declaration review, and the device/accessibility matrix (API levels, small and large
  screens, split-screen, foldables, TalkBack, RTL/pseudo-locales).

## Open decisions

1. Start with Phase 1 only, or attempt the full transformation?
2. Use the recommended defaults: **Photo / Video** labels, **Full screen**, and a
   refined version of the current dark neutral and warm-rose palette?
3. Include a static **Privacy & storage** explanation in Settings in Phase 1, with
   deletion deferred until the retention policy is decided?
4. Positioning: clip-first creator (recommended) or general camera replacement?

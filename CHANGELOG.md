# Changelog

## Unreleased

- Added Android projection emergency exit: the existing local three-tap unlock path can reveal a temporary native “退出投放” button, whose 8-second native timeout relocks the page through a one-way JavaScript hook. The button returns to Android settings without calling `/api/shutdown`; remote unlock, shortcuts, WebView errors, and renderer recovery keep their existing behavior.
- Added MD-A08 LAN host discovery for Android settings: a versioned UDP broadcast/multicast response on port 8766, nonce- and candidate-correlated HTTP handshake validation, multi-host selection, lifecycle-bounded scanning, and `http://` normalization for bare LAN addresses. The authorized Xiaomi/HyperOS target is now online for read-only diagnostics, but it was not in an active MarkerDeck projection, so the physical lock-screen comparison remains pending.
- Cancelled MD-A04 dedicated-device work and removed its policy code, manifest/resource declarations, UI, tests, and setup guidance because Device Owner administration and possible factory-reset/data-clear recovery carry too much operational risk for MarkerDeck. Legacy mode preferences are ignored and all Android connections now use ordinary display.
- Implemented the MD-A03 P0 screen-on priority slice: active ordinary projections restore visible WebView/window state through resume, focus, screen-on and user-present lifecycle events, with explicit keyguard diagnostics, saved active projection recovery, and renderer-failure recovery without authentication bypass claims.
- Changed active projection back handling so Android 13+ `OnBackInvokedCallback` and legacy `onBackPressed` consume the system back action without page navigation, returning to settings, or finishing; the settings surface still finishes normally and the explicit error-panel “返回设置” action remains available.
- Added a low-risk system permission settings entry. Xiaomi/HyperOS devices first try the MIUI per-app permission editor for manual “允许锁屏显示” configuration (some system versions label it “锁屏显示”), with runtime resolution/launch fallback to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`; no new runtime permission or automatic appops/system setting change is used.
- Recorded diagnostics for the authorized device: Xiaomi HyperOS API 35 was online, the current top activity/window was MIUI Home rather than MarkerDeck, the MarkerDeck process was cached/background-restricted, and Device Owner type was absent. The verified debug build was installed for UI testing; no permission grant, appops write, system setting write, lock credential change, or Device Owner action was performed.
- Implemented MD-A02 ordinary Android display MVP with explicit DataStore settings, validated server-origin input, a safe WebView display shell, visible loading/error/retry states, immersive fullscreen, native screen-on behavior, and Android-provided device-name handoff.
- Documented an Android-first mobile implementation plan split into acceptance-testable tasks, dependency gates, and release milestones.
- Implemented MD-A01 Android foundation with a standalone Kotlin/Android SDK project, fixed AGP and Gradle versions, a pure service URL unit test, and a separate GitHub Actions debug-check job.

## 1.3.0 - 2026-09-02

- Reorganized the repository around one shared `src` tree.
- Added repeatable macOS and Windows packaging scripts.
- Added cross-platform GitHub Release automation.
- Added source checks and server integration tests.
- Collapsed multiple display pages from the same physical device, with independent and batch selection.
- Stabilized device thumbnails with full aspect-ratio rendering for portrait and landscape screens, fixed-height name placeholders, and preserved device-list scroll position.
- Added offline device status, manual cleanup, and configurable automatic retention.
- Added control-side batch page selection, group management, and multi-page remote lock confirmation.
- Added a mobile control quick-preset panel with recent and favorite presets.
- Switched Windows desktop releases from portable EXEs to extractable ZIP packages and verified bundled FFmpeg and Node.js runtime files.
- Renamed the product and repository from Chroma Cross to MarkerDeck, including repository-facing source files, web pages, launch scripts, package names, and release artifacts.
- Preserved 302 redirects from `/chroma-launch.html` and `/chroma-cross-screen.html`, including their query parameters, for existing links.
- Added fallback migration for legacy `CHROMA_*` environment variables, `chroma-settings.json`, `chroma-presets.json`, and `chromaCross*` browser storage keys while `MARKERDECK_*` and `markerdeck*` names remain canonical.
- Documented the renamed macOS, Windows, and Electron packaging outputs, including `markerdeck-macos-arm64-v<version>.zip`, `markerdeck-windows-x64-v<version>.zip`, and `MarkerDeck.Client.<version>.zip`.

## 1.2.0-video - 2026-08-07

- Added custom-duration H.264 MP4 export through FFmpeg.
- Added current-screen and all-preset video export.
- Added ZIP and separate-file batch download modes.
- Added real-time video generation progress.

## 1.1.0 - 2026-08-03

- Added multi-device management, custom names and groups.
- Added batch control and remote projection locking.
- Added custom presets and PNG export.
- Added portable Windows Electron client.

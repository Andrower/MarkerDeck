# Changelog

## Unreleased

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

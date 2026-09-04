# Changelog

## Unreleased

- 完成 MD-A15 局域网发现提速与启动询问稳定性：Node/Android 在首个 mDNS/UDP 候选通过 nonce HTTP 验真后提前返回，并保留短暂多宿主宽限；两端使用同一 nonce 在约 300ms 后最多重发一次 UDP 请求，单目标发送失败不再终止整体扫描，abort/截止后不继续发送。Android 稳定保留 `STARTUP` trigger 和暂不可见 UI 的 pending prompt，以 generation 隔离旧扫描并统一释放 mDNS、UDP、HTTP、多播锁和延迟回调；同一 Activity 会话仍只自动提示一次，用户刷新可再次询问。真机性能、多宿主宽限、网络切换及不同路由器仍待复测。
- 实现 MD-A14 局域网 mDNS 自动发现：Node 使用 bonjour-service 发布 `_markerdeck._tcp.local`，Android 使用 NsdManager 发布/扫描并与 UDP 兼容回退；所有候选继续经过 nonce HTTP 验真，`/api/info` 增加准确的 `mdnsDiscovery` 能力。Android 启动时会在设置页短时发现并先询问连接，支持多宿主选择、设备命名复用、一次会话去重和生命周期清理；便携包与 Electron 包递归携带 bonjour-service 运行时依赖。USB Android 真机上的双向 mDNS、启动询问、命名连接、远程同步和后台发布已通过；不同路由器、网络切换、组播隔离及多宿主现场验证仍待完成。
- 整理 MD-A12 仓库主页与发布入口：以实际使用者为中心重写 README，补充四个平台下载说明、能力矩阵、网络与锁定边界、文档索引和任务记录。

## 1.5.0 - 2026-09-03

- Added MD-A10 Android QR host scanning on the settings page with JourneyApps ZXing Embedded and Activity Result permission/scanner flows. Existing MarkerDeck launch, control, display, query, and bare IP values normalize to the service origin; invalid schemes/content, cancellation, camera denial, missing cameras, and scanner failures preserve the existing input and report status, while successful scans still require explicit user confirmation to connect.
- Fixed MD-A10 QR scanner launches by routing `ScanContract` through a MarkerDeck-owned `CaptureActivity` declared `exported=false` with `sensorPortrait`, and locking the scanner orientation. Projection WebView, local projection, and host control page orientation behavior remain unchanged.
- Added MD-A11 overall-brightness state convergence: the UI keeps only “总体亮度”, while legacy `bgBrightness`/`crossBrightness` values are folded into RGB colors and emitted as string `"100"` for old clients. The canonical state is shared by live output, random points, thumbnails, PNG, video, presets, Node hosting, and Android hosting; built-in 60%/30% presets keep their names with folded color values. Android projection windows still use a per-Activity 100% brightness override and restore `BRIGHTNESS_OVERRIDE_NONE` on exit/cleanup without changing global system brightness. Remote locks execute immediately through DOM/canvas/native state, use the 1.5-second registration heartbeat when SSE is temporarily unavailable, preserve the global-command baseline, and keep batch ACK accounting; remote commands do not request browser Fullscreen.
- Fixed Android projection control-panel auto-hide during interaction: pointer, focus, keyboard (matched by key code/key), slider, color/select, text-input, preset, and button activity now pauses the existing timeout and restarts it only after interaction ends. Keyup, control blur, and window blur clear interrupted interactions, while control-surface events no longer bubble into canvas visibility handling; three-tap unlock, exit projection, remote lock, brightness, QR, and other projection behavior remain unchanged.

## 1.4.0 - 2026-09-03

- Added an installable debug-signed Android APK to the cross-platform GitHub Release workflow alongside the macOS and Windows packages.
- Added desktop LAN host discovery: the Node service actively scans MarkerDeck UDP responders, verifies each candidate through the nonce-scoped HTTP handshake, excludes itself, and exposes validated control URLs through `/api/hosts`. The desktop launch page now lists discovered phone or computer hosts with automatic scanning and manual refresh, while Android-hosted launch pages hide the unsupported scanner UI.
- Removed the redundant “打开当前控制地址” action from the shared control page. The action could navigate the Android localhost WebView to its LAN alias and correctly trigger the existing same-origin protection; QR display, address display, and address copying remain available.
- Moved the Android embedded HTTP/SSE/UDP host into a `connectedDevice` foreground service with a persistent service notification, one-time Android 13+ notification permission request, and explicit stop action. Leaving, destroying, or removing the Activity no longer stops an active host; explicit stop, remote-host switching, `/api/shutdown`, and force-stop still do.
- Made the Android native “退出投放” action visible immediately alongside the initial unlocked local adjustment panel. Locking projection hides it, while existing three-tap temporary unlock and relock behavior remains unchanged.
- Moved the Android projection emergency-exit button below the webpage status badge to prevent overlap on cutout displays.
- Added spacing between the embedded-host stop action and the MarkerDeck service-address section on the Android settings screen.
- Added an Android settings-home status and explicit stop action for the embedded HTTP/SSE/UDP host. Returning from the host control page keeps the host visible and controllable, while remote LAN-host projection remains separate.
- Added a one-time, persisted Android lock-screen display guidance flow with a manual system-settings route, same-Activity de-duplication, status refresh after returning from system settings, and gentle unknown/unsupported handling without automatic permission or authentication changes.
- Added the MD-A09 Android Host MVP first vertical slice: explicit local projection, LAN-host connection, and this-device-host mode entries; shared `src/web` Gradle assets; NanoHTTPD static/API/SSE/preset/device/lock handling; ZXing QR generation; UDP 8766 discovery response; and Android capability reporting that disables video export while desktop Node/FFmpeg behavior remains unchanged.
- Completed frontend modularization phase 1: split screen styles into ordered `markerdeck-base.css`, `markerdeck-control.css`, and `markerdeck-mobile.css` files, split the classic browser code into explicit core/API/canvas/export/presets/devices/projection/settings/launcher modules, and kept `markerdeck-bootstrap.js` as the sole assembly entry. The screen remains usable over HTTP and `file://` without a bundler or runtime dependency.
- Added an exact server static-asset allowlist with CSS/JavaScript MIME types, HTML reference and external-script syntax checks, and integration coverage for successful assets and unknown-resource 404 responses.
- Adjusted Android projection emergency controls: the local three-tap gesture can reveal a temporary native “退出投放” button while the projection is locked or unlocked. The button always requires confirmation and its source remains explicit: a button shown after unlocking an originally locked projection still relocks after 8 seconds, while a button shown from an originally unlocked projection only hides after 8 seconds. The webpage remains the only lock action; remote locking, back-key blocking, lock-screen display, and embedded host behavior remain unchanged.
- Added dynamic Android settings top padding from status-bar and display-cutout safe-area insets while preserving the layout's base padding and leaving projection immersive mode unchanged.
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

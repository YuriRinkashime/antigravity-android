# Changelog

All notable changes to **Antigravity for Android (Root Edition)** (`YuriRinkashime/antigravity-android`) are documented in this file.

For earlier upstream release history (`v2.33.0`, `v2.4.1`, and prior), see [`RELEASE_NOTES.md`](RELEASE_NOTES.md).

---

## [v2.34.14] - 2026-11-25

### Added
* **1-Tap Portable Backup & Restore (`StateBackupManager` & `QuickControlSheet`):** Export all local chats (`conversations/`), summaries DB, brain artifacts, and rules (`AGENTS.md`) as a portable `.zip` archive in `Download/Antigravity-Backups/` that survives app uninstalls, Clear Data, and custom ROM flashes.
* **Cross-Compatible Backup CLI (`agy-sys backup` & `agy-sys restore`):** Uses Python `zipfile` (`agy-backup-YYYYMMDD-HHmmss.zip`), 100% compatible with the in-app Restore button and safe against path traversal.

---

## [v2.34.13] - 2026-11-25

### Added
* **Crash & Anti-Kill Resilience (`RootResilience`):** Automatically whitelists `com.agy` in Android Doze and gently lowers `oom_score_adj` (`-100`) for Antigravity and `language_server` when root is active, preventing aggressive LMKD kills when switching apps.
* **Native Quick-Control & Diagnostics Sheet (`QuickControlSheet`):** Compact draggable `AGY` edge pill opening a theme-aware sheet for live Root/Sui status + Re-check, one-tap Anti-Kill protection, Desktop Mode toggle, Core Restart, and Log Copy/Export.
* **Root Power-User CLI (`agy-sys`):** Bundled CLI helper for `info`, `protect` (Doze/OOM), `modules` (KernelSU/bakaSU/Magisk), `install` (`pm install -r`), `logcat`, and `android-data` (`-mm`).
* **Clickable Background Agent Notifications (`agent_notify.js` & `NotificationHandler`):** Bridges task-finished and action-required cues to high-priority clickable Android notifications when Antigravity is backgrounded; tapping jumps straight back to the app.

### Fixed
* **FileDownloader 64 MiB Static RAM Leak:** Spills pending blob downloads to cache temp files with automatic cleanup on cancel/complete/destroy (`FileDownloader.clearPendingTask()`).
* **Go CLI DNS Resolution on Android:** Exported `GODEBUG=netdns=cgo` in `ShellBridgeInstaller` (`BRIDGE_FORMAT=5`) so Go CLI tools installed via `pkg` (`gh`, `go`, `lazygit`) resolve Android DNS cleanly.

---

## [v2.34.12] - 2026-11-25

### Added
* **Native Root Bridge (`RootBridge` & `RootManagerInfo`):** Dynamic `uid=0` probing compatible with **KernelSU / bakaSU / KernelSU-Next / SukiSU**, **Magisk**, and **APatch**, with global mount namespace (`-mm`), gateway `root_status` endpoint, and `exec(root=true, mount_master=true, fallback_rish=true)`.
* **Smart Root CLI Wrappers (`agy-root`, `sudo`, `su`):** Installed in `$PREFIX/bin` and isolated from Bionic `LD_LIBRARY_PATH` in `ShellBridgeInstaller` (`BRIDGE_FORMAT=4`) so both Android system binaries (`pm`, `am`, `ksud`, `iptables`) and AGY tools (`python`, `git`, `pkg`) work seamlessly as root.
* **In-App Root / Sui Setup Banner (`RootNoticeBanner`):** Dismissible dark/light themed notice shown only when root is not granted, instructing KernelSU / bakaSU users to grant Superuser & restart and Sui module users to allow Antigravity in Sui / Shizuku & restart.

### Fixed
* **Git HTTPS TLS Certificates:** Exported `GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"` and `GIT_CONFIG_NOSYSTEM=1` across `CoreEnvironment`, `ShellBridgeInstaller`, and `ExecHandler`.
* **WebView Stream Auto-Recovery:** `NetworkMonitorHolder` wakes stalled WebView streams on network recovery (`agy:network-online`), paired with `AbortSignal`-aware stepped retry backoff in `web/src/core/fetch-rewrite.js`.

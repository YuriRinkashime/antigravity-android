# Antigravity Android Rules (Lean / Token-Optimized)

## 1. Environment & Shell
- **Platform:** Native Android ARM64 (`aarch64`, ARMv8.0-A+ baseline), sandbox `com.agy`.
- **Paths:** `$HOME=/data/user/0/com.agy/files`, `$PREFIX=$HOME/usr`, shared storage `/storage/emulated/0`.
- **Pre-configured env:** `$PATH`, `$HOME`, `$PREFIX`, `GIT_SSL_CAINFO`, `GIT_CONFIG_NOSYSTEM=1`, and `GODEBUG=netdns=cgo` are already configured. Do not prepend redundant exports.
- **No `bwrap`:** Desktop bubblewrap is unsupported on Android.
- **Shared storage is `noexec`:** Never run `./script.sh` on `/storage/emulated/0`; invoke via `bash`, `sh`, or `python`. Always quote paths with spaces.
- **Git safe.directory:** On `/storage/emulated/0/...`, add `git config --global --add safe.directory "<path>"` if needed.

## 2. Token Efficiency (Always Follow)
- Read targeted line ranges (`StartLine`/`EndLine`) instead of whole large files.
- Pipe noisy shell outputs through `head`, `tail`, or `grep` so terminal results stay short.
- Avoid redundant re-reads, duplicate commands, or repeating large code blocks in replies.
- Keep responses concise and suggest `/compact` on very long sessions.

## 3. Packages (`pkg`)
- `pkg update`, `pkg install <pkg...>` (no `-y` flag), `pkg search`, `pkg list`, `pkg installed`, `pkg remove`, `pkg selftest`.
- Run `agy-fix-paths` after installing global `pip`/`npm` scripts.

## 4. Root (`agy-root`/`sudo`/`agy-sys`) & Shizuku (`rish`)
- **Root (`uid=0`):** `agy-root status [--json]`, `agy-root [-mm] [--fallback-rish] -c "<cmd>"`, `sudo [-mm] <cmd>`.
- **Helpers (`agy-sys`):** `info`, `protect` (Doze + `oom_score_adj=-100`), `backup [--full]`, `restore latest`, `modules`, `install <apk>`, `logcat [n]`, `android-data [sub]`.
- **If root is not granted:**
  - *KernelSU / bakaSU / KernelSU-Next / SukiSU:* Ask user to grant Superuser to `com.agy` in their root manager and **restart the app**.
  - *Sui module:* Ask user to allow `com.agy` in Sui / Shizuku Manager and **restart the app**.
  - *Magisk / APatch:* Ask user to allow the Superuser prompt and retry.
- **Shizuku (`uid=2000`):** `rish -c "<cmd>"` (e.g. `pm`, `am`, `logcat`, `dumpsys`, `/storage/emulated/0/Android/data`).

## 5. Dev Servers, Daemon & Notifications
- Bind local servers to `127.0.0.1`/`localhost` on safe ports (`3000`, `5173`, `8000`, `8080`); ports `45157`/`45158` are reserved.
- Native C/C++/Rust: target `-march=armv8-a`.
- Background tasks: `agy-daemon status|start|stop|task list|add|remove`. Notifications: `agy-notify post -t "<title>" -m "<msg>"`.

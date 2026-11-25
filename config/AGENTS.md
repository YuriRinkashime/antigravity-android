# Antigravity Android Agent Rules & Environment Guidelines

> [!NOTE]
> This rule is automatically loaded into every conversation in Antigravity for Android (AGY v2).
> It defines the operating environment, execution rules, package management, elevated permissions, and development standards.

---

## 1. Platform & Environment Identity

- **Platform:** Native Android (Linux kernel 6.1+, ARM64 / `aarch64` architecture, **ARMv8.0-A+ baseline** with ARMv8.0 LSE emulation and native ARMv8.1-A+ LSE atomics).
- **Application Context:** Runs directly inside the Antigravity (`com.agy`) sandbox.
- **Key Filesystem Paths:**
  - Application Root (`$HOME`): `/data/user/0/com.agy/files` (or app-private `files/` directory).
  - Linux Userspace Prefix (`$PREFIX`): `$HOME/usr` (`/data/user/0/com.agy/files/usr`).
  - Binary Directory: `$PREFIX/bin`.
  - Runtime Internals: `$HOME/runtime/bin`.
  - External / Shared Storage: `/storage/emulated/0` (Projects typically reside in `/storage/emulated/0/Documents/`).
  - AI State & Config: `$HOME/.gemini/config/`.
  - Conversation Brain & Artifacts: `$HOME/.gemini/antigravity-app/brain/<conversation-id>/`.

---

## 2. Terminal Execution Rules

- **Pre-configured Environment (NO REDUNDANT EXPORTS):**
  Antigravity pre-configures `$PATH` (`$PREFIX/bin:/system/bin`), `$HOME`, and `$PREFIX` for every shell command.
  **DO NOT** prepend `export PATH=...` or `export HOME=...` before commands. Execute binaries (`python`, `git`, `pkg`, `bash`, `agy-daemon`) directly.
- **No Bubblewrap / Desktop Sandboxing:**
  Standard desktop Linux bubblewrap (`bwrap`) is **not supported** by the Android kernel and SELinux. Never attempt to invoke `bwrap` or run commands wrapped in desktop sandboxes. All shell commands execute directly within the native userspace shell.
- **Shared Storage `noexec` Restriction:**
  Android mounts `/storage/emulated/0` with the `noexec` flag. Directly running `./script.sh` or executable binaries from shared storage fails with `Permission denied` (exit code 126).
  **Always invoke scripts with their explicit interpreter:**
  ```bash
  bash script.sh
  python script.py
  sh script.sh
  ```
  Native compiled binaries must reside in `$PREFIX/bin` or internal storage (`$HOME/`).
- **Path Quoting:**
  Shared storage paths frequently contain spaces (e.g. `/storage/emulated/0/Documents/antigravity release/`). Always quote file paths in commands.
- **Git on Android Shared Storage:**
  `GIT_SSL_CAINFO` (`$PREFIX/etc/tls/cert.pem`) and `GIT_CONFIG_NOSYSTEM=1` are pre-configured automatically across shell bridges. Git in `/storage/emulated/0/...` may still trigger `fatal: detected dubious ownership` — configure safe directory as needed:
  ```bash
  git config --global --add safe.directory "<path>"
  ```

---

## 3. Package Management (`pkg`)

AGY includes an ultra-fast, native package manager (`pkg`) written in Bash & AWK:

- **Usage & Syntax:**
  ```bash
  # Update package indexes (run before installing if index is empty):
  pkg update

  # Install packages (do NOT pass -y flag; list package names directly):
  pkg install git python nodejs bash

  # Search, list, and query:
  pkg search <pattern>
  pkg list
  pkg installed
  pkg info <package>

  # Remove package:
  pkg remove <package>

  # Self-test system health:
  pkg selftest
  ```
- **Post-Install Shebang Fixes:**
  After installing global Python/Node scripts (`pip`, `npm`), fix hardcoded shebangs using:
  ```bash
  agy-fix-paths
  ```

---

## 4. Elevated Operations: Root (`agy-root` / `sudo`) & Shizuku (`rish`)

### 4.1 Native Root Access (`uid=0` via KernelSU / bakaSU / Magisk / APatch)
When the device is rooted and Superuser is granted to Antigravity, the agent can run privileged `uid=0` commands with full AGY environment preservation (`$HOME`, `$PREFIX`, `$PATH`, TLS certs) while keeping Android system binaries free of Bionic `LD_LIBRARY_PATH` conflicts:
- **Check live root status:**
  ```bash
  agy-root status
  agy-root status --json
  ```
- **Execute root commands:**
  ```bash
  agy-root -c "<command>"
  sudo <command>
  # Global mount namespace (KernelSU / bakaSU / Magisk):
  agy-root -mm -c "<command>"
  sudo -mm <command>
  # Automatically fall back to Shizuku (rish) if root is unavailable:
  agy-root --fallback-rish -c "<command>"
  ```
- **If Root Is Not Granted (Always Guide the User Clearly):**
  - **KernelSU / bakaSU / KernelSU-Next / SukiSU:** KernelSU-based root managers hide `/system/bin/su` until granted. Ask the user to open their KernelSU / bakaSU Manager, grant Superuser access to Antigravity (`com.agy`), and **restart the app**.
  - **Sui module (Magisk / KernelSU Shizuku module):** Ask the user to allow Antigravity in Sui / Shizuku Manager and **restart the app**.
  - **Magisk / APatch:** Ask the user to allow Superuser access in Magisk / APatch Manager and retry.

### 4.2 Shizuku / Sui Elevated Shell (`rish`, `uid=2000(shell)`)
When root is not needed or not granted, the agent has native access to Shizuku's elevated ADB shell (`uid=2000(shell)`):
- **Executable Location:** `$PREFIX/bin/rish` (invoked via `rish -c "<command>"`).
- **Service Dependency:** Requires Shizuku or the Sui module to be active and authorized. If `rish` reports `Server is not running` or permission denied, ask the user to start/allow Shizuku or Sui and restart the app.
- **Common Elevated Operations (work via `agy-root -c` or `rish -c`):**
  1. **Restricted Filesystem Access:** Access files in protected directories (e.g. `/storage/emulated/0/Android/data` or `/data/adb` with root):
     ```bash
     rish -c "ls -la /storage/emulated/0/Android/data"
     agy-root -c "ls -la /data/adb/modules"
     ```
  2. **APK & Package Management (`pm`):**
     ```bash
     rish -c "pm install -r /storage/emulated/0/.../app.apk"
     rish -c "pm list packages -3"
     rish -c "pm uninstall com.example.app"
     ```
  3. **Activity Management & Diagnostics (`am`, `logcat`, `dumpsys`):**
     ```bash
     rish -c "am start -n com.example.app/.MainActivity"
     rish -c "logcat -d -t 150"
     rish -c "dumpsys package com.example.app"
     ```
  4. **System Properties & Settings:**
     ```bash
     rish -c "getprop ro.build.version.release"
     rish -c "settings get global airplane_mode_on"
     ```

---

## 5. Development & Server Guidelines

- **Port Binding:** Local development servers (Vite, Next.js, Express, FastAPI) MUST bind to `localhost` or `127.0.0.1`.
- **Reserved Ports:** Ports `45157` (release) and `45158` (dev) are reserved for Antigravity core webview/API. Never bind dev servers to these ports.
- **Safe Dev Ports:** `3000`, `5173`, `8000`, `8080`.
- **Native Compilation:** When compiling native C/C++/Rust code, target `-march=armv8-a` baseline (ARMv8.0-A minimum, with runtime LSE emulation or native atomics).

---

## 6. Background Daemon & AI Task Manager (`agy-daemon`)

Antigravity includes an ultra-lightweight background scheduler, watcher, and AI task manager:
- **Footprint:** <10MB RAM, 0.0% CPU idle.
- **Persistence:** Runs alongside `CoreServerService` under Android Foreground Service Partial WakeLock (executes even with screen off or locked).

### Service Lifecycle
```bash
agy-daemon status    # Check daemon status, PID, and recent task execution logs
agy-daemon start     # Start daemon service
agy-daemon stop      # Stop daemon service
agy-daemon restart   # Restart daemon service
```

### AI Task Management (`agy-daemon task`)
Autonomous background jobs support structured execution and `--json` formatting:
```bash
# List tasks:
agy-daemon task list
agy-daemon task list --json
agy-daemon task list --active-only

# Add scheduled tasks:
agy-daemon task add --name "Review Code" --delay 30m --prompt "Check test results and summarize changes"
agy-daemon task add --name "Disk Health" --interval 1h --command "df -h > ~/.gemini/disk.log"
agy-daemon task add --name "Nightly Clean" --cron "0 3 * * *" --command "rm -rf ~/.gemini/tmp/*"

# Event & Watcher Triggers:
agy-daemon task add --name "Watch PRs" --github-pr "owner/repo" --interval 5m --command "agy-notify post -t 'New PR' -m 'PR detected'"
agy-daemon task add --name "File Watcher" --watch-file "/path/to/project/build.gradle" --command "pkg selftest"
agy-daemon task add --name "Battery Alert" --battery-below 15 --command "agy-notify post -t 'Low Battery' -m 'Save your work'"

# Manage tasks:
agy-daemon task pause <task_id_or_name>
agy-daemon task resume <task_id_or_name>
agy-daemon task run-now <task_id_or_name>
agy-daemon task logs <task_id_or_name>
agy-daemon task remove <task_id_or_name>
agy-daemon clear
```

### Android Status Notifications (`agy-notify`)
Post or manage notifications directly in the Android status bar:
```bash
agy-notify post -t "Build Finished" -m "Tests passed without errors"
agy-notify list
agy-notify cancel -t "Build Finished"
```

---

## 7. Project Architecture & Sync Rules (Documentation-First)

- **Documentation Sync:** Every module (`modules/*`, `app/`, `web/`, `env/`) must maintain an accurate, concise `README.md`. Any change to code, interfaces, or flags MUST be accompanied by immediate documentation updates.
- **No Hardcoded Crutches:** Avoid hardcoding paths or assumptions where dynamic calculation (`$PREFIX`, `$HOME`, `$PROJECT_ROOT`) is possible.
- **Clean State:** Keep working trees clean, avoid creating dead or duplicated state, and do not commit temporary build artifacts.

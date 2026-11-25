# Antigravity for Android v2.34.14

## Portable Chat & Settings Backup / Restore (`StateBackupManager` & `agy-sys`)

* **1-Tap Backup & Restore in `QuickControlSheet`:** Export all local chats (`conversations/`), summaries DB, brain artifacts, and rules (`AGENTS.md`) as a portable `.zip` archive in `Download/Antigravity-Backups/` that survives app uninstalls, Clear Data, and custom ROM flashes.
* **Cross-Compatible CLI (`agy-sys backup` & `agy-sys restore latest`):** Uses Python `zipfile` (100% compatible with the in-app Restore button and safe against path traversal).

---

# Antigravity for Android v2.34.13

## Phase 2 Highlights (Anti-Kill Resilience, Quick-Control Sheet, `agy-sys` & Completion Notifications)

* **Crash & Anti-Kill Resilience (`RootResilience` & `FileDownloader`):**
  * Automatically whitelists `com.agy` in Android Doze and gently lowers `oom_score_adj` (`-100`) for Antigravity and `language_server` when root is granted, preventing aggressive LMKD kills when switching apps.
  * Fixed the static 64 MiB Base64 RAM leak in `FileDownloader` by spilling pending blob downloads to cache temp files with automatic cleanup on cancel/destroy.
  * Exported `GODEBUG=netdns=cgo` inside `ShellBridgeInstaller` (`BRIDGE_FORMAT=5`) so Go CLI tools installed via `pkg` (`gh`, `go`, `lazygit`) resolve Android DNS cleanly.
* **Native Quick-Control & Diagnostics Sheet (`QuickControlSheet`):** Compact draggable `AGY` edge pill opening a theme-aware control sheet for live Root/Sui status + Re-check, one-tap Anti-Kill protection, Desktop Mode toggle, Core Restart, and Log Copy/Export.
* **Root Power-User CLI (`agy-sys`):** Bundled helper for `info`, `protect` (Doze/OOM), `modules` (KernelSU/bakaSU/Magisk), `install` (`pm install -r`), `logcat`, and `android-data` (`-mm`).
* **Clickable Background Agent Notifications (`agent_notify.js` + `NotificationHandler`):** Bridges task-finished and action-required cues to high-priority clickable Android notifications when Antigravity is backgrounded; tapping jumps straight back to the app.

---

# Antigravity for Android v2.34.12

## Release Highlights (Root Support, Git TLS Fix & Stream Recovery)

* **Native Root Bridge (`RootBridge` & `RootManagerInfo`):** Dynamic `uid=0` probing compatible with **KernelSU / bakaSU / KernelSU-Next / SukiSU**, **Magisk**, and **APatch**, with global mount namespace (`-mm`), gateway `root_status` endpoint, and `exec(root=true, mount_master=true, fallback_rish=true)`.
* **Smart CLI Wrappers (`agy-root`, `sudo`, `su`):** Installed in `$PREFIX/bin` and isolated from Bionic `LD_LIBRARY_PATH` in `ShellBridgeInstaller` (`BRIDGE_FORMAT=4`) so Android system tools (`pm`, `am`, `ksud`, `iptables`) and AGY tools (`python`, `git`, `pkg`) both work seamlessly as root.
* **In-App Root / Sui Guidance (`RootNoticeBanner`):** Dismissible dark/light themed banner shown only when root is not granted, instructing KernelSU / bakaSU users to grant Superuser & restart and Sui module users to allow Antigravity in Sui / Shizuku & restart.
* **Git HTTPS TLS Fix:** Exported `GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"` and `GIT_CONFIG_NOSYSTEM=1` across `CoreEnvironment`, `ShellBridgeInstaller`, and `ExecHandler`.
* **Stream Auto-Recovery:** `NetworkMonitorHolder` wakes the WebView on network recovery (`agy:network-online`), paired with `AbortSignal`-aware retry backoff in `web/src/core/fetch-rewrite.js`.

---

# Antigravity for Android v2.33.0

## Спецификация пакетов релиза

| Файл пакета | Идентификатор приложения | Порт рантайма | Описание |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Стандартная редакция с оригинальными проверками Google (чистая версия без модификаций кода аутентификации). |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Редакция с обходом проверки учетных записей (позволяет авторизоваться под аккаунтами, блокируемыми со стороны Google; требует наличия сетевого туннеля/VPN). |
| **`SHA256SUMS.txt`** | — | — | Криптографические контрольные суммы бинарных файлов. |

---

## Изменения и исправления (v2.33.0)

### 1. Синхронизация с официальным релизом Google Antigravity v2.33.0
* Синхронизированы версия приложения (`APP_VERSION_NAME=2.33.0`) и версия ядра (`CORE_VERSION=2.33.0`).
* Обновлен конвейер бинарных патчей (`modules/patches`): добавлена поддержка унифицированных сигнатур перехвата `sigill_handler` и `resolv_redirect` для совместимости со свежими сборками Go runtime.
* Исправлены сигнатуры `crash_suppressor` (`failfast_cpu_and_atomic`) для Google Antigravity v2.33.0: устранен сбой `FATAL ERROR: This binary was compiled with lse enabled` и аварийное завершение с кодом 132 (SIGILL) на процессорах ARMv8.0-A (Cortex-A53, Helio G25/A22, Snapdragon 685).
* Пройдена полная модульная верификация всех подсистем (`core`, `patches`, `io`, `runner`, `env`, `builder`).

---

# Antigravity for Android v2.4.1

## Спецификация пакетов релиза

| Файл пакета | Идентификатор приложения | Порт рантайма | Описание |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Стандартная редакция с оригинальными проверками Google (чистая версия без модификаций кода аутентификации). |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Редакция с обходом проверки учетных записей (позволяет авторизоваться под аккаунтами, блокируемыми со стороны Google; требует наличия сетевого туннеля/VPN). |
| **`SHA256SUMS.txt`** | — | — | Криптографические контрольные суммы бинарных файлов. |

---

## Изменения и исправления (v2.4.1)

### 1. Поддержка встроенного веб-терминала и спавнера PTY (PR #19)
* **Низкоуровневый бинарный патч PTY-шеллы:** Устранено аварийное завершение терминала из-за отсутствия `/bin/bash` на Android. Реализован модульный бинарный патч с сохранением длины строки (`/bin/bash` ➔ `/bin///sh`, ровно 9 байт), безопасно перенаправляющий запросы спавнера ядра в системный шелл Android без сдвига смещений ELF-секций.
* **Приоритетная иерархия `$SHELL` в супервизоре процесса (`CoreEnvironment`):** Вместо жестко заданного `runtime/bin/bash` выстроена отказоустойчивая цепочка разрешения шелла:
  1. `$PREFIX/bin/bash` (полноценный bash окружения пользователя со всей поддержкой `.bashrc`, автодополнения и истории);
  2. `runtime/bin/bash` (встроенный bridge-рантайм);
  3. `/system/bin/sh` (гарантированный системный шелл Android).
* **Эмуляция терминала и база `terminfo`:** В окружение процесса добавлен экспорт `TERM=xterm-256color` и автоматическое подключение каталога `TERMINFO` из `$PREFIX/share/terminfo`, что предотвращает артефакты и искажения цветов и управляющих последовательностей ANSI в интерактивных консольных программах (`nano`, `htop`, mc).

### 2. Повышение стабильности локальной сборки пакетов (AAPT)
* В этапы сборки ресурсов `modules/builder/02_r_java.sh` и `modules/builder/06_package.sh` добавлен экспорт `export ANDROID_DATA="${ANDROID_DATA:-/data}"`. Это устраняет сбои компилятора `aapt` с кодом `SIGABRT (134)` при сборке APK непосредственно внутри ARM64 Android userspace.

---

## Ранее реализованные ключевые возможности (v2.4.0)

### 1. Безопасность и изоляция данных (Issue #6)
* Из релизных сборок полностью удален атрибут `android:debuggable="true"`.
* Устранена уязвимость, позволявшая локальному пользователю или стороннему приложению через `run-as com.agy` считывать OAuth-токены сессий и внутренние файлы конфигурации.
* Флаг отладки изолирован исключительно в dev-сборке (`com.agy.dev`).

### 2. Системный диалог выбора файлов (Issue #8)
* Устаревший ограниченный интерфейс Storage Access Framework (SAF) заменен на нативный системный Intent-селектор Android.
* Обеспечен прямой доступ к файлам, изображениям, видео и архивам из Галереи, Google Фото и любых сторонних файловых менеджеров.
* Реализовано автоматическое сохранение постоянных разрешений на чтение (`Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION`).

### 3. Совместимость с процессорами архитектуры ARMv8.0-A
* Добавлена библиотека динамической эмуляции атомарных инструкций `liblse_compat.so` и низкоуровневый перехватчик `sigill_handler`.
* Устранены аварийные завершения с кодом `SIGILL: illegal instruction` на устройствах с чипсетами Qualcomm Snapdragon 680/685 (Cortex-A53 / Redmi Note 12).

### 4. Оптимизация авторизации OAuth
* Полностью устранен дисковый опрос (polling с интервалом 80 мс) и создание промежуточных файлов при входе в учетную запись.
* Захват URL авторизации переведен на прямой перехват стандартного потока вывода ядра (stdout) с мгновенной отправкой Intent в системный браузер.

### 5. Аппаратный интерфейс фонарика
* Добавлена консольная утилита управления вспышкой камеры: `torch on` / `torch off`.

### 6. Фоновый планировщик задач (`agy-daemon`)
* Реализован системный микро-демон (<10 МБ RAM, 0% CPU в режиме ожидания) для выполнения отложенных и периодических задач по расписанию (cron, интервалы).
* Добавлена поддержка отслеживания изменений файлов и отправка нативных уведомлений в панель Android через `agy-notify`.

### 7. Диагностика запуска ядра
* В интерфейс ошибки инициализации сервера добавлены действия «Copy Logs» и «Save Logs» для быстрого экспорта журнала сбоя в буфер обмена или файл.

### 8. Модульная структура конвейера сборки
* Исходный код разнесен по изолированным модулям: `modules/patches`, `modules/runner`, `modules/builder`.
* Внедрена аргументная передача параметров компиляторам `@java_sources.txt` и `@class_files.txt`, исключающая ошибку переполнения буфера командной строки ядра Linux (`ARG_MAX` / `E2BIG`).

---

## Обновление и совместимость

Все сборки подписаны постоянным ключом проекта. Обновление устанавливается поверх предыдущих версий 2.x без удаления приложения. Пользовательские проекты в каталоге `Documents`, рабочее окружение и сессии полностью сохраняются.

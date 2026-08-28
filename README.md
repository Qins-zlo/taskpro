> **[English](README.md) | [中文](README.zh.md)**

# TaskPro

> An Android **task scheduler / script execution engine** with built-in Python 3.14 + Node.js + Shell runtime. No root required, no extra interpreters to install.

![Android](https://img.shields.io/badge/Platform-Android-3DDC84) ![Python](https://img.shields.io/badge/Runtime-Python%203.14-3776AB) ![Node](https://img.shields.io/badge/Runtime-Node.js-339933) ![Version](https://img.shields.io/badge/Version-7.66-blue)

---

## Features

### Task Scheduling
- **cron expression** based HTTP request / Shell command execution
- Quick schedule presets (every N minutes/hours/days)
- Driven by Android `AlarmManager` -- triggers even when the app is killed
- Push notifications on execution results (success can be silent, failure always notifies)

### Script Execution
- **Multi-language runtime**: Python 3.14 + Node.js + Shell (BusyBox)
- Built-in **cURL** with HTTPS/JSON/Cookie support
- **Variable system**: declare variables in comments -> auto-injected as environment variables
- Sensitive info auto-masked (`TOKEN` / `PASSWORD` / `COOKIE` / `SECRET` / `APIKEY`)
- **Script marketplace**: browse/install/update scripts from the GitHub script repo

### Immersive Terminal
- Interactive shell with Termux-style extra-keys keyboard
- Quick commands / font size adjustment / interrupt running process

### AI Assistant
- OpenAI-compatible API (DeepSeek / Qwen / GPT and more)
- Optional web search capability

### More
- Environment variable store (independently managed, injected into scripts)
- Artifacts manager (downloaded/generated files)
- Data backup/restore, one-click import/export tasks/scripts/logs
- Execution statistics, dark/light theme, custom accent color

---

## Project Structure

```
taskpro/
├── src/io/taskpro/          # Java source code
│   ├── MainActivity.java    # Basic mode main UI
│   ├── AdvActivity.java     # Advanced mode (scripts/artifacts/logs/terminal/env)
│   ├── AIActivity.java      # AI Assistant session
│   ├── TaskEngine.java      # Task execution engine
│   ├── RuntimeManager.java  # Runtime management (command rewriting, PATH/LD_LIBRARY_PATH injection)
│   ├── TerminalView.java    # Terminal emulator
│   ├── md/                  # Material 3 pure-code components (no XML dependencies)
│   └── ... (TaskStore/AlarmScheduler/CronParser/ScriptStore etc.)
├── lib/arm64-v8a/           # APK bundled native runtime (required for build)
│   ├── node                 # Node.js (aarch64-android, ~49MB)
│   ├── libpython3.14.so     # Python 3.14 core library
│   ├── *.cpython-314-aarch64-linux-android.so  # Python C extensions
│   ├── busybox / libcurl    # Shell tools + curl
│   └── libssl/libicu/libsqlite3/...  # Dependent dynamic libraries
├── assets/                  # APK resources (required for build)
│   ├── termux_lib.tar.gz    # Python 3.14 pure Python stdlib + pip
│   ├── termux_pkgs.tar.gz   # requests/certifi and other third-party packages
│   ├── termux_ca.pem        # CA root certificates
│   └── icons.ttf            # Material icon font
├── res/                     # Resources (light + dark theme)
├── AndroidManifest.xml
├── build.sh                 # One-click build script
└── mkicon.py
```

---

## Building

```sh
# Dependencies: JDK 8+ / Android SDK toolchain
# (aapt2 / d8 / apksigner / zipalign / platform-34 android.jar)
sh build.sh
# Output: out/taskpro.apk
```

> All runtimes are bundled in the repository -- **no internet download required** to build a fully functional APK.
>
> WARNING: A `taskrun.keystore` signing key will be generated/used during build. **This file is NOT committed** (excluded via `.gitignore`). Keep your own signing key secure.

---

## How the Runtime Works

Android ROMs often mount app data directories as `noexec`, making it impossible to run Python/Node interpreters from `files/`.

This project packages all executables into the APK's `lib/arm64-v8a/`. Android extracts them to `nativeLibraryDir` (which is always executable) during installation:

1. `RuntimeManager.buildCommand()` injects `PATH` / `LD_LIBRARY_PATH` / `PYTHONHOME` / `PYTHONPATH`
2. `rewriteCommand()` rewrites `python3 / pip / node / busybox / curl` to full nativeLibraryDir paths
3. Pure `.py` stdlib is extracted from assets to `files/termux/usr` (writable, no execution needed)
4. Shell wrappers are generated pointing to the real binaries

Removing `lib/` or `assets/` will **break python/js script execution** in the built APK.

---

## Disclaimer

This software is provided for **learning, research, and legitimate automation purposes only**. Do not use it for any illegal activities, violations of platform terms, or infringement of others' rights. Users assume all responsibility for their use of this software.

---

## License

**Non-Commercial License** -- Free for personal/educational use. **Commercial use is prohibited (except Author).**

- [OK] Personal learning, research, self-use -> Free
- [OK] Non-commercial distribution/modification -> Free, must retain copyright
- [NO] **Commercial use (selling, integrating into paid products, internal company operations, etc.) requires authorization from the Author**
- [OK] Author (Qins-zlo) is exempt from this restriction

See [LICENSE](./LICENSE) for full terms.

---

## Support

If you find this project helpful, feel free to Star / Fork. Built with pure Java programmatic UI, running in Sandbox environment. Welcome to discuss technical questions.

**Contact**: https://github.com/Qins-zlo
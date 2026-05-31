# Norm2Hacked

Reverse-engineering workspace for the **Norm 2 smartwatch** — a custom Android companion
app plus a Windows command-line tool, both built on a shared BLE protocol library.

| Module      | What it is                                                                              |
|-------------|-----------------------------------------------------------------------------------------|
| `:protocol` | Pure-JVM library: packet framing, command codes, response parsing (shared by app + CLI) |
| `:app`      | Android companion app (Kotlin + Jetpack Compose)                                        |
| `:cli`      | `normlink-cli` — Windows tool for testing BLE protocol comms with the watch             |

**Watch MAC (example used throughout):** `4C:59:80:12:44:F1`

---

## Prerequisites

- **JDK 17** (the Gradle toolchain targets JVM 17).
- **For the CLI only:** Python 3 with **bleak** — the CLI drives the watch's BLE through a
  bundled Python backend:
  ```powershell
  pip install bleak
  ```
- **For the app only:** an Android device (minSdk 30) with USB debugging, or Android Studio.

> Commands below use PowerShell syntax (`.\gradlew`). On macOS/Linux use `./gradlew`.

---

## normlink-cli (the test tool)

Talks to the physical watch over BLE from Windows. All protocol logic runs in Kotlin
(`:protocol`); a child Python (bleak) process handles the raw BLE link and the one-time
Bluetooth bonding.

### Build

```powershell
.\gradlew :cli:installDist
```

This produces a runnable distribution (with the Python backend bundled) at:

```
cli\build\install\normlink-cli\bin\normlink-cli.bat
```

### Run

```powershell
$cli = ".\cli\build\install\normlink-cli\bin\normlink-cli.bat"

& $cli battery     --mac 4C:59:80:12:44:F1
& $cli version     --mac 4C:59:80:12:44:F1
& $cli sync-count  --mac 4C:59:80:12:44:F1
& $cli brightness  --mac 4C:59:80:12:44:F1            # query
& $cli brightness  --mac 4C:59:80:12:44:F1 --set 70   # set 0-100
& $cli dnd         --mac 4C:59:80:12:44:F1
& $cli switch      --mac 4C:59:80:12:44:F1
& $cli watch       --mac 4C:59:80:12:44:F1 --duration 10   # listen passively

# Send an arbitrary command (hex bytes):
& $cli raw --mac 4C:59:80:12:44:F1 --cmd-byte 08 --action-byte 70 --payload 00
```

### Commands

| Command      | Description                                        | Key options                                          |
|--------------|----------------------------------------------------|------------------------------------------------------|
| `battery`    | Battery % and charging state                       |                                                      |
| `version`    | Firmware version string                            | `--type <n>` (default 6)                             |
| `sync-count` | Sport / sleep / heart-rate record counts           |                                                      |
| `brightness` | Query or set screen brightness                     | `--set 0-100`                                        |
| `dnd`        | Do-not-disturb settings                            |                                                      |
| `switch`     | Switch-settings bitmask (raise-wake, auto-sync, …) |                                                      |
| `raw`        | Send a raw command and print the response          | `--cmd-byte HH --action-byte HH [--payload "HH HH"]` |
| `watch`      | Passively listen for notifications                 | `--duration <sec>` (default 10)                      |

**Every command supports:**
`--mac <MAC>` (required) · `--json` (machine-readable output) · `--timeout <ms>` (default 10000).

### Notes

- **First run bonds the watch automatically** (a one-time "Just Works" pairing). Keep the
  watch awake and nearby for the first connect.
- The bundled Python prints its logs to stderr, prefixed `[bridge]`. Errors are reported as
  a single `error: …` line with a non-zero exit code (the BLE process is isolated from the JVM).
- **Overrides** (optional env vars): `NORMLINK_PYTHON` (interpreter, default `python`),
  `NORMLINK_PYTHON_DIR` (path to the `norm2_probe` package, for dev runs outside the dist).

---

## Android app

Clean architecture (UI → Domain → Data) with BLE as a separate infrastructure layer.
compileSdk 35 · minSdk 30 · JVM 17.

### Build / install / run

```powershell
.\gradlew assembleDebug          # build the debug APK
.\gradlew installDebug           # build + install on a connected device

.\gradlew lint                   # run Android lint
```

Before launching our app the original normconnect app should be disabled:
```
adb shell pm disable-user --user 0 com.normconnectappv2.watch
```

The APK lands at `app\build\outputs\apk\debug\app-debug.apk`. Launch it from the device, or
open the project in Android Studio and Run.

---

## Tests

```powershell
.\gradlew :protocol:test                                  # all protocol unit tests
.\gradlew :protocol:test --tests "com.norm2hacked.protocol.PacketTest"   # a single class
```

`:protocol` is pure JVM (no Android/BLE), so its tests run fast with no device.

---

## Working with the original APK (apktool)

The decompiled companion app (the source of truth for the protocol) lives in `NORM/`.

```powershell
# Decompile (already done — output is NORM/)
java -jar bin\apktool_3.0.2.jar d bin\NORM.apk -o NORM

# Recompile after editing smali (produces NORM\dist\NORM.apk)
java -jar bin\apktool_3.0.2.jar b NORM
```

---

## Quick reference

```powershell
.\gradlew :cli:installDist        # build the CLI tool
.\gradlew installDebug            # build + install the Android app
.\gradlew :protocol:test          # run protocol tests
.\gradlew lint                    # lint the app

# run the CLI
cli\build\install\normlink-cli\bin\normlink-cli.bat battery --mac 4C:59:80:12:44:F1
```

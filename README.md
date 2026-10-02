# Norm2Hacked

Reverse-engineering workspace for the **Norm 2 smartwatch** — a custom Android companion
app built on a BLE protocol library, and an emulator that runs the watch's own firmware on
the PC.

| Module            | What it is                                                                     |
|-------------------|--------------------------------------------------------------------------------|
| `:protocol`       | Pure-JVM library: packet framing, command codes, response parsing              |
| `:app`            | Android companion app (Kotlin + Jetpack Compose)                               |
| `tools/watchemu`  | The watch emulator (Python): the real firmware under Unicorn, a radio, a phone |

Reference documentation is in `docs/`: `app.md` (the app's architecture and what is
verified on the watch), `protocol.md` (the wire protocol and firmware updates),
`firmware.md` (the watch's firmware), `watch-emulator.md`, and `history.md`.

**Watch MAC (example used throughout):** `4C:59:80:12:44:F1`

---

## Prerequisites

- **JDK 17** (the Gradle toolchain targets JVM 17).
- **For the app:** an Android device (minSdk 30) with USB debugging, or the Pixel 8 AVD
  in `tools/emulator/`.
- **For `normwatch`:** Python 3.11 and `py -3.11 -m pip install -r tools/watchemu/requirements.txt`.

> Commands below use PowerShell syntax (`.\gradlew`). On macOS/Linux use `./gradlew`.

---

## Asking a watch a question from the PC (`normwatch cmd`)

One 0x6F command, sent the way the companion app sends it (to 8001, then `[03]` to 8002),
with every reply decoded. By default it asks the **emulated** watch — the firmware boots on
the PC, a virtual phone pairs with it; with `--mac` it asks the **physical** one over this
PC's own Bluetooth adapter. Same flow, same decoder, so where the answers differ it is the
watches that differ.

```powershell
$env:PYTHONPATH = "tools/watchemu"
py -3.11 -m normwatch cmd BATTERY_POWER CHECK --payload 00 --mac 4C:59:80:12:44:F1   # the real watch
py -3.11 -m normwatch cmd BATTERY_POWER CHECK --payload 00 --flash-state bound.zip   # the emulated one
py -3.11 -m normwatch cmd 07 71 --payload 3c --mac 4C:59:80:12:44:F1                 # a SET, in hex
```

- Commands and actions by `:protocol` name (`DEVICE_VERSION`, `CHECK`) or in hex. A CHECK
  needs its one payload byte (`--payload 00`); the watch ignores one without it.
- It pairs, reads checkInit, and binds only a watch that says it is still in first-run
  setup — as `BindWatchUseCase` does. `--no-bind` skips both.
- **The physical watch:** the first run bonds it with Windows (Just Works, no PIN; keep it
  awake and on its face). It must not be connected to a phone — the watch keeps one
  connection, so close the companion app or `:app` first.
- Exit status: 0 a reply came, 1 nothing came back, 2 the setup failed.

`tools/watchemu/README.md` has the rest, including `--flash-state`, which keeps an emulated
watch bound between runs. (This replaced `normlink-cli`, the `:cli` module, which wrote to
8003 — where the firmware never acknowledges a SET.)

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
.\gradlew installDebug            # build + install the Android app
.\gradlew :protocol:test          # run protocol tests
.\gradlew lint                    # lint the app

# ask the physical watch its battery level
$env:PYTHONPATH = "tools/watchemu"; py -3.11 -m normwatch cmd 08 70 --payload 00 --mac 4C:59:80:12:44:F1
```

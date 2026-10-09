# Norm+

Reverse-engineering workspace for the **Norm 2 smartwatch** — a custom Android companion
app built on a BLE protocol library, and an emulator that runs the watch's own firmware on
the PC.

| Module            | What it is                                                                     |
|-------------------|--------------------------------------------------------------------------------|
| `:protocol`       | Pure-JVM library: packet framing, command codes, response parsing              |
| `:app`            | Android companion app (Kotlin + Jetpack Compose)                               |
| `tools/normplus` | The Python tools, one uv package: the watch emulator, the Android emulator launcher, firmware tools |

Reference documentation is in `docs/`: `app.md` (the app's architecture and what is
verified on the watch), `protocol.md` (the wire protocol and firmware updates),
`firmware.md` (the watch's firmware), `watch-emulator.md` and `watch-emulator-internals.md`
(the watch emulator), `android-emulator.md` (the Android emulator), and `history.md`.

**Watch MAC (example used throughout):** `4C:59:80:12:44:F1`

---

## Getting the code

```powershell
git config --global core.longpaths true      # once per Windows machine: NORM/ has 184-character paths
git clone --recurse-submodules https://github.com/David-Aharoni01/NormPlus.git
```

`NORM/` is a **private** submodule: the official NORM app unpacked with apktool, which the
protocol work reads and the watch emulator runs. It is the vendor's material, so it is not in
this public repository. Without access to it, unpack your own copy of the app into `NORM/`
(`java -jar apktool_3.0.2.jar d NORM.apk -o NORM`); the Android app builds without it, minus
the bundled resource image.

## Prerequisites

- **JDK 17** (the Gradle toolchain targets JVM 17).
- **For the app:** an Android device (minSdk 30) with USB debugging, or the Pixel 8 AVD
  (`normphone setup`, below).
- **For the tools:** [uv](https://docs.astral.sh/uv/). It fetches Python 3.11 and every
  library itself.

> Commands below use PowerShell syntax (`.\gradlew`). On macOS/Linux use `./gradlew`.

---

## The tools

One install, from the repository root, puts seven commands on your PATH. They stay linked to
this checkout, so pulling new code updates them; after a pull that changes `pyproject.toml`,
run the install again.

```powershell
uv tool install --editable .
```

| Command | What it does | Try |
|---|---|---|
| `normwatch` | Runs the watch's own firmware on the PC | `normwatch boot --live` (a window with the watch's screen) |
| `normcmd` | Asks a watch one command and decodes the reply | `normcmd BATTERY_POWER CHECK --mac` |
| `normphone` | The Android emulator that runs the app | `normphone start`, `normphone start --watch`, `normphone install` |
| `normfw` | Checks, re-seals and patches firmware images | `normfw verify NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin` |
| `normtest` | Runs the Python tests | `normtest`, or `normtest ota` |
| `normboard` | The task board: GitHub issues on the NormPlus project | `normboard`, `normboard show 13` |
| `normhelp` | What every command is for, and the common recipes | `normhelp`, `normhelp phone start` |

Every command takes `--help`, and `normhelp` explains them all. Without installing, `uv run <command>` works from inside the
repository.

**Run the app against the emulated watch, no hardware at all:**

```powershell
normwatch boot --phone --live --flash-state watch.zip   # 1. the watch (keep it running)
normphone start --watch                                  # 2. the phone, in a second terminal
normphone install                                        # 3. the app, in a third
```

If the phone held a pairing with a different watch, `normphone clear-bond` forgets it; then
tap *Pair* when Android asks.

**Give the emulated watch something to sync.** A fresh one has no history. This has its own
firmware write a day of half-hourly sport records (about 2 minutes; `--count 200` for four
days) into the state file, then the recipe above with the same file syncs them. With
`--heart-rate N` it also takes N heart-rate readings: a pulse goes under the emulated sensor
and the firmware measures it, about a minute and a half each. With `--sleep N` it records a
sleep session on each of the last N nights, about six minutes each (a few minutes of sleep,
not a whole night).

```powershell
normwatch records --flash-state watch.zip --count 48
normwatch records --flash-state watch.zip --count 0 --heart-rate 5 --sleep 2
```

## Asking a watch a question from the PC (`normcmd`)

One 0x6F command, sent the way the companion app sends it (to 8001, then `[03]` to 8002),
with every reply decoded. By default it asks the **emulated** watch — the firmware boots on
the PC, a virtual phone pairs with it; with `--mac` it asks the **physical** one over this
PC's own Bluetooth adapter. Same flow, same decoder, so where the answers differ it is the
watches that differ.

```powershell
normcmd BATTERY_POWER CHECK --mac                    # the real watch
normcmd BATTERY_POWER CHECK --flash-state bound.zip  # the emulated one
normcmd 07 71 --payload 3c --mac                     # a SET, in hex
```

- Commands and actions by `:protocol` name (`DEVICE_VERSION`, `CHECK`) or in hex. A CHECK
  needs its one payload byte -- the watch ignores one without it -- so it sends `00` unless
  given another (`--payload 06`).
- `--mac` alone is the physical watch, `4C:59:80:12:44:F1`; `--mac ADDRESS` is another one.
- It pairs, reads checkInit, and binds only a watch that says it is still in first-run
  setup — as `BindWatchUseCase` does. `--no-bind` skips both.
- **The physical watch:** the first run bonds it with Windows (Just Works, no PIN; keep it
  awake and on its face). It must not be connected to a phone — the watch keeps one
  connection, so close the companion app or `:app` first.
- Exit status: 0 a reply came, 1 nothing came back, 2 the setup failed.

`docs/watch-emulator-internals.md` has the rest, including `--flash-state`, which keeps an emulated
watch bound between runs. (This replaced `normlink-cli`, the `:cli` module, which wrote to
8003 — where the firmware never acknowledges a SET.)

---

## Android app

Clean architecture (UI → Domain → Data) with BLE as a separate infrastructure layer.
compileSdk 35 · minSdk 30 · JVM 17.

### Build / install / run

```powershell
normphone start                  # the Android emulator (or plug in a phone)
normphone install                # build the debug app and install it (= .\gradlew installDebug)

.\gradlew assembleDebug          # just build the debug APK
.\gradlew lint                   # run Android lint
```

Before launching our app the original normconnect app should be disabled:
```
adb shell pm disable-user --user 0 com.normconnectappv2.watch
```

The APK lands at `app\build\outputs\apk\debug\normplus-debug.apk`. Launch it from the device, or
open the project in Android Studio and Run.

---

## Tests

```powershell
.\gradlew :protocol:test                                  # all protocol unit tests
.\gradlew :protocol:test --tests "com.normplus.protocol.PacketTest"   # a single class
.\gradlew :app:testDebugUnitTest                         # the app's unit tests
normtest                                                 # the Python tools' tests (~5 min)
```

`:protocol` is pure JVM (no Android/BLE), so its tests run fast with no device.

---

## Working with the original APK (apktool)

The decompiled companion app (the source of truth for the protocol) lives in `NORM/`.

```powershell
# Decompile (already done — output is NORM/)
java -jar NORM\_apk\apktool_3.0.2.jar d NORM\_apk\NORM.apk -o NORM

# Recompile after editing smali (produces NORM\dist\NORM.apk)
java -jar NORM\_apk\apktool_3.0.2.jar b NORM
```

---

## Quick reference

```powershell
uv tool install --editable .      # once: the seven commands on your PATH
normhelp                          # what each of them is for, and the common recipes
normphone start                   # the Android emulator
normphone install                 # build + install the Android app
normwatch boot --live             # the watch's own firmware, in a window
normcmd 08 70 --mac               # ask the physical watch its battery level
normtest                          # the Python tests
.\gradlew :protocol:test          # the protocol tests
```

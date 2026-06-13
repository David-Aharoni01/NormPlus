# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

Reverse-engineering workspace for the **Norm 2 smartwatch**. The goals are:
1. Understand the BLE communication protocol between the watch and its companion app (smali in `NORM/`)
2. Build a custom Android app that replicates/extends the companion app's functionality (`app/`)
3. Patch or replace the watch firmware via OTA

## Code Quality Standards

Every piece of code in this repo is grounded in the original NORM app smali. Before implementing any command, parsing any response, or deciding on any byte sequence:
- **Read the corresponding smali** first. The source of truth is `NORM/smali_classes2/cn/appscomm/`. If the smali contradicts your assumptions, the smali wins.
- **Every byte matters.** BLE packets to/from a physical device are unforgiving — an off-by-one, wrong endianness, or missing flag byte will silently fail or corrupt state on the watch. Be precise.
- **Model edge cases from the source.** The smali shows what the watch actually does with malformed or edge-case inputs. Follow its behavior, not what seems logical in the abstract.
- **Coroutine discipline.** BLE callbacks arrive on arbitrary threads. Channels and flows are the only safe bridge. Never share mutable state between the GATT callback and application code without a Channel or `@Volatile`.
- **Surface failures — never crash.** The app and BLE connection must stay alive under all non-fatal conditions. Unexpected response codes, out-of-sequence ACKs, malformed packets, and protocol errors must be logged with full hex context and surfaced to the UI as typed error states (via `BleConnectionState`, a `Flow` error emission, or a ViewModel `StateFlow`). They must **not** propagate as unhandled exceptions that terminate the connection or crash the process.
  - *Discrete user-initiated operations* (OTA, a single `sendAndAwait` call) may throw typed exceptions (`OtaException`, `GattWriteException`) — these are caught at the ViewModel layer and shown as error messages, then the app continues normally.
  - *The BLE connection itself and the background sync* must never die from a protocol surprise. Catch, log, emit error state, and let the existing retry/reconnect logic handle recovery.
  - `BleManager`'s scope uses `SupervisorJob()` — no child coroutine failure must ever reach it uncaught.

---

## Repo Layout

```
bin/
  NORM.apk          ← original companion app APK (version 1.1.18)
  apktool_3.0.2.jar ← tool used to unpack it

NORM/               ← unpacked APK output (apktool d NORM.apk -o NORM)
  smali_classes2/   ← app code: cn.appscomm.*  ← READ THIS FIRST

protocol/           ← pure JVM module: packet framing + command definitions
                       shared by both :app and :cli
app/                ← Android companion app (Kotlin + Compose)
cli/                ← normlink-cli: Windows JVM tool for autonomous BLE testing
  src/main/python/norm2_probe/  ← Python bleak BLE backend (bridge for the CLI)
```

The smali code that matters is entirely in `NORM/smali_classes2/cn/appscomm/`.

---

## Build Commands

```bash
# Build debug APK (Android app)
./gradlew assembleDebug

# Build and install on connected device
./gradlew installDebug

# Build the Windows CLI tool (fat JAR distribution)
./gradlew :cli:installDist

# Run the CLI (Windows)
cli\build\install\normlink-cli\bin\normlink-cli.bat --help
cli\build\install\normlink-cli\bin\normlink-cli.bat battery --mac 4C:59:80:12:44:F1

# Run protocol unit tests (no BLE/Android needed)
./gradlew :protocol:test

# Run a single test class
./gradlew :protocol:test --tests "com.norm2hacked.protocol.PacketTest"

# Run lint
./gradlew lint
```

Build config: `app/build.gradle.kts` — compileSdk 35, minSdk 30, JVM 17 target.
CLI config: `cli/build.gradle.kts` — pure JVM, installs to `cli/build/install/normlink-cli/`.

**Module graph:**
```
:protocol  (pure JVM — no Android deps, no BLE APIs)
    ↑           ↑
  :app        :cli
(Android)   (Windows JVM)
```

---

## `:protocol` Module (Shared JVM Library)

Pure Kotlin/JVM, zero Android or platform dependencies. Shared between `:app` and `:cli`.

```
protocol/src/main/kotlin/com/norm2hacked/
  ble/BleConstants.kt          UUIDs, MTU values, timeouts, frame markers
  protocol/
    Logger.kt                  Logging abstraction (Android wires Log.*, CLI uses stderr)
    WatchTransport.kt          Interface implemented by BleManager (Android) and PythonBridgeTransport (CLI)
    ble/WatchBonder.kt         Shared bonding contract (BondResult, BondingPolicy)
    CommandCode.kt             60+ command codes + Action enum (CHECK/SET/responses)
    Packet.kt                  Packet data class + PacketBuilder + PacketDeframer
    commands/
      WatchCommands.kt         Battery, Version, DateTime, SyncCount, Sport, HR, Sleep, etc.
      SettingsCommands.kt      Brightness, DND, Vibration, Language, Units, etc.
    ota/OtaProgress.kt         OTA progress types
  domain/model/Models.kt       DailyStats, SleepSummary, WatchSettings, SportType, etc.
protocol/src/test/kotlin/...
  PacketTest.kt                Unit tests for PacketBuilder + PacketDeframer
```

`WatchTransport` interface — what `:cli`'s `PythonBridgeTransport` and `:app`'s `BleManager` both implement:
```kotlin
interface WatchTransport {
    val isConnected: Boolean
    val parsedFlow: SharedFlow<Packet>
    suspend fun connect(mac: String)
    fun disconnect()
    suspend fun sendAndAwait(cmd, action, payload, timeoutMs): Packet
    fun writeToChar(bytes, charUuid)
}
```

---

## normlink-cli — Windows Autonomous Test Tool

**Purpose:** Claude can run this tool directly on Windows to test BLE protocol communication with the watch, without needing to deploy an Android APK. This is the **primary tool for autonomous testing**.

**Watch MAC:** `4C:59:80:12:44:F1`

### Commands

```
battery     --mac <MAC>                          Query battery % and charging state
version     --mac <MAC> [--type 6]               Query firmware version string
sync-count  --mac <MAC>                          Query sport/sleep/HR record counts
brightness  --mac <MAC> [--set 0-100]            Query or set screen brightness
dnd         --mac <MAC>                          Query do-not-disturb settings
switch      --mac <MAC>                          Query switch-settings bitmask
raw         --mac <MAC> --cmd-byte HH --action-byte HH [--payload "HH HH ..."]
watch       --mac <MAC> [--duration 10]          Listen for notifications passively

All commands support: --json (machine-readable output), --timeout <ms>
```

### Architecture — Python (bleak) BLE bridge behind `WatchTransport`

```
cli/src/main/kotlin/com/norm2hacked/cli/
  Main.kt                  Clikt CLI entry point + all subcommand classes
  PythonBridgeTransport.kt WatchTransport impl: spawns + drives the Python BLE bridge
cli/src/main/python/norm2_probe/
  bridge.py                stdio byte-pipe mode (the BLE backend; uses bleak)
  probe.py protocol.py …   the bleak connection/bonding/notification internals
```

The Windows BLE stack is owned by a **child process** (`python -m norm2_probe bridge <MAC>`).
`PythonBridgeTransport` spawns it and exchanges raw bytes over a tiny stdio line protocol;
**all `0x6F` protocol logic (framing, deframing, command/response parsing) runs in Kotlin
via `:protocol`** — exactly the code the Android app uses. The process boundary isolates the
JVM from bleak/WinRT instability (a BLE hiccup can't crash the test harness).

*(History: an earlier pure-Kotlin WinRT-COM-via-JNA transport (`WinTransport`) was abandoned —
hand-rolled COM vtables/agile-delegate marshaling were a long-term source of native crashes
unrelated to protocol logic. The two real bugs found there are recorded below for posterity.)*

### Bridge line protocol (`bridge.py` ⇄ `PythonBridgeTransport`)

```
stdin  (Kotlin → bridge):  W <hex>     write frame to write char, then trigger [0x03]→8002
                           QUIT        disconnect + exit (stdin EOF also quits)
stdout (bridge → Kotlin):  READY       connected + bonded + notifications subscribed
                           N <hex>      raw notification bytes from 8002/8004 (Kotlin deframes)
                           ERR <msg>    fatal error; bridge exits non-zero
```
stdout carries **only** protocol lines; all human/debug logging goes to **stderr** (surfaced
by the Kotlin side prefixed `[bridge]`). The bridge auto-bonds via `Norm2Probe._ensure_paired()`.

### Connection flow

```
1. PythonBridgeTransport.connect(mac)  spawns the bridge (PYTHONPATH → bundled app/python)
2. bridge: BleakScanner.find → _ensure_paired (custom Just Works) → BleakClient.connect
3. bridge: discover 5 services, pick write char (8003), subscribe notify 8002/8004 → READY
4. Kotlin sendAndAwait: PacketBuilder.build → "W <hex>" → await parsedFlow match (timeoutMs)
5. bridge: write to watch + [0x03] trigger; notification → "N <hex>"
6. Kotlin: PacketDeframer.feed("N" bytes) → emit Packet → command parser (e.g. BatteryCommand)
```

**Packaging:** `cli/build.gradle.kts` copies `src/main/python` into the installDist image at
`<APP_HOME>/app/python`; `PythonBridgeTransport` resolves it via `APP_HOME` (set by the start
scripts), with a dev fallback to `cli/src/main/python`. Override with env `NORMLINK_PYTHON`
(interpreter) / `NORMLINK_PYTHON_DIR` (package dir). Requires `pip install bleak` once.

---

## Android App Architecture

Clean architecture in three layers — **UI → Domain → Data** — with BLE as a separate infrastructure concern.

### Layer Map

```
ble/                  Infrastructure: BLE hardware abstraction
protocol/             Infrastructure: Packet framing, command definitions, OTA protocol
data/                 Data: Room DB + DataStore prefs
domain/               Domain: Business logic, use cases, models
notification/         Infrastructure: System notification interception
ui/                   Presentation: Compose screens + ViewModels
di/                   Dependency injection (Hilt)
```

### BLE Layer (`ble/`)

`BleManager` is the singleton that owns the GATT connection. Everything else reads from its public flows or calls its suspend functions.

```
BleManager            Singleton. Scanning, connect, disconnect, send/await, OTA writes.
BleWriteQueue         Actor that serializes all command writes to the watch.
BleGattCallback       Bridges BluetoothGattCallback → channels → BleManager.
BleService            Foreground service wrapping BleManager for background operation.
BleConnectionState    Sealed class: Disconnected | Scanning | Connecting | Discovering | Ready | Error
BleConstants          All UUIDs, MTU values, timeouts, frame markers.
```

**Public BleManager interface:**
- `connectionState: StateFlow<BleConnectionState>`
- `parsedFlow: SharedFlow<Packet>` — deframed command responses from 0x8002/0x8004
- `otaFlow: SharedFlow<ByteArray>` — raw ACK bytes from DFU chars 0x1531/0x1532
- `scanResultFlow: SharedFlow<BluetoothDevice>`
- `suspend fun connect(mac: String)` — rate-limited, mutex-guarded, auto-retries up to 8× (see "Connection lifecycle" below)
- `suspend fun sendAndAwait(cmd, action, payload, timeoutMs)` — routes through `BleWriteQueue`
- `fun writeToChar(bytes, charUuid)` — fire-and-forget (OTA control, clock sync, find-device)
- `suspend fun writeToCharAwait(bytes, charUuid, mtu)` — chunked OTA write with per-chunk ACK
- `fun drainOtaWriteChannel()` — call once before each new OTA session

**BleWriteQueue:** All command writes (not OTA) go through the queue. It subscribes to `parsedFlow` **before** writing to prevent the race where the watch replies before the subscriber registers. Urgent requests (e.g., time sync) skip the normal queue.

### Connection lifecycle & the cold-connect penalty (IMPORTANT)

**Symptom:** the first connect after the app/BT stack has been idle consistently *fails* (UI sits at "Setting up…") and only succeeds on the 2nd–3rd retry — a cold connect takes **~8–9s**. A Bluetooth toggle makes it connect first-try and instantly.

**Root cause (confirmed from `logcat` BT-stack logs):** this watch **exposes no GATT services until the bonded BLE link is encrypted.** On a cold start the Samsung BT stack's SMP (Security Manager) encryption for this device is **racy** — it reports `bta_dm_encrypt_cback: Encrypted:T` but then `smp_act: SMP state machine busy so skipping encryption enable`, so the link often isn't truly encrypted when `discoverServices()` runs. Discovery on an unencrypted link → the watch returns an **empty database** and the request **hangs** (no `onServicesDiscovered`). Only a fresh connection cycle (or a BT-adapter reset) clears the stuck SMP state.

**Current mitigation in `BleManager` (the best achievable at the app layer):**
- Discover after a short `DISCOVERY_SETTLE_MS` (700ms) delay, not immediately on `CONNECTED`.
- A **discovery watchdog** (`DISCOVERY_TIMEOUT_MS` ≈ settle + 3.5s) detects the hang and forces the disconnect/retry path instead of stalling for ~25s. It is cancelled the instant real services are found (so slow CCCD setup can't trip a false reconnect), and is generation-guarded (`connGen`) so a stale GATT callback can't double-fire recovery.
- **Moderate, spaced retries** (~1–1.5s, up to `MAX_RECONNECT_ATTEMPTS`=8). Counter-intuitively, *fast* retries make it **worse** (they thrash the SMP state) — verified on-device. Spacing lets encryption settle.
- Ruled out on-device (none fix the first attempt): longer settle (even 2s), `CONNECTION_PRIORITY_HIGH`, `autoConnect=true`.

**Hard limit:** a truly instant cold connect is **not** achievable from the app — it needs an SMP/adapter reset, and `BluetoothAdapter.enable/disable` is gone for non-system apps on modern Android. ~8s is the floor the stack imposes on a *cold* link.

**Warm-connection via `BleService` (the "snappy reconnect" fix) — IMPLEMENTED (pending on-device verification):**
The cold-start penalty only applies when the BLE link has gone fully idle. The app now **holds the GATT connection alive** in the foreground `BleService`, so a brief drop reconnects on a *warm* SMP state. What was built:
1. **`BleService` hardened** — typed foreground start via `ServiceCompat.startForeground(…, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` (closes the Android 12+/14 type gap), `START_STICKY`, and a "Disconnect" notification action (`ACTION_STOP`) for a clean user-initiated stop. It owns `BleManager`'s connection for the app/process lifetime.
2. **Always-on re-kick** — after `BleManager` exhausts its retry budget (→ `Disconnected`), the service waits `REKICK_DELAY_MS` (30s) and starts a fresh connect cycle, so the watch re-attaches on its own when back in range. Suppressed after a user stop (`userStopped`).
3. **Keep-alive** — a conservative periodic remote-RSSI read while `Ready` (`KEEPALIVE_INTERVAL_MS`, 90s), which exercises the radio without touching the command queue (can't fail the link). Relies primarily on the watch's connection supervision; tune/disable after on-device verification.
4. **Warm reconnect in `BleManager`** — tracks `reachedReady`; a drop from an established (encrypted) link reconnects after only `WARM_RECONNECT_DELAY_MS` (250ms) on the still-warm SMP state, instead of the 1–1.5s *spaced* cold-retry path. A warm attempt that fails to reach `Ready` degrades to the cold path automatically.
5. **UX fallback** — the service notification shows "try toggling Bluetooth" once cold-retry `retryCount ≥ 3`.

**TODO — verify on-device:** after a first (cold ~8s) connect, force a disconnect and confirm the warm reconnect is sub-second; compare against the cold path. Then tune `KEEPALIVE_INTERVAL_MS` / `REKICK_DELAY_MS` (or disable keep-alive if the link never drops idle).

### Protocol Layer (`protocol/`)

```
Packet.kt             Packet data class, PacketBuilder (frames bytes), PacketDeframer (reassembles MTU chunks)
CommandCode.kt        60+ enum values with byte codes; Action enum (CHECK/SET/CHECK_RESPONSE/SET_RESPONSE)
commands/
  WatchCommands.kt    Battery, DeviceVersion, DateTime, SyncCount, Sport, HeartRate, Sleep
  SettingsCommands.kt Brightness, ScreenTimeout, DND, Vibration, Language, Unit, WorkMode,
                      SwitchSetting, AppSetting, ControlDevice, and more
ota/
  ApolloOtaProtocol.kt  Full 5-step firmware update state machine (channelFlow-based)
  OtaProgress.kt        Progress sealed class: step, packetsTotal, packetsSent, errorMessage
```

**Packet format** (0x6F protocol, verified from `Leaf.smali`):
```
[0x6F][commandCode][action][lenLo][lenHi][payload...][0x8F]
```
`PacketDeframer` is length-guided: it reads `payloadLen` from bytes 3–4 of the header and does **not** scan for `0x8F` inside payloads.

**Adding a new command:** look up the byte code in `BluetoothCommandConstant.smali`, verify the payload structure in the corresponding `smali_classes2/cn/appscomm/bluetooth/protocol/` class, then add an object to `WatchCommands.kt` or `SettingsCommands.kt`. Always build the full framed packet via `PacketBuilder.build()` — never send raw bytes unless explicitly fire-and-forget via `writeToChar`.

### Data Layer (`data/`)

Room database v3 (`Norm2Database`). All timestamp index columns are **unique** — `OnConflictStrategy.IGNORE` on DAOs relies on this to prevent sync-retry duplicates.

```
sport_sessions         timestampEpoch (UNIQUE)
heart_rate_samples     timestampEpoch (UNIQUE)
sleep_sessions         startEpoch (UNIQUE)
sleep_stages           (sessionId, timestampEpoch) composite UNIQUE
blood_pressure         timestampEpoch (non-unique; not yet synced)
workouts               startEpoch (non-unique)
notification_rules     packageName (UNIQUE)
```

`WatchPreferences` (DataStore): `device_mac`, `last_sync_epoch`, `units`, `device_version`.

Schema migrations live in `di/AppModule.kt`. Bump `Norm2Database.version` and add a new `Migration` to `addMigrations(...)` any time you change entity structure.

### Domain Layer (`domain/`)

`SyncHealthDataUseCase` orchestrates the full multi-step sync: counts → fetch-by-index → delete-on-watch, for sport, heart-rate, and sleep. It emits `SyncProgress` as a `Flow`. All results are upserted into Room via the DAOs.

Domain models (`Models.kt`): `DailyStats`, `SleepSummary`, `WorkoutSummary`, `GpsPoint`, `WatchSettings`, `SportType` (24 types).

### Notification Layer (`notification/`)

`NotificationForwarder` (extends `NotificationListenerService`) — intercepts system notifications, checks per-app rules from the DB, deduplicates within a 30-second window via `RecentNotificationCache` (uses `ConcurrentHashMap.compute` for atomic check-and-set), then forwards to the watch via `NotificationPushCommand`.

#### Notification forwarding — status & the firmware wall (IN PROGRESS, READ BEFORE RESUMING)

**Goal:** phone notifications appear on the watch. **Current state: NOT working on-device — the watch
silently drops them.** The full send pipeline is fixed and verified on the wire, but the watch never
displays anything. Root analysis points at a firmware-compatibility wall (see below).

**The 4-stage pipeline and what was fixed (all verified via logcat):**
1. **Listener permission** — without it `onNotificationPosted` never fires. Granted in tests via
   `adb shell cmd notification allow_listener com.norm2hacked/com.norm2hacked.notification.NotificationForwarder`.
   *TODO: add an in-app "grant notification access" flow for real installs (no UI for it yet).*
2. **MTU chunking** — `writeToChar` does a single un-chunked `writeCharacteristic`; a 50-66 byte
   notification was truncated to ~20 bytes (MTU). Fixed: the forwarder now uses
   `BleManager.sendCommandNoResponse(...)` which routes through `BleWriteQueue` (MTU-chunked, per-chunk
   ACK). `BleRequest.awaitResponse=false` = fire-and-forget through the queue.
3. **`[0x03]` trigger** — the watch needs the "data complete, process now" trigger to `0x8002` after
   the write (send03ToDevice). The no-response path was skipping it; now it sends it for `CHAR_WRITE_8001`.
4. **Crash fix** — `sendCommandNoResponse` `await()`s the write completing, which throws
   `BleTimeoutException` if the link flaps; that propagated uncaught out of the forwarder's
   `SupervisorJob` scope → app crash. Now wrapped in `runCatching` (truly fire-and-forget).

**Decoded the legacy `MessageBT` body** (`NotificationPushCommand.appNotificationPayload`, cmd `0x79`
SET; source `MessageBT.getBytes()` + `MBluetooth.sendNewMessage`):
```
6F 79 71 [len] | [bodyLen(2)] [id(4,LE)] [crud(1)] [TLV…] | 8F
  TLV = [valueLen+1 (2,LE)] [tag] [value]   tags: 0x01=messageType 0x02=title 0x03=content
```
Package→icon-type map (`PackageTypeData.initSocialData`): WeChat 0x07, Viber 0x08, Snapchat 0x09,
WhatsApp 0x0A, QQ 0x0B, Facebook 0x0C, Messenger 0x0D, Instagram 0x0F, Twitter 0x10, LinkedIn 0x11;
email apps→0x03, calendar→0x04, SMS→0x01, calls→0x00/0x05/0x06, generic/unknown→0x02.

**THE WALL — the watch acks-but-ignores two legacy commands this feature needs:**
- **`SwitchSetting (0x90)` SET** — the watch's notification-kind bitmask (`BIT_CALL`/`BIT_SMS`/
  **`BIT_SOCIAL=0x80`**/`BIT_EMAIL`/…) gates which kinds it will *display*. With `BIT_SOCIAL` off it
  drops our `SOCIAL_NEW_PUSH`. We try to enable it on connect in `DashboardViewModel.refreshWatchStats`
  (query → OR in `SwitchSettingCommand.NOTIFICATION_BITS=0x3F0` → SET). The watch **acks the SET but
  the mask never changes** (re-query still shows `0x…0040`, social off). Verified with the byte-exact
  full-mask format from smali: content = `[0x00][mask LE4]` (5 bytes) via `SwitchSetting(cb,5,byte[])`
  — note the `(cb,I,B,B)` `[0x01,type,onOff]` variant is a *different* command (toggle ONE switch).
  `SwitchSettingCommand.setPayload`/`parse` were corrected to this format regardless.
- **`DeviceDisplayData (0x57)`** — same ack-but-ignore (documented under "live daily totals").

**Conclusion / hypothesis:** battery/sport/HR/brightness work (those legacy `0x6F` commands are
honored), but `0x90` switch-setting and `0x57` display are **silently ignored** — this firmware
(`A0.2N1.0B01`) has migrated them to the **newer `cn.appscomm.commonprotocol` command set**
(`SwitchSettingBT` / `LXSwitchSetting` / `SwitchExpandSetting`; notification body =
`MessageNewBT`, an `@Order`/`@BLEField` VarInt annotation-encoded format — NOT the legacy `MessageBT`).
Reverse-engineering the legacy smali keeps yielding accepted-but-dropped commands.

**NEXT STEPS (pick up here):**
1. **HCI-capture the official app** (`com.normconnectappv2.watch`) enabling notifications + forwarding
   one → ground-truth bytes for the switch-enable command AND the notification body (+ any bind/session
   preamble). This is the definitive path. (It needs the official app connected/forwarding; it has its
   own listener permission + contends for the watch's single connection — handle BT carefully, no
   rapid toggling.)
2. Or decode the `commonprotocol` `SwitchSettingBT` + `MessageNewBT` annotation encoder
   (`bluetooth/core/annotation/handler/*`) from smali — self-contained but slower (VarInt + length-prefix
   framework; one wrong byte = silent drop).
3. Then: add the in-app notification-listener permission request flow.

### UI Layer (`ui/`)

Jetpack Compose + Material Design 3. Each screen has a ViewModel that calls either `BleManager` directly or a use case.

```
screens/
  dashboard/          Steps, calories, HR, sleep summary for today
  activity/           Workout list + detail (GPS, duration, avg HR)
  firmware/           OTA file picker, progress bar, update type selector
  pairing/            BLE scan results, tap to pair
  settings/           Watch config (brightness, DND, language, units, vibration, etc.)
                      + notification rules (per-app toggles)
components/
  StatCard.kt         Reusable metric tile (value + unit + icon)
  Charts.kt           Heart rate sparkline, activity chart
```

Navigation graph: `ui/navigation/AppNavGraph.kt`.

---

## BLE Protocol Reference

### Main Communication Channel

**Service UUIDs:**
- Main service: `00006006-0000-1000-8000-00805f9b34fb`
- Write char (phone→watch): `00008001-...`
- Notify char (watch→phone): `00008002-...`
- Extended notify: `00008004-...`
- CCCD descriptor: `00002902-...`

**Action bytes:** `0x70` = CHECK (query), `0x71` = SET (write)

**Key command codes** (from `BluetoothCommandConstant.smali`):
- `0x02` WATCH_ID, `0x03` DEVICE_VERSION, `0x04` DATETIME, `0x08` BATTERY_POWER
- `0x0E` UPGRADE_MODE ← triggers OTA bootloader

### Apollo DFU (Firmware Update)

The Norm 2 uses an **Apollo chipset** with a custom 5-step OTA protocol over a **separate** GATT connection.

**DFU Service:** `00001530-0000-1000-8000-00805f9b34fb`
- Write char (0x1531): command/data, WRITE_WITH_RESPONSE
- Notify char (0x1532): watch ACKs; byte[1] = next expected step

**Firmware binary format:** first 4 bytes = target flash address, remaining bytes = raw firmware.

**OTA sequence** (implemented in `ApolloOtaProtocol.kt`; source: `OtaApolloCommand.smali`):
1. Send `UPGRADE_MODE` (`0x0E`) on main channel → watch reboots into bootloader
2. Re-connect GATT to same MAC → discover `0x1530` service → enable notify on both 0x1531 and 0x1532
3. Send BT param: `[0x10, 0x02]` → delay 500ms → await ACK byte[1]=0x01
4. Send INIT: `[0x01] + total_content_length_LE4`
5. Send SET header (15 bytes): `[0x02][updateType][addr[4]][len[4]][crc[4]][PACKAGE_COUNT=0x0A]`
6. Stream content in 20-byte (or 128-byte for picture) chunks; every 10 chunks = checkpoint ACK byte[1]=0x03, byte[2]=0x04
7. Send CRC verify: `[0x04]` → await byte[1]=0x04
8. Send reboot: `[0x05]` → await byte[1]=0x05

**OTA result codes:** `0x64` = success, `0x65` = in-progress, `0x66` = fail (throws `OtaException` immediately).

**CRC algorithm** (from `OtaUtil.smali`): CRC-16/CCITT, init `0xFFFF`. Returns 4 bytes: `[crc_lo, crc_hi, 0x00, 0x00]`.

### Device Version String

The watch reports `"A0.2N1.0B01"` (command `0x03`):

| Prefix | Component |
|--------|-----------|
| `A` | Apollo (main MCU) |
| `N` | Nordic |
| `B` | Beta/build |
| `T` | TouchPanel |
| `H` | HeartRate sensor |
| `R` | Picture/Resources |

---

## Key Smali Files for Protocol Work

| File | What it contains |
|------|-----------------|
| `smali_classes2/cn/appscomm/bluetooth/BluetoothCommandConstant.smali` | All command codes, UUIDs, action constants |
| `smali_classes2/cn/appscomm/bluetooth/protocol/Leaf.smali` | Packet framing — `getSendData()` builds wire bytes |
| `smali_classes2/cn/appscomm/bluetooth/BluetoothLeService.smali` | Android BLE Service — connection lifecycle |
| `smali_classes2/cn/appscomm/bluetooth/BluetoothLeService$2.smali` | `BluetoothGattCallback` — `onServicesDiscovered`, `onCharacteristicChanged` |
| `smali_classes2/cn/appscomm/bluetooth/BluetoothParse.smali` | Reassembles fragmented BLE packets |
| `smali_classes2/cn/appscomm/ota/OtaApolloCommand.smali` | Apollo DFU state machine |
| `smali_classes2/cn/appscomm/ota/OtaManager.smali` | OTA orchestration, reconnect logic |
| `smali_classes2/cn/appscomm/ota/util/OtaUtil.smali` | CRC, bin parsing, version string parsing |
| `smali_classes2/cn/appscomm/bluetooth/protocol/Setting/UpgradeMode.smali` | Bootloader trigger command |

## Reading Smali

Smali is Dalvik bytecode assembly. Useful patterns:
- `iget-object v0, p0, Lclass;->field:Ltype;` → read instance field
- `invoke-virtual {v0, v1}, Lclass;->method(Larg;)Lret;` → call method
- `const-string v0, "..."` → string literal
- `const/16 v0, 0x6f` → integer constant
- `p0` = `this`, `p1`…`pN` = method parameters, `v0`…`vN` = local variables
- Inner classes are named `OuterClass$N.smali` (anonymous) or `OuterClass$Name.smali`

---

## apktool Commands

```bash
# Decompile (already done — output is NORM/)
java -jar bin/apktool_3.0.2.jar d bin/NORM.apk -o NORM

# Recompile after modifying smali (produces NORM/dist/NORM.apk)
java -jar bin/apktool_3.0.2.jar b NORM

# Sign the recompiled APK (requires a keystore)
jarsigner -keystore my.keystore NORM/dist/NORM.apk alias_name
```

---

## Python BLE Backend (`cli/src/main/python/norm2_probe/`)

This bleak-based package is the **BLE transport backend for `normlink-cli`** (it is no longer a standalone tool). The Kotlin CLI spawns its `bridge` mode as a child process and runs all `:protocol` logic in Kotlin; the Python side only moves raw bytes over BLE (and performs the one-time bonding). It still runs directly for manual debugging.

### Setup

```bash
pip install -r cli/src/main/python/requirements.txt   # installs bleak>=0.21.0
```
Run directly (debugging): `set PYTHONPATH=cli/src/main/python` then `python -m norm2_probe <cmd> <MAC>`.

### Running

```bash
# Scan for nearby BLE devices
python -m norm2_probe scan

# Query battery level (connects, sends command, prints result, disconnects)
python -m norm2_probe battery 4C:59:80:12:44:F1

# Query device version
python -m norm2_probe version 4C:59:80:12:44:F1

# Query sport/sleep/heart-rate record counts
python -m norm2_probe sync-count 4C:59:80:12:44:F1

# Query/set brightness  (omit --set to query)
python -m norm2_probe brightness 4C:59:80:12:44:F1
python -m norm2_probe brightness 4C:59:80:12:44:F1 --set 70

# Query DND settings
python -m norm2_probe dnd 4C:59:80:12:44:F1

# Query switch settings bitmask
python -m norm2_probe switch 4C:59:80:12:44:F1

# Send raw command — useful for ad-hoc tests
python -m norm2_probe raw 4C:59:80:12:44:F1 08 70 --payload 00

# Run 4-variant CHECK matrix test (payload=[00]/[] × trigger/no-trigger)
python -m norm2_probe test-check 4C:59:80:12:44:F1

# Dump all GATT services and characteristics
python -m norm2_probe info 4C:59:80:12:44:F1

# Passively listen for notifications without sending anything
python -m norm2_probe watch 4C:59:80:12:44:F1 --duration 15
```

### Architecture

```
cli/src/main/python/norm2_probe/
  protocol.py   UUIDs, constants, build_packet(), PacketDeframer, fmt_packet()
  probe.py      Norm2Probe class — scan, connect, _ensure_paired/pair, _setup_notifications, send_and_await, watch
  commands.py   High-level command functions (cmd_battery, cmd_test_check_matrix, etc.)
  cli.py        argparse setup and command dispatch
  bridge.py     stdin/stdout byte-pipe mode consumed by the Kotlin PythonBridgeTransport
  __main__.py   Entry point (asyncio.run)
```

`Norm2Probe.connect()` auto-detects whether char 8003 is present (service 7006) and selects the correct write characteristic. Notifications are enabled on 8002, 8004, and 8005. `send_and_await()` drains stale notifications before writing, optionally sends the trigger byte `[0x03]` to 8002 after writing, then waits for the matching response.

### Windows Connection — SOLVED (the watch must be BONDED)

**The Windows blocker is solved. The fix is BLE bonding via a custom Just Works pairing ceremony.** The probe now connects end-to-end on Windows and exchanges `0x6F` protocol packets with the physical watch (verified: battery 34%, brightness 60, sync-count).

**Root cause (now understood):** The Norm 2 expects BLE bonding — its Android app calls `BluetoothDevice.createBond()` (see `bluetooth_bond/BluetoothUtils.smali`). On Windows, an *unbonded* device fails characteristic discovery (`GattCommunicationStatus.Unreachable`) because the watch's connection watchdog drops the link during slow live ATT discovery. Once **bonded**, Windows caches the full GATT table, so service/characteristic discovery is instant and the watchdog never fires.

**Why the old `pair` failed:** it called the default `pairing.pair_async()`, which negotiates a protection level the watch rejects (`ConnectionRejected`/status 19). The watch is a headless Just Works peripheral: **no PIN, no MITM**.

**The fix (now automatic):** `Norm2Probe.pair()` uses *custom* pairing — registers a `PairingRequested` handler that auto-accepts and requests `DevicePairingKinds.ConfirmOnly` + `DevicePairingProtectionLevel.None`. `connect()` calls `_ensure_paired()` first on Windows and bonds automatically if needed. After bonding, plain `BleakClient.connect()` discovers all 5 services normally.

```bash
# One-time (or automatic via any connect): bond the watch
python -m norm2_probe pair 4C:59:80:12:44:F1
#   -> [OK] Pairing succeeded: Paired

# Then any command just works — connect() auto-bonds if not already bonded
python -m norm2_probe battery 4C:59:80:12:44:F1     # Battery: 34%
python -m norm2_probe brightness 4C:59:80:12:44:F1  # Brightness: 60
```

The bond is an **OS-level** state, so it also unblocks the Kotlin `normlink-cli` (which resolves the now-bonded device directly by address — no scan needed).

---

## Current Status & Known Issues

### What's working

**Android app (`app/`):**
- Full BLE connection lifecycle (scan → pair → connect → notify setup → auto-retry)
- SET commands work — brightness, DND, vibration, language, and other settings apply on the watch
- Command send/await pipeline with write serialization and MTU chunking
- Packet framing/deframing (length-guided, handles 0x8F in payloads)
- Room database v3 with unique constraints and 2 migrations
- Notification forwarding with per-app filtering and dedup
- Apollo DFU OTA (all 5 steps, channelFlow-based, fail-fast on 0x66)
- Full Compose UI: dashboard, activity, firmware, pairing, settings

**`:protocol` module:**
- Fully extracted from `:app`, compiles as pure JVM with zero Android deps
- Unit tests: `PacketTest.kt` verifies PacketBuilder + PacketDeframer round-trips

**normlink-cli (`:cli`) — WORKING end-to-end:**
- `./gradlew :cli:installDist` bundles the Python bridge into the image (`app/python`).
- Verified live against the watch: `battery` → 100%, `sync-count` → 29 sport / 0 sleep / 0 HR, `brightness` → 60.
- Architecture: Kotlin `PythonBridgeTransport` spawns `python -m norm2_probe bridge`; all `:protocol` framing/parsing runs in Kotlin. Unreachable device → clean `error: …` message, exit 1 (no JVM crash).
- One-time setup: `pip install bleak`. Bonding is automatic (the bridge runs the Just Works ceremony on first connect).

**`:protocol` bonding contract:**
- `WatchBonder` interface + `BondResult` + `BondingPolicy` in `protocol/.../ble/WatchBonder.kt`.
- `:app` `AndroidBonder` (createBond) wired into `BleManager.connect`; `:cli` bonding is done inside the Python bridge. Both honor `BondingPolicy` (Just Works, ConfirmOnly + None).

### normlink-cli — Status: DONE

Fully functional autonomous test tool. Run any command, e.g.:
```
cli\build\install\normlink-cli\bin\normlink-cli.bat battery --mac 4C:59:80:12:44:F1
```

**Historical note (abandoned pure-Kotlin WinRT/JNA transport):** two real bugs were found and are
worth remembering if anyone revisits raw WinRT-via-JNA: (1) `BluetoothLEAdvertisementWatcher.Start()`
is vtable **[17]** (not 21 = `add_Stopped`); (2) `IAsyncOperation<T>` and `IAsyncInfo` are **separate**
interfaces — `get_Status` is on `IAsyncInfo` (QI `{00000036-…}`), `GetResults` is `IAsyncOperation[8]`;
reading status at `IAsyncOperation[7]` hits `get_Completed` and hangs every async op. The wall that
killed the approach: `add_ValueChanged` → `CO_E_NOT_SUPPORTED` (needs a Free-Threaded-Marshaler-aggregated
agile delegate). The bridge sidesteps all of this.

### CHECK commands — verified working (via the Kotlin CLI + bridge)

Every CHECK command requires a `[0x00]` payload (1 byte); the watch silently ignores empty-payload commands. Wire packet: `[6F][cmd][0x70][01][00][00][8F]`. Confirmed on the physical watch through the Kotlin CLI: `BATTERY_POWER`, `SCREEN_BRIGHTNESS` → 60, `TOTAL_SPORT_SLEEP_COUNT` → 29 sport. (Still pending verification in the Android `:app` on-device.)

**Note:** `DEVICE_VERSION` (cmd `0x03`, payload `[06]`) times out — a command-specific payload quirk to investigate (not a connection issue).

### CHECK commands — fixed (pending on-device verification)

**Root cause:** Every CHECK command requires `[0x00]` payload (1 byte). Watch silently ignores empty-payload commands. Fixed in `DashboardViewModel.kt` and `SyncHealthDataUseCase.kt`.

Wire packet: `[6F][cmd][0x70][01][00][00][8F]`

**Status: Fixed in code, not yet verified on Android device.**

### Pending features (priority order)
1. **Notifications → watch (IN PROGRESS, BLOCKED)** — send pipeline fixed (permission, MTU-chunking,
   `[0x03]` trigger, crash) but the watch silently drops them: the legacy `SwitchSetting (0x90)`
   notification-enable and the `MessageBT` body are acked-but-ignored on this firmware. Full state +
   next steps in **"Notification forwarding — status & the firmware wall"** under the Notification Layer.
   Next: HCI-capture the official app for the `commonprotocol` switch-enable + `MessageNewBT` format.
1. **Verify warm-connection on-device** — the feature is implemented (see "Warm-connection via `BleService`"); confirm a warm reconnect is sub-second vs the ~8s cold path, then tune `KEEPALIVE_INTERVAL_MS` / `REKICK_DELAY_MS`.
1. **Verify CHECK commands in the Android `:app` on-device** — the bridge-backed CLI confirms the protocol; the app's `BleManager` path still needs device verification.
2. **`DEVICE_VERSION` payload quirk** — cmd `0x03` with payload `[06]` times out; find the correct request format.
3. **Verify CHECK commands on Android** — deploy APK, confirm battery/version/sync receive responses
4. **GPS data display** — `GET_GPS_DATA (0x6B)` infrastructure exists; parse response and render tracks
4. **Blood pressure sync** — entity + DAO exist; wire `SyncHealthDataUseCase`
5. **Weather sync** — find command code in `BluetoothCommandConstant.smali`

### Known gaps
- No unit tests for the BLE layer or `PythonBridgeTransport` — protocol parsing is covered in `:protocol:test`
- `writeToChar` (fire-and-forget) has no error surface
- `BleService` keep-alive uses an RSSI read as a generic link probe; whether the watch's idle-drop watchdog actually resets on it is unverified (so far relying on connection supervision) — confirm on-device, switch to a periodic battery CHECK if needed

# The Android app and `:protocol`

How `:app` and `:protocol` are built, and what has been verified on the watch. Moved out of
`CLAUDE.md` (which keeps the rules and how to run things); the kanban board has what is still
to do.

## `:protocol` Module (Shared JVM Library)

Pure Kotlin/JVM, zero Android or platform dependencies, so it unit-tests on the plain JVM.
Used by `:app` (the Windows `normlink-cli` was its second user until it was removed).

```
protocol/src/main/kotlin/com/norm2hacked/
  ble/BleConstants.kt          UUIDs, MTU values, timeouts, frame markers
  protocol/
    Logger.kt                  Logging abstraction (Android wires Log.*, plain JVM gets stdout/stderr)
    WatchTransport.kt          Interface BleManager implements; the use cases depend on it
    ble/WatchBonder.kt         Shared bonding contract (BondResult, BondingPolicy)
    CommandCode.kt             60+ command codes + Action enum (CHECK/SET/responses)
    Packet.kt                  Packet data class + PacketBuilder + PacketDeframer
    commands/
      WatchCommands.kt         Battery, Version, DateTime, SyncCount, Sport, HR, Sleep, etc.
      SettingsCommands.kt      Brightness, DND, Vibration, Language, Units, etc.
    ota/ApolloOta.kt           Apollo DFU: frames, pieces, CRC, ApolloOtaSession, OtaTransport
    ota/OtaProgress.kt         OTA progress types
  domain/model/Models.kt       DailyStats, SleepSummary, WatchSettings, SportType, etc.
protocol/src/test/kotlin/...
  PacketTest.kt                Unit tests for PacketBuilder + PacketDeframer
  ApolloOtaTest.kt             The DFU frames for the real resource image; whole sessions
                               against a fake firmware
```

`WatchTransport` interface — what `:app`'s `BleManager` implements and the use cases depend on:
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

## Android App Architecture

Clean architecture in three layers — **UI → Domain → Data** — with BLE as a separate infrastructure concern.

### Layer Map

```
ble/                  Infrastructure: BLE hardware abstraction
protocol/             Infrastructure: Packet framing, command definitions, OTA protocol
data/                 Data: Room DB + DataStore prefs
domain/               Domain: Business logic, use cases, models
notification/         Infrastructure: System notification interception + junk filter
call/                 Infrastructure: Phone-call detection (PHONE_STATE) + watch call-control
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

**A second explanation, from the firmware image (2026-09):** the watch never answers a Read By
Type for its GATT Database Hash (0x2B2A) — Cordio's hash-update flag is set when the services
are added and `AttsCalculateDbHash()` is never called, so the read is parked for ever — and
Android reads exactly that before `discoverServices()` on every connection after the bonding
one. Against the emulated watch this reproduces the symptom precisely: the first one or two
attempts stall in discovery and a later one goes through once the stack gives up on the hash.
See "Binding" in `docs/watch-emulator-internals.md`. Not yet confirmed on the physical watch; if it
holds, the SMP theory above is describing the same stall from the wrong side. One more piece
of evidence against that theory: once the emulated watch was really asked for its key (see
"The watch keeps what it writes"), Android logged the same `SMP state machine busy so
skipping encryption enable:1` on reconnects whose HCI shows the firmware answering the LTK
request with the right key and both ends reporting Encryption Change, status 0. The line is
not evidence that encryption failed.

**Warm-connection via `BleService` (the "snappy reconnect" fix) — IMPLEMENTED (pending on-device verification):**
The cold-start penalty only applies when the BLE link has gone fully idle. The app now **holds the GATT connection alive** in the foreground `BleService`, so a brief drop reconnects on a *warm* SMP state. What was built:
1. **`BleService` hardened** — typed foreground start via `ServiceCompat.startForeground(…, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` (closes the Android 12+/14 type gap), `START_STICKY`, and a "Disconnect" notification action (`ACTION_STOP`) for a clean user-initiated stop. It owns `BleManager`'s connection for the app/process lifetime.
2. **Always-on re-kick** — after `BleManager` exhausts its retry budget (→ `Disconnected`), the service waits `REKICK_DELAY_MS` (30s) and starts a fresh connect cycle, so the watch re-attaches on its own when back in range. Suppressed after a user stop (`userStopped`).
3. **Keep-alive** — a conservative periodic remote-RSSI read while `Ready` (`KEEPALIVE_INTERVAL_MS`, 90s), which exercises the radio without touching the command queue (can't fail the link). Relies primarily on the watch's connection supervision; tune/disable after on-device verification.
4. **Warm reconnect in `BleManager`** — tracks `reachedReady`; a drop from an established (encrypted) link reconnects after only `WARM_RECONNECT_DELAY_MS` (250ms) on the still-warm SMP state, instead of the 1–1.5s *spaced* cold-retry path. A warm attempt that fails to reach `Ready` degrades to the cold path automatically.
5. **UX fallback** — the service notification shows "try toggling Bluetooth" once cold-retry `retryCount ≥ 3`.

**TODO — verify on-device:** after a first (cold ~8s) connect, force a disconnect and confirm the warm reconnect is sub-second; compare against the cold path. Then tune `KEEPALIVE_INTERVAL_MS` / `REKICK_DELAY_MS` (or disable keep-alive if the link never drops idle).

### Always-on uptime — every way the link dies silently (IMPLEMENTED, pending on-device verification)

The warm-connection work above keeps a *running* service connected. These are the layers that keep
the service itself running, so the user is never silently disconnected:

| Failure mode | Handled by |
|---|---|
| Phone rebooted | `ble/BootReceiver.kt` — `BOOT_COMPLETED` (+ `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`), `RECEIVE_BOOT_COMPLETED` |
| App updated (kills the service) | same receiver, `MY_PACKAGE_REPLACED` |
| Bluetooth toggled off/on | `ble/BluetoothStateReceiver.kt`, registered **dynamically** by `BleService` (`ACTION_STATE_CHANGED` is not an implicit-broadcast exemption). OFF → clean stand-down (no retry thrash); ON → reconnect |
| Process killed (memory / OEM optimiser) | `START_STICKY` + `onStartCommand` now resumes from DataStore on a **null intent** |
| Process killed *and* not restarted | `ble/ConnectionWatchdog.kt` — 15-min `AlarmManager` (`setAndAllowWhileIdle`) tick that restarts the service / re-kicks the connect |
| Doze / battery optimisation | in-app prompt (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, shown only when `isIgnoringBatteryOptimizations` is false; dismissal remembered in DataStore) |
| Permission revoked, app background-restricted | surfaced in the FGS notification *and* the Settings → **Connection health** card, with one-tap fixes |

Design rules to preserve:
- **All connect requests funnel through `BleService.requestConnect` → `BleManager.connect`** (mutex +
  rate-limited). Boot, adapter-ON, re-kick and the watchdog therefore cannot open concurrent cycles.
- **Service start intents are typed:** `ACTION_START` = explicit user intent (re-arms auto-start),
  `ACTION_RESUME` / null = unattended (honours the stored `autostart_enabled` flag), `ACTION_STOP` =
  user stop (persists `autostart_enabled=false`, cancels the watchdog).
- `BleService.resume()` **never throws**: a background FGS start is rejected on Android 12+ unless the
  app is battery-optimisation exempt — that's why the exemption prompt exists (it lifts this too).
- `AlarmManager`, not WorkManager: WorkManager isn't an `:app` dependency and buys nothing over an
  inexact 15-min alarm here. Alarms don't survive a reboot — `BootReceiver` → service → reschedule.
- New DataStore keys: `autostart_enabled`, `battery_opt_prompt_dismissed`, `last_connected_epoch`.

### Protocol Layer (`protocol/`)

```
Packet.kt             Packet data class, PacketBuilder (frames bytes), PacketDeframer (reassembles MTU chunks)
CommandCode.kt        60+ enum values with byte codes; Action enum (CHECK/SET/CHECK_RESPONSE/SET_RESPONSE)
commands/
  WatchCommands.kt    Battery, DeviceVersion, DateTime, SyncCount, Sport, HeartRate, Sleep
  SettingsCommands.kt Brightness, ScreenTimeout, DND, Vibration, Language, Unit, WorkMode,
                      SwitchSetting, AppSetting, ControlDevice, and more
ota/                  (in :protocol)
  ApolloOta.kt          Apollo DFU as the firmware answers it: ApolloOtaSession drives an
                        update over an OtaTransport (BleOtaTransport in :app's ble/)
  OtaProgress.kt        Progress: step, packetsTotal, packetsCurrent, errorMessage
```

**Packet format** (0x6F protocol, verified from `Leaf.smali`):
```
[0x6F][commandCode][action][lenLo][lenHi][payload...][0x8F]
```
`PacketDeframer` is length-guided: it reads `payloadLen` from bytes 3–4 of the header and does **not** scan for `0x8F` inside payloads.

**Adding a new command:** look up the byte code in `BluetoothCommandConstant.smali`, verify the payload structure in the corresponding `smali_classes2/cn/appscomm/bluetooth/protocol/` class, then add an object to `WatchCommands.kt` or `SettingsCommands.kt`. Always build the full framed packet via `PacketBuilder.build()` — never send raw bytes unless explicitly fire-and-forget via `writeToChar`.

### Data Layer (`data/`)

Room database v4 (`Norm2Database`). All timestamp index columns are **unique** — `OnConflictStrategy.IGNORE` on DAOs relies on this to prevent sync-retry duplicates.

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

`NotificationForwarder` (extends `NotificationListenerService`) — intercepts system notifications,
runs the **junk filter** (`NotificationFilter.decide`, drops charging/media/foreground-service/
progress/local-only/empty and the dialer's call notifications), checks the **app whitelist**
(`NotificationWhitelist`, see below), **coalesces over a 300 ms window** (`NotificationMergePolicy`),
suppresses exact repeats (`RecentNotificationCache`), then forwards to the watch via
`MessageNewCommand`. RTL title/content is reordered to visual order via `BidiUtil.formatRtlString`
before framing (see "Feature status — verified on-device").

Note the filter no longer drops group summaries — that call moved into `NotificationMergePolicy`,
which needs to see a notification's siblings to make it. See below.

#### Notification lifecycle — there is NO watch-side delete or update (IMPORTANT)

**The `New` push generation cannot retract, replace, or merge a notification once it is sent.**
`MessageNewBT` (cmd `0x76`) carries no id, no slot, no crud byte — `[type][countOrVersion][titleLen]
[contentLen][title][content][date][shockType][needReply]` and nothing else. The original app detects
phone-side dismissal and routes it all the way to `MessagePushRepositoryHelper.deleteMessage`
(`messagepush/repository/helper/MessagePushRepositoryHelper.smali:16-40`), which is a **literal
`return-void` unless `isPerfect()`** — no `isNew()` branch, no `isW04d()` branch. For our watch, a
dismissal on the phone produces **zero BLE traffic**. `MessagePushBLEService` has no
`deleteMessageNew`/`clearSocial`/`clearAll` at all (its only `clear*` is `clearCalendarView`).
So `onNotificationRemoved` is deliberately a no-op *toward the watch*; it only evicts from the
suppression cache.

**`countOrVersion` is a vestigial *count* byte, always `0x01`.** Grep over the whole APK finds it
only at `MessageNewBT.smali:10/:28/:56` (order entry, declaration, `= 1`) — nothing ever assigns it.
The count that *is* computed (`getActiveNotificationCount`) is explicitly discarded on the `New`
path (`MessagePushRepositoryHelper.smali:152-165`). **Do not use it as an id, slot, or badge.**

**Therefore all grouping is phone-side**, and we copy the original's pipeline (`notification/`):
- **300 ms debounce** — `MessagePushHandler.sendMessageDelayed(…, 0x12c)`. Anchored to the first
  post of a burst (not sliding), so a steady drip can't starve the flush.
- **Last-write-wins per merge key** `pkg + sbn.id + tag` (`NotificationParser.parseId`) —
  `NotificationTaskMerger.removeMessageTask`. Five in-place updates of one chat → one push.
- **Group summary vs children** — `isSupportGroupNotification()` is a hardcoded `false`
  (`BlueToothDevice.smali:155-161`), so the original takes the branch where **the summary wins and
  the children are evicted**. Our `NotificationFilter` used to do the exact opposite. One deliberate
  UX deviation: when a summary and *exactly one* child are in the window we forward the child (its
  real text beats "1 new message"); 2+ children still collapse to the summary. Tunable via
  `NotificationMergePolicy.PREFER_LONE_CHILD`.
- **Suppression cache** — `OldProtocolFilter` (installed for every non-`Perfect` device, i.e. ours):
  FIFO cap 100, key `(mergeKey, title, content)`, suppress exact repeats *indefinitely*, evict on
  removal. This replaced the old fixed 30 s dedup window.

Pure + unit-tested: `NotificationMergePolicy.merge`, `NotificationMergeBuffer`,
`RecentNotificationCache` (`app/src/test/kotlin/com/norm2hacked/notification/`).

**Dead ends — do NOT try** (all ruled out from smali):
- Re-pushing `0x76` with empty title/content to "blank out" the old one — no identity field, so it
  adds an *extra blank* notification.
- `0x79` / `MessagePerfectBT` with `crud=2` — only reachable behind `isPerfect()`; already recorded
  as acked-but-ignored on this firmware.
- `SET_OPERATION_DELETE (0x2)` / `DELETE_ALL (0x3)` and `COMMAND_CODE_6E_PHONE_DELETE_*_REMIND` —
  these are *reminder/alarm* ops on a different protocol generation, not social pushes.
- `MSG_COUNT_PUSH (0x72)` as a badge/merge signal — `W04d`-only.
- Enabling `SWITCH_BIT_SOCIAL (0x80)` on the `0x90` mask to "unlock" a delete — `SwitchSettingBT` is
  never called on the `New` path, and there is no delete command to unlock.
- `clearCalendarView()` as a generic clear — it is the calendar page (`id=0x99`, `pageType=7`).

#### App whitelist (`NotificationWhitelist`) + the app picker

Forwarding is **opt-in per app**. `NotificationWhitelistPolicy.decide(rule)` is pure (no row →
`NotWhitelisted`; `enabled=false` → `NotWhitelisted`; else `Allowed(suppressDuplicates)`)
and unit-tested in `app/src/test/.../NotificationWhitelistTest.kt`. `NotificationWhitelist` is the
injectable DB wrapper and **fails closed** — a Room error logs and drops rather than throwing.
The whitelist is *necessary, not sufficient*: `NotificationFilter` + the 30s dedup still apply.

- **DB v4.** `notification_rules.enabled` now defaults to **false** (Kotlin default only — the SQL
  column is unchanged). `MIGRATION_3_4` runs `UPDATE notification_rules SET enabled = 0`: up to v3
  the forwarder auto-inserted `enabled=1` for every app it ever saw, so those rows can't be
  distinguished from a real user choice; carrying them over would whitelist everything. Rows are
  kept (labels + vibrate/mute preferences survive), so re-enabling an app restores its settings.
- **Picker** (`ui/screens/settings/NotificationRulesScreen.kt` + `NotificationRulesViewModel.kt`,
  backed by `data/apps/InstalledAppsRepository.kt`). Settings-style installed-app list: icon +
  label + package + switch, search, "show system apps" toggle, enabled apps pinned to the top.
  Labels load first on `Dispatchers.IO`, then icons stream in in batches of 32 pre-rasterised to
  the row size — all into a `StateFlow`, never in composition, so 200+ rows scroll smoothly.
- **Package visibility:** `AndroidManifest.xml` declares `<queries><intent>` for `ACTION_MAIN` +
  `CATEGORY_LAUNCHER` — **not** `QUERY_ALL_PACKAGES` (Play-policy-restricted and unnecessary).
  Non-launchable apps aren't offered; an app that already has a rule is always re-added by package
  name so an old rule can never become unreachable.
- **Listener permission flow** (the old TODO): an inline card on this screen when
  `NotificationManagerCompat.getEnabledListenerPackages` doesn't contain us, with a button opening
  `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`; re-checked on `ON_RESUME`.

#### Notification forwarding — WORKING (commonprotocol MessageNewBT)

**Status: phone notifications appear on the watch** — verified on-device (emulator + dongle bridge):
posting a notification → the watch shows it, and replies `SET_RESPONSE cmd=0x76 status=0x00`.

**The key fix:** this firmware uses the newer `cn.appscomm.commonprotocol` notification command, not
the legacy `MessageBT (0x79)` (which it acks-but-ignores). `BlueToothDevice.getMessagePushConfig()`
picks a generation by device type — `PerfectSocial` (id `0x79`, `@IndexBody`; only for a hardcoded
OEM list), `W04d` (legacy granular `0x72/0x73`), else **`New`**. The **Norm 2 → `New`**, i.e.
`sendMessageNew(MessageNewBT)` = **cmd `0x76` (SOCIAL_EX_PUSH byte), SET**. Crucially the `New` path
sends *only* `sendMessageNew` — **no switch-enable / no preamble** (`SwitchSettingBT` exists in smali
but is never called; the old "enable `BIT_SOCIAL` on the `0x90` mask first" theory was a dead end).

**Wire format** — same outer frame as everything else (`BluetoothLeaf extends Leaf`), body is the
`@Body` annotation encoding (`FieldHandler`, decoded in `CommonProtocolCodec`):
```
6F 76 71 [lenLo lenHi] [type][countOrVersion][titleLen][contentLen][titleBytes][contentBytes][dateBytes][shockType][needReply] 8F
```
- length-prefixed fields (`title` max 0x5A, `content` max 0x80) emit their length byte **inline**, with
  the bytes **deferred** and flushed after the last such field; `date` (raw, no length) =
  `yyyyMMdd'T'HHmmss`; defaults `countOrVersion=1, shockType=0xFF, needReply=0`.
- `type` = package→icon category (`PackageTypeData`): WeChat 0x07, Viber 0x08, Snapchat 0x09,
  WhatsApp 0x0A, QQ 0x0B, Facebook 0x0C, Messenger 0x0D, Instagram 0x0F, Twitter 0x10, LinkedIn 0x11;
  email→0x03, calendar→0x04, SMS→0x01, calls→0x00/0x05/0x06, generic/unknown→0x02.

**Code:** `CommonProtocolCodec.encodeBody` (the `@Body` encoder) + `MessageNewCommand` (0x76) in
`protocol/.../commands/`; `NotificationForwarder` sends it via `BleManager.sendCommandNoResponse`.
The legacy `NotificationPushCommand` (0x79 MessageBT) is kept but unused. The forwarder skips its own
package (no self-feedback). Byte-exact test in `protocol/.../NotificationPushTest.kt`.

**Pipeline plumbing that was also needed** (all in place): listener permission (granted in tests via
`adb shell cmd notification allow_listener com.norm2hacked/.notification.NotificationForwarder`);
MTU-chunking via `BleWriteQueue`; the `[0x03]` "process now" trigger to `0x8002` after the write;
and `runCatching` around the fire-and-forget write so a link flap can't crash the forwarder's scope.

**Still TODO:** if a future firmware reports the `Perfect` generation, implement `MessagePerfectBT` (id `0x79`,
`@IndexBody` — a *different* encoding using index bytes, not the `@Body` rule above).

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

## Verified on the watch

### Feature status — verified on-device (works 100%)

These features are implemented, byte-faithful to the original app's smali, unit-tested where the
logic is pure, and **confirmed working on the physical watch** (phone calls and hand calibration
end-to-end user-confirmed; the notification junk filter confirmed running on-device via logcat
drops, e.g. `dropped: CALL_HANDLED_ELSEWHERE`):

- **Notifications → watch** (`notification/`, cmd `0x76` `MessageNewBT`). Phone notifications appear
  on the watch. Includes:
  - **RTL rendering** (`protocol/.../util/BidiUtil.kt`) — Hebrew/Arabic titles/content are reordered
    to visual order (Arabic reshaping + `java.text.Bidi`) before sending, since the watch draws bytes
    left-to-right and does no bidi of its own. Applied to both the active `MessageNewCommand` (0x76)
    and the legacy `NotificationPushCommand` (0x79).
  - **Junk filtering** (`notification/NotificationFilter.kt`) — drops types that are useless on the
    wrist (charging/system status, media/now-playing, foreground-service "Waiting for messages…",
    progress, local-only, empty content). Pure `decide(NotificationFacts)` rule chain +
    `StatusBarNotification.toFacts()` extractor; unit-tested. Per-app rules, the 300 ms coalescing
    window and exact-repeat suppression apply on top — see "Notification lifecycle" above.
- **Phone calls** (`call/`). Forwards incoming / missed / call-ended to the watch with the caller's
  **name** (resolved from contacts), and handles the watch's **answer / reject** buttons.
  - Detection: `PhoneStateReceiver` (PHONE_STATE broadcast) → pure `CallStateMachine`
    (RINGING→incoming, OFFHOOK→answered, IDLE→ended; RINGING→IDLE = missed). The caller number
    arrives on a *second* RINGING broadcast ~3ms after the first, so `CallManager` **coalesces** the
    incoming push over a 250ms window to send one push that already carries the name.
  - Phone→watch: reuses `MessageNewBT` (0x76) with the call type byte — incoming `0x05`, missed
    `0x00`, ended `0x06`.
  - Watch→phone: cmd **`0xDC`** `INCOME_CALL_RESPONSE`, `payload[0]` 0=accept / non-zero=reject →
    `TelecomManager.acceptRingingCall()` / `endCall()`.
  - Hosted by `BleService` (`CallManager.start/stop`). `NotificationFilter` drops `CATEGORY_CALL`/
    `CATEGORY_MISSED_CALL` so the dialer's own notifications don't double up. Permissions:
    `READ_PHONE_STATE`, `READ_CALL_LOG`, `READ_CONTACTS`, `ANSWER_PHONE_CALLS`.
- **Watch-hands calibration** (`ui/screens/calibration/`, cmds `0xB6`/`0xB8`). A guided screen to
  re-align drifted physical hands: unlock → move each hand (minute, then hour — Norm 2 has no second
  hand) to 12:00 via nudge (`WatchMoveOne` 0xB6) or hold-to-rotate (`WatchMoveKeep` 0xB8) → Save.
  - Save sends **one** `DATETIME` (0x04) with **byte[8]=1** (the re-home flag), which sets the clock
    and drives the hands from 12:00 to the current time, then `WatchMoveKeep` LOCK. LOCK is also sent
    on any early exit so the hands are never left free.
  - **Byte order gotcha (verified from `DateTime.smali` + `completeCalibration`):** the SET body is
    `[yLo][yHi][mo][d][h][mi][s][0][flag][tzSign][tzHr][tzMin]` — the re-home flag is **byte[8]**,
    not the last byte, with the timezone triplet at bytes[9..11]. Putting the flag at byte[11] makes
    the watch read it as a stray tz value and re-home to the internal clock (wrong time).
    **`DateTimeCommand.setPayload(instant, zone, reHome)` in `WatchCommands.kt` is the single
    builder for this body** — calibration just calls it with `reHome=true`. It defaults to `false`
    (guarded by a test): a routine clock sync that set byte[8]=1 would sweep the physical hands.

New command codes added this cycle (`protocol/.../CommandCode.kt`): `INCOME_CALL_RESPONSE(0xDC)`,
`WATCH_MOVE_ONE(0xB6)`, `WATCH_MOVE_KEEP(0xB8)`. New builders live in
`protocol/.../commands/WatchHandsCommands.kt` (`WatchMoveCommand`, `TranSpeedCommand`,
`CalibrationSaveCommand`).

### What's working

**Android app (`app/`):**
- Full BLE connection lifecycle (scan → pair → connect → notify setup → auto-retry)
- SET commands work — brightness, DND, vibration, language, and other settings apply on the watch
- Command send/await pipeline with write serialization and MTU chunking
- Packet framing/deframing (length-guided, handles 0x8F in payloads)
- Room database v4 with unique constraints and 3 migrations
- Notification forwarding with an explicit per-app whitelist (installed-app picker + listener-permission
  flow), junk-type filtering, RTL (Hebrew/Arabic), 300 ms coalescing/group-merge, and exact-repeat
  suppression
- Phone calls: forward incoming/missed/ended with caller name + answer/reject from the watch (`call/`)
- Watch-hands calibration: guided re-align of the physical hands (`ui/screens/calibration/`)
- Apollo DFU OTA of the resource partition (`ApolloOtaSession`, type 4) -- verified end to
  end against the emulated watch from the AVD (#56); not yet run on the physical watch (#13)
- Full Compose UI: dashboard, activity, firmware, pairing, settings, calibration

**`:protocol` module:**
- Fully extracted from `:app`, compiles as pure JVM with zero Android deps
- Unit tests: `PacketTest.kt` verifies PacketBuilder + PacketDeframer round-trips

**`normwatch cmd --mac` — the physical watch from the PC — WORKING.** Verified live
2026-10-02: bonded Windows on first use, then `BATTERY_POWER CHECK` → `6F 08 80 01 00 5F
8F` (95%) and checkInit 1, ~3s a run. It replaced `normlink-cli` (verified live in its day:
battery, sync-count → 29 sport, brightness → 60). See "Asking the physical watch from the PC".

**`:protocol` bonding contract:**
- `WatchBonder` interface + `BondResult` + `BondingPolicy` in `protocol/.../ble/WatchBonder.kt`.
- `:app` `AndroidBonder` (createBond) wired into `BleManager.connect`. `normwatch cmd --mac` bonds Windows with the same policy (Just Works, ConfirmOnly + None).

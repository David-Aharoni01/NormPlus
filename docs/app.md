# The Android app and `:protocol`

How `:app` and `:protocol` are built, and what has been verified on the watch. Moved out of
`CLAUDE.md` (which keeps the rules and how to run things); the GitHub board (`normboard`) has what is still
to do.

## `:protocol` Module (Shared JVM Library)

Pure Kotlin/JVM, zero Android or platform dependencies, so it unit-tests on the plain JVM.
Used by `:app` (the Windows `normlink-cli` was its second user until it was removed).

```
protocol/src/main/kotlin/com/normplus/
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
    fun sendAndStream(cmd, action, payload, idleTimeoutMs, isLast): Flow<Packet>
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
status/               The shared watch status: link, battery, sync, blockers and their fixes (#97)
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
- `fun sendAndStream(cmd, action, payload, idleTimeoutMs, isLast): Flow<Packet>` — one write, then
  every matching response until `isLast` accepts one; the timeout is an idle timer restarted by
  every frame (a record stream, see "Domain Layer")
- `fun writeToChar(bytes, charUuid)` — fire-and-forget (OTA control, clock sync, find-device)
- `suspend fun writeToCharAwait(bytes, charUuid, mtu)` — chunked OTA write with per-chunk ACK
- `fun drainOtaWriteChannel()` — call once before each new OTA session

**BleWriteQueue:** All command writes (not OTA) go through the queue. It subscribes to `parsedFlow` **before** writing to prevent the race where the watch replies before the subscriber registers. Urgent requests (e.g., time sync) skip the normal queue. A streamed request (`sendAndStream`) holds the queue until its stream ends, so nothing is written to the watch in the middle of one -- the watch restarts a record stream on any new request for it -- and buffers responses without bound, because the GATT callback's packet channel drops frames when the collector behind it stalls. Around each stream the queue calls `BulkLink` (`BleManager.streamLink`, #86): the ATT MTU goes to 247 once per connection (one notification per sport frame instead of two), HIGH connection priority is requested and **re-asserted every 4 s** -- some 20 s after each connection the watch asks for a 120-180 ms interval, and only asking again wins the link back -- and at the end the link goes to LOW_POWER (100-125 ms, latency 2), the nearest public setting to what the watch asks for itself. 952 sport records: 139 s → 32 s.

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
1. **`BleService` hardened** — typed foreground start via `ServiceCompat.startForeground(…, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` (closes the Android 12+/14 type gap), `START_STICKY`, and a "Stop" notification action (`ACTION_STOP`) for a clean user-initiated stop. It owns `BleManager`'s connection for the app/process lifetime.
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
| Permission revoked, app background-restricted | surfaced in the FGS notification, the app's banner on every screen (#97) *and* the Settings → **Connection health** card, with one-tap fixes |

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

Room database v5 (`Norm2Database`). All timestamp index columns are **unique** — `OnConflictStrategy.IGNORE` on DAOs relies on this to prevent sync-retry duplicates.

```
sport_sessions         timestampEpoch (UNIQUE); activeMinutes is the record's sportTime, in minutes (#89, v5)
heart_rate_samples     timestampEpoch (UNIQUE)
sleep_sessions         startEpoch (UNIQUE); one per 0x10..0x11 session on the watch (#90)
sleep_stages           (sessionId, timestampEpoch) composite UNIQUE; stage is SleepStage.code:
                       0 deep, 1 light, 2 awake; durationSeconds until the next record (#90)
blood_pressure         timestampEpoch (non-unique; not yet synced)
workouts               startEpoch (non-unique)
notification_rules     packageName (UNIQUE)
```

`WatchPreferences` (DataStore): `device_mac`, `last_sync_epoch`, `units`, `device_version`.

Schema migrations live in `di/AppModule.kt`. Bump `Norm2Database.version` and add a new `Migration` to `addMigrations(...)` any time you change entity structure.

### Domain Layer (`domain/`)

`SyncHealthDataUseCase` orchestrates the sync as the official app does (`SyncBluetoothDataNew`): **one** `TOTAL_SPORT_SLEEP_COUNT` for every count, then each record type -- sport, heart rate, sleep -- as **one request whose reply is a stream** of every record on the watch, then the clock. It emits `SyncProgress` as a `Flow` (the dashboard shows `Sport data 412/922`). All results are upserted into Room via the DAOs.

**Delete after syncing** (#91, Settings → Sync, on by default; `WatchPreferences.deleteAfterSync`): once a type's stream is complete and in the database, the sync deletes it from the watch, as the official app does, so the next sync reads only what the watch has written since -- the watch cannot stream from an index, so this is the only way to read less. Each delete (`0x53` / `0x5A` / `0x55`, SET `[00]`) erases that type's whole ring, so what was written after the read would go with it:

- **Sport** is deleted straight after its read: the watch erases it only if its count is still the one this sync's count request reported, and otherwise keeps everything (and acknowledges the same) -- a `:29`/`:59` record that lands mid-sync is read next time.
- **Heart rate and sleep** have no such guard on the watch, so they are deleted only if a second count, after both reads, says they have not moved: a measurement that ended meanwhile moves the heart-rate count, and a sleep session that began makes the sleep count 0. That second count makes the watch write the half hour so far as a sport record, which stays for the next sync.
- A delete that fails is only logged: the records stay, and the next sync reads them again, which the unique timestamps make harmless.

On the AVD against the emulated watch: the first sync read 14 sport, 1 heart-rate and 12 sleep records and deleted all three; the next read the 1 sport record written since. Switched off, every sync reads everything, as it did before.

How the reads work, as the smali and both watches have them (#85, `docs/protocol.md` "Health records"):

- `GET_SPORT_DATA [00 00]` (`GetSportData(cb, 2, 0, count)`) and `GET_HEART_RATE_DATA [00]` are each answered with **every** record, one frame each, indexed from 1. The index in the request is ignored. The physical watch streamed 920 sport records in ~29 s and 271 heart-rate records in ~9 s.
- So the timeout is an idle one, 10 s without a frame (`Leaf.isTimeout`, reset by `setLastSendTime`), and the stream ends at the frame indexed `count` (`RecordStreams.isLast`). `RecordStreams.assemble` checks what came: each index once, what never came, what did not parse. A short stream is inserted and reported as an error rather than shown as a full history.
- **The heart-rate count comes from the same `0x52` reply** (`[4..5]`, `AllDataTypeCount`): `TOTAL_HEART_RATE_COUNT`'s reply from this watch is four bytes and `HeartRateCount.smali` accepts two. Asking for the counts also makes the watch write the half hour so far as one more sport record, which is why there is exactly one count per sync.
- **Sleep comes back as sessions** (#90), as the official sync takes the stream apart
  (`ModeConvertUtil.getGroupleepDataList`, then `SleepNewDBService`): `SleepSessions.group`
  makes a session of each 0x10 ... 0x11, each record's stage lasting until the next record
  (0 deep, 1 light; 2-4 and the start itself awake), and leaves out what is outside one --
  the state the watch writes again after each end, a session with no end. Time asleep is deep
  plus light (`SleepBreakdown.totalSec`). It used to be one session from the first record to
  the last plus five minutes, with the markers stored as stages and the breakdown reading 2 as
  light and 3 as deep.
- **What it used to do, and why it timed out:** one `sendAndAwait` per index. The watch restarted its stream from record 1 on every request, so the replies were records 1, 2, 1, 2, 3, 2, ... -- never past ~4 -- while every request left another stream running; some requests were answered `00 02` (wiped by the stream), and on the phone the flood ended in timeouts.

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
`RecentNotificationCache` (`app/src/test/kotlin/com/normplus/notification/`).

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
`adb shell cmd notification allow_listener com.normplus/.notification.NotificationForwarder`);
MTU-chunking via `BleWriteQueue`; the `[0x03]` "process now" trigger to `0x8002` after the write;
and `runCatching` around the fire-and-forget write so a link flap can't crash the forwarder's scope.

**Still TODO:** if a future firmware reports the `Perfect` generation, implement `MessagePerfectBT` (id `0x79`,
`@IndexBody` — a *different* encoding using index bytes, not the `@Body` rule above).

### UI Layer (`ui/`)

Jetpack Compose + Material 3, in the **Dark Dial** world (#92; the direction contract is in
`.impeccable/surfaces/app-src-main-kotlin-com-normplus-ui.md`, the screen structure in the
brief on #95): a dark room with the watch in it. A near-black ground with one soft violet
glow behind each screen's hero, the day's numbers on rounded cards, big bold titles, pill
buttons, and the watch itself drawn live on its own tab. Material 3 supplies the navigation,
the components and system Back; the world supplies type, palette, density and that one
signature move. Each screen has a ViewModel that calls `BleManager` directly or a use case.

```
ui/
  theme/        the theme: NormPlusTheme, colour roles, type, shapes, spacing, motion, the glow
  components/   the shared components every screen is built from (below)
  legacy/       DEPRECATED: what the pre-redesign screens still use; deleted with the last one
  screens/      one package per screen, each rebuilt by its own issue (#98-#107)
  shell/        the app shell (#97): NormPlusApp, the tabs, the status line and banner, the fixes
  navigation/   the routes (Destinations) and the app's graph (AppNavGraph)
```

#### The theme (`ui/theme/`)

`NormPlusTheme { }` wraps the app (MainActivity). Dark first, with a designed light scheme;
both follow the system. There is no Dynamic Color. Read everything from it:

| What | Where |
|---|---|
| Material colour roles: primary = signal violet (selection, actions, progress), primaryContainer / secondaryContainer = the violet tint selection sits on, background = surface = the ground, surfaceContainer = a card, surfaceContainerHigh = raised, tertiary = the "fine" green, error | `MaterialTheme.colorScheme` |
| Dark Dial's own colours: `card`, `cardHairline`, `raised`, `glow`; `fine` / `fineContainer` (green: fine, goal reached); `fix…` (amber: fix-its and their banner); `failBanner`; the chart and sleep-stage inks; the dial's (`dialDisplay`, `dialBezel`, `dialRing`, `dialHand`, `dialInk`, `dialAccent`) | `NormPlusTheme.colors` |
| Material type roles, on Roboto Flex (headlines and display roles in the heavy weights) | `MaterialTheme.typography` |
| `screenTitle`, `heroFigure`, `tileFigure`, `figureUnit`, `cardTitle`, `sectionTitle`, `pill`, `chartLabel`, `dialText` | `NormPlusTheme.type` |
| Spacing and sizes: `gutter` (16 dp side margin), `cardPadding`, `s`/`m`/`l`/`xl`…, `touchTarget` (48 dp), `hairline`, `progressBar`, `icon`, `capsuleHeight` | `NormPlusTheme.spacing` |
| Material shapes (cards 20 dp, dialogs 28 dp, chips full pills), and `hero`, `card`, `tile`, `banner`, `pill`, `barCorner` | `MaterialTheme.shapes`, `NormPlusTheme.shapes` |
| Fade-through between tabs, shared axis X between days, the hands' sweep, the dial's pager, a progress bar filling; `NormPlusTheme.animationsRemoved` | `NormMotion` |
| The one glow behind a screen's hero | `Modifier.heroGlow()` |

Every text pair passes 4.5:1 (the figures are noted in `Color.kt`). The watch's display is a
black AMOLED in both schemes, so the dial draws the same object in light and dark; only its
violet ring follows the scheme.

Roboto Flex is bundled (`res/font/roboto_flex.ttf`, the variable font from `google/fonts`,
SIL OFL 1.1, licence in `assets/licenses/roboto_flex_OFL.txt`); its weight, grade and
optical-size axes make the bold display cuts and the plain text cut. Every style asks for
tabular figures.

**Rules for every screen:**
- **No colour, size or font is written in a screen.** No `Color(…)`, no `Color.White`, no
  literal `dp`/`sp`, no `FontFamily`: roles from the table above, or a component. If a screen
  needs something the theme lacks, add it to the theme (or a component) in the same change,
  named for its job.
- **Violet is for selection, actions, progress and the dial's ring.** Never decoration. No
  colour per metric: tiles are uniform cards, the figures are the colour.
- **State colours always come with an icon and words**: green "fine" and "Goal reached"
  (`StatusPill`, `GoalReachedChip`, a goal day's bar with its row's words), amber fix-its
  (`FixItCard`, `ConnectionBanner(NeedsFixing)`), red errors (`Notice(Failed)`,
  `StateMark(NotSent)`, `ConnectionBanner(Failed)`). Never a bare coloured figure.
- **One glow per screen**, behind its hero (`heroGlow()`): Today's steps card, Watch's dial,
  calibration's dial. Nothing else glows.
- **A figure the watch has not reported is absent, never zero**: `StepsHeroCard(steps = null)`,
  `MetricTile(value = null, absentText = …)`, a `null` bar.
- **Every watch preview is our own drawing** (`WatchDial`, `WatchScreen`): an icon and a
  label on the black round, never the watch's artwork.
- **48 dp touch targets**, at least (Material buttons, list items, navigation items and icon
  buttons already are; `PillButton` is 48 dp tall; anything custom uses
  `NormPlusTheme.spacing.touchTarget`).
- **Edge-to-edge**: the activity draws behind the system bars; every screen applies the
  insets (`LargeTitleHeader` takes the status bar's, `NavigationCapsule` the navigation
  bar's; content under the capsule leaves `NavigationCapsuleDefaults.contentPadding`; IME
  insets on text fields). The ground follows the scheme, so the system bars' icons do too
  (`SystemBarStyle.auto`; `NormPlusTheme.isDark` says which).
- **RTL-safe**: `start`/`end`, never `left`/`right`; `AutoMirrored` icons for anything with a
  direction; `placeRelative` in custom layouts. The charts, the progress bar and the grid
  mirror themselves. Labels in any script keep their own direction (the notification-app list).
- **TalkBack**: every control and icon is labelled or marked decorative; a composite reads as
  one sentence (`clearAndSetSemantics { contentDescription = … }`: the hero card, tiles, day
  rows, the dial); headings are `heading()`; changing state is a polite live region (the
  pill, marks and banners are already).
- **Remove animations**: motion goes through `NormMotion`, which is a cut when
  `NormPlusTheme.animationsRemoved`; nothing else moves. The sending arc stops turning but
  stays an arc.

#### The shared components (`ui/components/`)

| Component | For |
|---|---|
| `LargeTitleHeader` | Every screen's head: Material's large top app bar with a big bold title (collapsing on scroll), actions, Back or Close, and the status slot under the title |
| `StatusPill`, `StatusKind` | The quiet line about the watch under the title: fine (green), charging, syncing (violet), offline ("as of"), neutral (a flow's step) |
| `ConnectionBanner`, `BannerTone` | The pill grown into a full-width rounded banner: the state and its one fix. Working (violet tint), Notice (raised), NeedsFixing (amber fill), Failed (red fill) |
| `ShellStatus`, `PillContent`, `BannerContent`, `LocalShellStatus`, `ShellStatusSlot`, `ShellBanner` | The status the shell resolved (#97), provided to every screen: the slot prints the banner when there is one, the pill otherwise |
| `ScreenScaffold` | A screen below a tab (#97): the header with Back, the title and `ShellStatusSlot`, collapsing on scroll; the content's padding |
| `NavigationCapsule`, `NavigationCapsuleDefaults` | The floating rounded navigation bar (Material `NavigationBar` semantics, 48 dp items): the selected tab violet on its tinted pill |
| `PillButton`, `PillTone`, `pillColors` | Full-round buttons: Primary (violet), Tonal (raised, violet words), Neutral, Danger, Quiet |
| `NormCard` | The rounded card with its hairline every card uses |
| `StepsHeroCard`, `HeroMetricCard`, `ProgressBar`, `GoalReachedChip` | The hero card: label, the big figure, "of 8,000 · 1,588 to go", the violet bar that turns green with "Goal reached" at the goal; one TalkBack sentence |
| `MetricTile`, `MetricGrid`, `figure`, `durationFigure`, `FitText`, `CardTitle` | The two-column tiles (sleep, heart rate, calories, distance), figures with small units, absent figures in words; text that shrinks to fit (40,000 at font scale 1.3) |
| `DayRow` | A row that opens a day: date badge, title, the day's figure, "Goal reached", chevron (Today's Yesterday, History's rows) |
| `SendState`, `StateMark`, `MarkGlyph` | The one set of state marks, as tinted pills: sending, sent, not sent, waiting |
| `SettingsSection`, `SettingRow`, `SettingsDivider` | Grouped settings on a card, each row with its own send state, Retry, "as of", disabled reason |
| `FixItCard`, `Notice`, `NoticeTone` | A blocker with its one-tap fix (amber); a failure (red), with Details |
| `BarChart`, `ChartBar` | One bar per record (half hour or day): violet, goal days green, a goal line, heart-rate ranges with an average tick, single readings; gaps empty, zeros as stubs |
| `SleepStackChart`, `SleepNight`, `SleepStageBand`, `StageSpan`, `SleepStage`, `SleepLegend` | Sleep stacked by stage per night; a night as a stage band with a lane per stage |
| `FlowScaffold` | A full-screen flow (first run, calibration, firmware): Close, the title, the step pill and the banner under it, pill actions along the bottom |
| `WatchDial`, `DialHand`, `WatchScreen`, `rememberWatchTime` | The signature: the watch drawn live, its hands at the time (no second hand), sweeping; a hand highlighted and a twelve mark for calibration (#105); every screen's preview |
| `DialPager`, `PageDots`, `rememberDialPagerState`, `moveDialTo` | The Watch tab's hero: the watch's screens in their order (`WatchScreen.inWatchOrder`), neighbours peeking, the name, page dots |
| `WatchScreenThumbnail` | A screen's round thumbnail with its place in the order, the selected one ringed in violet (#103) |
| `countText`, `durationText`, `rememberClockFormatter`, `dateLineText` | How numbers, durations, clock times and dates print, the same everywhere |

#### The app shell (#97)

What every screen sits in. The shell owns `MainActivity`, `ui/navigation/`, `ui/shell/`,
`status/` and `strings_shell.xml`.

- **`MainActivity`**: edge-to-edge, both system bars transparent over the ground, their icons
  following the system's dark setting as the theme does (`SystemBarStyle.auto`: light icons on
  the dark ground, dark on the light one). It starts the connection service when the app is
  opened (not on a rotation or a theme change), calls the first run's `LaunchPermissions`
  (below), and shows `NormPlusApp`.
- **`ui/shell/NormPlusApp`**: the app's graph, starting at the first run when no watch is saved
  and at Today otherwise. It provides `LocalWatchStatus` (the facts) and `LocalShellStatus` (the
  pill and the banner, worded, with their fixes) to every screen, and samples the system again
  whenever the app comes to the front.
- **The head of every screen** is `LargeTitleHeader`: a large bold title (collapsing to a small
  one as the content scrolls) with the watch's status under it. All is well: the **status
  pill**. In any state but Ready, or with any blocker: the **banner** in the pill's place,
  full width, in its state colour, with its one fix (`ShellStatusSlot`; a short crossfade
  between the two, a cut with Remove animations).
- **The tabs** (`ui/shell/TabsShell.kt`, `TabsScaffold`): Today · History · Watch. One header
  with the tab's title (and Sync at its end on Today); the tab's content, running the full
  height of the screen and scrolling under the header and the floating **navigation capsule**. The header
  and the capsule stay put when the tab changes; only the content fades through. Each tab keeps
  its state and its header's collapse; Back from History or Watch returns to Today, and Back
  from Today leaves the app.
- **Screens below a tab** are drawn full screen over the tabs with `ScreenScaffold` (the header
  with Back); **flows** (first run, calibration, firmware) use `FlowScaffold` (the header with
  Close, the step's pill, and the banner under it when there is one). Both read
  `LocalShellStatus`, so no screen has to remember to print it. The first run has neither pill
  nor banner: the connection is its own business there.
- **Motion:** between tabs, fade-through; going to a screen, a cut; going back, Material's
  predictive back preview (`NormMotion.backPreviewExit`: the screen shrinks toward 90 % as the
  back gesture is drawn, then fades over the one beneath). All of it a cut with Remove
  animations. Predictive back is on (`enableOnBackInvokedCallback`), so the system draws
  back-to-home too.
- **The pill** (`statusPill`): "Norm 2 · 82% · synced 14:32", green with a tick, while
  connected (a bolt while charging); "Reading sport records · 412 of 922", violet with the
  sending arc, while a sync runs; "Norm 2 · synced 14:32", grey with the link off, while not
  connected (the battery is only shown live; on a screen the banner stands in its place then).
  A sync on an earlier day prints its date; none yet, "not synced yet".
- **The banner** (`StatusWords.banner`) names one thing and its one fix, in this order: a
  blocker that stops the link (the Bluetooth permission, the service stopped from its
  notification, Bluetooth off, background restricted); then the connection in any state but
  Ready (Scanning, Connecting, Setting up, Reconnecting with the attempt, Disconnected with
  Connect; after three failed attempts, "Turning Bluetooth off and on usually helps" with a way
  to Bluetooth settings); then battery optimisation (unless the person said "Not now") and
  notifications blocked. Under way it is violet (Working), a plain fact grey (Notice), a
  blocker amber (NeedsFixing).
- **The service notification** says what the banner says, in the same words (`StatusWords`):
  the blocker that stops the link, the connection's state, or "Connected". Its action is Stop;
  the banner then says "Norm+ was stopped from its notification · Start". Opening the app
  afresh starts the service again, as it always has.

#### The navigation contract (#97)

Ten screen issues are built in parallel on top of the shell, so each owns exactly one entry
composable with a fixed signature, in its own package and file. The shell's graphs call these
and nothing else; a screen never sees the `NavController`.

| Route (`ui/navigation/Destinations.kt`) | Entry composable | File | Issue | Chrome |
|---|---|---|---|---|
| `Destination.FirstRun` (start, no watch saved) | `FirstRunRoute(onFinished: () -> Unit, onOpenNotificationApps: () -> Unit)` | `ui/screens/firstrun/FirstRunRoute.kt` | #98 | `FlowScaffold`, no pill or banner |
| `Destination.Today` (tab, start otherwise) | `TodayRoute(contentPadding: PaddingValues, onOpenDay: (LocalDate) -> Unit)` | `ui/screens/today/TodayRoute.kt` | #99 | the shell's |
| `Destination.History` (tab) | `HistoryRoute(contentPadding: PaddingValues, onOpenDay: (LocalDate) -> Unit)` | `ui/screens/history/HistoryRoute.kt` | #100 | the shell's |
| `Destination.DayDetail(epochDay: Long)` | `DayDetailRoute(date: LocalDate, onBack: () -> Unit)` | `ui/screens/daydetail/DayDetailRoute.kt` | #101 | `ScreenScaffold` |
| `Destination.Watch` (tab) | `WatchRoute(contentPadding: PaddingValues, onOpenNotificationApps, onOpenWatchScreens, onOpenHandsCalibration, onOpenFirmwareUpdate, onOpenTechnical, onWatchForgotten)`, the callbacks each `() -> Unit` | `ui/screens/watch/WatchRoute.kt` | #102 | the shell's |
| `Destination.WatchScreens` | `WatchScreensRoute(onBack: () -> Unit)` | `ui/screens/watchscreens/WatchScreensRoute.kt` | #103 | `ScreenScaffold` |
| `Destination.NotificationApps` | `NotificationAppsRoute(onBack: () -> Unit)` | `ui/screens/notificationapps/NotificationAppsRoute.kt` | #104 | `ScreenScaffold` |
| `Destination.HandsCalibration` | `HandsCalibrationRoute(onClose: () -> Unit)` | `ui/screens/calibration/HandsCalibrationRoute.kt` | #105 | `FlowScaffold` |
| `Destination.FirmwareUpdate(customFile: String? = null)` | `FirmwareRoute(customFile: Uri?, onClose: () -> Unit)` | `ui/screens/firmware/FirmwareRoute.kt` | #106 | `FlowScaffold` |
| `Destination.Technical` | `TechnicalRoute(onBack: () -> Unit, onUpdateFromFile: (Uri) -> Unit)` | `ui/screens/technical/TechnicalRoute.kt` | #107 | `ScreenScaffold` |

- `DayDetail` takes the day as `LocalDate.toEpochDay()`; build it with `Destination.DayDetail(date)`.
  The previous and next day are the Day detail screen's own business (shared axis X inside it),
  not new routes.
- `onWatchForgotten` returns to the first run with the back stack cleared; `onFinished` goes to
  Today and drops the first run. `onUpdateFromFile` opens the firmware flow with the chosen
  `.bin`, so the custom-file update goes through #106's deliberate steps rather than a copy of
  them.
- **A tab's entry** fills the whole screen under the shell's header and capsule, with no header
  of its own (a Scaffold for a snackbar host is fine). Its content scrolls under both, so it
  passes `contentPadding` (the header at the top; the capsule and the system navigation bar at
  the bottom) to its list's `contentPadding`, adding its own gutter. A scrolling tab collapses
  the header on its own (the shell connects the nested scroll); the header takes the ground's
  colour once something scrolls beneath it. Today's Sync action and pull to refresh both call `WatchStatusSource.sync()`; the
  hero's glow (`heroGlow()`) shows through the transparent header.
- **A screen below a tab** draws `ScreenScaffold(title, onBack, actions, snackbarHost) { padding -> }`
  (`ui/components/Shell.kt`) and applies `padding`; its own actions (Done on Watch screens) go in
  `actions`. **A flow** draws `FlowScaffold`, and catches system Back with a `BackHandler` only
  where leaving must be asked about first (an update that is sending).
- **Every callback is safe to call twice**: the graph acts only for the screen on top, so a
  double tap cannot open two screens or pop two.

**Who edits what.** A screen issue edits its own package (`ui/screens/<package>/`: the entry
file's body, and anything else it adds there), its own `res/values/strings_<screen>.xml`, its
own tests (`app/src/test/kotlin/com/normplus/ui/screens/<package>/`), and the data or BLE code
its brief needs. It does **not** edit `MainActivity`, `ui/navigation/`, `ui/shell/`, `status/`,
`strings_shell.xml`, or another screen's package or strings. A screen that needs a new callback
or route argument says so on its issue; the shell changes once, not ten times in parallel. A
change to a shared component (`ui/components/`) re-records that component's goldens in the
same commit; two issues changing the same component file will conflict, so say so on both.

**The stubs** keep the app usable at every merge, and are what each issue replaces:
Today → the old dashboard; History → the old activity history (its workout rows open nothing;
the Workouts screens are dropped); Watch → the old settings; Notification apps → the old
notification rules; Hands calibration, Firmware update, First run → their old screens;
Day detail, Watch screens and Technical → a placeholder. The old files are deleted by the issue
that replaces them:

| Old files | Deleted by |
|---|---|
| `screens/pairing/`, and `firstrun/LaunchPermissions.kt` with `MainActivity`'s two calls to it | #98 |
| `screens/dashboard/` | #99 |
| `screens/activity/` (the activity history, and the now unreachable `WorkoutDetailScreen` and its ViewModel) | #100 |
| `screens/settings/WatchSettingsScreen.kt`, `WatchSettingsViewModel.kt`, `ConnectionHealthSection.kt`, `ConnectionHealthViewModel.kt` | #102 |
| `screens/settings/NotificationRulesScreen.kt`, `NotificationRulesViewModel.kt` | #104 |
| `screens/calibration/HandsCalibrationScreen.kt` (its ViewModel may stay) | #105 |
| `screens/firmware/FirmwareScreen.kt` (its ViewModel may stay; the custom-file part goes to #107) | #106 |
| `ui/legacy/` | whichever issue deletes the last old screen |

**Permissions.** The request `MainActivity` made at launch (everything at once) now lives in
`ui/screens/firstrun/LaunchPermissions.kt`, behind `requestMissing()`, which the activity calls
on opening. It is #98's: the first run asks for each permission at the step that needs it and
replaces it.

#### The shared status source (`status/`, #97)

One place holds what the app knows about the watch; the banner, the status pill, the service
notification and every screen read the same value, and nobody else samples the system.

- **`WatchStatusSource`** (a Hilt singleton; inject it in a ViewModel):
  - `status: StateFlow<WatchStatus>`;
  - `sync(): Boolean` starts the sync (one at a time, only while connected; it runs app-wide, so
    it outlives the screen that started it), and `status.sync` shows its progress and outcome;
  - `refresh()` samples the system facts again (the shell does it on every return to the front;
    `rememberStatusFixes()` after every fix); `onAppVisible()`; `refreshBattery()`;
  - `dismissBatteryOptimisation()`: "Not now" to the battery prompt (it stays a fix-it, it
    leaves the banner);
  - the permission lists `BLUETOOTH_PERMISSIONS`, `CALL_PERMISSIONS`, `NOTIFICATION_PERMISSIONS`.
- **`WatchStatus`**:
  - `link: Link`: `NoWatch`, `Disconnected`, `Scanning`, `Connecting`, `SettingUp`,
    `Reconnecting(attempt)` (failed attempts so far; 0 is a dropped link picked up at once; a
    reconnect stays Reconnecting through its own Connecting and Setting up), `Ready`;
  - `battery: WatchBattery?` (`percent`, `charging`, `readAtEpochMs`; the last reading, kept
    after the link drops), read on every connection, after every sync, and on coming to the
    front when older than 15 minutes;
  - `lastSyncEpochMs` (null: never), and `sync: SyncState` (`Idle`; `Running(stage, done, total)`
    with `SyncStage` Counting, Sport, HeartRate, Sleep; `Finished(atEpochMs, problems)`, each
    `SyncProblem` with its stage and, for a stream that stopped short, `received` of `expected`);
  - `blockers: List<Blocker>`, most severe first, and `dismissed`.
- **`Blocker`**, each with its one `Fix`: `BluetoothPermissionMissing`, `ServiceStopped`,
  `BluetoothOff`, `BackgroundRestricted`, `BatteryOptimisationOn`, `NotificationsBlocked` (in the
  banner); `NotificationAccessOff`, `CallsNotAllowed` (fix-its for Watch, Notification apps and
  the first run, never in the banner).
- **In a composable**: `LocalWatchStatus.current`; `rememberStatusFixes()` returns
  `(Fix) -> Unit`, which opens Android's own dialog or settings page for each fix (or starts the
  service) and samples the status again afterwards.
- **Words**: `StatusWords` chooses them, `strings_shell.xml` holds them. A fix-it card is
  `FixItCard(title = stringResource(StatusWords.title(b)), reason = stringResource(StatusWords.reason(b)),
  actionLabel = stringResource(StatusWords.fixLabel(b)), onAction = { fixes(b.fix) })`.
- It never throws: a battery reply that makes no sense is logged with its bytes and the last
  good reading kept; a failed sync ends as `Finished` with its problems.

#### Strings

Every screen keeps its words in its **own** `res/values/strings_<screen>.xml` (English;
`strings_today.xml`, `strings_watch.xml`, …), so screens rebuilt in parallel never edit the
same file. Prefix the names with the screen (`today_sync_action`). Use plurals for counts.
The components' words are in `strings_components.xml`; `strings.xml` keeps the app's name.

#### Screenshot tests (Paparazzi)

Every shared component and every rebuilt screen is rendered on the PC, without a device, in
light, dark, and dark at font scale 1.3, and compared with its golden image in
`app/src/test/snapshots/images/` (tracked; our own renders) on **every**
`./gradlew :app:testDebugUnitTest`. A render that drifts from its golden fails the build; the
diff lands in `app/build/paparazzi/failures/`.

A screen's tests go in `app/src/test/kotlin/com/normplus/ui/screens/<screen>/`, beside the
components' (`ui/components/*SnapshotTest.kt` are the examples):

```kotlin
@RunWith(Parameterized::class)
class TodaySnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries          // light, dark, largefont
    }

    @get:Rule val paparazzi = normPaparazzi(variant, component = false)   // the whole Pixel 8 screen

    @Test fun goalMet() = paparazzi.snapshot {
        NormPlusTheme(animationsRemoved = true) { TodayContent(state = TodayUiState(/* … */)) }
    }
}
```

Render the screen's **stateless content** composable with a hand-made UI state, one test per
state the issue lists (no ViewModel, no Hilt, no BLE in a snapshot). Pass the dial a fixed
time, never `rememberWatchTime()`. Then:

```bash
./gradlew :app:recordPaparazziDebug --tests "com.normplus.ui.screens.today.*"   # write its goldens
./gradlew :app:testDebugUnitTest                                                 # verify everything
```

Look at every new golden before committing it: a golden is a claim that the render is right.
A change that is meant to move an existing component re-records its goldens in the same
commit. Paparazzi runs on this Windows machine; one Gradle build at a time (memory).

The old screens (`screens/`, until each is rebuilt) still compile against `ui/legacy/`, whose
colour names now resolve to the theme's roles, so they show in the new palette. A rebuilt
screen imports nothing from `legacy`; the package is deleted with the last old screen.


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
- Room database v5 with unique constraints and 4 migrations
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

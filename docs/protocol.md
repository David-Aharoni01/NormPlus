# The Norm 2 protocol

The wire protocol between the watch and its companion app -- the 0x6F command channel and
the Apollo DFU update -- and how to read it out of the original app's smali. Moved out of
`CLAUDE.md`. For what the watch's firmware is and does, see `docs/firmware.md`.

## Main Communication Channel

**Service UUIDs:**
- Main service: `00006006-0000-1000-8000-00805f9b34fb`
- Write char (phone→watch): `00008001-...`
- Notify char (watch→phone): `00008002-...`
- Extended notify: `00008004-...`
- CCCD descriptor: `00002902-...`

**Action bytes:** `0x70` = CHECK (query), `0x71` = SET (write)

**Key command codes** (from `BluetoothCommandConstant.smali`):
- `0x02` WATCH_ID, `0x03` DEVICE_VERSION, `0x04` DATETIME, `0x08` BATTERY_POWER
- `0x0E` UPGRADE_MODE ← OTA mode, inside the application (no bootloader, no reset)

### CHECK commands — verified working (on the physical watch)

Every CHECK command requires a `[0x00]` payload (1 byte); the watch silently ignores empty-payload commands. Wire packet: `[6F][cmd][0x70][01][00][00][8F]`. Confirmed on the physical watch through `normlink-cli` (since replaced by `normwatch cmd --mac`): `BATTERY_POWER`, `SCREEN_BRIGHTNESS` → 60, `TOTAL_SPORT_SLEEP_COUNT` → 29 sport. (Still pending verification in the Android `:app` on-device.)

**Note:** `DEVICE_VERSION` (cmd `0x03`, payload `[06]`) times out — a command-specific payload quirk to investigate (not a connection issue). Over 8001 (which `:app` uses) the emulated watch answers it with the generic `6F 01 81 02 00 03 01`, i.e. status 1, refused — bound or not; over 8003 (where `normlink-cli` wrote) nothing comes back for any SET-style refusal, which is the "timeout". `normwatch cmd 03 70 --payload 06` shows both. **Confirmed on the physical watch** (2026-10-02, `--mac`): over 8001 it answers the same `6F 01 81 02 00 03 01 8F`, byte for byte — the request is refused, not lost.

### CHECK commands — fixed (pending on-device verification)

**Root cause:** Every CHECK command requires `[0x00]` payload (1 byte). Watch silently ignores empty-payload commands. Fixed in `DashboardViewModel.kt` and `SyncHealthDataUseCase.kt`.

Wire packet: `[6F][cmd][0x70][01][00][00][8F]`

**Status: Fixed in code, not yet verified on Android device.**

## Apollo DFU (Firmware Update)

**Verified against the watch's own firmware in the emulator (2026-10-02, `tests/test_ota.py`,
README "Rehearsing an OTA"); not yet run on the physical watch.** A full resource update
of the unmodified `Picture_P03B_NORM2_0.4.bin` goes through: CRC `04 01`, REBOOT `05 01`
(the app's 0x64), the partition byte-identical, and the watch boots identically after it.

**Implemented by `ApolloOtaSession`** (`protocol/.../ota/ApolloOta.kt`, driven from the
Firmware screen through `BleOtaTransport`) and verified end to end from `:app` in the AVD
against the emulated watch (#56): 2159 pieces, CRC and REBOOT accepted, all 197 resource
pages rewritten and identical. It refuses types outside 1-4, refuses a main-MCU update
(type 1) unless explicitly allowed -- nothing in the app allows it -- and refuses a Telink
image. The physical watch has not had one yet (#13).

**It is all inside the application, over the same connection.** `UPGRADE_MODE` (`0x0E`
SET `[00]`, 8001) is acknowledged and nothing resets; the DFU service is in the running
application's GATT table (`ew_mod_ota_protocol.c`, handler at `0x0003B798`).

**DFU service `1530`** -- both characteristics are **write without response only**:
- `0x1531` READ | WRITE_WITHOUT_RESPONSE | NOTIFY: control commands in, every reply out.
- `0x1532` READ | WRITE_WITHOUT_RESPONSE, no CCCD: image data in.

A reply is `[command][status][...]`; status `01` is success, anything else is failure
(`OtaApolloCommand.parse`).

**The sequence** (cn.appscomm.ota.OtaApolloCommand; the firmware's answers):
1. `UPGRADE_MODE` `[00]` on 8001 → generic ack, no reset.
2. BT_PARAM `[10 02]` → 1531. No reply; the app waits 500 ms and carries on.
3. INIT `[01] + content length LE32` (exactly 5 bytes) → `01 01`.
4. SET (15 bytes) `[02][type][addr 4][length 4][crc 4][0A]` → `02 01`. **Type 4 is the
   resource partition**; the handler accepts 1-4 only and answers anything else `02 00`
   -- including the 8 that `cn.appscomm.ota`'s `getUpdateType` and our Kotlin send for
   `Picture_*.bin` (`cn.appscomm.bluetooth.ota` has `UPDATE_TYPE_PICTURE_LANGUAGE = 4`).
   **A type-4 SET erases the live resource partition at `0x0C780000` at once**; an update
   cut short leaves the UI without resources (the OTA code is in internal flash and
   survives, so it can be re-sent).
5. Data → 1532 in **200-byte pieces** (each 2 KB page as ten 200s and a 48; the rest in
   200s), each written in 128-byte (type 4) or 20-byte writes. **Every piece** is answered
   `03 01 01|02 <bytes so far LE32>`; `02` marks a completed page, programmed in place.
6. CRC `[04]` → `04 01`. CRC-16/CCITT init `0xFFFF` as `[lo, hi, 0, 0]` (`OtaUtil`),
   over the content -- accepted by the firmware.
7. REBOOT `[05]` → `05 01`. For a resource update the watch does **not** reset; it shows
   "Upgrade Success". It erases and programs NAND page 0 at the end (a record).

**Do not start one during the first ~10 s after the watch boots.** An OTA screen opened over
the boot animation sends the UI task into unbounded recursion in the notify / window
manager code and it overflows its stack (README).

**Firmware binary format:** first 4 bytes = target address, the rest = the content.
**OTA result codes:** `0x64` success, `0x65` in progress, `0x66` fail.

**CRC algorithms — there are two, and a patched image needs both to be right:**

1. **Transport CRC-16** (from `OtaUtil.smali`), computed by the phone and sent in the SET
   header: CRC-16/CCITT, init `0xFFFF`, over `file[4:]`. Returned as 4 bytes
   `[crc_lo, crc_hi, 0x00, 0x00]`. `ApolloOta.crc` computes it.
2. **Image CRC** stored at `file+0x10`, checked by the first-stage bootloader: **CRC-32,
   polynomial `0x1EDC6F41`, MSB-first (NOT reflected), init `0`, no final xor, over
   `file[48:]`** — exactly the byte count declared at `file+0x0C`. Recovered by parameter
   search and verified against the factory image (`0xD392D741`). Castagnoli's polynomial but
   non-reflected with a zero seed, which is why the standard CRC-32C variants all miss it.

Nothing here is cryptographic — no signature, no encryption. **A patched image can be
re-sealed and will validate**: `python tools/firmware/image_tool.py seal patched.bin --in-place`.

**Where updates come from.** The app's Apollo channel is the Retrofit
`POST device/queryProductVersion` in `cn/appscomm/server/UrlService.smali`. The public
endpoint `api.normdenmarkupdate.com` is the *Telink* channel and serves Norm 1 firmware for a
Telink SoC (`KNLT` magic) -- never send it to this watch (`docs/firmware.md` section 8;
`OtaImage.parse` refuses it).

## Device Version String

The watch reports `"A0.2N1.0B01"` (command `0x03`):

| Prefix | Component |
|--------|-----------|
| `A` | Apollo (main MCU) |
| `N` | Nordic |
| `B` | Beta/build |
| `T` | TouchPanel |
| `H` | HeartRate sensor |
| `R` | Picture/Resources |

`DEVICE_VERSION` CHECK with payload `[01]` (what the Firmware screen sends) is answered with
the whole string by the emulated watch -- `A0.2R0.4T0.0H0.0B0.1` after a resource update,
with an unreadable `R` before one, since the resource version lives in the record that
update writes last. Payload `[06]` is refused (status 1) on both watches (#2).

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

## apktool Commands

```bash
# Decompile (already done — output is NORM/)
java -jar bin/apktool_3.0.2.jar d bin/NORM.apk -o NORM

# Recompile after modifying smali (produces NORM/dist/NORM.apk)
java -jar bin/apktool_3.0.2.jar b NORM

# Sign the recompiled APK (requires a keystore)
jarsigner -keystore my.keystore NORM/dist/NORM.apk alias_name
```

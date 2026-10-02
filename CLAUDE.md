# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

Reverse-engineering workspace for the **Norm 2 smartwatch**. The goals are:
1. Understand the BLE communication protocol between the watch and its companion app (smali in `NORM/`)
2. Build a custom Android app that replicates/extends the companion app's functionality (`app/`)
3. Patch or replace the watch firmware via OTA
4. Run the watch's own firmware on the PC, so the watch's behaviour can be observed and
   changed without the hardware (`tools/watchemu/`)

## Code Quality Standards

Every piece of code in this repo is grounded in something the watch or its app actually
does — the NORM app smali for the protocol work, the firmware image itself for
`tools/watchemu/`. Before implementing any command, parsing any response, deciding on any
byte sequence, or modelling any register:
- **Read the corresponding smali** first. The source of truth is `NORM/smali_classes2/cn/appscomm/`. If the smali contradicts your assumptions, the smali wins.
- **For firmware work, the image is the source of truth.** Never guess a register value, a
  pin number or a device response: derive it from the firmware's own driver sequence, an
  address, or an `assert()` string, and cite that in the comment. Every device model in
  `watchemu` was built this way and it is why they work.
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

protocol/           ← pure JVM module: packet framing + command definitions (used by :app)
app/                ← Android companion app (Kotlin + Compose)
tools/
  emulator/         ← Pixel 8 ANDROID emulator + Bumble HCI bridge, for running :app
                       (the primary way to run/test :app — see "Testing on the Android emulator")
  watchemu/         ← the WATCH emulator: runs the watch's own Apollo3 firmware under
                       Unicorn, no hardware. See "Watch Firmware Emulator" below.
                       `normwatch cmd --mac` also asks the PHYSICAL watch from the PC.
  firmware/         ← image_tool.py: verifies and re-seals Ambiq firmware images
```

Note the two "emulators": `tools/emulator/` runs the **phone**, `tools/watchemu/` runs the
**watch**. They are unrelated codebases that meet only at card #25 (BLEIF -> HCI).

The smali code that matters is entirely in `NORM/smali_classes2/cn/appscomm/`.

---

## Build Commands

```bash
# Build debug APK (Android app)
./gradlew assembleDebug

# Build and install on connected device (or the emulator — use the DEBUG variant;
# release is unsigned and fails to install with INSTALL_PARSE_FAILED_NO_CERTIFICATES)
./gradlew installDebug

# Boot the test emulator (Pixel 8 AVD). Bluetooth-to-watch bridge is ON by default;
# -NoBridge for a UI-only boot. See "Testing on the emulator".
tools\emulator\launch-emulator.ps1
tools\emulator\launch-emulator.ps1 -NoBridge

# Ask the PHYSICAL watch one 0x6F question from the PC (bleak, this PC's own adapter).
# See "Asking the physical watch from the PC". Exit 0 a reply / 1 none / 2 setup failed.
PYTHONPATH=tools/watchemu py -3.11 -m normwatch cmd BATTERY_POWER CHECK --payload 00 --mac 4C:59:80:12:44:F1

# Run protocol unit tests (no BLE/Android needed)
./gradlew :protocol:test

# Run a single test class
./gradlew :protocol:test --tests "com.norm2hacked.protocol.PacketTest"

# Run lint
./gradlew lint
```

### Watch emulator (`tools/watchemu`) — Python 3.11, no Gradle

```bash
# One-time: same interpreter the Android emulator's BLE bridge uses
py -3.11 -m pip install -r tools/watchemu/requirements.txt

# Boot the watch firmware and print a triage report. --seconds is watch time;
# the default 30M-instruction budget is 0.6s, which is still inside the 8.3s
# boot animation, so pass --seconds 18 to reach the UI.
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot --seconds 18
# --no-ble is ~2.9x faster and reaches the same screen in --seconds 14; use it
# whenever the run is not about the radio. See "The BLE controller" below.
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot --seconds 14 --no-ble
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot --live        # window + mouse touch
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot --screenshot out.png
# A radio behind the firmware's BLE stack (a bumble controller): the watch advertises.
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot --radio --seconds 18
# ...and the Android emulator's netsim endpoint on the same virtual air, so the
# Pixel 8 AVD (launch-emulator.ps1 -Watch) pairs with the EMULATED watch.
# Implies --realtime. --hci-trace logs every packet across the seam.
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot --netsim --live
# Keep the watch's flash between runs: bind it once, and every later run with the same
# file boots to the face with its bond. Saved at exit, closed window and Ctrl-C included.
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot --netsim --live --flash-state watch.zip

# Skip the boot: save the machine once past the boot animation, start there next time
# (0.47s instead of 11s). Same switches on both; not with --radio/--netsim.
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot --seconds 9 --no-ble --save-state ui.snap
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot --no-ble --load-state ui.snap --seconds 2 --live
# Ask the firmware one 0x6F question: boots, pairs, binds if checkInit says 0, sends,
# prints every reply decoded. ~4s from a bound state file, ~22s binding a fresh watch.
# Hex or :protocol names; --char 8003 writes to the other write characteristic (a SET there
# is never acknowledged); --no-bind skips the bind. --mac MAC asks the physical watch instead.
PYTHONPATH=tools/watchemu py -3.11 -m normwatch cmd BIND_END CHECK --payload 00 --flash-state bound.zip --save
PYTHONPATH=tools/watchemu py -3.11 -m normwatch cmd 08 70 --payload 00 --flash-state bound.zip

# Parse the image header / list source modules recovered from assert() strings
PYTHONPATH=tools/watchemu py -3.11 -m normwatch info
PYTHONPATH=tools/watchemu py -3.11 -m normwatch modules

# Tests are standalone scripts, not pytest. Run one:
PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_native_hook.py

# Run all of them (each exits non-zero on failure):
for t in tools/watchemu/tests/test_*.py; do   PYTHONPATH=tools/watchemu py -3.11 "$t" >/dev/null || echo "FAIL $t"; done

# The C block hook builds itself on demand; to build it by hand:
py -3.11 tools/watchemu/native/build.py
```

Build config: `app/build.gradle.kts` — compileSdk 35, minSdk 30, JVM 17 target.

**Module graph:**
```
:protocol  (pure JVM — no Android deps, no BLE APIs)
    ↑
  :app     (Android)
```

---

## Testing on the Android emulator

**`:app` is developed and tested on a local Android emulator** (not a physical phone) — a
Pixel 8 AVD (`Pixel_8_API35`, AOSP Google-APIs Android 15 / API 35; no Android Studio). All
tooling lives in `tools/emulator/` (see its `README.md` for the full story).

- **Boot + install:** `tools\emulator\launch-emulator.ps1`, then `./gradlew installDebug`.
  Always use the **debug** variant — the release build is unsigned and won't install.
- **Real-watch BLE works in the emulator.** The emulator has no host Bluetooth radio, so a
  dedicated **USB BLE dongle (CSR8510 `0A12:0001`, driver swapped to WinUSB via Zadig)** is
  bridged in via **Bumble**. `launch-emulator.ps1` starts this bridge by default (`-NoBridge`
  = UI-only). The bridge is `tools/emulator/norm_emu_bridge.py` — a *custom* bridge, not stock
  `bumble-hci-bridge`, because it must (1) handle emulator 36.x's newer netsim `packet` proto
  field and (2) short-circuit `LE_GET_VENDOR_CAPABILITIES (0xFD53)`, which the CSR8510 ignores
  and which otherwise fatally aborts the guest BT stack. Don't "fix" the bridge by reverting
  either workaround.
- **Bonding gotcha:** the watch's SMP is racy (same as on a real phone — see "cold-connect
  penalty"). If a connect attempt fails to bond, toggle guest BT and retry:
  `adb -e shell svc bluetooth disable; adb -e shell svc bluetooth enable`.
- **The dongle is independent of the host's built-in adapter**, which is what `normwatch cmd
  --mac` uses (it bonds the watch with Windows on first use). Don't run the emulator BLE bridge,
  `normwatch cmd --mac` and the official app at the same time — they contend for the watch's
  single active connection.
- **Rebuild from scratch:** `tools\emulator\setup.ps1` (SDK packages + AEHD + AVD + Bumble);
  the Zadig WinUSB swap is the one manual step.
- **The emulated watch instead of the real one:** `launch-emulator.ps1 -Watch` skips the dongle
  and points the AVD at the watch emulator, which serves the same netsim endpoint itself
  (`normwatch boot --netsim --live`, start it first). `:app` bonds with it, binds it (the
  pairing screen's first-run handshake) and lands on the dashboard while the watch goes to
  its face; nothing physical involved. Verified on this AVD. Run the watch with
  `--flash-state FILE` and it keeps the bind and the bond: restart it with the same file and
  `:app` reconnects with its stored keys (the firmware answers the LTK request from flash),
  no wipe, no re-bind — verified on this AVD. Three things to expect: restarting the watch
  means restarting the AVD too (`adb emu kill`, then `-Watch` again — the emulator's packet
  streamer never reconnects to a new endpoint; bond and app data survive it); the AVD's bond
  has to be removed when the watch on the other end does not hold it — a new state file, or
  after the physical watch (`adb root`, delete the `[4c:59:80:12:44:f1]` section of
  `/data/misc/bluedroid/bt_config.conf`, toggle BT); and the first one or two connection
  attempts on a bonded reconnect stall in discovery before one goes through — the Database
  Hash park below, not the emulator. See "A real stack behind the seam", "Binding" and "The
  watch keeps what it writes" in `tools/watchemu/README.md`.

`normwatch cmd --mac` (below) is the fastest headless way to ask the *physical* watch a
0x6F question; without `--mac` the same command asks the emulated one, in ~4s from a bound
state file. Both write to 8001 as the app does and decode with the same code. The AVD is
for exercising the full `:app` stack (`BleManager`, sync, UI) against either.

---

## Watch Firmware Emulator (`tools/watchemu`)

> **Two different things are called "the emulator" in this repo.** `tools/emulator/` is the
> **Pixel 8 Android** emulator that runs `:app`. `tools/watchemu/` is the **watch** emulator
> that runs the watch's own firmware. This section is about the second one.

Runs `NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin` — the real firmware shipped inside the
APK — on an emulated **Ambiq Apollo3 Blue** (Cortex-M4F @ 48 MHz) under Unicorn. No watch
involved. It is a hardware emulator, not a protocol mock: nothing reimplements watch
behaviour, the behaviour comes from executing the watch's code. The same "read the source
of truth first" rule as the smali applies, with the firmware image as the source of truth.

`tools/watchemu/README.md` is long and is the real documentation — it records *why* each
piece is the way it is, mostly as post-mortems of wrong answers. Read it before changing
anything in `fw/`.

### Where the boot gets to

FreeRTOS boots, the scheduler ticks and preempts, the SPI NAND is identified against the
firmware's own part table, the resource blob mounts, and the panel draws. Run to 600M
instructions with no fault. Touch, buttons, the accelerometer, the battery gauge and the
charger are all modelled and answer the firmware's own driver sequences.

Two facts that cost a lot to rediscover, in this order:

1. **`ui_notify_poweroff_dlg.c` is a 134-frame boot animation**, ~2.95M instructions a
   frame, so ~400M total = **8.3 seconds of watch time**. The default `--max-instructions`
   of 30M is 0.6s, i.e. frame 8 of 134. Every early "the screen is black / the watch is
   stuck" investigation was a run that was too short. `boot` now says so when a run ends
   mid-animation and prints the budget that would clear it.
2. Past the animation the watch reaches **first-run setup** — "Select a Language", then a
   QR pairing screen showing `A0.2(R.T0.0H0.0B01)` / `Norm2#00000`. It ignores swipe and
   button there because it is waiting for a phone. **The watch face is behind first-run
   setup, and setup is behind the bind** — not the bond: bonding alone leaves it there.
   The companion app's `BindDevice.start6F` sends bindStart (0x93) / setDateTime (0x04) /
   bindEnd (0x94), the watch shows "Pairing Success" and goes to its face, and
   `tests/test_ble_end_to_end.py` and `:app`'s pairing screen both do exactly that now.
   `--flash-state FILE` keeps it: a watch bound once boots straight to its face.

### The BLE controller

**The radio powers up.** For a long time the story here was "the firmware never touches
BLEIF", and card #25 was written on that basis. It was wrong. `am_hal_ble_power_control`
(`0x0008A340`) was running and failing one step in, at
`am_hal_pwrctrl_periph_enable(BLEL)` (`0x0008D2C0`) — which polls `PWRCTRL.DEVPWRSTATUS`
for a bit it looks up in the firmware's own table at **`0x000CB1EC`** (three words per
peripheral: DEVPWREN mask, DEVPWRSTATUS mask). **Those are different bits.** This model
mirrored DEVPWREN into DEVPWRSTATUS, which satisfies every other peripheral by luck — MSPI
wants status `0x40` and gets it because IOM5's *enable* bit is `0x40` — but can never
satisfy BLEL, which wants status `0x100` from enable `0x2000`. The second half was the
address: every site that reads the controller's state uses **`0x5000C30C`**, not the
`0x308` this model had guessed.

With both fixed the firmware runs its whole bring-up at ~0.27s of watch time — CLKCFG,
`PWRCMD.RESTART`, `BLECFG=0x00200003`, `FIFOTHR`, `FIFOCTRL`, `PWRCMD=0x0D` — and hands
over to its HCI transport. `tests/test_ble_powerup.py` reads that table back out of the
image and compares, so the model cannot drift from the firmware.

**With nothing behind the BLEIF FIFO it costs about 2.1x, and that is expected**: the
transport polls `BSTATUS` inside a critical section: ~100M instructions of interrupt-masked
retry up front and periodic retries after, 5.5M MMIO reads over a boot, `bootrom_delay_cycles`
up from 190k to 4.3M, and 18% of the FreeRTOS ticks never delivered. The screen still gets
to the same place, one third later in budget terms (`--seconds 18` rather than 14).

**`--no-ble` withholds that one status bit**, which makes the firmware give up on the radio
exactly as it used to. It reproduces the pre-fix run *bit for bit* — same exception counts,
same 9,770 idle skips, same 509 NAND pages, same frame — so it is the right switch for any
run that is not about the radio, and the three optimisation tests use it. With the radio on
there is much less idle time to measure: `--idle-skip` still skips 276M instructions but is
worth 1.09x rather than 1.33x, because more of the wall clock is un-skippable MMIO.

All of this goes away when the FIFO answers — a `NationzController` (the default with
`--ble-controller`) or a real radio (`--radio`) — see below.

### The BLEIF transport, and the packet in the way

The FIFO is modelled and **the watch now sends HCI**. Every field came off the running
firmware, not a datasheet: `PWRCMD` bits [3:2] wake the controller (3) or let it sleep (2);
**`BSTATUS` bit 3** is the controller answering "awake, SPI ready" and is the gate on
everything (clear it and the firmware waits 48ms, gives up, retries); **`BSTATUS` bit 7**
means it has something to hand over; `CMD` carries direction in [4:0] and TSIZE in [19:8];
**`FIFOPTR` bits [15:8] are write room and bits [23:16] are the read count**; `INTSTAT`
bit 0 is transfer-complete. `Bleif` in `peripherals.py` cites the address of each.

*The `FIFOPTR` transposition is the trap:* the old placeholder returned `0x00200000`
meaning "plenty of transmit room", but 0x20 there is the **read** count. The send loop
reads [15:8], saw no room, and waited for ever.

**The first packet out of the watch is `01 bb f1 02 54 06`.** The builder at `0x00089C24`
writes it a byte at a time — `1` for an H4 command, an opcode low byte from a table, a
hardcoded `0xF1` high byte, length 2, and a 16-bit value from the same entry — so it is
Ambiq's vendor register/trim sequence, one command per table entry.

`attach_ble_controller(machine, controller)` puts something on the other side;
`--ble-controller` does it from the CLI and prints the captured HCI in the boot report.
`BleController` records and replays; a bumble `Controller` subclasses it later.

### The controller is a Nationz NZ8801, and it boots empty

That first packet is not standard HCI — it is the first command of a **firmware download**.
The watch's BLE controller is a **Nationz NZ8801** (the NVDS section names it), and it has
no firmware until the Apollo3 gives it some. Three commands per section, opcode high byte =
phase, low byte = section id:

```
01 <section> F1 02 <len lo> <len hi>      begin, total length
01 <section> F2 <n> <n bytes>             data, n <= 0x80
01 <section> F3 02 <crc lo> <crc hi>      end
```

Four sections: `0xBB` (1620 B of code), `0xCC` (560 B), `0xDD` (194 B — a RivieraWaves
**NVDS** blob, `[tag][0x06][len][data]`, tag 1 = BD address, tag 2 = `"NZ8801V1A"`), then
`0xEE` with length 0 meaning *run it*.

**The firmware tells you what to reply.** At `0x00089CC6` it builds the expected event and
memcmps it at `0x00089CEA`: `04 <section> <phase> 01 00` — five bytes, not an HCI Command
Complete, which is why an ordinary one is rejected. After the `0xEE` go-ahead the controller
restarts and announces itself *unsolicited* in the same shape; the firmware waits for that
at `0x00089F56` and checks only that the second byte is `0xEE`. From then on the link is
ordinary HCI, and the first thing across it is vendor command `0xFD04`.

`NationzController` (`devices.py`) implements all of it and keeps every downloaded section,
so `nvds_tags()` gives the part name and BD address straight off the wire. One trap: the
announcement is read **two bytes at a time** (`0x00089F64`), and the drain loop refuses to
move below four (`0x000899C2`) — so `FIFOPTR`'s count must be rounded up to a whole word or
the firmware parks for ever against its own threshold.

**With this the radio is fully up and costs nothing:** a 576M boot is 14.45s against 14.37s
with the radio off, all 16 tasks healthy, the BLE task parked on its event group, 136 frames
and 509 NAND pages exactly as before. The retry storm was never the price of a radio, only
of one nothing was talking back to.

Three details in that handshake each cost a debugging round and are each pinned by a test:

- **The go-ahead is announced, not acked.** The controller restarts into what it was given,
  so it cannot answer `01 EE F1`; it sends *one* message. Queueing both an ack and an
  announcement leaves five bytes behind, skews every later read, and the firmware restarts
  the download from `0xBB`.
- **A read is padded to TSIZE.** The bus clocks TSIZE bytes however few the controller has.
  The firmware reads an HCI event in a fixed nine-byte window (`0x0008A058`); hand back only
  the seven bytes of a Command Complete and its drain loop waits for two more for ever.
- **`INTSTAT` bit 8 mirrors `BSTATUS` bit 3.** `0x000895A4` reads *either*, depending on a
  handle byte the firmware sets at `0x0008A076` at the end of bring-up. Model only BSTATUS
  and the firmware gets all the way up, then decides its controller has gone away.

### Where the radio stands: on the air

`blelink.py` puts a bumble `Controller` on a `LocalLink` behind the NZ8801 seam, and the
watch is on the air with it: it advertises as `Norm2#00000` at the physical watch's address
(`--address`), a phone connects, pairs (LE *legacy* Just Works — the firmware's Pairing
Response is `02 03 00 01 10 02 01`, AuthReq 0x01, no Secure Connections), discovers the GATT
table (`6006`/`8001-8004`, `1530`/`1531-1532`, `FEE7`) and gets 0x6F answers from the
firmware. `tests/test_ble_end_to_end.py` does all of that with a bumble host, then
restarts the watch from its saved flash and reconnects with the stored keys, in ~100s;
`--netsim` does it with the Pixel 8 AVD (`launch-emulator.ps1 -Watch`).

The seam answers three kinds of thing itself and forwards the rest: the download phase
(not HCI), every OGF 0x3F vendor opcode (bumble answers those with silence), and the LE
crypto primitives `LE_Read_Local_P-256_Public_Key`, `LE_Generate_DHKey` and `LE_Encrypt`
— bumble 0.0.229's P-256 handler is a `TODO` stub and the other two do not exist, and with
0x2025 unanswered Cordio's command queue never reopened, so the watch never advertised.
They are real P-256/AES-128 from `cryptography`, and the phone checks the results.

Unsolicited events reach the firmware through **IRQ 12**: `HciDrvRadioBoot` ends
(`0x0004704E`) with INTCLR/INTEN 0x281 and `NVIC_EnableIRQ(12)`; the ISR (`0x000889CC`)
reads INTSTAT & INTEN, clears it and wakes the HCI task. INTSTAT bit 7 (BLECIRQ) is the
interrupt form of BSTATUS bit 7, latched on its rising edge, and `Bleif.pending_irqs`
raises it.

**Bumble's controller has nine gaps a phone walks into, each of which looked like a hang**
— the P-256 stub above; silence for unknown opcodes; LE data stamped with the sender's
*random* address (the watch uses its public one, so its ATT responses were dropped); a
filter accept list that is never consulted (Android connects through it); no
`Read_Remote_Version_Information` (Android will not discover services without it); no
`LE_Connection_Update` (jams the central's command queue); own_address_type 2 read as
"random" (after a bond the watch re-advertises as `00:00:00:00:00:00` and no reconnect
matches); and data delivered *now* rather than at the next connection event, which lands
the phone's next write inside the firmware's reply-then-clear window on 8001 and gets it
answered `00 02`; and encryption reported on both ends without the peripheral's host ever
being asked for its key, so the firmware never looked up a bond or refused one (a restart
test passed with the restored flash thrown away). `VirtualController` and `Air` in
`blelink.py` close them,
`tests/test_ble_link.py` and `tests/test_ble_end_to_end.py` pin them, and the README's "A
real stack behind the seam", "Binding" and "The watch keeps what it writes" tell each
story. Android additionally aborts
unless the controller claims Secure Simple Pairing, a BR/EDR bit the phone's controller
now claims.

**Three firmware facts the bind exposed** (all cited in the README's "Binding"): a SET
written to 8003 is never acknowledged while the same SET on 8001 gets `6F 01 81 02 00
<cmd> <status>` — two dispatchers in front of one command table, and `normlink-cli`
(since removed) wrote to 8003; bindStart opens a 300-frame window in `ui_notify_pairing_dlg.c` that only bindEnd
closes; and a Read By Type for the Database Hash (0x2B2A) is parked for ever, because
`AttsAddGroup` sets Cordio's hash-update flag and `AttsCalculateDbHash()` is never called.
Android reads the hash by type before discovery on every connection after the bonding one,
so `:app`'s discovery stalls until its watchdog reconnects and the third attempt goes
through. That is very likely `BleManager`'s "cold-connect penalty" on the physical watch
too, but it has not been checked on hardware.

**Two rules when touching the seam.** Everything on the bumble side lives on one loop
(`Radio`): `LocalLink` dispatches on the *sender's* running loop, so controllers on
different loops cannot hear each other. And a phone keeps real time, so the watch must
too: `--netsim` implies `--realtime`, which sleeps whenever watch time is ahead of the
wall clock and never tries to catch up; when the watch falls *behind* (drawing the pairing
animation does it), `Apollo3Machine.realtime_lag` says by how much, and `Air` delivers
data into the watch on the watch's clock so the firmware's timing windows stay the width
the firmware expects. `--hci-trace` is the first thing to turn on when the two disagree
about what was said.

### The invariants that break silently

- **The clock unit is halfwords, not instructions.** `size >> 1` per basic block;
  48,000,000 = one second of watch time. Unicorn's `emu_start(count=)` counts
  *instructions* — mixing the two ran the clock ~30% fast and showed up as double the
  timer interrupts, not as anything that looked like a bug.
- **Interrupt deliverability is checked every basic block, not once per slice.** FreeRTOS
  sets PendSV inside a critical section and expects the switch the instant BASEPRI is
  released. ~100 instructions late and `vTaskSwitchContext` early-returns, the yield is
  lost, and the task re-blocks on a queue it is already on — which corrupts the event list
  into a self-referential node and hangs the system.
- **Never register a Unicorn memory hook on this machine.** A `UC_HOOK_MEM_WRITE` over
  SRAM does not merely miss writes, it derails the run (dies at ~155k instructions on an
  unmapped fetch with `pc=0`) *and* reports the same ~103,659 writes whatever the run
  length, which is what makes it so convincing. Use `machine.watch()`, which is
  block-hook based.
- **Stopping from a block hook leaves the block unexecuted**, so it is hooked again on
  resume. Both hooks carry a `just_stopped`/`stopped_address` guard. Without it the clock
  double-counts, and with a large quantum the run *livelocks* — clock advancing, CPU
  executing nothing, looking exactly like a firmware hang.

### Architecture

`Apollo3Machine.run()` is the centre. It slices execution, and after every `emu_start`
returns it advances the clocks, fires scheduled stimulus, polls IRQ sources and delivers
the highest-priority pending exception. The block hook only ever *counts* and asks to
stop; it never advances anything. That is deliberate — see below.

- **`cortexm.py`** implements exception entry and return in software. Unicorn's M-profile
  core has no PPB and raises `UC_ERR_EXCEPTION` on an `EXC_RETURN` branch, so SVCall,
  PendSV and SysTick — the three FreeRTOS is built on — are all handled here.
- **`peripherals.py`** models registers; **`devices.py`** models the chips hanging off the
  buses (SPI NAND, PSRAM, RM67162 panel, touch, accelerometer, CW6303 PMU/charger). A
  device implements some of: `read`/`write`, `exchange` (SPI), `advance(cycles)` (state
  that evolves on the clock), `pending_irqs()`, `next_deadline()`.
- **Two block hooks that must stay indistinguishable.** The Python one in `machine.py` and
  the C one in `native/watchemu_hook.c`, loaded through `fasthook.py`, which builds it on
  demand when the DLL is missing *or older than the .c*. `tests/test_native_hook.py`
  demands their exception counts match **exactly**; that is not pedantry, it is what
  caught the halfword/instruction bug and a stale-pending bug that made every peripheral
  IRQ fire twice. When the two hooks were allowed to have different *shapes*, the
  difference silently masqueraded as a property of `--idle-skip` for months.
- **The quantum comes from the next deadline**, not a fixed grid: each clock-driven source
  says when it next matters and the run loop goes exactly that far. `Mspi.pending_irqs`
  and `Gpio.pending_irqs` are deliberately excluded — the first *walks the command queue
  as a side effect* (poll rate = how fast pixels reach the panel) and the second is
  level-sensitive (poll rate = how often a pin re-pends). For those the rate is the model,
  so they keep a 256-cycle interval while active and offer no deadline when idle.

### Performance switches, and which are exact

Measured over a full 445M-instruction boot to the UI (9.27s on the watch itself):

| switch | worth | exact? |
|---|---|---|
| C block hook (default; `--no-fast-hook` off) | 3.3x | **yes** — identical, including instruction totals |
| `--idle-skip` | 1.6x | **yes** — identical at 20M/100M/200M/445M on both hooks |
| deadline quantum (default; `--fixed-quantum` off) | 1.6x | **no** — outputs identical, interrupts move <=0.4% |
| `--trace` | costs 28% | yes, but forces the Python hook |

`--idle-skip` and the deadline quantum overlap (both remove idle time), so together they
are worth 1.95x rather than 2.5x. `--trace` and `machine.watch()` both need per-block
Python and switch the C hook off on their own.

**Those figures are `--no-ble` figures** — they were all taken before the radio powered up,
and they still hold with `--no-ble`. With the radio up the C hook is still bit-identical
and the deadline quantum is off by the same <=0.4%, but `--idle-skip` is not quite: with
the NZ8801 model answering it delivers exactly one STIMER interrupt fewer, lost once
between 225M and 250M, everything else identical (#54, pinned in `tests/test_golden.py`).
Radio off it is exact over a full boot. What changed is what they are worth, measured over the same 576M boot with the
radio on: C hook **4.89x**, `--idle-skip` **1.09x**, deadline quantum **1.31x**. Say which
of the two you measured — quoting a `--no-ble` figure for a BLE-on run is the mistake this
note exists to prevent.

The library default `Apollo3Machine(fast_hook=...)` is **False** while the CLI default is
on, so probes and tests keep watchpoints.

**When measuring anything, say which configuration you measured.** A "35M instr/s" number
taken with `--idle-skip` is not what `normwatch boot` does, and quoting one as the other
has already gone wrong once here.

### Working on it

Claims about firmware behaviour need firmware evidence — an address, an assert string, a
traced value. Two specific traps:

- **A linear Capstone sweep of the whole image desynchronises** and will report that an
  instruction does not exist when it does. Search for the encoding directly.
- **`symbols.py` is nearest-neighbour.** `AssertMap` gives exact points from `assert()`
  strings, but attributing an arbitrary address still interpolates. Confirm module
  identity from an assert operand before trusting an attribution — "hot charger blocks"
  seen during exploration turned out to be a shared critical-section helper.

Scratch probes are throwaway and live outside the repo; anything worth keeping becomes a
test. Every optimisation so far has been accepted or rejected on a before/after
fingerprint captured **on the same tree** (stash, capture, pop, capture) — comparing two
settings within one build cannot catch a change that moved both. That is now
`tests/test_golden.py`: four deterministic workloads (radio off, radio on, a language tap,
a swipe on the face from a bound-state fixture) against fingerprints recorded in
`tests/golden.json`, in the ordinary test loop. After a change that is *meant* to move the
watch, re-record with `test_golden.py --update` and read the diff it prints.

---

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
    ota/OtaProgress.kt         OTA progress types
  domain/model/Models.kt       DailyStats, SleepSummary, WatchSettings, SportType, etc.
protocol/src/test/kotlin/...
  PacketTest.kt                Unit tests for PacketBuilder + PacketDeframer
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

---

## Asking the physical watch from the PC (`normwatch cmd --mac`)

**The fastest headless way to ask the physical watch a 0x6F question** — no APK, no AVD,
and Claude can run it directly. It is `normwatch cmd` (see the watch emulator's build
commands) with `--mac`: the same flow and the same decoder as against the emulated watch,
over this PC's own Bluetooth adapter with bleak (`tools/watchemu/normwatch/physical.py`).
Where the two answers differ, the watches differ — this is how an emulator finding is
checked on hardware.

```bash
PYTHONPATH=tools/watchemu py -3.11 -m normwatch cmd BATTERY_POWER CHECK --payload 00 --mac 4C:59:80:12:44:F1
PYTHONPATH=tools/watchemu py -3.11 -m normwatch cmd 03 70 --payload 06 --mac 4C:59:80:12:44:F1
```
```
   2.2s  phone: connected, paired, encryption not reported
   2.4s  watch: initialised (checkInit 1), not binding
   2.4s  -> 8001  6f 08 70 01 00 00 8f    0x08 BATTERY_POWER CHECK [00]
   3.1s  <- 8002  6f 08 80 01 00 5f 8f
   3.1s           = 0x08 BATTERY_POWER CHECK_RESPONSE [5f]
```

**Watch MAC:** `4C:59:80:12:44:F1`. Needs `py -3.11 -m pip install -r
tools/watchemu/requirements.txt` (bleak).

What it does, as `BindWatchUseCase` does: scan, connect, bond, subscribe to 8002/8004
*before* the first write, checkInit (0x94 CHECK), the bind only if that reads 0
(`--no-bind` skips both), then the frame to **8001** with response and `[03]` to 8002
without, and every notification until a whole frame has come and 0.5s passed — decoded by
`fw/phone.py` (`Deframer`, `describe`, names from `CommandCode.kt`). Exit status: 0 a reply
came, 1 nothing came back, 2 the setup failed. The watch serves one connection at a time,
so close the companion app or `:app` first. `tests/test_physical.py` pins the
phone's behaviour against a fake `BleakClient`.

**Windows needs the watch BONDED.** Unbonded, characteristic discovery fails
(`GattCommunicationStatus.Unreachable`): the watch drops the link during Windows' slow live
discovery. Bonded, Windows caches the GATT table and discovery is instant. The default
`PairAsync()` ceremony is rejected by the watch (recorded as status 19); the bond is made
with *custom* pairing instead — a `PairingRequested` handler that accepts, and
`DevicePairingKinds.ConfirmOnly` at `DevicePairingProtectionLevel.None` (Just Works: no PIN,
no MITM; the watch's own Pairing Response asks for exactly that). `--mac` does it on first
use (`physical.bond_windows`); the bond is the OS's and persists. Verified 2026-10-02:
the bond went through, but the connect straight after it ran out its 30s and the next
took 1.3s — so after a fresh bond the connect is tried twice.

**History.** This replaced `normlink-cli` (`:cli`, removed 2026-10-02): `:protocol`'s
Kotlin parsers over a Python/bleak child process. Its bridge chose 8003 whenever the watch
had it, and the firmware's 8003 dispatcher (`0x00036460`) throws a handler's result away,
so it only ever saw CHECK replies and every SET timed out. Before that bridge, a pure-Kotlin
WinRT-via-JNA transport was abandoned; two of its bugs are worth remembering if anyone
revisits raw WinRT: `BluetoothLEAdvertisementWatcher.Start()` is vtable **[17]** (not 21 =
`add_Stopped`); and `IAsyncOperation<T>` and `IAsyncInfo` are **separate** interfaces —
`get_Status` is on `IAsyncInfo` (QI `{00000036-…}`), `GetResults` is `IAsyncOperation[8]`,
and reading status at `IAsyncOperation[7]` hits `get_Completed` and hangs every async op.
The wall that killed it: `add_ValueChanged` → `CO_E_NOT_SUPPORTED` (it needs a
Free-Threaded-Marshaler-aggregated agile delegate).

---

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
See "Binding" in `tools/watchemu/README.md`. Not yet confirmed on the physical watch; if it
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

`NotificationForwarder` (extends `NotificationListenerService`) — intercepts system notifications,
runs the **junk filter** (`NotificationFilter.decide`, drops charging/media/foreground-service/
progress/group-summary/local-only/empty and the dialer's call notifications), checks per-app rules
from the DB, deduplicates within a 30-second window via `RecentNotificationCache` (uses
`ConcurrentHashMap.compute` for atomic check-and-set), then forwards to the watch via
`MessageNewCommand`. RTL title/content is reordered to visual order via `BidiUtil.formatRtlString`
before framing (see "Feature status — verified on-device").

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

**Still TODO:** in-app notification-listener permission flow (no UI yet — granted via adb for now).
If a future firmware reports the `Perfect` generation, implement `MessagePerfectBT` (id `0x79`,
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
- `0x0E` UPGRADE_MODE ← OTA mode, inside the application (no bootloader, no reset)

### Apollo DFU (Firmware Update)

**Verified against the watch's own firmware in the emulator (2026-10-02, `tests/test_ota.py`,
README "Rehearsing an OTA"); not yet run on the physical watch.** A full resource update
of the unmodified `Picture_P03B_NORM2_0.4.bin` goes through: CRC `04 01`, REBOOT `05 01`
(the app's 0x64), the partition byte-identical, and the watch boots identically after it.

**`ApolloOtaProtocol.kt` does NOT match this and will fail at SET** -- see the list below
and card #56. Do not run it against the watch until it is fixed and has passed against
the emulated watch.

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

## Current Status & Known Issues

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
    progress, group summaries, local-only, empty content). Pure `decide(NotificationFacts)` rule
    chain + `StatusBarNotification.toFacts()` extractor; unit-tested. Per-app enable/vibrate/mute
    rules and 30s dedup (`RecentNotificationCache`) still apply on top.
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
- Room database v3 with unique constraints and 2 migrations
- Notification forwarding with per-app rules, junk-type filtering, RTL (Hebrew/Arabic), and dedup
- Phone calls: forward incoming/missed/ended with caller name + answer/reject from the watch (`call/`)
- Watch-hands calibration: guided re-align of the physical hands (`ui/screens/calibration/`)
- Apollo DFU OTA (all 5 steps, channelFlow-based, fail-fast on 0x66)
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

### CHECK commands — verified working (on the physical watch)

Every CHECK command requires a `[0x00]` payload (1 byte); the watch silently ignores empty-payload commands. Wire packet: `[6F][cmd][0x70][01][00][00][8F]`. Confirmed on the physical watch through `normlink-cli` (since replaced by `normwatch cmd --mac`): `BATTERY_POWER`, `SCREEN_BRIGHTNESS` → 60, `TOTAL_SPORT_SLEEP_COUNT` → 29 sport. (Still pending verification in the Android `:app` on-device.)

**Note:** `DEVICE_VERSION` (cmd `0x03`, payload `[06]`) times out — a command-specific payload quirk to investigate (not a connection issue). Over 8001 (which `:app` uses) the emulated watch answers it with the generic `6F 01 81 02 00 03 01`, i.e. status 1, refused — bound or not; over 8003 (where `normlink-cli` wrote) nothing comes back for any SET-style refusal, which is the "timeout". `normwatch cmd 03 70 --payload 06` shows both. **Confirmed on the physical watch** (2026-10-02, `--mac`): over 8001 it answers the same `6F 01 81 02 00 03 01 8F`, byte for byte — the request is refused, not lost.

### CHECK commands — fixed (pending on-device verification)

**Root cause:** Every CHECK command requires `[0x00]` payload (1 byte). Watch silently ignores empty-payload commands. Fixed in `DashboardViewModel.kt` and `SyncHealthDataUseCase.kt`.

Wire packet: `[6F][cmd][0x70][01][00][00][8F]`

**Status: Fixed in code, not yet verified on Android device.**

### Watch emulator — status

Boots the real firmware to first-run setup; runs at **1.25x watch speed** at CLI defaults
and 1.00x with `--idle-skip`, from 8.7x slower when the work started. Touch, buttons,
accelerometer, battery/PMU, charger, SPI NAND, PSRAM and the display panel are all
modelled from the firmware's own driver sequences. 25 test files, all standalone scripts.

**The radio works end to end, and so does first-run setup** (card #25): the firmware's own
BLE stack runs behind a bumble controller, and with `--netsim` the Pixel 8 AVD's `:app`
bonds with the emulated watch, binds it (bindStart / setDateTime / bindEnd, the companion
app's post-QR handshake) and reads its battery, while the watch goes from "Select a
Language" to its face — `watchemu` and `tools/emulator/` meet there, with no hardware at
all. With `--flash-state` (#41, done) it stays that way across restarts: the watch boots to
its face and the phone reconnects with its stored keys, and `normwatch cmd` (#49, done) asks
it any 0x6F question in ~4s. `--save-state` / `--load-state` (#46, done) skip the boot for
everything that is not about the radio: 0.47s to the UI instead of 11s, and a restored run
lands on the golden fingerprints exactly. Open cards, in the order to do them (the
README's "What to build next" says why):

- **#54 `--idle-skip` one tick short with the radio up** — found by the golden test
  (#47, done); one lost STIMER between 225M and 250M.
- **#56 fix `ApolloOtaProtocol.kt`** against the emulated watch — the OTA rehearsal (#50,
  done) showed it would fail at SET (type 8) and diverges in five more places.
- **#51 health records** — so `SyncHealthDataUseCase` runs something other than its
  empty paths; #52 script the AVD's bond removal; #53 the MMIO at `0x50023800`.
- **#48 Database Hash on hardware** — not emulator work: the firmware parks Android's
  Read By Type for 0x2B2A (see "Binding" in the README); check the physical watch does
  the same, and if so rewrite `BleManager`'s cold-connect story and its watchdog.

### Pending features (priority order)

*(Done ✅ — see "Feature status — verified on-device" above: notifications→watch incl. RTL + junk
filter, phone calls incl. answer/reject, watch-hands calibration.)*

1. **Notification-listener permission flow** — the forwarder works but the listener permission is
   still granted via adb; add the in-app enable-permission UI.
1. **Reject-call-with-SMS** — the watch's incoming-call screen has a "send message" (canned-SMS)
   button; not yet implemented (needs `SEND_SMS` + RE of the watch's send command — it's not the
   `0xDC` accept/reject command).
1. **Music as a control channel** — the original's `MusicManager`/`MediaController` (play/pause/next/
   prev + now-playing via `KEYCODE_MEDIA_*`); we currently just suppress media notifications.
1. **Verify warm-connection on-device** — the feature is implemented (see "Warm-connection via `BleService`"); confirm a warm reconnect is sub-second vs the ~8s cold path, then tune `KEEPALIVE_INTERVAL_MS` / `REKICK_DELAY_MS`.
1. **Verify CHECK commands in the Android `:app` on-device** — the bridge-backed CLI confirms the protocol; the app's `BleManager` path still needs device verification.
2. **`DEVICE_VERSION` payload quirk** — cmd `0x03` with payload `[06]` times out; find the correct request format.
3. **Verify CHECK commands on Android** — deploy APK, confirm battery/version/sync receive responses
4. **GPS data display** — `GET_GPS_DATA (0x6B)` infrastructure exists; parse response and render tracks
4. **Blood pressure sync** — entity + DAO exist; wire `SyncHealthDataUseCase`
5. **Weather sync** — find command code in `BluetoothCommandConstant.smali`

### Known gaps
- No unit tests for the BLE layer — protocol parsing is covered in `:protocol:test`
- `writeToChar` (fire-and-forget) has no error surface
- `BleService` keep-alive uses an RSSI read as a generic link probe; whether the watch's idle-drop watchdog actually resets on it is unverified (so far relying on connection supervision) — confirm on-device, switch to a periodic battery CHECK if needed

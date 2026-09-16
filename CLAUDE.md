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

protocol/           ← pure JVM module: packet framing + command definitions
                       shared by both :app and :cli
app/                ← Android companion app (Kotlin + Compose)
cli/                ← normlink-cli: Windows JVM tool for autonomous BLE testing
  src/main/python/norm2_probe/  ← Python bleak BLE backend (bridge for the CLI)
tools/
  emulator/         ← Pixel 8 ANDROID emulator + Bumble HCI bridge, for running :app
                       (the primary way to run/test :app — see "Testing on the Android emulator")
  watchemu/         ← the WATCH emulator: runs the watch's own Apollo3 firmware under
                       Unicorn, no hardware. See "Watch Firmware Emulator" below.
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
CLI config: `cli/build.gradle.kts` — pure JVM, installs to `cli/build/install/normlink-cli/`.

**Module graph:**
```
:protocol  (pure JVM — no Android deps, no BLE APIs)
    ↑           ↑
  :app        :cli
(Android)   (Windows JVM)
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
- **The dongle is independent of the host's built-in adapter**, which stays bonded to the watch
  for `normlink-cli`. Don't run the emulator BLE bridge, `normlink-cli`, and the official app at
  the same time — they contend for the watch's single active connection.
- **Rebuild from scratch:** `tools\emulator\setup.ps1` (SDK packages + AEHD + AVD + Bumble);
  the Zadig WinUSB swap is the one manual step.
- **The emulated watch instead of the real one:** `launch-emulator.ps1 -Watch` skips the dongle
  and points the AVD at the watch emulator, which serves the same netsim endpoint itself
  (`normwatch boot --netsim --live`, start it first). `:app` bonds with it, binds it (the
  pairing screen's first-run handshake) and lands on the dashboard while the watch goes to
  its face; nothing physical involved. Verified on this AVD. Two things to expect: a stale
  bond with the physical watch (same address) has to be removed first (`adb root`, delete
  the `[4c:59:80:12:44:f1]` section of `/data/misc/bluedroid/bt_config.conf`, toggle BT —
  the emulated watch keeps no bond across runs, so this is every run); and the first two
  connection attempts after bonding stall in discovery and the third goes through — that is
  the Database Hash park below, not the emulator. See "A real stack behind the seam" and
  "Binding" in `tools/watchemu/README.md` for the bumble gaps that made this look impossible.

`normlink-cli` (below) remains the fastest headless way to sanity-check the wire protocol;
the emulator is for exercising the full `:app` stack (`BleManager`, sync, UI) against the watch.

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
   The watch forgets it on restart until #41 (flash persistence).

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
firmware. `tests/test_ble_end_to_end.py` does all of that with a bumble host in ~10s;
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

**Bumble's controller has eight gaps a phone walks into, each of which looked like a hang**
— the P-256 stub above; silence for unknown opcodes; LE data stamped with the sender's
*random* address (the watch uses its public one, so its ATT responses were dropped); a
filter accept list that is never consulted (Android connects through it); no
`Read_Remote_Version_Information` (Android will not discover services without it); no
`LE_Connection_Update` (jams the central's command queue); own_address_type 2 read as
"random" (after a bond the watch re-advertises as `00:00:00:00:00:00` and no reconnect
matches); and data delivered *now* rather than at the next connection event, which lands
the phone's next write inside the firmware's reply-then-clear window on 8001 and gets it
answered `00 02`. `VirtualController` and `Air` in `blelink.py` close them,
`tests/test_ble_link.py` and `tests/test_ble_end_to_end.py` pin them, and the README's "A
real stack behind the seam" and "Binding" tell each story. Android additionally aborts
unless the controller claims Secure Simple Pairing, a BR/EDR bit the phone's controller
now claims.

**Three firmware facts the bind exposed** (all cited in the README's "Binding"): a SET
written to 8003 is never acknowledged while the same SET on 8001 gets `6F 01 81 02 00
<cmd> <status>` — two dispatchers in front of one command table, and `normlink-cli` writes
to 8003; bindStart opens a 300-frame window in `ui_notify_pairing_dlg.c` that only bindEnd
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
and they still hold with `--no-ble`. None of the switches became *inexact* when it did: the
C hook and `--idle-skip` are still bit-identical, and the deadline quantum is off by the
same <=0.4%. What changed is what they are worth, measured over the same 576M boot with the
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
settings within one build cannot catch a change that moved both. Card #47 is to make that
a checked-in test instead of a scratchpad script.

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
one. Against the emulated watch this reproduces the symptom precisely: attempts 1 and 2 stall
in discovery, attempt 3 goes through once the stack gives up on the hash. See "Binding" in
`tools/watchemu/README.md`. Not yet confirmed on the physical watch; if it holds, the SMP
theory above is describing the same stall from the wrong side.

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

**Note:** `DEVICE_VERSION` (cmd `0x03`, payload `[06]`) times out — a command-specific payload quirk to investigate (not a connection issue). Over 8001 (which `:app` uses) the emulated watch answers it with the generic `6F 01 81 02 00 03 01`, i.e. status 1, refused; over 8003 (the CLI) nothing comes back for any SET-style refusal, which is the "timeout".

### CHECK commands — fixed (pending on-device verification)

**Root cause:** Every CHECK command requires `[0x00]` payload (1 byte). Watch silently ignores empty-payload commands. Fixed in `DashboardViewModel.kt` and `SyncHealthDataUseCase.kt`.

Wire packet: `[6F][cmd][0x70][01][00][00][8F]`

**Status: Fixed in code, not yet verified on Android device.**

### Watch emulator — status

Boots the real firmware to first-run setup; runs at **1.25x watch speed** at CLI defaults
and 1.00x with `--idle-skip`, from 8.7x slower when the work started. Touch, buttons,
accelerometer, battery/PMU, charger, SPI NAND, PSRAM and the display panel are all
modelled from the firmware's own driver sequences. 19 test files, all standalone scripts.

**The radio works end to end, and so does first-run setup** (card #25): the firmware's own
BLE stack runs behind a bumble controller, and with `--netsim` the Pixel 8 AVD's `:app`
bonds with the emulated watch, binds it (bindStart / setDateTime / bindEnd, the companion
app's post-QR handshake) and reads its battery, while the watch goes from "Select a
Language" to its face — `watchemu` and `tools/emulator/` meet there, with no hardware at
all. Open cards:

- **Database Hash on hardware** — the firmware parks Android's Read By Type for 0x2B2A
  (see "Binding" in the README); check the physical watch does the same, and if so
  rewrite `BleManager`'s cold-connect story and its watchdog around that.
- **#41 flash persistence** (`--flash-state PATH`) — the cheap route: keep the watch
  provisioned across runs so it starts past setup. Also unblocks #13 (OTA rehearsal).
- **#46 snapshot/restore** — every probe currently pays a 12s boot; this is the biggest
  iteration win left, and composes with #41.
- **#47 golden-fingerprint test** — the equivalence checks that gate every optimisation
  still live in throwaway scripts.

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
- No unit tests for the BLE layer or `PythonBridgeTransport` — protocol parsing is covered in `:protocol:test`
- `writeToChar` (fire-and-forget) has no error surface
- `BleService` keep-alive uses an RSSI read as a generic link probe; whether the watch's idle-drop watchdog actually resets on it is unverified (so far relying on connection supervision) — confirm on-device, switch to a periodic battery CHECK if needed

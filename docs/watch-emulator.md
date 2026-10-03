# The watch emulator, the short version

`docs/watch-emulator-internals.md` is the long form: why each piece is the way it is, mostly as
write-ups of wrong answers. This is the summary that used to live in `CLAUDE.md`, which now
keeps only the rules for working on it (the invariants that break silently, and how to make
claims about the firmware).

> **Two different things are called "the emulator" in this repo.** `normphone` (`tools/normplus/phone/`) is the
> **Pixel 8 Android** emulator that runs `:app`. `normwatch` (`tools/normplus/watch/`) is the **watch** emulator
> that runs the watch's own firmware. This section is about the second one.

Runs `NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin` — the real firmware shipped inside the
APK — on an emulated **Ambiq Apollo3 Blue** (Cortex-M4F @ 48 MHz) under Unicorn. No watch
involved. It is a hardware emulator, not a protocol mock: nothing reimplements watch
behaviour, the behaviour comes from executing the watch's code. The same "read the source
of truth first" rule as the smali applies, with the firmware image as the source of truth.

`docs/watch-emulator-internals.md` is long and is the real documentation — it records *why* each
piece is the way it is, mostly as post-mortems of wrong answers. Read it before changing
anything in `fw/`.

## Where the boot gets to

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

**The watch's factory resources are in `NORM/_nand` and are mounted by default (#65, #71).**

```bash
normwatch boot --live                         # the watch as it really looks
normwatch boot --live --no-factory-resources  # ~22s faster, and "No data" everywhere
```

**They cost about 22 s on a boot that plays the whole animation** -- 134 full-screen
images really decoded instead of failing fast, which is 18,420 NAND page reads through
the emulated SPI against 509 without. The mounting itself is 0.176 s of that; the rest is
the data moving. The penalty tracks how much of the animation plays:

| `--seconds` | without | with | cost |
|---|---|---|---|
| 2 | 3.2 s | 8.2 s | +5.1 s |
| 6 | 7.8 s | 23.9 s | +16.0 s |
| 14 (animation done) | 15.8 s | 37.4 s | +21.6 s |

So `--no-factory-resources` is the `--no-ble` of artwork: use it when the run is not about
what is on the screen. `--no-resources` leaves the NAND erased altogether, and any
explicit `--nand` replaces the default rather than adding to it. Nothing in the test suite
goes through this path -- the tests build their machines directly -- so none of this
changes what they cost.

Without them most screens show "No data" and the boot animation runs black -- which is
missing data, not broken drawing (#64): LVGL's placeholder for an image it could not
decode, for images that live in tens of MB of NAND from `0x026DA430` upwards and in no
file the app ships. The emulated NAND otherwise has only the app's 401 KB resource image.

Those 40 MB were read off the physical watch on 2026-10-03 over a patched 0xEE (#70,
`docs/firmware.md` section 11) and are the vendor's material, so they live in the private
reference repo rather than here. With them mounted the boot report says `images: every
image the firmware drew was in the NAND`, the animation plays its 134 frames, and the
setup screen has its artwork. The live window counts what is missing ("N images not in the
NAND") and the boot report lists the ranges. See "Most of the watch's images are not in
the resource image" in `watch-emulator-internals.md`.

## The BLE controller

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

## The BLEIF transport, and the packet in the way

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

## The controller is a Nationz NZ8801, and it boots empty

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

## Where the radio stands: on the air

`blelink.py` puts a bumble `Controller` on a `LocalLink` behind the NZ8801 seam, and the
watch is on the air with it: it advertises as `Norm2#00000` at the physical watch's address
(`--address`), a phone connects, pairs (LE *legacy* Just Works — the firmware's Pairing
Response is `02 03 00 01 10 02 01`, AuthReq 0x01, no Secure Connections), discovers the GATT
table (`6006`/`8001-8004`, `1530`/`1531-1532`, `FEE7`) and gets 0x6F answers from the
firmware. `tests/test_ble_end_to_end.py` does all of that with a bumble host, then
restarts the watch from its saved flash and reconnects with the stored keys, in ~32s;
`--netsim` does it with the Pixel 8 AVD (`normphone start --watch`).

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
the firmware expects. So nothing waits for the watch to catch up: a phone binds once the
setup screen is *drawn* -- the panel's 136th frame, not the animation's last -- and that
is all (#55; README "Binding"). `--hci-trace` is the first thing to turn on when the two
disagree about what was said.

The factory resources make that lag bigger, because the animation is now really drawn
(#71): they are mounted by default on `boot`, so a `--netsim` session that only needs the
pairing to happen is quicker with `--no-factory-resources`. Nothing about the handshake
depends on it -- the phone binds on the setup screen being drawn, and `Air` keeps to the
watch's clock either way -- but there is no reason to spend the 22 s if nobody is looking
at the screen.

## Architecture

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

## Performance switches, and which are exact

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

## Status

Boots the real firmware to first-run setup; runs at **1.25x watch speed** at CLI defaults
and 1.00x with `--idle-skip`, from 8.7x slower when the work started. Touch, buttons,
accelerometer, battery/PMU, charger, SPI NAND, PSRAM and the display panel are all
modelled from the firmware's own driver sequences. 26 test files, all standalone scripts.

**The radio works end to end, and so does first-run setup** (card #25): the firmware's own
BLE stack runs behind a bumble controller, and with `--netsim` the Pixel 8 AVD's `:app`
bonds with the emulated watch, binds it (bindStart / setDateTime / bindEnd, the companion
app's post-QR handshake) and reads its battery, while the watch goes from "Select a
Language" to its face — the two emulators meet there, with no hardware at
all. With `--flash-state` (#41, done) it stays that way across restarts: the watch boots to
its face and the phone reconnects with its stored keys, and `normwatch cmd` (#49, done) asks
it any 0x6F question in ~4s. `--save-state` / `--load-state` (#46, done) skip the boot for
everything that is not about the radio: 0.47s to the UI instead of 11s, and a restored run
lands on the golden fingerprints exactly.
Open issues are on the GitHub board (`normboard`); `docs/watch-emulator-internals.md`'s "What to build next" says why.

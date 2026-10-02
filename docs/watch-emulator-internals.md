# `watchemu` — an emulator for the Norm 2 watch hardware

Runs the watch's **real firmware** — `NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin`, the
image shipped inside the original APK — on the PC, by emulating the **Ambiq Apollo3 Blue**
it executes on. No physical watch involved.

This is a hardware emulator, not a protocol mock. Nothing here reimplements the watch's
behaviour; the behaviour comes from executing the watch's own code.

## Status

**The watch OS boots and reaches a stable running state.** FreeRTOS starts, the scheduler
ticks and preempts continuously, the storage chip is identified against the firmware's own
part table, the watch's real resource blob is mounted, and the display panel is brought out
of sleep and switched on. No faults, no hangs, no failed asserts.

```
stopped: budget — ran the full 40000000 instruction budget
basic blocks executed: 8859291 (7085 unique)
exceptions dispatched: STIMER_CMPR0 x588, PendSV x74, SVCall x1
SPI NAND: identified as EF AB 21, 197 resource pages resident
display : 360x360, panel awake/on, frames drawn and captured
```

`STIMER_CMPR0` is the RTOS tick and `PendSV` the context switcher, so this is a genuinely
running multitasking system, and coverage keeps growing the longer it runs. It has been
run to 600M instructions without a fault.

**The watch's sensors answer.** The accelerometer is modelled and receives its
real configuration — see [No I2C write carried any
data](#no-i2c-write-carried-any-data), which is the defect that hid it. The
battery gauge answers too, on a bus the firmware clocks by hand on two GPIOs;
that is what stopped the low-power dialog churning sixteen times a second.

**The watch navigates.** With `--force-gestures` a drag on the window is recognised as
a swipe, the page changes, and the next screen draws — the goal ring to the left, the
"How to bind to APP" pairing help to the right. The flag is needed because every swipe is
cancelled by the low-power notification dialog being built and torn down once per UI
cycle; [Touch reaches the firmware, swipes do not reach the
UI](#touch-reaches-the-firmware-swipes-do-not-reach-the-ui) traces the input path and
[The swipe killer is the low-power dialog, not the watch
face](#the-swipe-killer-is-the-low-power-dialog-not-the-watch-face) identifies the cause.
Why that dialog is raised at all is the one thing still open — the prime suspect is the
missing battery/PMU model.

Throughput is roughly 4.4M instructions/second with `--no-trace` and 3.4M with tracing
on (~1/10th of real time). Every MMIO access is a Python callback, so tracing, heavy GPIO
polling and bus traffic all cost. The live window itself is cheap — about 8%.

**The watch is on the air, and a phone can set it up.** With `--radio` the firmware's own
BLE stack comes all the way up behind a bumble controller and advertises as `Norm2#00000`;
with `--netsim` the Pixel 8 AVD is on the same virtual air, and `:app` bonds with the
emulated watch, *binds* it the way the companion app does after a QR scan -- bindStart,
setDateTime, bindEnd -- and the watch leaves first-run setup for its watch face, showing
the time the phone set. A bumble host does the same in `tests/test_ble_end_to_end.py`.
See "A real stack behind the seam" for the things bumble does not do that each looked like
a hang, and "Binding, and three things the firmware does about it" for what the bind
taught.

**And it remembers.** `--flash-state PATH` keeps what the firmware wrote to its flash, so a
watch bound once boots straight to its face and keeps its bond: the phone reconnects with
the keys it stored, and the firmware answers the key request from its own flash. See "The
watch keeps what it writes".

**The watch draws its screen, live, and you can touch it.** LVGL renders and flushes
real frames — 360×360 RGB565, four 64,800-byte DMA stripes per frame — and `--live`
puts them in a window as they arrive, with the mouse acting as a finger:

```bash
normwatch boot --live --no-trace
```

Click and drag on the window to touch the panel. `--screenshot out.png` still captures
the final frame instead, for headless runs, and `--press PIN[:AT[:HOLD]]` injects a
button or sensor edge at a chosen point in the boot.

Input has to be expressed in *watch* time, which is the thing that catches you out: the
core runs at 48 MHz, so a 150 ms tap is 7.2M instructions and a half-second button hold is
24M. A gesture that lasts a few hundred thousand instructions is a sub-millisecond flick
and the firmware rightly ignores it.

The status line says `STOPPED` in red if the emulator thread ends — it used to keep
claiming "running" while the CPU had quietly stopped, which is indistinguishable from a
hang.

The panel model is **byte-exact**: the framebuffer it assembles from the bus traffic
matches the firmware's own frame buffer in SRAM, pixel for pixel. So what the window
shows is what the watch drew, with nothing added or lost in between.

**The watch face draws.** The firmware mounts its resource partition off the emulated
SPI NAND and renders the real face - the digital time and the weekday/date line, drawn
from the artwork in `Picture_P03B_NORM2_0.4.bin`:

```
                        00:00
                    MON | 03 APR
```

The rest of the face is black because the face's own background image is a 360x360
all-black bitmap - that is what is in the resource file, byte for byte. This is a hybrid
watch: the analogue hands carry the time and the AMOLED only adds the digital block.

Getting here needed four fixes to the bus model, all recorded below: the resource path,
the MSPI interrupt, the command queue, and bit-expanded NAND commands.

**Where it stops short.** *The radio powers up, but nothing is behind it yet.*

This paragraph used to say the BLE stack never reached the BLEIF registers at all. That
was wrong, and it was wrong in the most expensive way: it was a conclusion drawn from an
access counter that read zero, and the reason it read zero was two mistakes in **this**
model, not a decision by the firmware.

`am_hal_ble_power_control` (`0x0008A340`) was running the whole time. It got through the
MCUCTRL.FEATUREENABLE handshake and then failed in `am_hal_pwrctrl_periph_enable`
(`0x0008D2C0`), which polls `PWRCTRL.DEVPWRSTATUS` for a per-peripheral bit it reads out
of a table at `0x000CB1EC` - three words each, word0 the DEVPWREN mask and word1 the
DEVPWRSTATUS mask. **They are different bits.** This model answered DEVPWRSTATUS by
mirroring DEVPWREN, which satisfies every other peripheral by coincidence (MSPI wants
status `0x40` and gets it because IOM5's *enable* bit is `0x40`) and cannot ever satisfy
the BLE controller, which wants status `0x100` from enable `0x2000` - and `0x100` belongs
to UART1, which this firmware never powers. The second mistake was the status register's
address: all three sites that read the controller's state use `0x5000C30C`
(`0x0008A422` for `B2M_STATE`, `0x00089590` for bit 3, `0x0008923A` for bit 7), not the
`0x308` that had been guessed here.

With those two fixed the firmware runs its entire bring-up at ~0.27s of watch time and
hands over to its HCI transport, which then polls `BSTATUS` for a controller that does not
exist yet. So there *is* an HCI stream to bridge now, and the seam is the FIFO - see
[What to build next](#what-to-build-next).

The lesson worth keeping: a counter that reads zero is not evidence the firmware declined
to do something. It is equally evidence that this model told it not to.

**It is not free.** The transport polls inside a critical section, so with no controller
answering the firmware spends ~100M instructions up front in an interrupt-masked retry and
keeps retrying afterwards: about 5.5M MMIO reads over a boot, `bootrom_delay_cycles` up
from 190k to 4.3M, 18% of the FreeRTOS ticks never delivered, and roughly 2.1x on the wall
clock — 3.1x until the boot ROM shim was profiled, which is its own story below. The screen
still reaches first-run setup, about a third later in budget terms.
`--no-ble` withholds that one status bit and gives back the old behaviour bit for bit -
same exception counts, same 9,770 idle skips, same 509 NAND pages - which is what the
optimisation tests use, since with the radio on there is much less idle time to measure.

## Running it

```bash
uv sync
```

Then, from the repo root (Python 3.11 — the same interpreter the emulator BLE bridge uses):

```bash
normwatch boot
```

```bash
normwatch info
```

```bash
normwatch modules
```

| Command | What it does |
|---|---|
| `info` | Parses the Ambiq image header, checks both CRCs, prints the vector table |
| `modules` | Lists the source files recovered from the firmware's `assert()` strings |
| `boot` | Runs the firmware and prints a triage report |
| `cmd` | Boots the watch, connects a phone the way the app does, sends one 0x6F command, prints every reply |

Two switches put a radio behind the firmware's BLE stack: `--radio` (a bumble controller,
alone on the air) and `--netsim [PORT]` (the same, plus the Android emulator's netsim
endpoint on PORT — default 8877 — so `normphone start --watch` gives the AVD the
emulated watch as its Bluetooth peer). `--netsim` paces the watch against the wall clock;
`--hci-trace` logs every packet across the seam. See "A real stack behind the seam".

`--flash-state PATH` keeps the watch's flash between runs: restored at start if the file
exists, saved at exit -- a closed window and Ctrl-C included. Bind the watch once and every
later run with the same file starts on the watch face, bonded:

```bash
normwatch boot --netsim --live --flash-state watch.zip
```

`cmd` asks the firmware one question. It boots the watch, puts a bumble host on the same
air (on the radio's own loop -- no port, so it runs beside a live AVD), pairs, does what
`BindWatchUseCase` does (checkInit, and the bind only if the watch says 0), writes the
frame and `[03]` to 8002, and prints everything that comes back, decoded:

```bash
normcmd 08 70 --payload 00 --no-bind
```
```
   3.0s  phone: connected, paired, encrypted
   3.0s  -> 8001  6f 08 70 01 00 00 8f    0x08 BATTERY_POWER CHECK [00]
   3.5s  <- 8002  6f 08 80 01 00 4d 8f
   3.5s           = 0x08 BATTERY_POWER CHECK_RESPONSE [4d]
```

Commands and actions take hex or `:protocol`'s names (`DEVICE_VERSION CHECK`); command
names are read from `CommandCode.kt`. `--char 8003` writes to the other write
characteristic (a SET there is never acknowledged), `--no-trigger` leaves out the `[03]`,
`--no-bind` asks the watch as first-run setup leaves it. With the bind it takes ~14s, the
boot animation being most of it; without, ~4s. So bind once into a state file and ask
everything after it from there:

```bash
normcmd BIND_END CHECK --payload 00 --flash-state bound.zip --save
normcmd 03 70 --payload 06 --flash-state bound.zip
```

`--flash-state` is read only for `cmd` unless `--save` is given, so probing never rewrites
an AVD's state file. "Bound" means checkInit reads 1, not that bindEnd was acknowledged:
the firmware writes its init flag when the "Pairing Success" dialog closes, two seconds
later, and a state saved before then is an unbound watch. Exit status: 0 a reply came, 1
nothing did, 2 the setup failed. The watch and the phone are `fw/phone.py`'s
`EmulatedWatch` and `Phone`, which `tests/test_ble_end_to_end.py` uses too.

**`--mac MAC` asks the physical watch instead**, over this PC's own Bluetooth adapter:

```bash
normcmd 03 70 --payload 06 --mac 4C:59:80:12:44:F1
```

Same flow, same frames, same decoder -- `physical.PhysicalPhone` is `Phone` over bleak,
and both get exchange, checkInit and the bind from one `Conversation` class in
`fw/phone.py` -- so where the two answers differ, the watches differ. That is how a
finding here gets checked on hardware. On Windows the first run bonds the watch with the
PC, with the custom Just Works ceremony the default one is refused without (ConfirmOnly,
protection level None; the module docstring has why). The watch keeps one connection,
so it must not be connected to a phone; and `--flash-state` / `--save` are refused, the
physical watch keeping its own flash. `tests/test_physical.py` checks it against a fake
`BleakClient`. It replaced `normlink-cli`, the Kotlin `:cli` module, which wrote to 8003
(below, "Binding").

`--save-state PATH` / `--load-state PATH` skip the boot for anything that is not about the
radio: save once past the boot animation, and every later run starts there in half a
second. See "Snapshots".

### The live window

```bash
normwatch boot --live --scale 2 --no-trace
```

The emulator runs on a worker thread and Tk owns the main one; only the framebuffer and
a revision counter are shared, so the UI never blocks the CPU. Closing the window stops
the run and prints the usual boot report. `--live` runs until you close it rather than
to an instruction budget, unless you pass `--max-instructions` yourself.

Keys **1**-**5** in the window pulse the GPIO pins the firmware enables interrupts on
(2, 3, 10, 24, 38). Which is the crown and which the side button is not yet known, so
they are offered for experiment rather than named; 28 is left out because it is the
touch panel.

**Touch** is a click or drag on the window. Mouse events do not reach into the
peripherals directly — they go through `machine.post`, which runs them on the emulator
thread between basic blocks, so the interrupt arrives by the same path the firmware
would really see. Expect roughly 1.5M instructions of lag between the click and the
watch acting on it: the GPIO handler only posts to the touch task, which does the I2C
read when it is next scheduled. That is the firmware's own latency, not the emulator's.

The boot report includes a **FreeRTOS task list** read out of the running machine — every
task, its priority, and the address it is parked at — which is the quickest way to see
whether the system is healthy or wedged:

```
 16 FreeRTOS tasks:
   name             prio  TCB        stack top  parked at
    SystemTask        15  0x100080E0 0x10008074 0x000B909C  (~tasks.c)
    BLE               15  0x10009C68 0x1000A72C 0x000B9720  (~event_groups.c)
    Storage           14  0x100091C8 0x10009124 0x000B9D92  (~queue.c)
   *UITask             2  0x1000EE78 0x1000ECDC 0x10002DE4
```

It needs no symbols: task names live inside their control blocks, so searching RAM finds
them (each candidate validated against its priority and stack pointers), and
`pxCurrentTCB` is read from the literal that `vPortSVCHandler` loads.

Useful `boot` flags: `--max-instructions N`, `--stall N` (instructions without new code
before declaring a hang), `--chiprev 0x12` (force a silicon revision), `--no-trace` (faster,
no coverage), `--force-gestures` (see below), `--json`.

## How it is put together

```
normplus/watch/fw/
  image.py        Ambiq image header: link address, payload, both CRCs
  machine.py      The machine: memory map, MMIO bus, run loop, exception delivery
  cortexm.py      ARMv7-M private peripheral block: SCB, SysTick, NVIC + exceptions
  peripherals.py  Apollo3 peripheral register models (CLKGEN, MCUCTRL, CTIMER/STIMER, MSPI, IOM, …)
  devices.py      The chips on the buses: SPI NAND, PSRAM, RM67162 panel, touch panel
  bootrom.py      Shim for the Apollo3 mask-ROM helper functions
  fasthook.py     Loads (and builds) the C block hook; falls back to Python
  console.py      Hooks the firmware's own printf so its asserts and logs are readable
  symbols.py      Address -> source module, recovered from assert() strings
  trace.py        Coverage, execution path, stall detection
  viewer.py       The live window: framebuffer -> Tk, mouse -> touch panel
native/
  watchemu_hook.c The per-block timing hook, so the hot path never enters Python
  build.py        Finds MSVC and builds it; fasthook.py calls this on demand
tests/            Each file is a standalone script -- run it directly:
                  normtest <name>
```

Memory map, per `docs/firmware.md` §4:

```
0x00000000 - 0x000FFFFF   1 MB internal flash  (application linked at 0x00020000)
0x08000000 - 0x08000FFF   Apollo3 boot ROM     (shimmed)
0x10000000 - 0x1005FFFF   384 KB SRAM
0x40000000 / 0x50000000   peripherals
0xE0000000 - 0xE00FFFFF   private peripheral block
```

Execution starts at the application's reset vector, because the first 128 KB of flash holds
a first-stage bootloader that is not in the OTA image.

### A stall heuristic that stops a healthy run is worse than none

The tracer stops the run when it decides the firmware is wedged. Its liveness signal was
"has a context switch happened since the last check", which is wrong for this firmware:
the hardware bring-up sits inside one long `am_hal_interrupt_master_disable` region
(`0x0003E86A` to `0x0003E9B2`), so for millions of instructions there are no interrupts at
all — no PendSV, no tick — while the storage stack mounts the resource partition. The run
was being cut off at ~17M instructions, long before the watch face draws.

Driving MMIO now counts as progress too, which separates the two cases cleanly: a wedged
poll loop reads one register forever and writes nothing, a working driver keeps writing.
`--live` turns the stop off entirely — an interactive session must not be killed by a
heuristic.

The tracer also used to register its own `UC_HOOK_BLOCK`, doubling the Python callbacks
per basic block; it now rides on the machine's timing hook. Between the two, tracing went
from "ends the run at 17M" to a 25% overhead.

### Interrupt latency has to be near zero, or FreeRTOS quietly breaks

The single subtlest bug in this emulator. Delivering pending exceptions on a fixed
instruction slice — even a fairly fine one — is not good enough:
`xTaskResumeAll` sets PendSV *inside* a critical section and expects the context
switch the instant `BASEPRI` is released. Deliver it ~100 instructions late and the
task has already called `vTaskSuspendAll` again, so `vTaskSwitchContext`
early-returns and **the yield is silently lost**.

The task then loops and blocks on a queue it is already queued on. That inserts a
list item that is already in the list, `vListInsert`'s walk lands on the item
itself, and the node becomes self-referential — an infinite loop inside the
scheduler, at the highest priority, freezing the watch. It presents as "the system
booted and then went quiet", with `vListInsert` at 53% of all execution.

So `_on_block_timing` tests deliverability **every basic block** (behind a cheap
"could anything be pending?" guard) while advancing the clocks only every
`time_quantum` cycles. Fixing this took the boot from 4113 to ~6300 unique basic
blocks and is what got the display drawing at all.

A related trap in the same area: `NVIC_IPR` and `SHPR` are **byte addressable**,
and this firmware writes them one byte at a time. Treating every write as 4 bytes
wide means a store to one IRQ's priority silently zeroes the next three — which
put the RTOS tick at priority 0x00, above FreeRTOS's `BASEPRI` mask and free to
preempt critical sections.

### Two ways to turn a watch face into noise

Both of these produced a screen of diagonal static that looked like a decoding problem
and was not one. They are worth recording because each looks like the other.

**The command queue must not be replayed.** The MSPI's command queue is a ring of
(register, value) pairs in SRAM: `CQCURIDX` is where the controller has got to,
`CQENDIDX` is how far software has posted, and each entry ends by writing its own index
to `CQCURIDX` so the hardware advances. A model that ignores that bookkeeping and simply
walks the ring from `CQADDR` whenever software touches the block re-runs whatever the
*previous* operation left there — so every display stripe was sent to the panel twice,
518,400 bytes into a 259,200-byte frame. The giveaway is a frame that is an exact
multiple of the right size; `display:` in the boot report now prints it.

**The window is in panel coordinates.** The RM67162's memory is larger than the glass,
and this watch maps its 360×360 area at (20, 16) inside it, so every window the firmware
sets is `20..379` by `16..375`. Taking those as framebuffer coordinates clips each row to
340 pixels instead of 360 and shears the picture a little further on every row. The model
translates and clips, and re-derives the origin at runtime from any full-size window.

While in there: bulk pixel payloads are now classified *before* the command patterns, so
a stripe that happens to begin `0x02` or `0x32` can no longer be mistaken for a panel
command.

### ITSTATE must be cleared on exception entry

The one that took longest to find. A run used to die after ~107M instructions on a
prefetch abort at `0xC0000000`, deterministic to the instruction and moving with the
slice size.

On exception entry ARMv7-M saves ITSTATE in the stacked xPSR and **clears** it. Leave it
live and the handler's first instructions inherit the interrupted block's condition and
are silently skipped when it is false — they are executed, and counted, as NOPs.

It only bites where a translation block starts *inside* an IT block, which is rarer than
it sounds: a false IT block is folded and executed whole, so it offers no boundary to
stop on, and being interrupted inside one therefore implies the condition was true. In
this firmware there is exactly one such place — the `itt hs` at `0x00020FFC` in memcpy,
whose conditional instructions straddle the `0x00021000` page boundary, forcing a block
split. An exception landing there swallowed the STIMER handler's `push {r4, lr}`, so its
closing `pop {r4, lr}` took `LR` from the stale frame the first `SVCall` left at the top
of MSP, and the tick handler returned into `0xC0000000` a few instructions later. The
number of instructions swallowed tracked how many IT slots were left — two when
interrupted at `0x00020FFE`, one at `0x00021002` — which is what finally gave it away.

Guarded by `tests/test_exception_entry.py`, which reproduces it from the firmware's own
memcpy bytes:

```bash
normtest exception_entry
```

### Touch reaches the firmware, swipes do not reach the UI

Every stage of the input path works, and the swipe still dies. Worth writing down in
full, because the failure is one instruction deep and nothing above it looks wrong.

```
panel model  ->  GPIO 28 IRQ  ->  driver 0x0004182C reads the report over I2C
             ->  state block 0x10011D00  ->  ring of 20 at 0x1005D66C
             ->  LVGL read cb 0x0003F2EC  ->  lv_indev_read 0x000A6904
             ->  indev_proc_press          ->  PRESSED/PRESSING to the face object
             ->  gesture recogniser 0x00084B78
             ->  direction event 0x17 (4/5/6/7)  ->  page handler 0x0007C740
             ->  navigate 0x000840A8
```

The recogniser accumulates the drag in a context at `0x10001BF8` and decides at the
release: under 8 px in both axes is a tap, under 30 px in both is discarded, otherwise
the dominant axis picks 4/5/6/7. Bit 6 of `0x10001BFA` says a gesture is in progress,
and the release path throws the gesture away if it is clear.

**`ui_delete_object` (0x00084284) clears that bit** — every time any UI object is
destroyed, the in-flight gesture is cancelled:

```
0x000842A4  bic r1, r1, #0x40
0x000842A8  strb r1, [r0, #2]        ; r0 = 0x10001BF8
```

And the watch face rebuilds one of its elements on *every* UI cycle, so the cancel fires
about 16 times a second. The recogniser therefore only ever measures the travel between
the two LVGL indev reads that bracket one cancel — about 7 px — and never the 30 px it
wants. Measured, with the finger dragging 240 px in 350 ms:

```
  156,095,903  gesture  state=1 pt=(293,180) flags=0x00   press-begin, start=293
  156,138,179  ui_delete_object                           flags &= ~0x40
  156,365,139  gesture  state=1 pt=(252,180) flags=0x00   press-begin, start=252
  159,297,788  gesture  state=1 pt=(245,180) flags=0x40   drag, dx = -7
  159,340,112  ui_delete_object                           flags &= ~0x40
  ...
  172,360,528  gesture  state=0 pt=(299,180) flags=0x01   release -> discarded
```

Two ends of the chain were emulator defects and are fixed — the panel was mirroring its
coordinates (below) and the missing TE line was stretching the UI cycle to 153 ms
(below). Neither changes the outcome: with a 61 ms cycle the cancel simply lands more
often. Suppressing that one `bic` is the only thing that does, and then all four
directions work and open four different pages, so everything else on the path is sound.
That is what `--force-gestures` does (`normplus/watch/fw/patches.py`), and it is off by
default because it is **not** what the shipped firmware does.

### The swipe killer is the low-power dialog, not the watch face

The "the watch face rebuilds an element every cycle" story above was wrong, and a one-line
experiment disproves it: force the suppression gate at `0x000843DC` to return 1 and
`ui_delete_object` gets **zero** callers in an entire run. Nothing else in the UI destroys
objects. It is all one subsystem, and it is not the watch face.

What actually happens, once per UI cycle:

```
element 0x1005                                     posted by system_watch_task.c
  -> vtable[0x000CC8D8 + 0x2EC](0x1005) = 3        a static switch at 0x0005BA18
  -> notify table 0x000BCAE4 + 3*12 = 0x000BCAFC   48 entries of {id, ctor, flag}
  -> ctor 0x00073FC9                               ui_notify_lowpower_dlg.c
  -> create a fresh LVGL screen, lv_scr_load it
  -> ~18,700 instructions (0.4 ms) later, close it and delete it
```

The dialog is the **low-power notification**, and that is its own assert's `__FILE__`
rather than a guess: the ctor at `0x00073FC8` asserts `hParent` at line 153 of
`ui_notify_lowpower_dlg.c`. It is built and destroyed about sixteen times a second, and it
never stays up long enough to be drawn.

Three things were checked and are *not* the cause:

- **The refresh is not routed to the wrong root.** `0x0007756C` picks `lv_layer_top()`
  when it has children and `lv_scr_act()` otherwise; the top layer is empty for the whole
  run, so it is always `lv_scr_act()`. There is no second element-id space to reconcile —
  the earlier note about `0x20C..0x215` was chasing a dead end.
- **It is not an unanswered peripheral.** The firmware never touches the ADC, and since
  the I2C write fix no transfer on any bus goes unanswered.
- **It is not the gate.** `0x000843DC` returns the byte at `0x10001C00`, and the notify
  manager skips the dialog when that is 1. It is 0 for the entire run, including all the
  way through a swipe — written exactly once, to 0, by the window-manager init, and **no
  code anywhere in the image ever stores 1 to it**.

Forcing that gate is not a fix, incidentally. It suppresses *every* notify screen, and the
watch face is built through the same path, so the display just stays black. It is useful
only as the experiment that isolates the cause.

So the open question is sharper, and it has moved: not "does the face rebuild itself" but
**"why is the low-power dialog raised at all, and what would ever set `0x10001C00`"**. The
obvious suspicion is that the watch believes it is on a flat battery — there is no PMU, no
charger and no battery model here, so whatever the power manager
(`system_power_manager_task.c`, `0x0005B610`) would normally read is whatever BSS left it.

### The battery gauge is on a bus the firmware clocks by hand

Every hardware I2C controller on this chip stays idle for the whole boot, and yet
`pmu_cw6303.c`, `wireless_charge_p9027lp.c` and `system_power_manager_task.c` all
execute. The gauge is not on an IOM at all: `ew_drv_sim_i2c.c` bit-bangs it on two
GPIOs. That is why reading GPIO was the hottest MMIO in the run by an order of
magnitude — 225,148 reads — and why there was no bus traffic to notice missing.

Recovered by decoding the waveform rather than guessing: SDA falls while SCL is
high, then eight bits give the address byte.

```
SCL = GPIO 11        SDA = GPIO 13        device 0x62
```

The register map is the firmware's own traffic. It writes MODE, reads CONFIG,
uploads a 64-byte profile at 0x10..0x4F, then settles into reading 0x02 (twelve
times), 0x04, 0x06/0x07, 0x08 and 0x0A — the CellWise CW201x layout, with cell
voltage and state-of-charge each big-endian across a register pair.

A second device at **0x09** shares the bus — the charger, described below. It is
not a separate driver: `pmu_cw6303.c` owns both addresses, which is why hunting
for a second module talking to it found nothing.

Two things had to change to support any of this:

- **GPIO needed open-drain.** `read()` returned `out | inputs`, so once the
  firmware drove a pin high nothing could contradict it and no slave could ever
  acknowledge. `Gpio.pull_low` and a `pulled_low` mask fix that.
- **The bus is decoded at the pin level**, not by intercepting the driver, so the
  firmware's own timing and framing are exercised. `BitBangI2cBus` watches the two
  pins, recognises START/STOP, and pulls SDA low to ACK or to return a byte.

**Every ninth clock is the acknowledge, not data.** Counting it shifts each byte
one place right, and the result is quietly plausible — 0x08 arrives as 4, 0x5F as
0x17, and a profile that really starts at 0x10 looks like it starts at 0x08. That
is pinned by a test, because nothing about the wrong answer looks wrong.

### What the battery actually changed

With the gauge answering, `ui_notify_lowpower_dlg.c` goes from 29 executed blocks
to **zero**, and `ui_delete_object` from 18 calls to **none**. The churn described
above is gone at its cause, and the firmware completes its full 64-byte profile
upload, so it is satisfied with the part.

The trigger is specifically the **state-of-charge** register. A device that ACKs
but returns 0xFF or 0x00 leaves the churn exactly as it was; modelling 0x04 stops
it. Voltage alone does not.

The screen then goes black, and that appears to be *correct*: the face background
in the shipped resource blob is 259,200 bytes of zero (see below), the watch has
physical hands for the time, and the lit pixels seen before were the low-power
dialog's own text. Touch still reaches the firmware — 37 interrupts, 37 reports,
and the UI polls the input device three times more often than before.

Swipes still did not navigate at that point, and the reason turned out to have
nothing to do with waking. See below.

### The swipe works; there is no watch face to swipe on

The gesture pipeline is healthy now, end to end, on the shipped firmware with no
patch. Traced through a 240 px drag:

```
   153,484,755  press-begin   state=1 pt=(300,180) flags=0x00 -> sets bit 0x40
   153,585,957  drag          state=1 pt=(293,180) flags=0x40  start=(300,180)
   155,192,178  drag x42      state=1 pt=(272,180) flags=0x41  last=(279,180)
   170,715,729  release       state=0 pt=( 60,180) flags=0x41  start=(300,180)
                              -> dispatched into the active screen's handler
```

The release arrives with the in-progress bit still set and the full travel
recorded, and window_manager hands it to the screen that is up. Nothing is lost.

What is up is not the watch face. **The only screen the watch ever opens is
notify id 4, `ui_notify_poweroff_dlg.c`**, 158 ms into boot, and it never leaves
it:

```
0x00077BB8  notify_entry(id) -> 0x000BCAE4 + 12*(id-1)     48 entries {id, ctor, flag}
  called exactly once in a 20M-instruction boot, with id = 4
0x0005BA18  system_power_manager_task.c maps a power event to that id
  element 0x100B -> 4        (the same mapper that turns element 0x1005 into 3)
```

So the swipe lands on the power-off dialog, which has nothing to navigate to. The
LVGL tree agrees: `lv_scr_act()` holds one window_manager container whose two
children are both empty and one of which has no style at all. There is no face,
no labels, no images — which is also why the screen is black, and why every
attempt to make the gesture "work" was aimed at the wrong thing.

Neither a three-second hold of the button nor reporting the watch as charging
changes this; the decision is made at 7.57M instructions, before any input can
reach it. The remaining question is what makes the power manager raise element
0x100B at boot, and the strongest suspect is the unmodelled device at 0x09 on
the bit-banged bus — the charger — since the driver's charging bits come from
there and it NAKs 28 times a boot.

### The charger is the other half of the same part

The device at 0x09 makes 28 transactions a boot and used to NAK every one, which
hides everything: a NAK aborts the transfer before the register byte, so there is
nothing to decode. Putting a device there that acknowledges makes the whole
conversation visible. Bring-up, 175k instructions in:

```
0x01 = 0xB7   0x02 = 0x87   0x03 = 0x00   0x04 = 0xFF
0x0A..0x0D = 0xBD           0x00 = 0x00
```

0x01 and 0x02 are packed at `0x0004E9C8` out of two lookup tables — 32 halfwords
at `0x000CE008` and 8 at `0x000CE048` — as `mode << 6 | flag << 5 | index` and
`0x80 | index`. That is the charge current and the charge voltage.

**0x03 is the status register.** `0x0004E6B6` reads it into `0x10001555` and
decodes it; `0x04` is a latch written 0x5F before the read and 0xFF after, and
0x03 is written 0 after every read — so a real part re-asserts rather than
staying cleared, and the model regenerates the byte on each read.

Rather than name the bits from a datasheet, feed the firmware each value and
watch the code the driver returns at `0x0004E6F4`:

| register 3 | driver codes | power event |
|---|---|---|
| absent | 0, then 4 | 0x03 |
| 0x00 | 0, then 4 | 0x03 |
| 0x10 | 5, then 4 | 0x03 |
| 0x20 | 2, then 3 | 0x18 (no screen) |
| 0x80 | 2, then 4 | 0x03 |
| 0xA0 | 2, then 3 | 0x18 (no screen) |

Bit 5 is what the steady query distinguishes, bit 7 additionally raises the
one-shot code 2, and bit 4 gives codes 5 and 6. Which of 3 and 4 the firmware
*calls* "on the charger" is still unproven; the model follows the only inference
the evidence supports — a part that is absent, so every read fails, is a watch
that is not charging — and `--charger-status` exists so the question stays open
to experiment.

**It does not explain the power-off screen.** Every one of those status values
still leaves the watch opening notify id 4 and staying there. The charger was
the strongest suspect and it is now ruled out.

### The accelerometer needed its interrupt before it was ever read

The sensor was modelled, configured by the firmware's own 16-entry init table,
and streaming into its FIFO — and the firmware read **zero bytes** of it for an
entire boot. The driver is interrupt-driven and never polls, and the model's
`irq_pin` had never been given a value.

Wiring it to pin 16 is the whole fix:

```
irq_pin=None   58 samples made,   0 bytes read, 464 queued
irq_pin=16     58 samples made, 400 bytes read,  64 queued
               system_step_task.c  50 -> 93 blocks
               i2c.c              134 -> 169 blocks
```

Which pin is decided by the firmware's configuration, not by the device: an
interrupt line is asserted by driving it to the level its `INTD` bit implies,
so a falling-edge pin rests high and is *pulled down* to signal. Both the
sensor and the touch panel used to drive their line high to mean "asserted",
which is backwards for both. `Gpio.assert_irq` now decides that in one place.
It changed no behaviour — the edge is latched either way, and neither driver
samples the level — but it is the kind of thing that is invisible until
something does read the level and then makes no sense at all.

### What Python was costing, and what was done about it

Unicorn is already C, so "rewrite the emulator in C++" would not touch the CPU
emulation. Measured on a synthetic loop with the block size this firmware
averages:

```
no hook at all (Unicorn's ceiling)   285,000,000 instr/s     21 ns/block
empty Python hook                     21,500,000 instr/s    279 ns/block
hook + one uc.reg_read                 3,800,000 instr/s  1,580 ns/block
```

A 40M-instruction run taking 8.99s broke down as:

| | | share |
|---|---|---|
| Unicorn's JIT | 0.14 s | **1.6%** |
| Python crossings, block hook (7.39M) | ~2.1 s | 23% |
| Interrupt-mask register reads | ~3.2 s | 36% |
| Everything else Python | ~3.5 s | 39% |

**Under 2% of the wall clock was emulating the CPU.** Two changes, no rewrite:

- **`_masked` stopped going through the binding.** It reads FAULTMASK, PRIMASK
  and BASEPRI on nearly every block while FreeRTOS holds a critical section —
  1,094,454 times per 40M. `uc.reg_read` is 1,161 ns and almost none of it is
  the call; the binding resolves a register class through a generator every
  time. Straight to `uc_reg_read` with a preallocated buffer it is 279 ns.
  (`reg_read_batch` was tried first and is *slower*: 4,892 ns for three against
  3,477 ns individually.)
- **`--fast-hook` runs the block hook in C** (`native/watchemu_hook.c`), which
  is where the 23% went. Unicorn's C API takes a plain function pointer, so it
  is registered through ctypes; everything else — devices, probes, tests — stays
  in Python.

```
                                      boot to the UI (445M instructions)
                                      CLI defaults    --idle-skip
at the start of this work                  80s            --
--no-trace, now the default                58s            --
+ fast register reads                       --           24s
+ the C block hook                        21.9s         12.4s
+ the three exact wins below              18.5s         10.9s
+ the deadline quantum below              11.9s          9.4s
```

**1.28x slower than the watch at defaults, 1.02x with `--idle-skip`**, against
9.2s on the watch itself. Keep the two columns apart when quoting a number:
`--idle-skip` is opt-in precisely because it is not bit-identical, so its
figures do not describe what `normwatch boot` actually does.

The C hook is held to exact equivalence — same exception counts, not close ones
— and that caught two bugs that never looked like crashes:

- Unicorn's `count` argument counts *instructions* while the emulator's clock
  counts halfwords. Letting `count` end the slice ran the clock 30% fast, which
  surfaced as twice the timer interrupts.
- After `enter()` consumed a pending IRQ nothing told the C side, so it stopped
  on the handler's first block, and the poll that followed re-armed the
  interrupt before the handler had cleared its source. **Every peripheral IRQ
  was taken exactly twice** — 338 STIMER against 674 — while PendSV, SVCall and
  the instruction count all matched perfectly. Refreshing on entry and return
  fixes it.

It builds itself. `fasthook.load` compiles `native/watchemu_hook.c` when the
library is missing *or older than its source*, because a stale library is worse
than a missing one — it loads and runs. It falls back to the Python hook when
there is no compiler, when a rebuild changes the shared struct (checked against
the C `sizeof`), when `--trace` is on, or when a watchpoint is asked for; the
last two need per-block Python by definition.

**It is the default for `normwatch boot`** (`--no-fast-hook` opts out), on the
strength of a wider comparison than the unit test does. Every observable that
could be captured — exception counts, notify screens opened, boot-animation
frame, lit pixels, frames, pixel bytes, NAND pages, touch reports, motion FIFO
bytes, the active LVGL screen pointer and the gesture flags — over three
workloads:

| | `--idle-skip` off | on |
|---|---|---|
| full boot to the UI (445M) | identical | STIMER only, −7% |
| boot + a swipe | identical | STIMER only, −7% |
| boot + select a language | identical | STIMER only, −7% |

So the C hook on its own is indistinguishable, including through the gesture
path, which is the part of the firmware most sensitive to interrupt latency.

That right-hand column was read the wrong way round at the time. It was taken as
"`--idle-skip` is the inexact one", and it is not — it says the two *hooks*
disagree when the skip is on, and the C hook was the one that was right. The
Python hook advanced the clocks in place instead of handing back, so when it
stopped to fast-forward it was holding counted-but-unapplied cycles. Fixed in
the deadline-quantum work below; the column is zeroes now.

The library default (`Apollo3Machine(fast_hook=...)`) stays **off**, so probes
and tests keep watchpoints. It is the CLI — where the runs are long and the
watchpoints are not — that turns it on.

### After the C hook, the cost moved to leaving Unicorn

Profiling a 200M boot with the native hook active shows a completely different
shape: the block hook is gone from the top of the list, and what dominates now
is **776k round trips out of `emu_start`** — one every 258 clock units, because
`time_quantum` is a fixed 256. Each one costs an `emu_start`, a
`_service_native`, an `_advance_time` over every timer and IRQ source, a
`mem_read` and two register reads.

Three of those costs were pure overhead and came off without changing a single
emulated value:

- **`Bus.find` scanned every peripheral on every MMIO access** — 722,408 linear
  scans over twenty-odd entries per 200M instructions. It is a page-indexed dict
  lookup now. It could not simply be bound into the `mmio_map` closure: the MMIO
  windows are two 256 MB regions, not one per peripheral, so the closure knows
  the window and not the device.
- **The WFI probe read two bytes out of the emulator after every handback** —
  803,666 `mem_read`s and as many ctypes buffers, to look at the same
  instruction over and over. Memoised by PC, and invalidated whenever flash is
  programmed, because the code underneath a cached address can change.
- **The post-`emu_start` PC read went through the binding.** `CortexM` already
  builds a raw `uc_reg_read` reader for `_masked`; the run loop uses it too now.
  PC was added to that reader's self-check, since a wrong answer *there* sends
  emulation somewhere rather than merely misreading a mask bit.

Together: 48.98M Python calls per 200M instructions down to 41.21M, and the boot
from 21.9s to 18.3s. Fingerprints identical across boot, boot + a swipe and boot
+ a language selection — captured before and after on the same tree, which is
the only comparison that can catch a change moving both sides of a setting.

What is left is the handback *rate*, and a fixed quantum is a fixed sampling
rate. Raising it to 512 is a third faster and still bit-identical; 1024 loses
half the GPIO interrupts; 2048 starts moving STIMER counts, which is exactly
where you would expect it, since one STIMER tick is 48e6/32768 = 1464 cycles and
a larger chunk can cross two compare points and coalesce them. Raising the
constant buys ~35% and buys it by moving closer to a cliff. The fix is to stop
sampling and take the quantum from the next deadline instead.

### The watch talks to its radio

Once power-up worked the firmware's Cordio host started driving the transport, and the
whole of it could be read off a running machine rather than guessed. The send path is at
0x000891F0 and the receive path at 0x00089C92:

| what | where the firmware reads it | meaning |
|---|---|---|
| `PWRCMD` bits [3:2] | written at 0x0008A6D4 / 0x0008A6E8 | 3 = wake the controller, 2 = let it sleep |
| `BSTATUS` bit 3 | 0x00089590 | controller is awake and its SPI is ready |
| `BSTATUS` bit 7 | 0x0008923A, 0x00089C92 | controller has something to hand over |
| `CMD` [4:0] / [19:8] | written at 0x00089672 | direction (1 write, 2 read) and TSIZE |
| `FIFOPTR` bits [15:8] | 0x0008931E | free space in the write FIFO |
| `FIFOPTR` bits [23:16] | 0x000899B4 | bytes waiting in the read FIFO |
| `INTSTAT` bit 0 | 0x0008938E | the transfer you asked for is done |

Bit 3 is the gate on everything: with it clear the firmware waits 300 x 160us = 48ms,
gives up, and starts over. The old placeholder never set it, which is why nothing had
ever been seen to move.

`FIFOPTR` is worth singling out because it is the kind of mistake that looks like a hang.
The placeholder returned `0x00200000` with the comment "plenty of transmit room" — but
0x20 there lands in bits [23:16], which is the *read* count. The send loop reads bits
[15:8], saw zero room, and waited for ever. The two counts are one register and one
transposition apart.

**The first packet out of the watch is `01 bb f1 02 54 06`.** The builder at 0x00089C24
assembles it a byte at a time: `1` for an H4 command, an opcode low byte from a table, a
hardcoded `0xF1` for the high byte, a length of 2, and a 16-bit value from the same table
entry. So it is Ambiq's vendor register/trim sequence, one command per entry.

### The radio is a Nationz NZ8801 with no firmware in it

That first packet is not standard HCI, and chasing it as HCI is a dead end. It is the first
command of a **firmware download**: the watch's BLE controller is a Nationz NZ8801 that
boots empty, and the Apollo3 gives it its firmware every time the watch starts.

The protocol is three commands per section — opcode high byte is the phase, low byte is the
section id:

```
01 <section> F1 02 <len lo> <len hi>      begin, and the total length
01 <section> F2 <n> <n bytes of payload>  data, n <= 0x80
01 <section> F3 02 <crc lo> <crc hi>      end
```

and this watch sends four: `0xBB` (1620 bytes of code), `0xCC` (560 more), `0xDD` (194
bytes) and `0xEE` with a length of zero, which means *run it*. `0xDD` is the interesting
one — it is a RivieraWaves **NVDS** blob, `"NVDS"` then `[tag][0x06][length][data]` records,
tag 1 a BD address and tag 2 the string `"NZ8801V1A"`. That is how the part names itself.

**The firmware spells out what it wants back.** At `0x00089CC6` it assembles the reply it
expects and compares against it at `0x00089CEA`:

```
04 <section> <phase> 01 00
```

Five bytes, not an HCI Command Complete — which is why answering with one is refused. After
the `0xEE` go-ahead the controller restarts into what it was given and says so
*unsolicited*, in that same shape; the firmware is already waiting for it at `0x00089F56`
and checks only that the second byte is `0xEE`. From there the link is ordinary HCI and the
first thing across it is the vendor command `0xFD04`.

One trap in that last step. The announcement is read **two bytes at a time**
(`0x00089F64`), and the drain loop will not move at all below four (`0x000899C2`). Report a
literal 2 in `FIFOPTR` and the firmware parks against its own threshold for ever, which
looks exactly like a dead controller — the count has to be rounded up to a whole word,
because the FIFO holds words.

`NationzController` in `devices.py` does all of this and keeps every section it is given,
so `nvds_tags()` reads the part name and BD address straight off the wire. It answers so
the firmware's stack can start; it is not a radio, and putting a real one behind it is the
rest of card #25.

**The radio now costs nothing.** A 576M boot is 14.45s with it fully up against 14.37s with
it switched off, 136 frames and 509 NAND pages either way, all 16 tasks healthy and the BLE
task parked on its event group. The retry storm was never the price of having a radio, only
the price of having one that nothing was talking back to.

### Three ways to desynchronise a byte stream

Getting from "the download completes" to "the firmware trusts its controller" was three
separate rounds, and all three failures looked the same from outside: the watch restarts
the download. Worth writing down, because each one is invisible in a register trace and
obvious in a byte log.

**The go-ahead is announced, not acked.** `01 EE F1` means *run what I gave you*. The
controller restarts into it, so it cannot answer the command -- it sends one message, the
announcement, once it is up. Sending both an ack and an announcement leaves five bytes in
the queue, and from then on every read is five bytes out of step. This one is nastier than
it sounds: the run still reported "HCI up" and a plausible packet count, and it took a log
of `take(n)` calls against what was queued to see that a message had been left behind.

**A read is padded to TSIZE.** The bus clocks TSIZE bytes off the controller whether or not
it has that many. The firmware reads an HCI event in a fixed nine-byte window
(0x0008A058), so handing back exactly the seven bytes of a Command Complete leaves the
drain loop at 0x000899B0 waiting for two that are never coming.

**`INTSTAT` bit 8 mirrors `BSTATUS` bit 3.** The readiness check at 0x000895A4 reads
*either* register depending on a handle byte, and the firmware sets that byte at
0x0008A076 -- the last step of bring-up. So every check before that point goes through
BSTATUS and every check afterwards through INTSTAT, and a model with only the first gets
the firmware all the way up before it decides its controller has vanished.

### A real stack behind the seam

`blelink.py` puts a bumble `Controller` on a `LocalLink` behind the NZ8801 bootloader,
and with it **the watch is on the air**: it advertises as `Norm2#00000` at the physical
watch's address, a phone connects to it, pairs with it, discovers its GATT table
(`6006` with `8001`-`8004`, the `1530` DFU service, `FEE7`) and gets its 0x6F protocol
answered by the firmware -- a battery CHECK comes back `6f 08 80 01 00 4d 8f`. The phone
can be a bumble host (`tests/test_ble_end_to_end.py`, about ten seconds, no hardware) or
the Pixel 8 AVD with `:app` in it (`--netsim`, below). The seam's rule is unchanged: the
download phase and the OGF 0x3F vendor opcodes are answered locally, because neither means
anything to bumble, and the rest is forwarded.

Bumble runs on its own thread because it is asyncio and the emulator is not, and because
`Controller.send_hci_packet` defers through `call_soon` -- a reply is never ready by the
time `on_hci_packet` returns. A command hands off and waits briefly for *its* answer (a
Command Complete or Command Status carrying its opcode, not just anything the controller
says meanwhile), which makes the exchange look synchronous to the emulator and keeps the
watch's own timeouts from racing the loop. Anything the controller says unasked -- a
connection, data from the phone -- is queued and reaches the firmware through **IRQ 12**:
`HciDrvRadioBoot` ends (`0x0004704E`) with INTCLR 0x281, INTEN 0x281 and
`NVIC_EnableIRQ(12)`, and the ISR (`0x000889CC` via `0x00046EE8`) reads INTSTAT & INTEN,
clears it and wakes the HCI driver's task. Bit 7 of that mask is BLECIRQ, the interrupt
form of BSTATUS bit 7, latched on its rising edge; the blocking transfers mask it (INTEN
0x201 at `0x00089274`) so a reply they are about to poll for does not also interrupt.

**Bumble 0.0.229 is missing six things a phone needs, and each one looked like a hang.**
None of them is a bug in the watch; all of them are pinned by `tests/test_ble_link.py`:

- `LE_Read_Local_P-256_Public_Key` (0x2025) has a handler that is a `TODO` stub: it returns
  sync parameters for an async command, the dispatcher rejects that, and nothing comes
  out. The firmware sends it during HCI init and then waits -- Cordio holds every later
  command until `Num_HCI_Command_Packets` says the controller can take one -- so the
  watch never got as far as advertising. `LE_Generate_DHKey` (0x2026) and `LE_Encrypt`
  (0x2017) have no handler at all. The seam answers all three itself with real P-256 and
  AES-128 from `cryptography` (a bumble dependency), not invented values: the phone does
  the same arithmetic and checks it. As it turns out this firmware pairs LE *legacy* Just
  Works -- its Pairing Response is `02 03 00 01 10 02 01`, AuthReq 0x01, bonding without
  Secure Connections -- so the DHKey path is unit-tested but the AES one is what pairing
  uses (five `LE_Encrypt` calls: c1 twice, s1 once).
- An opcode bumble has no class for becomes a generic `HCI_Command`, and the dispatcher
  answers one of those with silence. `VirtualController` answers every command: vendor
  opcodes get the Command Complete `normplus/phone/bridge.py` already proved Android accepts,
  everything else the Command Status a real controller gives (Unknown HCI Command).
- `LocalLink.send_acl_data` stamps every LE data PDU with the sender's *random* address.
  The watch uses its public one, so every ATT response it sent arrived at the phone from
  `00:00:00:00:00:00` and was dropped with "no connection" -- service discovery timed out
  after the requests had visibly reached the watch. `Air` puts the address the connection
  was made with on the PDU.
- The filter accept list is accepted and never consulted. Android connects by putting the
  peer on the list and issuing `LE_Extended_Create_Connection` with filter policy 1 and a
  zero peer address, so nothing ever matched and every connect timed out.
- `Read_Remote_Version_Information` is unhandled, and Android's GATT client will not start
  service discovery until it has it ("Pausing service discovery till remote version is
  read") -- so the app connected, bonded, and never saw a service. The watch's controller
  reports what the physical one does (LMP 8 / subversion 809 / company 96, from the
  bond record Android kept of the real watch).
- `LE_Connection_Update` is unhandled. The watch asks for 15 ms / latency 4 / 4 s right
  after pairing; the central's host turns that into this command, and with no answer its
  whole command queue stops -- the next thing it could not do was disconnect. It is
  granted as asked and both ends get the Connection Update Complete event.

One more is Android's, not bumble's: its stack aborts start-up unless the controller
claims Secure Simple Pairing (`btm_sec_dev_reset: only controllers with SSP is
supported`, three fatal signals and then Bluetooth is simply off). The phone's virtual
controller claims the bit; it is BR/EDR-only and changes nothing on the LE side.

**The loop is shared on purpose.** `LocalLink` dispatches every PDU with
`asyncio.get_running_loop().call_soon` on the *sender's* loop, so controllers that are to
hear each other must live on the same one. That is `Radio`: one thread, one loop, one
link. `AndroidLink` serves the Android emulator's netsim endpoint on it (the transport is
imported from `normplus.phone.netsim_transport`, which owns the fix for emulator 36.x's
`packet` field) with a second `VirtualController` behind it -- the phone in the AVD and
the watch in the emulator are two controllers on one piece of air.

```bash
normwatch boot --netsim --live     # the watch, on port 8877
normphone start --watch                                # the phone, pointed at it
```

`--netsim` implies `--realtime`: a phone answers in real time, and a watch whose clock runs
ahead of the wall clock (1.25x at CLI defaults; `--idle-skip` and the deadline quantum
fast-forward it further) times out on a peer that is answering promptly. The machine
sleeps whenever it is ahead and never tries to catch up. `--hci-trace` logs every packet
across the seam, and is the first thing to turn on when the phone and the watch disagree
about what was said -- every one of the six gaps above was found in that trace, or in
`adb logcat` next to it.

### Binding, and three things the firmware does about it

Bonding put the phone on the watch, and the watch stayed on "Select a Language". What
takes it off is the companion app's *bind* -- `BindDevice.start6F` in the smali, three
commands the Norm 2 gets right after a QR scan, now `BindStartCommand` /
`DateTimeCommand` / `BindEndCommand` in `:protocol` and `BindWatchUseCase` in `:app`:

```
6F 93 71 01 00 02 8F      bindStart, mode 2 = BIND_START_QR_CODE
6F 04 71 0C 00 <12> 8F    setDateTime, the ordinary clock set
6F 94 71 01 00 01 8F      bindEnd
```

The watch answers each with the generic `6F 01 81 02 00 <cmd> <status>`, shows "Pairing
Success / Hooray!" for two seconds and goes to the face. `6F 94 70 01 00 00 8F`
(checkInit) reads `00` before and `01` after, which is what the original app polls on
every connect and un-pairs over if it reads 0. Getting there found three things, all in
the firmware, none guessed:

- **bindStart opens a 300-frame window.** The 0x93 handler (`0x00037054`) posts UI
  message 0x0C, which opens `ui_notify_pairing_dlg.c` (`0x00075250`) straight into its
  animating state; the dialog counts frames at 50 ms (`0x0007514C`, to 0x12C) and then
  reports failure -- "Pairing Failed / Try again!", and `93 01` to the phone, some 15-19 s
  later. bindEnd (0x94, `0x00037088`) posts 0x0D, the one message the dialog is waiting
  for. The dialog also has an accept/decline form (`0x000753CC`, buttons 0x821/0x822)
  that mode 1 would presumably use; mode 2 skips it.
- **A SET written to 8003 gets no acknowledgement; the same SET written to 8001 does.**
  One command table (`0x000CCD60`, 87 entries), two dispatchers in front of it. The one
  behind 8001 (`0x00036208`) turns a handler's result into the generic reply -- 0 and 1
  become `<cmd> 00` / `<cmd> 01`, 3 means the handler already answered (CHECKs do that
  themselves), 4 defers. The one behind 8003 (`0x00036460`) throws the result away. The
  companion app writes to 8001 (`AppsCommDevice.smali`), so does `:app`, and
  `normlink-cli` wrote to 8003 -- which is why the CLI only ever saw CHECK replies, and
  why the bind could not be driven from it. (It has since been replaced by `normwatch cmd
  --mac`, which writes to 8001.) It also means the original app's queue, which
  waits for each reply before sending the next command, gets bindEnd out inside the
  window only because 8001 acknowledges bindStart at once.
- **The firmware never answers a Read By Type for its Database Hash.** Its GATT service
  has the 0x2B2A characteristic (handle 0x17), a plain Read of it returns sixteen zeros,
  and a Read By Type -- the form the spec prescribes and the form Android uses -- is
  parked for ever. `AttsAddGroup` (`0x00026DCC`) sets Cordio's `isHashUpdateInProgress`
  (`0x100067D0`) whenever a service is added, `attsProcReadByTypeReq` (`0x00091DF4`) holds
  a hash read while that flag is set, and the one thing that clears it, the CMAC callback
  at `0x00092166` from `AttsCalculateDbHash()`, is never reached because the firmware never
  calls it: no `LE_Encrypt` ever leaves the watch outside pairing. Nothing about the
  emulator is involved. Android's stack reads the hash by type before service discovery
  whenever its cached table for the device has 0x2B2A in it -- i.e. on every connection
  after the one the bond was made on -- so `discoverServices()` from `:app` hangs there
  until its watchdog reconnects, and the third attempt goes through when the stack gives
  up on the hash. That is the shape of the "cold-connect penalty" `BleManager` has always
  seen on the physical watch and blamed on SMP; the firmware image says it is this. Not
  verified on hardware yet -- the physical watch runs a build with the same version
  string, not provably the same bytes.

And two more gaps in the virtual air, each hit only once the phone reconnected:

- **After a bond the watch advertises with own_address_type 2** ("resolvable private
  address from the resolving list, else the public address"), having put the phone on the
  resolving list with an all-zero local IRK. Bumble keeps no resolving list and reads
  anything but 0 as "the random address", which for the watch is `00:00:00:00:00:00`, so
  the watch came back on the air as a random all-zero address, the phone's accept list
  held the public one, and every reconnect timed out (status 147). `VirtualController`
  maps 2 and 3 to 0 and 1, which is what an empty resolving list amounts to.
- **`LocalLink` delivers data now, and a connection does not.** Data crosses only at
  connection events, one per interval -- 15 ms once the watch has asked for it, 30 ms
  from Android's create-connection until then. The firmware relies on that without
  knowing: its 8001 dispatcher hands the reply to the radio and clears its receive buffer
  *afterwards* (`0x00036294`), and a frame that lands in between is cleared with it and
  answered `00 02` when its trigger comes. Over `call_soon` the phone's next write landed
  in that window every time, so the bind failed at its second command, on the AVD and
  from a bumble host alike. `Air` now holds each direction of each connection in a lane
  and flushes it one interval later -- on the *watch's* clock for data into the watch,
  because the window is milliseconds of watch time and painting the pairing dialog puts
  the emulator behind the wall clock (`Apollo3Machine.realtime_lag` says how far), and on
  the wall clock for data into the phone, which is what a phone keeps. The interval comes
  from the create-connection command and every `LE_Connection_Update` after it.

**When to bind: once the setup screen is drawn, and no later (#55).** The bind's pairing
dialog opens on top of "Select a Language", so a phone has to wait for it -- and *drawn*
is the word. The animation is 135 of the panel's frames (its first is drawn as the
animation opens, before the firmware's counter at `0x00075A7C` has counted one); "Select a
Language" opens at 398.9M instructions and its first frame, the panel's 136th, lands
somewhere between 402M and 420M (radio off). A bind sent at frame 135 is acknowledged,
all three commands, and nothing happens: the dialog never shows, checkInit stays 0.
`EmulatedWatch.past_boot_animation` waits for frame 136 (`FIRST_SCREEN_FRAME`). It used
to wait for frame 135 and then for the emulator to catch up with the wall clock -- which
hid the wrong frame, because catching up took long enough for 136 to be drawn, but on
the idle setup screen the catch-up runs at ~0.1s a second and took up to 41s.
`test_ble_end_to_end.py` gives each boot 60s of watch time, and on a slow run the bind
landed at 54s and the watch's budget ran out under the next checkInit: a GATT timeout,
then everything after it (#55). The catch-up was never needed -- `Air` puts data into the
watch on the watch's clock, which is the whole of what the lag could break -- and binding
3.7 to 11.4s behind the wall clock, under load, passed every time. The test went from
100-155s to ~32s, and `cmd`'s bind from ~22s to ~14s. The screen checks that follow count
*watch* seconds too: three wall seconds of a slow run can be less than "Pairing Success"'s
two.

`tests/test_ble_end_to_end.py` runs the bind back to back with no pauses and asserts the
acknowledgements, the screen change and the init flag; `tests/test_ble_link.py` pins the
advertising address. On the AVD, expect the first two connection attempts after the bond
to stall on the hash read and the third to bind; the watch's pairing dialog is only opened
by the attempt that gets through.

### The watch keeps what it writes (`--flash-state`), and a ninth gap

A real watch keeps its flash when it restarts. The emulator began every run from the
shipped image, so every run started at "Select a Language" and every phone had to pair
and bind again. Logging every `nv_page_erase` / `nv_program_main` and every NAND program
or erase over a boot, a bond and a bind shows what there is to keep, and that all of it is
the firmware's own doing:

| page | written | what it is |
|---|---|---|
| `0x1C000` | every boot, a full page | the boot record (#39) |
| `0xDE000` | at boot | the canned quick replies: "Call me later.", "In meeting.", ... |
| `0xFE000` | the moment the link is encrypted | the bond |
| `0x18000` | at bindStart | `04`, the LE32 epoch setDateTime sent, `01` |
| `0xE0000-0xF7FFF` | the bind erases all of it | blank on a fresh emulated watch anyway |
| `0xF8000` | the bind | |
| `0xFA000` | boot, then ~20 times during the bind | byte 0: `ff` after a boot, `01` once bound |

No NAND page is programmed or erased in any of it.

`--flash-state PATH` (`fw/flashstate.py`) saves exactly the pages the firmware wrote --
`Apollo3Machine.flash_dirty` and `SpiNand.dirty`, tracked at the two write paths -- into a
zip (`manifest.json`, `flash/0x000FA000.bin`, `nand/<page>.bin`; `unzip -l` shows it), and
puts them back over a fresh machine before anything runs. A page restored and never
touched again is saved again, an erased page stays erased, the save is atomic, and a file
saved against another image or resource blob is refused rather than half-applied. A patch
is not the watch writing its flash: `--force-gestures` writes with `persist=False`, or a
later run without the flag would get the patch anyway. Stopping between an erase and its
program loses that page, as pulling the battery at that instant would.

What it does not keep is the clock: the RTC is not flash, so a restarted watch shows
`12:00 SUN 01 JAN` until a phone sets the time.

**The ninth gap: bumble never asked the watch for its key.** The first version of the
restart test passed -- the phone's stored keys "encrypted" the restarted link without
pairing -- and passed just the same with the restore thrown away. Bumble's controller
reports encryption on both ends the moment the central asks for it ("For now, just setup
the encryption without asking the host"), so the firmware never got an `LE Long Term Key
Request`, never looked up a bond and could never refuse one. Every "encrypted" link with
the emulated watch until now, the AVD's included, was encrypted without it. The
peripheral's `VirtualController` now raises the request (7.7.65.5) with the central's
Rand and EDIV and waits: the host's key equal to the central's encrypts both ends; a
negative reply (7.8.26) gives the central `PIN or Key Missing` (0x06) and leaves the link
up; a different key drops the link with 0x3D, which is what a MIC failure does on air;
no answer in 40 s drops it with 0x22. Pairing goes through it too -- the firmware answers
the STK request -- and `tests/test_ble_link.py` pins all three outcomes.

With that, the restart is the real test. Against `:app` on the AVD, after a restart from
the saved flash, the watch's HCI shows Android's request and the firmware's answer:

```
<- 04 3e 0d 05 01 00 18 e2 89 aa 58 2d 9a 44 b3 0c          LTK Request, Rand, EDIV 0x0CB3
-> 01 1a 20 12 01 00 d2 84 77 f5 d5 50 22 fd 7b 29 ...      LTK Request Reply
<- 04 08 04 00 01 00 01                                      Encryption Change, on
```

and the LTK, Rand and EDIV are byte for byte the `LE_KEY_PENC` Android stored when it
bonded (`d28477f5...b2b1ad 18e289aa582d9a44 b30c`). With the restore disabled the firmware
answers `01 1b 20` instead and the phone gets 0x06.

Two things that follow for the AVD:

- **Keep one state file per bond.** With the same file, the AVD's bond stays good across
  watch restarts and nothing needs wiping. It only needs wiping when the watch on the other
  end does not hold that bond: a new state file, or after the physical watch.
- **Restarting the watch means restarting the AVD.** The emulator's packet streamer does
  not reconnect to a new endpoint; the guest's HCI times out on `RESET` and a Bluetooth
  toggle does not bring it back. `adb emu kill` and `normphone start --watch` again; the
  bond and `:app`'s data are on the guest's data partition and survive it.

One more observation, for #48: on those reconnects Android logs `smp_link_encrypted: SMP
state machine busy so skipping encryption enable:1` -- the line `BleManager`'s cold-connect
comment cites from the physical watch as evidence of racy encryption -- on links the wire
shows encrypted with the right key. It does not by itself mean encryption failed.

### Turning the radio on moved the cost somewhere nobody had looked

Powering up the BLE controller made a boot about 3.1x slower, and the obvious
suspect was the BLEIF register the firmware polls: 5,365,844 of the 5,473,007
BLEIF reads in a boot are `BSTATUS`, hammered by an HCI transport waiting for a
controller that is not there yet. That suspicion was wrong, and it is a good
example of why the profiler comes before the fix.

`cProfile` over a 40M window inside the spin, 21.281s total:

```
  ncalls  tottime  cumtime
  162827    2.446   16.623  unicorn emu_start
 2613008    1.675    3.694  unicorn _reg_read
  653197    1.343    9.572  bootrom._on_execute      <- 45% of the run
 1780848    0.852    2.311  peripherals Bus.read
```

The MMIO reads were 16%. **The boot ROM shim was 45%**, and it had nothing to do
with BLE except in volume: `am_util_delay_us`, called from the HAL's retry loop,
goes through the ROM helper table, and a boot went from 190,544 ROM entries to
4,313,279. Three things were wrong with `_on_execute` at that frequency, none of
which mattered at 190k:

* it read **all four** argument registers on every call, through
  `uc.reg_read` — and every handler ends in `*_args`, so three of the four were
  read and thrown away. `bootrom_delay_cycles` takes one argument and is over
  99% of the calls.
* it read them through the binding rather than the fast reader that already
  exists for exactly this (`cortexm._make_fast_reg_reader`, 1,161 ns -> 279 ns).
* it resolved the handler with `getattr(self, f"_rom_{name}")` — building an
  f-string per call — instead of once at install time.

Asking each handler's signature how many arguments it actually declares, and
resolving the handler when the table is parsed, took the 40M window from 21.281s
to 13.618s and `_on_execute` from 9.572s to 1.508s. A full radio-on boot went
from 50.6s to 36.0s with every fingerprint identical.

A second, smaller one on the path the profile then pointed at: the MMIO callback
tested "is this the PPB window" and "is there a tracer" on every access, though
both are fixed for the life of the machine, and `Bus.read` called `find()` as a
separate Python call. Four flat closures bound at install time and an inlined
page lookup (with `find()` still the fallback, so shared pages behave the same)
took a radio-on boot to 34.2s and a radio-off one from 16.3s to 15.3s.

**What was tried and rejected.** During the spin the MSPI interrupt is pending
and the core is masked, so `Mspi.next_deadline` wins the deadline 135,396 times
out of 162,827 and delivers nothing each time. Returning `None` once the
exception is already latched in the NVIC is worth another 1.14x and looks
obviously safe — the run loop will deliver it the moment the core allows, and
deliverability is checked every basic block anyway. It is not safe: that poll
*pumps the command queue* as a side effect. With it skipped, a radio-off boot
moves from 12,040 STIMER interrupts to 12,043, and with `--idle-skip` to 12,039
— so the two stop agreeing with each other, which is the one property that
switch has to keep. `test_mspi_still_asks_for_a_rate_when_its_irq_is_already_latched`
pins it so it is not quietly reintroduced.

What is left is intrinsic: ~1.8M MMIO reads and ~163k slices per 40M
instructions, because the firmware really is executing a busy-wait. The way to
get it back is not to make the spin cheaper but to stop it happening, which is
what card #25 does when the FIFO answers.

### Don't pick a sampling rate — ask when the next thing happens

Everything that moves with the clock knows when it next will: the STIMER's next
tick, the TE line's next edge, the accelerometer's next sample, the next
scheduled touch. `_next_quantum` takes the nearest of those and the run loop goes
exactly that far. In a normal boot the answer is almost always the STIMER tick,
because the FreeRTOS tick is an STIMER compare and the counter is readable —
every `am_util_delay_*` spins on `STTMR`, so running past a tick without
advancing would hand a delay loop a stale counter. That makes **1464 cycles the
natural quantum** where it used to be 256, and the boot goes 18.5s → 11.9s.

Two sources are deliberately *not* on deadlines, and finding out why was the
whole difficulty:

- **`Mspi.pending_irqs` walks the command queue as a side effect.** It is not a
  query. How often it is called is how fast the controller runs, which is how
  fast pixels reach the panel — at one poll per 16384 cycles a boot loses two
  frames and three NAND pages.
- **`Gpio.pending_irqs` is level-sensitive**, so how often it is looked at is how
  many times an asserting pin re-pends.

For both, the poll frequency *is* the model, so they keep the historical
256-cycle interval while they have something to say and offer no deadline at all
when they do not — which is most of a boot. Skipping a poll that provably does
nothing is free; skipping one that would have done something is not.

The other half is `cut_slice`, for changes the CPU causes mid-slice that no
deadline could have foreseen — an interrupt enabled over an already-latched
status, a GPIO edge asserted from inside an I2C read. It expires the quantum
rather than calling `emu_stop`, because stopping would leave the CPU part-way
through a block the hook had already counted and re-entering it would count it
twice.

Two bugs came out of this, both of which only a wide comparison could have found:

- **The two hooks did not have the same shape.** The Python hook advanced the
  clocks *in place* and carried on; the C one could only stop. So the native path
  also advanced at exception stops and the Python path did not. At a fixed 256
  that was indistinguishable; at 1464 the native path polled the MSPI queue more
  often and took two more MSPI interrupts per 20M. Both hooks now only count, and
  the run loop advances — and they agree on the instruction total exactly, which
  they never did before.
- **The Python hook had no re-entry guard.** `watchemu_hook.c` has one, because
  stopping from a block hook leaves the block unexecuted and it is hooked again
  on resume. In Python that had always been a rounding error — until the hook
  started handing back every quantum, at which point a block of 256 halfwords or
  more re-expires the quantum on re-entry, stops again, and **the run livelocks
  with the clock advancing and the CPU executing nothing.** It looked like the
  firmware hanging at 3M instructions.

It is on by default (`--fixed-quantum` opts out) and it is *not* bit-identical,
so here is exactly what moves. Over a full boot to the UI, a boot with a swipe
and a boot with a language selection:

| | fixed 256 | deadline |
|---|---|---|
| frames, pixel bytes, NAND pages, `scr_act`, gesture flags, animation frame, unmodelled MMIO | — | **all identical** |
| GPIO (exc 29) | 72 / 106 / 102 | **identical** |
| MSPI (exc 36) | 3091 / 1278 / 3528 | +2 / +1 / +2 |
| STIMER (exc 39) | 9243 / 4101 / 9561 | +1 / +1 / +1 |
| PendSV (exc 14) | 4632 / 1998 / 4720 | −8 / 0 / −18 |

Everything the watch actually *did* is identical; exception delivery moves by up
to 0.4%. (`--idle-skip`, for a long time the standing example of an inexact
switch, turned out not to be one — see below.)
One check had to be loosened: `test_idle_skip`'s clock comparison, because
`machine.cycles` also counts what the boot ROM's delay helper says it burned and
the two runs enter it a different number of times — 0.012% over 100M, in a
counter nothing reads.

### Speed: 70% of a boot is the idle task spinning

Reaching the UI takes ~441M instructions, which is 9.2 seconds on the watch. The
emulator was taking 80 of them — about **8.7x slower than real time**.

A PC sample every 40,000 instructions across the whole run says where it goes:

```
tasks.c                    70.2%     two adjacent 64-byte regions
?                          17.2%
display_amoled_rm67162.c    3.0%
lv_vdb.c                    2.4%
everything else            ~7%
```

Those two regions are FreeRTOS's idle task, and `halts` is **zero** for the
entire run: this build never executes WFI, so the core spins at full speed with
nothing to do. The watch really behaves this way — it is not an emulator
artefact — but there is no reason to reproduce a busy-wait cycle for cycle.

Two changes, and together they are 2.9x:

- **Tracing is off by default** (`--trace` brings it back). It costs 27%.
- **`--idle-skip`** fast-forwards the clocks through that loop instead of
  emulating it, in the same spirit as the existing WFI handling.

```
                         instr/s     to reach the UI
default, before          5.4M        80s
--no-trace (now default) 7.6M        58s
+ --idle-skip           16.0M        28s     (71% of the run not executed)
```

That is 3.0x slower than the watch instead of 8.7x.

**Idle-skip is bit-identical, and the long-standing claim that it was not was
never about the skip.** For most of this project's life the docs here said the
STIMER count moved ~4% (338 -> 352) with the skip on, and treated that as the
price of it. It was the *Python block hook*: it advanced the clocks in place and
carried on rather than handing back, so at the moment it stopped to fast-forward
it held cycles it had counted and not yet applied, and the skip began from a
clock that was behind. On the commit before that was fixed, the same 20M run
gives 338 -> 352 on the Python hook and **338 -> 338 on the C hook**. The skip
was never the inexact part.

Once both hooks were put on the same footing, exception counts match exactly
with the skip on and off — at 20M, 100M, 200M and 445M, on both hooks. So
`tests/test_idle_skip.py` now asserts exact equality where it used to allow
STIMER 10% of slack, which is a much stronger guard: a skip that shifts
interrupt delivery at all is a skip that decided the core was idle when it was
not.

One thing is still not exact, and it is not the clock: `machine.cycles` also
accumulates whatever the boot ROM's delay helper reports burning, and the two
runs enter that helper a different number of times — 0.012% over 100M, in a
counter nothing in the emulator reads.

**With the radio up it is one tick short.** All of the above was measured radio-off.
With the NZ8801 model answering, a full 445M boot with `--idle-skip` delivers exactly one
fewer STIMER interrupt than without it, and nothing else differs — not PendSV, not a
frame, not a pixel. Bisected: identical through 225M, one short from 250M on, and still
exactly one short at 450M, so it is one lost tick between ~4.7 and ~5.2s of watch time,
not drift. Card #54; `tests/test_golden.py` pins it as it stands.

### Rehearsing an OTA

`ApolloOtaProtocol.kt` had never run against anything, and its first run on the watch was
going to be an update of the resource partition. The emulator can run it first -- if the
watch's application serves an update itself, rather than a bootloader that is not in the
image. It does, and the rehearsal (`tests/test_ota.py --full`) goes all the way through:
the unmodified `Picture_P03B_NORM2_0.4.bin` sent as the companion app sends it, CRC `04 01`,
REBOOT `05 01`, all 197 pages of the partition identical to the file, "Upgrade Success" on
the screen, and the watch boots from that flash exactly as before it. What it took to get
there is mostly what `ApolloOtaProtocol.kt` got wrong (#56 replaced it).

**UPGRADE_MODE is a mode switch, not a reboot.** `0x0E [00]` on 8001 is acknowledged and
nothing resets: the DFU service (`1530`, `1531`/`1532`) is the running application's, served
by `ew_mod_ota_protocol.c` (handler at `0x0003B798`, a switch on the command byte).

**Both DFU characteristics are write-without-response only.** 1531 takes control
commands and notifies every reply; 1532 takes data and has no CCCD. Android picks the
write type from the properties, which is why the app's default writes are no-response.
A reply is `[command][status]`, status `01` meaning yes.

**The firmware accepts update types 1-4.** The SET handler (`0x0003B8A8`) keeps an
address/length/CRC slot for each and answers anything else `[02 00]`. The resource
partition is **type 4** -- `UPDATE_TYPE_PICTURE_LANGUAGE` in `cn.appscomm.bluetooth.ota`.
The 8 that `cn.appscomm.ota.getUpdateType` returns for `Picture_*.bin`, and that our Kotlin
sends, is refused. A type-4 SET erases four 128 KB blocks at `0x0C780000` -- the live
partition -- straight away, and each 2 KB page is programmed in place as it completes. No
staging, no bootloader, no reset afterwards: the watch shows "Upgrade Success", and the
last thing it writes is NAND page 0.

**The data is stop-and-wait, in 200-byte pieces.** Each 2 KB page goes as ten 200-byte
pieces and a 48 (`OtaApolloCommand.addMiddleCommand`), each piece in 128-byte writes, and
the watch answers every piece with `03 01 <01, or 02 at a page boundary> <bytes so far,
LE32>`. Our Kotlin sends MTU-sized chunks to 1531 and waits every tenth.

**The NAND model had never been written to.** The driver sends a PROGRAM LOAD as two MSPI
transfers -- opcode and column (`02 00 00`), then the 2048 bytes on their own (PIO,
CTRL `0x08000401`) -- the same way it reads a feature (`0F C0`, then a separate one-byte
receive), with the chip select (GPIO 7) never moving. The model took the data as a new
command, its first byte `0x04` as WRITE DISABLE, and programmed every page as `0xFF`; the
firmware read the page back, and the CRC step failed. `SpiNand` now takes the transfer
after a data-less PROGRAM LOAD as its data.

**And a firmware bug on the way.** The first attempt was sent during the boot animation,
which is the power-off dialog (notify 4). Opening the OTA screen over it sends the UI task
round `ui_notify_poweroff_dlg` -> window manager -> `ui_notify_screen` -> back, six frames
and 120 bytes a lap, until it is ~50 laps deep, off the end of its 6.4 KB stack
(`0x1000D570`); the next queue send's `memcpy` lands on its own frame and `pop {pc}` loads
0. From the face it does not happen. Not an emulator effect -- real hardware stacks
*larger* exception frames, not smaller -- but a narrow one: the phone would have to start
an update within the first ~10 s after the watch boots.

### Snapshots: `--save-state` and `--load-state`

A boot to the UI is ~400M instructions of boot animation, 11s of wall clock, paid by every
probe. `--save-state PATH` writes the whole machine at the end of a run; `--load-state PATH`
starts the next run there instead of booting:

```bash
normwatch boot --seconds 9 --no-ble --save-state ui.snap
normwatch boot --no-ble --load-state ui.snap --seconds 2 --live
```

The second command is 0.47s from start to screenshot, the interpreter included, and draws
the same screen. A snapshot is ~575 KB, saved in ~40 ms and restored in ~20 ms. With
`--load-state`, `--seconds`, `--max-instructions` and `--press` times count from where
the snapshot was taken.

It is not an approximation of the run it came from. Saved part-way through each golden
workload, restored **in another process**, and carried on -- with the tap or the swipe
scheduled only after the restore -- every one lands on its golden fingerprint to the
instruction (`tests/test_snapshot.py`). That rests on two things:

- **`run(..., resume=True)`.** `run()` used to start from reset every time. Resumed, it
  carries on from the PC the last run stopped at, with the clocks and pending exceptions
  as they were, and its budget is the total since the boot. The budget never shapes a
  quantum -- only the idle fast-forward reads it -- so running to B1 and resuming to B2
  is the same run as going to B2 at once, which was checked before anything was saved.
- **Everything comes across.** `fw/snapshot.py` saves memory (flash, the boot ROM window,
  SRAM), the CPU, the native hook's counters and the Python state of every object
  reachable from the machine -- 61 of them, from CortexM to the touch panel. A reference
  from one to another is written as the other's path (`m.bus.by_base[0x50014000]`) and
  comes back as the object at that path in a machine built the same way, so a new
  attribute on a device is saved without anyone remembering to. Restore is strict: an
  object the snapshot has and the machine does not, or the reverse, is an error naming it
  (`the snapshot has Cw6303Pmu (m.sim_i2c.devices[98]) ... which this machine does not`),
  as are a different image, a different hook, or a different `--no-ble` / `--idle-skip`.

**Unicorn's CPU context does not travel.** Two things, each found by a crash:

- The binding's pickling of a context (`UcContext.__setstate__`) casts a
  `create_string_buffer` it does not keep, so the context points into freed memory; the
  restore reads it and the process dies at exit (127).
- A context is not position-independent: six of its words, at bytes 4080-4136 of 4660,
  are per-engine heap pointers -- by their place in the structure most likely QEMU's MPU
  region arrays. Copied into another engine, the restore writes through them -- an access
  violation.

So `_restore_cpu` copies the saved bytes into a context the target engine allocated
itself, and every word that differs between two fresh engines, or between the saving
process's blank context and this one's, keeps the target's own value: the general,
special and FPU registers come across (checked across processes), the pointers do not.
Unicorn's MPU is never configured anyway -- the PPB is CortexM's, in Python.

What a snapshot cannot hold: a bumble radio (`--radio`, `--netsim`, `normwatch cmd`) --
its link state lives in bumble, outside the machine; the NZ8801 model alone is fine. For
BLE work `--flash-state` is the fast path. And stimulus still pending when it is saved.
A snapshot is a pickle: load only ones you made.

### Golden fingerprints

`tests/test_golden.py` records what the watch does over four fixed, deterministic runs
in `tests/golden.json` and fails field by field when that changes: exception counts by
name, frames, pixel bytes, lit pixels, a hash of the final screen, NAND pages read, the
boot animation's frame, LVGL's active screen (`0x10001710`), the gesture flags
(`0x10001BFA`), touch reports and the flash pages written.

| workload | what it covers |
|---|---|
| `boot` | radio off, 445M, to "Select a Language" |
| `radio` | the same with the NZ8801 model answering: BLEIF, the download, HCI init |
| `language` | a tap on the first language row: touch into setup's navigation |
| `face_swipe` | from `tests/fixtures/bound-watch.zip`, so it boots to the face; a swipe with `--force-gestures` opens the activity page |

It also holds `--idle-skip` to the `boot` fingerprint exactly, and to `radio` minus the
one tick above. Two runs of each workload on one tree agree to the instruction, and
moving the MSPI command-queue pump from 256 to 255 cycles fails `boot`. The swipe used to
be done during the boot animation, where it reaches no further than the GPIO count; on
the face it reaches the gesture recogniser and changes the page.

It is in the ordinary test loop (about a minute), so nothing has to remember to run it.
After a change that is *meant* to move the watch, re-record and read the diff it prints:

```bash
normtest golden -- --update
```

The fixture is a watch bound over BLE by `normwatch cmd ... --save`; rebuilding it gives
a different bond and bind time, so it is kept rather than rebuilt per run.

It stays opt-in regardless. It reasons about when the core has nothing to do
instead of executing it, and that judgement is worth asking for on purpose.

Detection is deliberately narrow. The skip only engages on the second
consecutive arrival at the loop head within 200 instructions — meaning the loop
went round and nothing else ran — and it refuses to engage at all unless the
bytes at `0x000B2F22` are the loop it was measured on.

### Never use a Unicorn memory hook here

Registering a `UC_HOOK_MEM_WRITE` over SRAM does not merely miss writes — it
derails the run. Same firmware, same budget, the only difference being a hook
whose callback increments a counter:

```
                 no memory hook                    with memory hook
stop             budget, ran the full 8,000,000    fault, unmapped fetch of
                                                   0x10060000 from pc=0
instructions     8,000,038                         154,758
frames           1                                 0
```

0x10060000 is one byte past the end of SRAM, and the PC is zero; neither is
something the firmware did. Worse, the hook reports the same ~103,659 writes
whatever the length of the run, so it looks like it is working. Two separate
investigations concluded "nothing writes this address" from a hook that had
already killed the run several million instructions before the write happened.

`machine.watch(address, size)` checks from the block hook instead — the
machine's own, and known good. It costs one `mem_read` per watchpoint per basic
block, so it is a debugging tool rather than something to leave on, and a run
with no watchpoints pays nothing. Resolution is a basic block rather than an
instruction, which is enough to disassemble and name the store.

```python
point = machine.watch(0x10007529, 1, label="power state")
machine.run(max_instructions=20_000_000)
for instructions, block_pc, before, after in point.changes:
    ...
```

### The watch was never stuck. Every run was too short

`ui_notify_poweroff_dlg.c` is a **134-frame boot animation**, and the whole UI is
behind it:

```
0x00075A7C  r0 = [r4+0x10] ; r0 += 1 ; [r4+0x10] = r0     the frame counter
0x00075A82  r1 = [r4+4]                                    the frame count, 0x86
0x00075A86  bhs 0x00075AE0                                 finished -> tear down
```

A frame takes about 2.95M instructions, so the animation runs for roughly 400M —
**8.3 seconds of watch time**. The default budget is 30M, which is 0.6s, and
every investigation of "the black screen" had been looking at frame 8 of 134.

Run it for long enough and the watch simply boots:

```
      5,167,124  notify id 4          the animation starts
    397,018,898  animation finished (frame 134 of 134)
    398,447,529  ui_delete_object     torn down
    398,926,501  notify id 5          "Select a Language"
```

Tap a row of that list — the rows are at y 205..274, 295..364 and 385..454, and
a tap at (180,180) lands in the gap below the title, which is how the first
attempt looked like no response — and it advances to a QR code carrying
`A0.2(R.T0.0H0.0B01)` and `Norm2#00000`. That is the firmware version and the
serial, rendered by the watch's own UI, with the `N` (Nordic) component absent
exactly as you would expect on a machine with no BLE modelled.

`normwatch boot` now takes `--seconds N` and, when a run ends mid-animation,
says so and prints the budget that would get past it, rather than leaving a
black screenshot to be misread as a broken emulator.

### Why the watch shows the power-off screen

The chain, end to end, all of it found with those watchpoints:

```
ew_mod_storage.c 0x0003C8DE   read 0x2C bytes from storage address 0x1E000
                 0x0003C8EC   look at the word at +0x28
                                2      -> power-state byte = 1
                                1 or 3 -> power-state byte = 2
                                else   -> power-state byte = 0
                              the byte lives at 0x10006BCC + 0x95D = 0x10007529
0x0005BF70                    branch on it; 0 posts elements 0x1010 then 0x100B
                              to the UI task's queue at 0x1000D318
ui_task.c 0x0007A5A8          xQueueGenericSend
0x0005BA18                    element 0x100B -> notify id 4
0x00077BB8                    table 0x000BCAE4 + 12*3 -> ctor 0x00075B09
                              ui_notify_poweroff_dlg.c
```

**The byte at 0x10007529 is never written in an entire boot.** It is zero from
BSS, and zero is the branch taken. Force the stored word to 1 or 3 and the watch
shows *"Upgrade Success"* instead, which was the first thing the emulated
display ever drew — 5,349 lit pixels against a lifetime of zero. Force 2 and it
shows the other outcome.

So the watch is not deciding to shut down, and it is not asleep. Holding the
button across the decision changes nothing — every earlier button test pressed
at 20M instructions, and the decision is made at 7.57M.

**But a missing record is not the anomaly, and the paragraph above used to say
it was.** The save path settles it. Straight after reading the record, the
firmware writes −1 into `+0x28` and stores it back:

```
0x0003C93C  mov.w r0, #-1
0x0003C940  str r0, [sp, #0x28]
0x0003C942  ...  blx [storage+0]      erase(0x1E000)
0x0003C952  ...  blx [storage+4]      write(0x1E000, record, 0x2C)
```

It is a **one-shot notice**: an upgrade result, consumed once and cleared. A
watch that has ever finished an upgrade reads back −1 on its next boot, which is
"none of the above" — the same branch. So state 0 is where a settled watch
lives, and posting element 0x100B to open notify id 4 is what a normal boot
does.

Which moved the question again — and the answer turned out to be "nothing needs
to dismiss it, it dismisses itself after 134 frames". See above. The screen is a
boot animation, mode 3 at 0x10006C4C selects which one, and every run had been
stopping a fraction of the way into it.

`ui_notify_poweroff_dlg.c` hosts exactly one notify screen, id 4, so this is
genuinely the power-off dialog rather than a boot animation sharing a file —
nine other modules in that table do host several dialogs each, so it was worth
checking.

### Internal flash, and where the watch keeps its settings

The application links at 0x20000 and is 747,156 bytes, so it occupies
0x20000..0xD6693. Everything else in the 1 MB flash window is the bootloader
below it and storage above and below — and the emulator only ever loaded the
application, leaving the rest reading as zeros.

**Erased flash reads 0xFF.** Firmware that checks for a blank record cannot tell
"erased" from "all zeros" if the emulator hands it zeros, and the settings the
watch restores at boot live at 0x1E000, outside the image. The flash window is
now filled with 0xFF before the application is written into it.

The storage object it goes through is a three-slot vtable — `[+0]` erase, `[+4]`
write, `[+8]` read — reached from `ew_mod_storage.c` and served by the internal
flash driver, not the SPI NAND: the read takes 92 instructions and moves no NAND
pages and no MSPI DMA.

**Programming can only clear bits.** `write_flash` now ANDs with what is already
there and counts any program that needed a bit the page did not have set,
because a missing page erase is invisible if you model programming as an
overwrite — and then it is a corrupt page on the watch instead. A clean boot
reports zero, so the firmware's own erase discipline is sound.

The page geometry is confirmed against this image rather than assumed. Every
`nv_program_main` destination in a boot lands exactly on a page a preceding
`nv_page_erase` cleared, with `instance * 0x80000 + page * 0x2000`:

| erase | address | matching program |
|---|---|---|
| `(0, 0x0E)` | 0x1C000 | `nv_program_main(src, 0x1C000, 0x800 words)` |
| `(0, 0x0C)` | 0x18000 | `nv_program_main(src, 0x18000, 0x130 words)` |
| `(1, 0x3D)` | 0xFA000 | `nv_program_main(src, 0xFA000, 0x130 words)` |

Those are a boot's. A bond and a bind write more -- the whole list is in "The watch keeps
what it writes".

### Which pin is which

The firmware configures its own interrupt pins, and that configuration is the
only evidence there is for how the board is wired. Read back after boot:

```
INT0EN = 0x1001040C   INT1EN = 0x00000040      -> pins 2, 3, 10, 16, 28, 38
every pad 0x1A        FNCSEL=GPIO, INPEN=1, PULL=0   -> no internal pull-up
CFG nibble 8 on all of them except pin 3, which is 0  -> INTD, the interrupt edge
```

Pressing each in turn and diffing module coverage against an idle run names two
of them:

| pin | edge | what it is |
|---|---|---|
| 3 | low→high | **the button** — wakes `key_irq.c`, `gpio_irq.c`, `ew_drv_gpio_irq.c`, `system_vibrator_buzzer_task.c` and `ui_notify_poweroff_dlg.c` |
| 16 | high→low | **the accelerometer** — `system_step_task.c` 47 → 92 blocks, `i2c.c` 133 → 167. Wired up; see below |
| 28 | high→low | the touch panel (already modelled) |
| 2, 10, 38 | high→low | still unidentified |

Since no internal pull-up is enabled, a pin that interrupts on the *falling* edge
must be held high by a resistor on the board and pulled to ground by whatever
drives it. `press_button` used to drive every pin high to mean "pressed", which
is backwards for five of the six, and leaves the line reading as held down for
the whole run. It now takes the direction from `Gpio.resting_level`, which reads
the pin's own INTD bit. (It made no behavioural difference here — the firmware
never samples those levels while idle — but it is the sort of thing that is
invisible until it is not.)

### Attributing an address to a source file

Two independent sources, and the weaker one is misleading on its own.

`SymbolMap` looks for literal-pool words pointing at a `__FILE__` string. That finds only
the modules whose compiler output actually uses a pool; most of this image passes
`__FILE__` in a PC-relative `ADR`, so those modules are invisible and their code is
silently attributed to whichever unrelated neighbour owns the closest pool entry. This is
not hypothetical — it is why the low-power dialog was written up as
`ui_notify_skipPairingConfirm_dlg.c` for a while, from a pool entry over a kilobyte away.

`AssertMap` (`normplus/watch/fw/assertsites.py`) decodes the call sites instead. Every assert
in this firmware calls the handler at `0x000305B0` with `r0 = __FILE__`, `r1 = line`,
`r2 = expression`, so reading back the `ADR` that set `r0` gives an exact fact: 467 sites
across 106 modules, each certain, including `system_power_manager_task.c`,
`window_manager.c` and `wireless_charge_p9027lp.c` that the pools cannot see at all.
`SymbolMap.nearest` now answers from whichever of the two sits closer.

### The panel's coordinates are already the screen's

The touch driver stores `359 - raw` (0x000418B4), which reads like the panel's axes
running backwards. They do not: the axis stage above it (0x0003F250) is configured
`{1, 1, 0}` at `0x10001875` and rotates the point 180 degrees, so the two cancel and the
raw report reaches LVGL unchanged. The model mirrored as well, which put every tap in the
opposite corner and reversed the direction of every swipe — visible only once the swipe
actually navigated, because it then opened the page for the opposite direction.

### The panel raises TE, and the firmware waits for it

The RM67162 pulses its tearing-effect line once per internal refresh and the firmware
blocks on that edge before starting a frame. The wait is at `0x00024006` in
`display_amoled_rm67162.c`: read GPIO 18 through `am_hal_gpio_state_read`, give up after
`0x186A0` = 100,000 tries.

With nothing driving the pin, every frame ran that timeout to the end — 282,756 GPIO
reads per 20M instructions, ~4M instructions per frame, **more than half of everything
the watch executed**. `Rm67162Display.attach_te` now drives pin 18 as a 60 Hz pulse
(1 ms high), which is what the panel does.

| | before | after |
|---|---|---|
| UI cycle | 153 ms | 61 ms |
| frames in 200M instructions | 24 | 75 |
| GPIO reads per 20M instructions | 282,756 | 96,819 |

The reads that remain are the real thing: the firmware polling until vertical blanking.

### No I2C write carried any data

The IOM's `FIFOPTR` tells the HAL how much room is left to push. The model
answered a constant — read as 32 bytes always waiting to be read and **zero room
to write** — so the HAL's write loop never pushed a payload and every register
write on every I2C bus arrived empty. Reads worked the whole time, which is why
it went unnoticed for so long; the touch panel is read-mostly and its one write
is an interrupt acknowledge that ignores its own byte.

The motion sensor is what made it visible: its entire configuration is register
writes, so it received sixteen writes of `0x00` and stayed switched off.

Two details are worth keeping:

- **The read half of `FIFOPTR` is still a constant, deliberately.** Reporting the
  true byte count makes the HAL wait on a threshold a short transfer never
  reaches, and every sensor read stops. Only the write half is modelled honestly.
- **The HAL pushes whole 32-bit words**, so a one-byte register write arrives as
  one word with three bytes of padding. Those belong to that transfer: leaving
  them queued shifts every later value along by one, and the sensor gets its
  neighbours' bytes — `6C=00 19=3F 1A=13` instead of `6C=3F 19=13 1A=01`.

`tests/test_iom_and_motion.py` pins both, and that a write nobody answers still
swallows its payload rather than leaving it for the next device.

### The motion sensor has to fill its FIFO on the clock

The accelerometer on IOM1 at 0x68 is an InvenSense MPU-6500-class part, and the
whole model is transcribed from the firmware rather than a datasheet. Bring-up at
`0x000522FC` reads `WHO_AM_I` (0x75), resets through `PWR_MGMT_1` = 0x80, waits
100 ms, then walks a 16-entry (register, value) table at `0x000CE058`:

```
6C=3F  19=13  1A=01  1B=00  1C=10  1D=08  23=08  37=00
38=10  39=40  60=00  61=C8  6A=04  6A=44  6B=28  6C=07
```

— accelerometer only, 50 Hz, ±8 g, streaming into the FIFO with its interrupt
enabled. The read side is at `0x00052000`: `INT_STATUS` (0x3A), `FIFO_COUNTH`
/`COUNTL` (0x72/0x73) big-endian and clamped to 200, then a burst from `FIFO_R_W`
(0x74) of `count & ~7` bytes, parsed as 8-byte samples whose first three
big-endian int16s are X, Y and Z.

The watch never polls this part — it configures it once and waits — so a model
that only answers register reads stays silent. `MotionSensor` therefore runs off
the emulated clock like the TE line, filling its FIFO at whatever rate
`SMPLRT_DIV` asks for.

It is configured correctly and streaming, and **the firmware still never reads
it**. The sample reader at `0x00051F78` is reached only through a vtable (its
pointer sits at `0x0005235C`), and nothing has been seen to call it; pulsing each
of the interrupt-capable GPIOs in turn does not either. That is the next thing to
find.

### The resource path

Worth writing down, because almost none of it is guessable and it took a long time to
recover. Images on the watch face are **not** compiled-in bitmaps: they are files whose
*names are addresses*.

```
snprintf(buf, 20, "0x%x", 0x0C780000)      0x0006F5D4   the name IS the resource address
  -> lv_img_set_src copies "0xc780000" into the LVGL heap
  -> lv_fs_open                            0x000A4FA4   a leading '0' is remapped to
                                                        drive letter 'F', then the driver
                                                        list at 0x10053550 is walked
  -> the 'F' driver at 0x1005377C          ready/open/read = 0x000A8A01/0x000A8891/0x000A89A1
  -> read dispatches via [0x10001884]      -> 0x000330A4 -> [0x1005D650] = 0x0003E2F8
  -> 0x0003E2F8 builds `13 rr rr rr`       0x13 PAGE READ + a 3-byte row address
```

That whole chain now works end to end. The image headers are LVGL 5.3's packed
`lv_img_header_t` - `cf:5, always_zero:3, reserved:2, w:11, h:11` - which is how you can
tell the object at `0x0C780000` is the face background: it decodes as 360x360
`TRUE_COLOR`, and exactly 259,200 bytes of payload follow it. The glyphs are
`TRUE_COLOR_ALPHA`, three bytes a pixel, mostly one colour with a varying alpha.

### Most of the watch's images are not in the resource image (#64)

Run the watch live and almost every screen comes out wrong: "No / data" in grey over and
over, sometimes two of them a few pixels apart ("NNo ddata"), arcs cut off, the
notification screen black. It looks like broken drawing. It is not: **it is LVGL's own
placeholder for an image it could not decode**, and every one of those images is missing
from the emulated NAND.

`lv_draw_img` (`0x000A1504`, the source in r2) draws the string `"No\ndata"` (at
`0x000A15C8`) over the image's area on two branches: `0x000A1554` when the source is NULL,
and `0x000A1524` when `lv_img_draw_core` (`0x000A59B4`) fails. Logging the source at the
entry and counting the second branch, over a tour of ten screens:

```
187 resources opened        10 present: all inside 0x0C780000 - 0x0C7E2202
                            177 blank
167 "No data" placeholders  every one a blank resource

blank, clustered:  0x026DA430 - 0x0496BDAB   142 images  (~36 MB)
                   0x04E1152B - 0x04E1C617     3
                   0x08CBF981 - 0x0902DDC9    16          (~3.6 MB)
                   0x0AA58ACE - 0x0AA6E973    12
                   0x0C53D84A - 0x0C55619A     4
```

The emulated NAND holds one thing: `Picture_P03B_NORM2_0.4.bin`, the 401 KB resource image
the companion app ships, at `0x0C780000`. The physical watch also carries resources that
were programmed at the factory -- tens of megabytes from `0x026DA430` upwards -- which no
file in the app contains (its only binaries are the firmware and that resource image). The
firmware reads those pages, gets `0xFF`, and does exactly what it would on a watch that did
not have them. The doubled labels are two image objects whose placeholders overlap.

**The boot animation is the plainest case.** Its 134 frames are 360x360 `TRUE_COLOR` images
at `0x0288B517 + n * 0x3F484` -- a 4-byte header and 259,200 bytes of pixels each -- and all
134 are missing. That is why it has always run black ("The watch was never stuck"): not a
display problem, a NAND with nothing at those addresses.

`fw/resources.py` (`MissingResources`) does this counting all the time now: the live
window's status line says "N images not in the NAND", and the boot report lists the missing
ranges. `tests/test_resources.py` pins that every placeholder is a blank resource, so a
change that really breaks drawing shows up as a placeholder for an image the NAND *does*
have. Getting the resources themselves -- off the physical watch, or from the vendor's
update channel -- is #65.

### The NAND sees every command bit-expanded

The storage driver keeps the MSPI configured for four lanes, so it cannot clock an
ordinary one-lane command. Instead it **expands every command bit into a nibble** before
sending it: the packer at `0x00053788` fills the frame with `0xEE` and then ORs the bit
into bit 0 of each nibble, most significant bit first. `0xEE` holds DQ1..DQ3 high and
puts the data bit on DQ0/SI - exactly what a standard SPI slave samples, so the part
still sees a normal command.

```
0x13 PAGE READ  ->  EE EF EE FF
0x0F 0xC0       ->  EE EE FF FF  FF EE EE EE
```

Replies come back the same way but on DQ1/SO, so the bit lands in bit 1 of each nibble;
the firmware's own un-packer at `0x000534EC` rebuilds a byte from bit 5 and bit 1 of four
consecutive received bytes. A model that does not undo this sees opcode `0xEE` and
answers nothing. `tools/tests/test_spinand_expansion.py` pins the codec against
both firmware routines, transcribed instruction for instruction, over all 256 values.

`0x6B` READ FROM CACHE x4 really is a four-lane data phase, so *its* payload is raw -
only the command frame is expanded.

### The row address is relative to the selected die

The part is two 1 Gbit dies and the resources live on the second one. `0xC2` DIE SELECT
picks it, and every row address after that is die-relative. Decoding a row without that
offset reads the wrong half of the part, which comes back erased: the headers then parse
as `cf=31`, every decode fails, and LVGL paints its "No data" placeholder instead of the
face.

### The command queue has to run like hardware

Three separate things, all of which have to be right before a single page read completes.

**`CQADDR` reads back the controller's position.** `am_hal_cmdq`'s sync helper at
`0x000B85BC` does `q->pRead = *pCQAddrReg`, and `am_hal_cmdq_alloc_block` refuses to wrap
the ring past `pRead`. Returning the value software last *wrote* pins `pRead` to the base
of the ring, so the queue looks permanently full and every transfer posted through it
fails with `AM_HAL_STATUS_OUT_OF_RANGE`.

**A queue entry that writes `CQADDR` is a jump.** When a block will not fit before the end
of the ring, `alloc_block` appends a `(CQADDR, pBase)` pair and puts the block at the
base. The controller has to follow that, not treat it as an ordinary register poke.

**The controller runs on its own.** Software posts an entry while the queue happens to be
paused and relies on the controller picking it up later, so pumping the queue only when
software touches a register leaves it one entry behind for ever; the model advances it
from the clock instead, in `pending_irqs`. `CQCURIDX`/`CQENDIDX` are **eight bits** - the
sync helper reads the index back with a `UXTB` - so they have to be compared modulo 256,
or once software's own counter passes 255 the queue never looks caught up and stalls.

### The MSPI interrupt has to be raised, or every driver waits for a timeout

`Mspi` had no `pending_irqs()`, so IRQ 20 never fired. Anything that posts a transfer and
waits on a completion semaphore — the SPI NAND driver, and parts of the display path —
timed out instead, burning ~620k instructions per operation.

The bit meanings came from the firmware rather than a datasheet: the ISR at `0x0008DFB4`
calls `am_hal_mspi_interrupt_service` at `0x0008CD10`, which masks the accumulated status
with `0x18C0` for "finished or failed" and `0x1880` for "failed". The bit in the first
mask but not the second — **bit 6** — is DMA complete; 7, 11 and 12 are the error
sources. The driver enables `0x1AC0`, those plus CQUPD at bit 9.

Raising it took the emulator from ~2.15M to ~7.26M instructions/second.

### The I2C direction bits are the other way round

`IOM.CMD` bits[1:0] are **1 = write, 2 = read**. Backwards, every sensor read looks like
a write, nothing on the bus ever answers, and the whole I2C side reads as "no hardware
present" — which is indistinguishable from a device model that is simply missing. Two
transfers pin it down: the motion sensor's `WHO_AM_I`, which is a read-only register,
and the touch panel's report, whose bytes the driver goes on to parse as coordinates.

The touch panel's protocol was recovered the same way, from the driver rather than a
datasheet — see `docs/firmware.md` §4 for the report format, and `TouchPanel` in
`devices.py` for the model.

### The four things that had to be solved to get it booting

Each of these presents as a dead hang or a nonsense crash, and none is obvious from the
outside — worth recording so they are not re-derived.

1. **The Apollo3 boot ROM.** The application calls into mask ROM at `0x08000000` through a
   table of function pointers (`g_am_hal_bootrom_helper`, at `0x000CAF64` in this image).
   The HAL routes `CACHECTRL` accesses and all flash programming through ROM, so a normal
   boot walks into it within the first 150k instructions and an unshimmed emulator dies with
   a fetch fault. `bootrom.py` finds the table by shape (a run of ≥8 words pointing into the
   ROM window with the Thumb bit set), fills the window with `bx lr`, and services the
   entries in Python. Indices 8, 9 and 10 are confirmed by call-site analysis
   (`read_word`, `write_word`, `am_hal_flash_delay`); the rest are named from the AmbiqSuite
   layout and log their arguments when reached.

2. **Unicorn has no exception machinery on M-profile.** It executes instructions and banks
   MSP/PSP, but provides neither the PPB registers nor exception entry/return: a `bx lr` with
   an `EXC_RETURN` value leaves PC at `0xFFFFFFF8` — and, importantly, *`emu_start` at such
   an address returns successfully having executed nothing*, so handling it only on the error
   path spins forever. `cortexm.py` implements entry and return in software.

3. **NVIC priority bits.** Apollo3 implements 3, so the low 5 bits of every priority byte are
   RAZ/WI. FreeRTOS writes `0xFF` to a priority register and reads it back to discover the
   width, then asserts on the result. A model storing all 8 bits fails that assert and the
   scheduler never starts. The firmware itself said so, once the console hook was in place:

   ```
   Assert Failed: ucMaxPriorityValue == ( configKERNEL_INTERRUPT_PRIORITY & ucMaxPriorityValue ),
   File: ..\Config\FreeRTOS_AMapollo3\port.c:479
   ```

4. **The RTOS tick is an STIMER compare, not SysTick.** The AmbiqSuite FreeRTOS port
   overrides `vPortSetupTimerInterrupt`. Two details bite: CTIMER and STIMER share one 4 KB
   block with *interleaved* registers (STIMER's counter at +0x140, CTIMER's interrupt
   registers at +0x200, STIMER's at +0x300), and `SOFTWARE0` sits at IRQ 21 so the STIMER
   compares are IRQ 23-30, not 22-29. Get either wrong and the tick is enabled on one line
   and pended on another, which looks exactly like a dead scheduler.

### The firmware tells you what is wrong

The single most useful tool here is `console.py`. The image contains a printf-family
formatter and an assert handler that formats

```
Assert Failed:%s, File:%s:%d [curInterrupt=%d][curPriority=%d][maxPriority=%d]
```

before spinning in a `b .` loop. `console.py` locates the formatter by shape — find that
format string, find the literal-pool word pointing at it, find the `ldr` that loads it, take
the target of the next `bl` — hooks it, and renders the arguments. So instead of "stuck at
0x0003063A" you get the failing expression, source file and line, straight from the firmware.

When the firmware does *not* assert and simply hangs, `trace.py` reports which basic blocks
it is spinning over (symbolised to a source module) and which registers it is polling. That
turns a hang into a specific, fixable statement about a missing status bit.

A worked example of the workflow, which is how the MSPI model was derived: the stall report
pointed at `MSPI.CTRL`; the polling site turned out to be AmbiqSuite's generic
`am_hal_flash_delay_status_check(iterations, address, mask, value, isEqual)`; hooking that
one instruction and reading `r4/r5/r6` printed the answer directly —

```
status-check polls  (address, mask, expected) -> count
  0x50014000  mask=0x00000002  expect=0x00000002   x161563   [MSPI.CTRL]
```

— so the awaited bit was read off the running firmware rather than guessed.

## What to build next

The emulator is good enough to work against today for two things: questions about what
the firmware does (it has answered four the physical watch could not — the 8001/8003
split, the pairing window, the Database Hash park, and `DEVICE_VERSION` being refused
rather than ignored), and `:app`'s BLE stack end to end through the AVD. It was not
good for anything that crosses a restart (#41 has fixed that), anything that needs data on
the watch, or casual use: measured on 2026-10-02, a boot to the UI is 18.9s (`--seconds 14 --no-ble`)
and a bond-and-bind round trip is 51.9s (`test_ble_end_to_end.py`), every time. (Since
#55, ~18s: it no longer waits for the emulator to catch up with the wall clock.)

In the order to do them. All are GitHub issues on the NormPlus board (`normboard`).

1. ~~**#41 — persist flash**~~ -- done: `--flash-state PATH`, see "The watch keeps what it
   writes". It also closed a ninth bumble gap (the firmware was never asked for its key).
2. ~~**#49 — `normwatch cmd`**~~ -- done: one 0x6F question in ~4s from a bound state
   file, see "Running it". Its first answers: `DEVICE_VERSION CHECK [06]` is refused
   (status 1) on a bound watch as on an unbound one, and gets silence over 8003.
3. ~~**#47 — golden-fingerprint test**~~ -- done, and done first, because #46 changes
   the run loop: `tests/test_golden.py`, see "Golden fingerprints". It found #54.
4. ~~**#46 — snapshot and restore**~~ -- done: `--save-state` / `--load-state`, see
   "Snapshots". A boot to the UI goes from 11s to 0.47s, and a snapshot restored in
   another process lands on every golden fingerprint. Not with a bumble radio.
5. ~~**#50 — rehearse an OTA against the emulated watch**~~ -- done: the application
   serves it, the resource partition is type 4 (8 is refused), and a full update goes
   through; see "Rehearsing an OTA". **#56** then replaced `ApolloOtaProtocol.kt` with
   `ApolloOtaSession` in `:protocol`, and the same update went through end to end from
   `:app` in the AVD.
6. **#51 — health records.** A fresh watch has no sport, sleep or HR records, so `:app`'s
   sync only runs its empty paths against it. Drive the modelled accelerometer so the
   firmware's own pedometer records steps.
7. **#52 — script the AVD's stale-bond removal** for `normphone start --watch`.
8. **#53 — `0x50023800` / `0x50023804`**, unmodelled MMIO the BLE path touches.
9. **#54 — `--idle-skip` loses one STIMER tick with the radio up**, once, between 225M
   and 250M.

Not emulator work, but produced by it: **#48** — check the Database Hash park on the
physical watch; if it holds, `BleManager`'s cold-connect story is describing that stall
from the wrong side. And **#58**: `normwatch cmd --mac` asks the physical watch the way
`cmd` asks this one, so each of the four answers above can be checked on hardware with the
same command line; it replaced `normlink-cli`. The first one is done: on 2026-10-02 the
physical watch answered `DEVICE_VERSION CHECK [06]` over 8001 with `6f 01 81 02 00 03 01
8f`, byte for byte what this one answers.

Done, for the record: resources load from the NAND (#28, the stall this list used to
lead with), and BLEIF → HCI through first-run setup (#25 and the bind).

## Relationship to the rest of the repo

- `normphone` (`tools/normplus/phone/`) runs the **phone** side (Pixel 8 AVD) and bridges a real BT dongle to a
  real watch. `watchemu` is the other end: no watch. They meet at `--netsim` /
  `normphone start --watch`, where the AVD's Bluetooth is the emulated watch's radio.
- `normfw` (`tools/normplus/firmware/image_tool.py`) verifies and re-seals images; `watchemu`'s `image.py` is the
  loader's view of the same format and independently implements both CRCs.
- `docs/firmware.md` is the analysis this is built on. It is worth reading first.

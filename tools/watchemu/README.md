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

**The watch draws its screen, live, and you can touch it.** LVGL renders and flushes
real frames — 360×360 RGB565, four 64,800-byte DMA stripes per frame — and `--live`
puts them in a window as they arrive, with the mouse acting as a finger:

```bash
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot --live --no-trace
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

**Where it stops short.** *No radio.* The BLE stack never reaches the BLEIF registers, so
there is nothing to bridge to the phone yet - see
[What to build next](#what-to-build-next).

Getting the emulated firmware to talk to `:app` in the Android emulator needs the BLEIF
model (below), and is not implemented.

## Running it

```bash
py -3.11 -m pip install -r tools/watchemu/requirements.txt
```

Then, from the repo root (Python 3.11 — the same interpreter the emulator BLE bridge uses):

```bash
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot
```

```bash
PYTHONPATH=tools/watchemu py -3.11 -m normwatch info
```

```bash
PYTHONPATH=tools/watchemu py -3.11 -m normwatch modules
```

| Command | What it does |
|---|---|
| `info` | Parses the Ambiq image header, checks both CRCs, prints the vector table |
| `modules` | Lists the source files recovered from the firmware's `assert()` strings |
| `boot` | Runs the firmware and prints a triage report |

### The live window

```bash
PYTHONPATH=tools/watchemu py -3.11 -m normwatch boot --live --scale 2 --no-trace
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
normwatch/fw/
  image.py        Ambiq image header: link address, payload, both CRCs
  viewer.py       The live window: framebuffer -> Tk, mouse -> touch panel
tests/
  test_exception_entry.py   Regression test for the ITSTATE bug (run it directly)
  machine.py      The machine: memory map, MMIO bus, run loop, exception delivery
  cortexm.py      ARMv7-M private peripheral block: SCB, SysTick, NVIC + exceptions
  peripherals.py  Apollo3 peripheral register models (CLKGEN, MCUCTRL, CTIMER/STIMER, MSPI, IOM, …)
  devices.py      The chips on the buses: SPI NAND, PSRAM, RM67162 panel, touch panel
  bootrom.py      Shim for the Apollo3 mask-ROM helper functions
  console.py      Hooks the firmware's own printf so its asserts and logs are readable
  symbols.py      Address -> source module, recovered from assert() strings
  trace.py        Coverage, execution path, stall detection
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
PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_exception_entry.py
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
That is what `--force-gestures` does (`normwatch/fw/patches.py`), and it is off by
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
BSS, and zero is the fallback. The record it should be restored from is an
upgrade result: force the stored word to 1 or 3 and the watch shows *"Upgrade
Success"* instead, which is the first thing the emulated display has ever drawn
— 5,349 lit pixels against a lifetime of zero. Force 2 and it shows the other
outcome.

So the watch is not deciding to shut down, and it is not asleep. It boots,
looks for a record that our storage does not have, and takes the branch for
"none of the above". Holding the button across the decision changes nothing —
every earlier button test pressed at 20M instructions, and the decision is made
at 7.57M.

`ui_notify_poweroff_dlg.c` hosts exactly one notify screen, id 4, so this is
genuinely the power-off dialog rather than a boot animation sharing a file —
nine other modules in that table do host several dialogs each, so it was worth
checking.

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
| 16 | high→low | **the accelerometer** — `system_step_task.c` 47 → 92 blocks, `i2c.c` 133 → 167 |
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

`AssertMap` (`normwatch/fw/assertsites.py`) decodes the call sites instead. Every assert
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
answers nothing. `tools/watchemu/tests/test_spinand_expansion.py` pins the codec against
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

In dependency order. These are tracked on the kanban board (`/kanban`).

1. **Load UI resources from the NAND.** The render pipeline is known-good and the
   resource path is mapped end to end (above), so what is left is one specific stall.
   `0x00053A0C`, the SPI NAND read-cache call, blocks for ~1.1M instructions and returns
   -1 having issued **no MSPI traffic at all** — no register writes, no PIO, no DMA, no
   command-queue runs — so it is waiting on another task, not on the bus. The suspect is
   a storage/MSPI worker: the only NAND traffic after init is a periodic 16- or 8-byte
   DMA every ~11M instructions from `0x086680` → `0x050FD2` → `0x000B9BD4` (queue.c),
   carrying garbage read out of the HAL scratch buffers the display path also uses.
   Cheap thing to try first: only DMACMP is raised on the MSPI interrupt — the command
   queue's own completion bits (CQUPD at 9, and a CQCMP bit) are not, and the driver
   enables CQUPD. If the NAND driver waits on queue completion rather than DMA
   completion, that alone explains the stall.
2. **BLEIF -> HCI.** The Apollo3's BLE controller is on-die behind the BLEIF interface, and
   the firmware runs the Cordio/ExactLE host over it (`wsf_timer.c` is already being
   reached). Modelling BLEIF well enough to extract the HCI byte stream lets the emulated
   firmware's own BLE stack be bridged to a Bumble virtual controller, and from there — via
   the same `-packet-streamer-endpoint` netsim path that `tools/emulator/norm_emu_bridge.py`
   already uses for the USB dongle — to `:app` running in the Pixel 8 AVD. That is the route
   to connecting the emulated watch to the emulated phone, with no hardware at all.

## Relationship to the rest of the repo

- `tools/emulator/` runs the **phone** side (Pixel 8 AVD) and bridges a real BT dongle to a
  real watch. `watchemu` is the other end: no watch. Item 4 above is where they meet.
- `tools/firmware/image_tool.py` verifies and re-seals images; `watchemu`'s `image.py` is the
  loader's view of the same format and independently implements both CRCs.
- `docs/firmware.md` is the analysis this is built on. It is worth reading first.

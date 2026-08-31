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
data](#no-i2c-write-carried-any-data), which is the defect that hid it.

**The watch navigates.** With `--force-gestures` a drag on the window is recognised as
a swipe, the page changes, and the next screen draws — the goal ring to the left, the
"How to bind to APP" pairing help to the right. The flag is needed because of a firmware
behaviour the emulator provokes; [Touch reaches the firmware, swipes do not reach the
UI](#touch-reaches-the-firmware-swipes-do-not-reach-the-ui) is the whole story.

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

What is still open is whether the physical watch really rebuilds a face element every UI
cycle. If it does not — if something we get wrong keeps the face permanently dirty — the
cancel would be rare on real hardware and swipes would work without any patch. The refresh
is driven from `0x00077ABC`, which rebuilds when the pending-update count at `0x10002A24`
is 1, and that queue is fed once per cycle by elements `0x1005`/`0x1006` in the chain at
`0x00077E5C`. Nothing in the emulated state has been shown to make those elements dirty;
the alternative is that this is simply what the firmware does.

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

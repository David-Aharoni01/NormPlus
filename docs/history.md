# Retired tools and corrected beliefs

What the project used to have or believe, kept so that nobody rebuilds a dead end.

## `normlink-cli` and the WinRT-via-JNA transport

This replaced `normlink-cli` (`:cli`, removed 2026-10-02): `:protocol`'s
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

## Flags that were removed or renamed (#77, #80)

- **`normwatch boot --netsim`** is **`--phone`** since #80: it is how a person runs the app
  against the emulated watch, and netsim is only the Android emulator's transport. The old
  name still works, unlisted.

- **`normwatch boot --force-gestures`** suppressed the firmware's gesture cancel
  (`0x000842A4`), because every swipe in the emulator was cancelled. The cause was the
  low-power dialog being built and torn down once per UI cycle, and once the battery gauge
  answered it stopped: the golden face swipe is identical with and without the patch, radio
  on or off. A swipe needs nothing now. It had also been broken since flash programming
  started ANDing into what is there (7067f46): written without `erase=True`, the two NOPs
  landed as `00 b0 00 01` -- `add sp, #0; lsls r0, r0, #4` -- which would have sent the
  next store somewhere else in any run that reached the cancel. None did.
  `Apollo3Machine.write_flash(erase=True, persist=False)` -- a patch never saved into
  `--flash-state` -- stays for the next patch.
- **`boot --no-trace`** had done nothing since tracing went off by default.
- **`boot --slice`** set the instructions per `emu_start`, said to be "between
  timer/interrupt checks". It had become a safety cap: the block hook stops emulation the
  moment an interrupt is deliverable, and the deadline quantum before the next one.
- **`dump --page-pause`** paced the NAND dump. Measured at every value, 0 included, it did
  not stop the physical link dropping, and it cost 40% of the run.

And one default: `boot` given no budget used to run 30M instructions, 0.6s of watch time,
inside the 8s boot animation. It now runs until the animation is over and the UI is drawn.

## Two corrections the firmware analysis made (2026-08)

**Two corrections to what this file used to say:**

1. **`UPGRADE_MODE` does not reboot into a separate bootloader.** The DFU characteristic
   strings (`"Characteristic 1531"`/`"1532"`) and the `Upgrading… / Upgrade Failed` LVGL
   screens are all *inside the application image*, as are `ew_mod_ota_protocol.c` and the
   `OtaProtocol` FreeRTOS task. `0x0E` is a mode switch within the running firmware.
   **Consequence: the component that accepts OTA is the component you would be replacing.**
   A non-booting custom image leaves no BLE recovery path — that needs SWD, whose
   availability is still unknown.
2. **There are two CRCs, not one** (see below).

Both still hold. A third came from the watch emulator (2026-10, #50 / #56): the resource
partition is update type **4**, not 8 -- the firmware's SET handler refuses 8 -- and the
original `ApolloOtaProtocol.kt`, written to the type-8 path, diverged from the firmware in six
places. It was replaced by `ApolloOtaSession` in `:protocol`.

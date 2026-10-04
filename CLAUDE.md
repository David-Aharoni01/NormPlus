# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

It holds the rules and how to run things. Reference material lives in `docs/` (see "Where
things are documented"), and what is left to do lives on the GitHub board (`normboard`) -- not here.

## Purpose

Reverse-engineering workspace for the **Norm 2 smartwatch**. The goals are:
1. Understand the BLE communication protocol between the watch and its companion app (smali in `NORM/`)
2. Build a custom Android app that replicates/extends the companion app's functionality (`app/`)
3. Patch or replace the watch firmware via OTA -- the firmware is understood in depth; see
   [`docs/firmware.md`](docs/firmware.md)
4. Run the watch's own firmware on the PC, so the watch's behaviour can be observed and
   changed without the hardware (the watch emulator, `normwatch`, in `tools/normplus/watch/`)

## Task tracking -- GitHub issues on the NormPlus board

Work is tracked as **issues on `David-Aharoni01/NormPlus`**, shown on the GitHub project
board **NormPlus** (https://github.com/users/David-Aharoni01/projects/1: Todo / In Progress /
Done). It is the single source of truth for what is planned, in progress and done -- not prose
lists in this file, and not TODO comments in code. Issue numbers continue the local kanban
board this replaced (2026-10-02), so every `#N` in the docs and in commit messages is issue N.

`normboard` (`tools/normplus/board.py`) does the board in one step; `gh` does everything else:

```bash
uv run normboard                          # what is open: In Progress, then Todo, by priority
uv run normboard show 56                  # one issue with its comments
uv run normboard new "Title" -a emulator -p high -f body.md [--start]
uv run normboard area 56 firmware         # move it to another area (its milestone)
uv run normboard start 56                 # -> In Progress, when work begins
uv run normboard note 56 -f finding.md    # a finding, a result, a commit hash
uv run normboard done 56 -m "Done in <sha>: ..."   # closes it; the board says Done
```

- **Before starting any non-trivial new work, there must be an issue for it**, In Progress on
  the board. Create it first (`normboard new ... --start`).
- **Continuing or changing work that already has an issue is not new work.** A follow-up
  request, a correction, or a change of approach to something started under an issue goes on
  that issue -- a comment with what changed and the commit -- and its commits name it. File a
  new issue only for work that would stand on its own.
- **Discovering separate follow-up work means filing an issue**, not appending to a list here.
- **Findings and results go on the issue as comments**, with the commit hashes. A commit
  message saying `Fixes #56` closes the issue when it reaches `main`.
- **Dependencies** go on the first line of the body: `Depends on: #N`.
- **Areas are milestones** (#78, #79): every issue, open or closed, is in exactly one --
  App, Protocol, Firmware, Emulator or Tooling -- so the board can be grouped and sliced by
  them. `normboard areas` says what each covers; an issue goes where it is *done*.
  `normboard new` refuses an issue without `-a AREA`, and `normboard sync` makes any of the
  milestones that is missing.
- **Labels:** one `priority: high|medium|low` on every issue. Anything else (`ble`, `ota`,
  `verification`, ...) is an optional tag, `-l TAG`.
- **Split an issue** if it exceeds ~1h, spans two layers, is hard to roll back, or is uncertain.
- **The repository is public, so the issues are.** Findings, not vendor code excerpts or
  personal data.
- `gh` is at `C:\Program Files\GitHub CLI\gh.exe`, signed in with the `project` scope;
  `normboard` finds it there. The old board is archived at
  `D:\NormPlus-backup\kanban-Norm+-2026-10-02.json`; the `/kanban` skills are not used here.

## Code Quality Standards

Every piece of code in this repo is grounded in something the watch or its app actually
does — the NORM app smali for the protocol work, the firmware image itself for
the watch emulator (`tools/normplus/watch/`). Before implementing any command, parsing any response, deciding on any
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

## Repo layout

```
NORM/               PRIVATE submodule (NormPlus-reference): the official companion app v1.1.18,
                    unpacked with apktool, and the APK itself in _apk/ (Git LFS)
  smali*/cn/appscomm/, smali_classes2/com/appscomm/   its own code  <- READ THIS FIRST
  smali*/...        the bundled libraries (androidx, Google, RxJava, ...), kept for reference
  assets/           Apollo3_P03B_NORM2_F0.2B01.bin (the watch firmware the emulator runs),
                    Picture_P03B_NORM2_0.4.bin (the resource image; the app build copies it in)
protocol/           :protocol -- pure JVM: framing, commands, the Apollo DFU session
app/                :app -- the Android companion app (Kotlin + Compose)
tools/              the Python tools: one uv project (pyproject.toml, uv.lock at the root)
  normplus/
    watch/          the WATCH emulator (normwatch, normcmd): the watch's own Apollo3 firmware
                    under Unicorn; normcmd --mac also asks the PHYSICAL watch from the PC
    phone/          the Pixel 8 ANDROID emulator (normphone) + the Bumble HCI bridge, for :app
    firmware/       image_tool.py (verify / re-seal), nand_patch.py (the NAND read-out
                    patch), query_ota.py (the vendor OTA server): normfw
    testing.py      normtest
  tests/            the Python test suite: standalone scripts, run by normtest
docs/               reference: app.md, protocol.md, firmware.md, watch-emulator.md,
                    watch-emulator-internals.md, android-emulator.md, history.md
```

Note the two "emulators": `normphone` (`tools/normplus/phone/`) runs the **phone**,
`normwatch` (`tools/normplus/watch/`) runs the **watch**. They meet at `normwatch boot --netsim` / `normphone start --watch`.

### This repository is public; the vendor's material is not

`NormPlus` is a **public** repository. The official app's code, its resources and the watch
firmware are the vendor's copyrighted material, and they live only in the **private**
submodule at `NORM/` (`NormPlus-reference`) -- whose history was rewritten out of this one on
2026-10-02. Hard rules:

- **Never commit vendor files here**: no smali, no decompiled code, no firmware images, no
  APKs, no resources lifted from the app. New vendor material goes into the `NORM/` repo
  (commit and push it there, then commit the updated submodule pointer here).
- **Nor anything derived from them**: a patched firmware image is the vendor's image with
  our bytes in it. `normfw patch-nand` builds one on demand; tests build theirs into a
  temporary directory. Never into a tracked path.
- Citing the smali by path and quoting a few lines in a comment or a doc is fine; copying
  whole methods or files is not.
- Builds and tests read vendor files from `NORM/` at run time (the app's bundled resource
  image, the watch emulator's firmware) and must not copy them into tracked paths.

**Getting `NORM/` on another machine:** `git config --global core.longpaths true` first on
Windows (the longest path in `NORM/` is 184 characters, past MAX_PATH from most clone
locations), then `git clone --recurse-submodules
https://github.com/David-Aharoni01/NormPlus.git` (signed in as the owner), or
`git submodule update --init` in an existing clone. The APK in `NORM/_apk/` comes through
Git LFS. Without access to the private repo,
regenerate it from your own copy of the APK: `java -jar apktool_3.0.2.jar d NORM.apk -o NORM`.

## Where things are documented

| Where | What |
|---|---|
| `docs/app.md` | `:app` and `:protocol` architecture: BLE layer, the cold-connect penalty, always-on uptime, notifications, calls, data, UI; what is verified on the watch |
| `docs/protocol.md` | The 0x6F channel, CHECK/SET rules, the Apollo DFU update as the firmware answers it, the version string, key smali files, apktool |
| `docs/firmware.md` | The watch firmware itself: image format, the two CRCs, hardware, the vendor OTA server, what can be patched, the NAND read-out patch |
| `docs/watch-emulator.md` | The watch emulator in summary: boot, radio, NZ8801, architecture, performance switches, and **where it differs from the real watch** (#74) -- read that before trusting an emulator-only result |
| `docs/watch-emulator-internals.md` | The watch emulator in full -- read it before changing anything in `fw/` |
| `docs/android-emulator.md` | The Android emulator and its Bluetooth bridge |
| `docs/history.md` | Retired tools (`normlink-cli`) and corrected beliefs |
| `README.md` | The human entry point: building, and asking a watch a question |

---

## Build Commands

### Python tools (uv)

Everything in Python is one uv project: `pyproject.toml` at the root, the code in
`tools/normplus/`, the tests in `tools/tests/`. It installs seven commands:

| Command | What |
|---|---|
| `normwatch` | the watch emulator: `boot`, `info`, `modules`, `dump` (`tools/normplus/watch/`) |
| `normcmd` | one 0x6F question to a watch -- the emulated one, or the physical one with `--mac` |
| `normphone` | the Android emulator: `setup`, `start [--watch / --no-bridge]`, `install`, `clear-bond`, `bridge` |
| `normfw` | firmware images: `verify`, `seal`, `patch-nand`, `query-ota` |
| `normtest` | the Python test suite, one file at a time (`normtest ota cmd`, `normtest ota -- --full`) |
| `normboard` | the task board: GitHub issues on the NormPlus project (see "Task tracking") |
| `normhelp` | what every command is for, the common recipes; `normhelp <tool> [subcommand]` for its help |

```bash
uv sync                          # once, and after pulling: Python 3.11 + the pinned libraries (uv.lock)
uv run normwatch info            # any command, from the repo, always the current code
uv tool install --editable .     # once per machine: the commands on PATH, still editable
```

uv for everything: no pip, no `py -3.11`, no `PYTHONPATH`. In Claude's Bash, use
`uv run <command>` (the `.venv` is not on that PATH). The commands find the repository from
their own (editable) location, so they work from any directory.

**Every `--help` has two readers** (#76). `options` are the owner's: what the README's and
`normhelp`'s recipes use, troubleshooting the docs tell a person to do, and decisions that are
the owner's to make (`ota --allow-mcu`). `developer options` (`normplus.developer_options(parser)`)
are Claude's: diagnosis, measurement, tuning, overriding a default that is right for normal
use. A new flag goes in one of the two; when in doubt, developer.

### Gradle (`:app`, `:protocol`)

```bash
# Build debug APK (Android app)
./gradlew assembleDebug

# Build and install on connected device (or the emulator — use the DEBUG variant;
# release is unsigned and fails to install with INSTALL_PARSE_FAILED_NO_CERTIFICATES)
./gradlew installDebug

# Boot the test emulator (Pixel 8 AVD). Bluetooth-to-watch bridge is ON by default;
# --no-bridge for a UI-only boot. See "Testing on the Android emulator".
# (normphone install = ./gradlew installDebug)
normphone start
normphone start --no-bridge

# Ask the PHYSICAL watch one 0x6F question from the PC (bleak, this PC's own adapter).
# See "Asking the physical watch from the PC". Exit 0 a reply / 1 none / 2 setup failed.
normcmd BATTERY_POWER CHECK --mac

# Run protocol unit tests (no BLE/Android needed)
./gradlew :protocol:test

# Run a single test class
./gradlew :protocol:test --tests "com.norm2hacked.protocol.PacketTest"

# Run lint
./gradlew lint
```

### Watch emulator (`normwatch`, `normcmd`, `normtest`)

```bash
# Boot the watch firmware and print a triage report. Given no budget it runs until the
# boot animation is over and the UI is drawn (#77): 51s with the radio, 37s without.
# --seconds N runs N seconds of watch time instead (the animation alone is 8.3s).
normwatch boot
# --no-ble reaches the same screen faster; use it whenever the run is not about the
# radio. See "The BLE controller" below.
normwatch boot --no-ble
# The watch's own factory resources (NORM/_nand, #65) are mounted by default, so the
# screens and the boot animation are the real ones. That is ~22s of BOOT and nothing
# after it: idling, swiping and changing screen cost the same with them as without
# (#72). So for a live session skip the boot, not the artwork -- --load-state gives a
# usable window with every image in 3.6s. --no-factory-resources is for cold runs that
# are not about what is on the screen (#71).
normwatch boot --no-ble --no-factory-resources
normwatch boot --live        # window + mouse touch
normwatch boot --screenshot out.png
# A radio behind the firmware's BLE stack (a bumble controller): the watch advertises.
normwatch boot --radio
# ...and the Android emulator's netsim endpoint on the same virtual air, so the
# Pixel 8 AVD (normphone start --watch) pairs with the EMULATED watch.
# Implies --realtime. --hci-trace logs every packet across the seam.
normwatch boot --netsim --live
# Keep the watch's flash between runs: bind it once, and every later run with the same
# file boots to the face with its bond. Saved at exit, closed window and Ctrl-C included.
normwatch boot --netsim --live --flash-state watch.zip

# Skip the boot: save the machine once past the boot animation, start there next time
# (0.47s instead of 11s). Same switches on both; not with --radio/--netsim.
normwatch boot --no-ble --save-state ui.snap
normwatch boot --no-ble --load-state ui.snap --seconds 2 --live
# Ask the firmware one 0x6F question: boots, pairs, binds if checkInit says 0, sends,
# prints every reply decoded. ~4s from a bound state file, ~14s binding a fresh watch.
# Hex or :protocol names; a CHECK's payload defaults to the [00] every CHECK needs (#77).
# --char 8003 writes to the other write characteristic (a SET there is never acknowledged);
# --no-bind skips the bind. --mac asks the physical watch instead (--mac MAC another one).
normcmd BIND_END CHECK --flash-state bound.zip --save
normcmd 08 70 --flash-state bound.zip

# Parse the image header / list source modules recovered from assert() strings
normwatch info
normwatch modules

# Tests are standalone scripts, not pytest. Run one:
normtest native_hook

# Run all of them (each exits non-zero on failure):
normtest

# The C block hook builds itself on demand; to build it by hand:
uv run python tools/normplus/watch/native/build.py
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
tooling is `normphone` (`tools/normplus/phone/`; `docs/android-emulator.md` has the full story).

- **Boot + install:** `normphone start`, then `./gradlew installDebug`.
  Always use the **debug** variant — the release build is unsigned and won't install.
- **Real-watch BLE works in the emulator.** The emulator has no host Bluetooth radio, so a
  dedicated **USB BLE dongle (CSR8510 `0A12:0001`, driver swapped to WinUSB via Zadig)** is
  bridged in via **Bumble**. `normphone start` starts this bridge by default (`--no-bridge`
  = UI-only). The bridge is `tools/normplus/phone/bridge.py` — a *custom* bridge, not stock
  `bumble-hci-bridge`, because it must (1) handle emulator 36.x's newer netsim `packet` proto
  field and (2) short-circuit `LE_GET_VENDOR_CAPABILITIES (0xFD53)`, which the CSR8510 ignores
  and which otherwise fatally aborts the guest BT stack. Don't "fix" the bridge by reverting
  either workaround.
- **Pairing asks for consent.** With no stored bond, Android answers the app's `createBond()`
  with a "Pair with Norm2#00000?" dialog (Just Works consent, pairing variant 3). Nobody
  tapping *Pair* within 30s fails it with `SMP_RSP_TIMEOUT`, and `BleManager` carries on
  unbonded -- GATT still works, so it looks connected. If the dialog lands as a notification
  (it does while the app is still starting), toggle guest BT to get it again. In automation:
  poll `uiautomator dump` for the *Pair* button and tap it.
- **Bonding gotcha:** the watch's SMP is racy (same as on a real phone — see "cold-connect
  penalty"). If a connect attempt fails to bond, toggle guest BT and retry:
  `adb -e shell svc bluetooth disable; adb -e shell svc bluetooth enable`.
- **The dongle is independent of the host's built-in adapter**, which is what `normwatch cmd
  --mac` uses (it bonds the watch with Windows on first use). Don't run the emulator BLE bridge,
  `normwatch cmd --mac` and the official app at the same time — they contend for the watch's
  single active connection.
- **Rebuild from scratch:** `normphone setup` (SDK packages + AEHD + AVD + Bumble);
  the Zadig WinUSB swap is the one manual step.
- **The emulated watch instead of the real one:** `normphone start --watch` skips the dongle
  and points the AVD at the watch emulator, which serves the same netsim endpoint itself
  (`normwatch boot --netsim --live`, start it first). `:app` bonds with it, binds it (the
  pairing screen's first-run handshake) and lands on the dashboard while the watch goes to
  its face; nothing physical involved. Verified on this AVD. Run the watch with
  `--flash-state FILE` and it keeps the bind and the bond: restart it with the same file and
  `:app` reconnects with its stored keys (the firmware answers the LTK request from flash),
  no wipe, no re-bind — verified on this AVD. Three things to expect: restarting the watch
  means restarting the AVD too (`adb emu kill`, then `--watch` again — the emulator's packet
  streamer never reconnects to a new endpoint; bond and app data survive it); the AVD's bond
  has to be removed when the watch on the other end does not hold it — a new state file, or
  after the physical watch: `normphone clear-bond` (it deletes the `[4c:59:80:12:44:f1]`
  section of `/data/misc/bluedroid/bt_config.conf` with BT off); and the first one or two connection
  attempts on a bonded reconnect stall in discovery before one goes through — the Database
  Hash park below, not the emulator. See "A real stack behind the seam", "Binding" and "The
  watch keeps what it writes" in `docs/watch-emulator-internals.md`.

`normwatch cmd --mac` (below) is the fastest headless way to ask the *physical* watch a
0x6F question; without `--mac` the same command asks the emulated one, in ~4s from a bound
state file. Both write to 8001 as the app does and decode with the same code. The AVD is
for exercising the full `:app` stack (`BleManager`, sync, UI) against either.

---

## Asking the physical watch from the PC (`normwatch cmd --mac`)

**The fastest headless way to ask the physical watch a 0x6F question** — no APK, no AVD,
and Claude can run it directly. It is `normwatch cmd` (see the watch emulator's build
commands) with `--mac`: the same flow and the same decoder as against the emulated watch,
over this PC's own Bluetooth adapter with bleak (`tools/normplus/watch/physical.py`).
Where the two answers differ, the watches differ — this is how an emulator finding is
checked on hardware.

```bash
normcmd BATTERY_POWER CHECK --mac              # --mac alone: the watch at 4C:59:80:12:44:F1
normcmd 03 70 --payload 06 --mac               # a CHECK sends [00] unless told otherwise
```
```
   2.2s  phone: connected, paired, encryption not reported
   2.4s  watch: initialised (checkInit 1), not binding
   2.4s  -> 8001  6f 08 70 01 00 00 8f    0x08 BATTERY_POWER CHECK [00]
   3.1s  <- 8002  6f 08 80 01 00 5f 8f
   3.1s           = 0x08 BATTERY_POWER CHECK_RESPONSE [5f]
```

**Watch MAC:** `4C:59:80:12:44:F1`. Needs `uv sync` (bleak is one of the
dependencies).

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

It replaced `normlink-cli` (`:cli`), removed 2026-10-02; `docs/history.md` has why, and what that tool's predecessor learned about raw WinRT.

---

## Working on the watch emulator (`tools/normplus/watch`)

It runs `NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin` on an emulated Ambiq Apollo3 Blue under
Unicorn. It is a hardware emulator, not a protocol mock: the behaviour comes from executing
the watch's code, and the firmware image is the source of truth exactly as the smali is for
the protocol. `docs/watch-emulator.md` summarises it; `docs/watch-emulator-internals.md` explains it.

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

### Working on it

- **"No data" on the screen is missing NAND data, not broken drawing.** It is LVGL's
  placeholder for an image it could not decode, and the emulated NAND has only the 401 KB
  resource image -- not the watch's factory resources (tens of MB from `0x026DA430`), where
  most screens' images and all 134 boot-animation frames live. The boot report's `images:`
  line and the live window's status line count them (`fw/resources.py`, #64, #65).

**An emulator-only result is not a result about the watch.** The places the two are known
to differ are listed in `docs/watch-emulator.md` ("Where the emulated watch and the real
one differ", #74) -- the storage stack never goes down here and usually is down there,
nothing cycles so leaks never show, the link never drops, and 0xEE answers 2.5x slower.
Three separate incidents on the only watch we have were green in the emulator first. Read
that list before trusting a run, and add to it when the two disagree again.

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

## OTA safety -- hard rules

- **The resource partition (update type 4) is the only one sent casually.** `ApolloOtaSession`
  refuses types outside 1-4 and refuses a main-MCU update (type 1) unless explicitly allowed;
  nothing in `:app` allows it, and that stays true. The code that receives an update is the
  code a main-MCU update replaces, so a non-booting image has no recovery path until SWD is
  established (#14).
- **A main-MCU update (type 1) goes only through `normwatch ota --allow-mcu`, and only with
  the owner's go-ahead for that flash.** It was first done for #70 (the NAND read-out patch).
  Everything that gates it is in `fw/otasend.py` and cannot be flagged away: a Telink image is
  refused, and so is an image whose length or CRC does not match its own contents -- the
  bootloader checks that CRC and a half-sealed image is a brick. Before any such flash:
  rehearse the exact image against the emulated watch (`normtest ota_mcu -- --payload FILE`),
  and read the running firmware back over 0xEE to confirm the watch is running the image the
  patch was built from.
- **A patch that changes when something is freed must be checked against every object on
  that path**, not only the one it is about. The emulator cannot catch this: it runs minutes,
  not hours, and nothing in it changes screens for an hour. Removing the storage teardown
  (#70) left two creates unguarded where one was obvious, and the second leaked an allocation
  per screen change until the watch's heap ran out -- twice, on the only watch we have, after
  a clean emulator run. Audit the frees in the function being bypassed, each one's create, and
  what calls it again.
- **Never send a Telink image.** The vendor's public OTA server serves Norm 1 firmware for a
  Telink SoC (`KNLT` at +0x08); `OtaImage.parse` refuses it. Do not keep copies in the repo.
- **A type-4 SET erases the live resource partition at once.** An interrupted update leaves
  the watch without its UI until it is re-sent (the OTA code survives in internal flash).
- **Not within ~10s of the watch booting** -- an update opened over the boot animation
  overflows the UI task's stack (#57).
- Anything new is rehearsed against the emulated watch first (`tests/test_ota.py`, and
  `tests/test_ota_mcu.py` for a main-MCU image, then `:app` through `normphone start
  --watch`), and only then on the physical watch. A type-1 rehearsal ends at the watch's
  reset: the bootloader's copy that follows cannot be emulated (`docs/firmware.md` §5).

## Git

`main` carries everything. Work happens on a feature branch and is merged back; there is one
remote (see `git remote -v`). Commit messages end with the attribution line the session gives.

# Norm 2 watch firmware — what it is and what we can do to it

Analysis of the firmware images bundled in the original companion APK
(`NORM/assets/`), plus the vendor's OTA server. Everything below is derived from
the binaries and the app's smali; inferences are flagged as such.

> **Updated 2026-10 (#50, #56).** Where this document says the resource partition is
> update type 8, read **4**: the watch's own SET handler accepts 1-4 and refuses 8, which
> the watch emulator showed by running the firmware. The update protocol as the firmware
> answers it is in `docs/protocol.md` ("Apollo DFU"), and the resource rehearsal has been
> done against the emulated watch from `:app`.

## Summary

The Norm 2 runs an ODM firmware (Shenzhen Appscomm; the Allview Allwatch H looks like the same
platform rebranded) on an **Ambiq Apollo3 Blue** — Cortex-M4F, 1 MB flash @0x00000000, 384 KB
SRAM @0x10000000, integrated BLE 5. Board string `EW_WBT21-A`. The stack is **FreeRTOS 9** +
the **AmbiqSuite** `ambiq_ble` Cordio host + **LVGL 5.x** for the UI, with a vendor `ew_` HAL.
Hardware from the driver names: RM67162 AMOLED, WCN1F3549 touch, PixArt PAH8011 HR, CW6303
PMU, IPUS PSRAM, SPI NAND, P9027LP wireless charging, stepper-driven physical hands.

The image is **unencrypted, unpacked and unobfuscated**, and its 130 `assert()` `__FILE__`
strings leak the whole source tree — so it is very tractable to analyse and patch.

```
internal flash  0x00000000–0x0001FFFF   bootloader + config (128 KB reserved)
                0x00020000–0x000D6693   application
external NAND   0x0C780000              resources / pictures      (update type 4; 8 is refused)
                0x0FC00000              OTA staging               (update type 1)
```

---

## 1. The images

| File | Size | What it is |
|---|---|---|
| `NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin` | 747,204 B | The **entire watch OS** — main MCU application |
| `NORM/assets/Picture_P03B_NORM2_0.4.bin` | 401,926 B | Resource blob (images, fonts, language pack) |

Both are raw and **unencrypted, unpacked, unobfuscated**. The Apollo image is plain
ARM Thumb-2 with intact `assert()` `__FILE__` strings; the resource blob is packed
image data behind a small table.

Update types the OTA path understands (`OtaApolloCommand.getUpdateType`):
`1` = Apollo (main MCU), `2` = TouchPanel, `3` = HeartRate, `8` = Picture / Language /
WatchFace. **That is `cn.appscomm.ota`'s table; the firmware accepts only 1-4, and the
resource partition is 4 (`cn.appscomm.bluetooth.ota`'s `UPDATE_TYPE_PICTURE_LANGUAGE`).**

---

## 2. Binary format

Confirmed against `OtaApolloCommand.create()` (`NORM/smali_classes2/cn/appscomm/ota/`):
the app calls `getBinContent(path, 0)`, takes `file[0..3]` as the destination address
and `file[4..]` as *content*, CRC-16-CCITTs the content, and streams it.

```
+0x00  0x0FC00000   destination address; the app strips these 4 bytes and puts
                    them in the OTA SET header. NOT part of the CRC'd content.
─────── everything below is "content": CRC'd, length-counted, streamed ───────
+0x04  0x00020000   link address (where it executes in internal flash)
+0x08  0x0FC00000   staging address again
+0x0C  0x000B6694   payload byte count = filesize − 48
+0x10  0xD392D741   image CRC (the bootloader's own — algorithm NOT identified, see §7)
+0x14  0xFFFFFFFF   override GPIO      ┐ matches Ambiq's am_bootloader_image_t
+0x18  0xFFFFFFFF   override polarity  ┘ (override disabled)
+0x1C  0x1005FA50   initial stack pointer
+0x20  0x00020101   reset vector (thumb bit set)
+0x24  0x00000000
+0x28  0xFFFFFFFF
+0x2C  0x00000001
+0x30  ─── Cortex-M vector table ───
       SP=0x1005FA50  Reset=0x00020101  NMI=0x0002011F  HardFault=0x000203B1
       MemManage=0x00020121  BusFault=0x00020123  UsageFault=0x00020125 …
```

The app-side CRC-16-CCITT (poly 0x1021, init 0xFFFF) over the content of this file is
**0x1979**; the app sends it as `[lo, hi, 0x00, 0x00]` in the SET header.

The resource blob has the same 4-byte address prefix (`0x0C780000`) and then raw data —
no Apollo image header, no vector table.

---

## 3. The software stack

Every `assert()` in the firmware carries its `__FILE__`, which leaks the whole source
tree. This is a commercial ODM firmware, not a bespoke one:

- **RTOS: FreeRTOS 9** — `..\..\Rtos\FreeRTOS9_OS\Source\{tasks,queue,timers,event_groups,list}.c`,
  port at `..\Config\FreeRTOS_AMapollo3\port.c`.
- **BLE: AmbiqSuite Apollo3 SDK** — `..\Board_SDK\Apollo3-SDK\ambiq_ble\apps\band\band_main.c`,
  i.e. the stock Ambiq `ambiq_ble` app framework over the ARM **Cordio** host
  (`WSF Timer` / `xWsfTimer` strings present). Also `Board_SDK\Apollo3-SDK\utils\am_util_faultisr.c`.
- **GUI: LVGL v5.x** — `..\..\GUI\pc_sim\lvgl\lv_objx\*`, `lv_core\{lv_obj,lv_refr,lv_indev}.c`,
  `lv_draw\lv_draw_vbasic.c`, `lv_core\lv_vdb.c` (the VDB and `lv_objx` naming pin this to
  the 5.x generation). Custom widgets live in `..\..\GUI\lv_obj_ex\` (`lv_barcode`,
  `lv_scrollview`, `lv_page_ex`, `lv_img_ex`), port layer in `..\..\GUI\Port\{ew_lcd_port,ew_nfs}.c`.
  Every screen is a `ui_*_dlg.c` under `..\..\GUI\pc_sim\lv_examples\UI\` — they develop the
  UI against the LVGL PC simulator and cross-compile it.
- **Vendor HAL, `ew_` prefix** (board string **`EW_WBT21-A`**):
  - `..\Driver\` — `ew_drv_{flash,gpio_irq,i2c,sim_i2c,pwm,qspi,rtc,timer,uart}.c`
  - `..\..\Device\` — `Bluetooth, Display, HeartRate, Key, Nand, Ots, Pmu, PSRAM, Sensor,
    Touch, Vibrator, Watch, WirelessCharge`
  - `..\..\Modules\` — `Ble\ew_mod_ota_protocol.c`, `Clock`, `Hash`, `QRCode\rsecc.c`, `Storage`, `Timer`
  - `..\Board\` — `flash.c, gpio_irq.c, i2c.c, pwm.c, qspi.c, rtc.c, timer.c, uart.c`
- **FreeRTOS tasks:** `SystemTask, Storage, VibratorBuzzer, BleProtocol, OtaProtocol,
  UartProtocol, UITask, NotUrgentTask`, backed by
  `..\..\System\system_{ble,ble_protocol,clock,hr,not_urgent,ota,ota_protocol,power_manager,step,storage,vibrator_buzzer,watch}_task.c`.

Version string embedded in the image: `A0.2R0.1T1.1H0.5B0.1`.

**Provenance:** the app is white-labelled from **Shenzhen Appscomm** (`appscomm.cn`,
`fashioncomm.com` privacy URLs, `cn.appscomm.*` package root). The same APK also carries
`allviewmobile.com/allwatchh` manual/privacy URLs — the **Allview Allwatch H** is almost
certainly the same platform under another brand, which matters if you want a second,
sacrificial unit.

---

## 4. Hardware, read off the driver filenames

| Part | Evidence |
|---|---|
| **Ambiq Apollo3 Blue** — Cortex-M4F, 1 MB flash @0x00000000, 384 KB SRAM @0x10000000, integrated BLE 5 | link addr 0x20000, SP 0x1005FA50 (just under the 0x10060000 SRAM top), `FreeRTOS_AMapollo3`, `apollo3_bluetooth.c` |
| **Raydium RM67162** AMOLED, QSPI | `..\..\Device\Display\display_amoled_rm67162.c` |
| **WCN1F3549** touch controller | `..\..\Device\Touch\touch_wcn1f3549.c` |
| **PixArt PAH8011** optical HR | `hr_pah8011.c`, `pah_hrd_function.c`, `pah_factory_test.c` |
| **CW6303** (Cellwise) PMU / fuel gauge | `..\..\Device\Pmu\pmu_cw6303.c` |
| **IPUS PSRAM**, QSPI (LVGL framebuffer) | `..\..\Device\PSRAM\psram_ipus.c` |
| **SPI NAND**, 256 MB address space | `ew_dev_spinand.c`; 0x10000000 appears as a bound |
| **IDT / Renesas P9027LP** wireless-charging RX | `wireless_charge_p9027lp.c` |
| Stepper motors driving the physical hands | `..\..\Device\Watch\watch_cb.c` — `targetDegree`, `curDegree`, `deltDegree`, `restore` |
| Crown + button | `..\..\Device\Key\key_irq.c`; UI text "Rotate the crown to set …" |
| Accelerometer, **InvenSense family** (MPU-6xxx / ICM-206xx) at I2C **0x68** on IOM1 | `ew_dev_sensor.c` names no chip, but its register map is unmistakable — the driver writes SMPLRT_DIV 0x19, CONFIG 0x1A, GYRO/ACCEL_CONFIG 0x1B/0x1C/0x1D, FIFO_EN 0x23, INT_PIN_CFG/INT_ENABLE 0x37/0x38, USER_CTRL 0x6A, PWR_MGMT 0x6B/0x6C and reads WHO_AM_I 0x75 (observed under the watch emulator) |
| `ew_dev_ots.c` | **unidentified** (temperature sensor? object transfer?) |

### The I2C bus, decoded by running the firmware

Driver filenames say what the parts are; running the image under the watch emulator (`normwatch`) says
where they are and how they are spoken to. Both of these were read off the executing
firmware, not from a datasheet.

| | Touch panel | Motion sensor |
|---|---|---|
| Controller | IOM2 (`0x50006000`) | IOM1 (`0x50005000`) |
| I2C address | `0x20` | `0x68` |
| Interrupt | **GPIO 28** | not yet identified |

GPIO 28 was found by pulsing each of the six interrupt-enabled pins (2, 3, 10, 24, 28, 38)
in turn and seeing which one made the watch talk to a sensor.

**WCN1F3549 touch report.** On each edge the driver reads eight bytes from register
`0x8000` and two more from `0x8400` into one buffer, then parses:

```
buffer[4:6]  X, little endian      buffer[9]  event flags
buffer[6:8]  Y, little endian                 bits 0-1 = contact, bit 3 = lift
```

It bounds-checks both coordinates against 360 and stores **`359 - value`**, so the panel's
axes run opposite to the screen's. A byte written to register `0x03` acknowledges the
interrupt. The parsed state lands at `0x10011D00`: `+0x0C` X, `+0x0E` Y, `+0x10` pressed.

**IOM `CMD` bits[1:0] are `1` = write, `2` = read** — the opposite of the natural guess, and
worth stating because getting it backwards makes every sensor read look like a write and
nothing on the bus can ever answer. The register address is up to three bytes: the low one
in `CMD[31:24]`, the rest in `OFFSETHI`, with only the bottom `OFFSETCNT` on the wire.

### The display window is in panel coordinates, not screen coordinates

The RM67162's memory is larger than the glass. This watch maps its 360x360 visible area at
**(20, 16)**, so every window the firmware sets is `20..379` by `16..375`, and a frame is
four 64,800-byte DMA stripes of 90 rows each. Treating those addresses as screen
coordinates clips each row to 340 pixels and shears the picture into diagonal noise.

### The resource partition, and how the firmware reads it

Recovered by running the firmware under the watch emulator and watching the bus; every
address below is from the image itself.

**Images are files whose names are addresses.** `snprintf(buf, 20, "0x%x", addr)` at
`0x0006F5D4` turns a resource address into a path, `lv_img_set_src` copies it to the LVGL
heap, and `lv_fs_open` (`0x000A4FA4`) maps the leading `0` to drive letter `F`. The `F`
driver at `0x1005377C` parses the rest of the path back into an address and reads through
`ew_dev_spinand.c` at `0x0003E2F8`.

**The headers are LVGL 5.3's packed `lv_img_header_t`** — `cf:5, always_zero:3,
reserved:2, w:11, h:11`. The object at `0x0C780000` is the face background: 360x360
`TRUE_COLOR`, followed by exactly 259,200 bytes of payload, and in the shipped
`Picture_P03B_NORM2_0.4.bin` every one of those bytes is zero — the background really is
black. The glyphs are `TRUE_COLOR_ALPHA`, three bytes a pixel.

**Commands reach the part bit-expanded.** The driver keeps the MSPI in quad mode, so it
cannot clock a one-lane command; the packer at `0x00053788` fills the frame with `0xEE`
and ORs each command bit into bit 0 of a nibble, MSB first. `0xEE` holds DQ1..DQ3 high and
drives the bit on DQ0/SI, which is what a standard SPI slave samples. So `0x13` PAGE READ
goes out as `EE EF EE FF`, and `0F C0` (GET FEATURE, status) as `EE EE FF FF FF EE EE EE`.
Replies come back on DQ1/SO, one bit in bit 1 of each nibble; the un-packer at
`0x000534EC` rebuilds a byte from bit 5 and bit 1 of four received bytes. `0x6B` READ FROM
CACHE x4 is a genuine four-lane data phase, so only its command frame is expanded.

**The part is two 1 Gbit dies** and the resources are on the second. `0xC2` DIE SELECT
picks it, and row addresses after that are die-relative: page 36,608 of die 1 is
`0x0C780000`.

**Transfers go through the MSPI hardware command queue**, not the PIO FIFO. `am_hal_cmdq`
keeps a ring of `(register, value)` pairs in SRAM; `CQADDR` reads back the controller's
own fetch pointer (the sync helper at `0x000B85BC` copies it into `pRead`), an entry that
writes `CQADDR` is a jump back to the base of the ring, and `CQCURIDX`/`CQENDIDX` are
eight-bit indices.

### Memory / flash map

```
internal flash  0x00000000 – 0x0001FFFF   bootloader + config (128 KB reserved)
                0x0001E000                a type-1 update's record: the image header (§5, #67)
                0x00020000 – 0x000D6693   application (747,156 B)
                                          (Apollo3 Blue flash ends at 0x000FFFFF)
SRAM            0x10000000 – 0x1005FFFF   384 KB, stack top 0x1005FA50

external SPI NAND (inferred from the OTA addresses + the 0x10000000 bound):
                0x0C780000                resource / picture partition
                0x0FC00000                OTA staging (last 4 MB of a 256 MB device)
```

---

## 5. Correction to CLAUDE.md: `UPGRADE_MODE` is not a separate bootloader

`CLAUDE.md` currently says `UPGRADE_MODE (0x0E)` makes the watch "reboot into bootloader".
The evidence says otherwise:

- `"Characteristic 1531"` and `"Characteristic 1532"` (the DFU chars) live in **this
  application image**, at 0xA8FEE/0xA9004, immediately after `"Characteristic 8001"`…`"8004"`
  at 0xA86B8.
- The `Upgrading… / Ready For Updates / Upgrade Success / Upgrade Failed / Watch Face
  Update Success` LVGL screens are drawn by this image.
- `..\..\Modules\Ble\ew_mod_ota_protocol.c`, `system_ota_task.c` and `system_ota_protocol_task.c`
  are all in this image, and `OtaProtocol` is one of its FreeRTOS tasks.

So `0x0E` is a **mode switch inside the running application**: the app firmware receives the
image over BLE, stages it into external NAND at the address from the file header, and a small
bootloader in the first 128 KB applies it on the next boot.

**Consequence, and it is the important one: the component that accepts OTA is the component
you would be replacing.** A custom image that fails to boot, or boots but doesn't implement
`ew_mod_ota_protocol`, leaves no BLE recovery path. Recovery then requires SWD.

Whether the first-stage bootloader has its own independent recovery (re-checking the staged
NAND image, or a GPIO-forced entry) is **unknown** — the header's OverrideGPIO field is
0xFFFFFFFF, i.e. disabled, which is not encouraging.

### What the application hands the bootloader (#67)

Rehearsed by sending the unmodified image as update type 1 to the emulated watch
(`tools/tests/test_ota_mcu.py`, `normtest ota_mcu -- --full`, ~15 min). Every step is
accepted, and when REBOOT has been answered this is the state of the watch:

```
SPI NAND  0x0FC00000-0x0FCB66BF  file[4:], byte for byte: the 44-byte header, then the
                                 0xB6694-byte payload, in 365 pages. The last page is
                                 programmed whole from a buffer that is not cleared, so
                                 past the image it holds page 363's bytes at the same
                                 offsets -- not 0xFF
          0x0FCB6800-0x0FCFFFFF  erased: the rest of the eight 128 KB blocks the SET
                                 erased (0x0005A292); nothing outside them is touched
internal  0x0001E000-0x0001E02B  file[4:0x30] -- link 0x00020000, staging 0x0FC00000,
flash                            length 0xB6694, image CRC 0xD392D741, SP, reset vector --
                                 read back from staging, checked and programmed there by
                                 the OTA task on REBOOT (0x0005A538-0x0005A59C); the rest
                                 of that 8 KB page erased. No other internal-flash write.
CPU       SYSRESETREQ            the application resets; on the watch the bootloader runs
```

The checks the task makes on that header before writing it: the staging address below
`0x10000000`, the length at most 1 MB, the word at +0x28 equal to 1, and the reset vector
at or above the link address and below link + 4 x length (a loose bound, as written).
Fail one and the record is not written; a status routine is called with 5 instead
(`0x0005A588`). A patched image keeps every one of these fields, so it passes them.

**This is where the rehearsal stops.** The bootloader in the low 128 KB is not in the OTA
image, and #66 found its low ~32-48 KB read-protected over 0xEE, so the emulator has nothing
to run after the reset -- the copy from NAND into internal flash at `0x00020000`, whatever it
checks, and what it does with the record at `0x0001E000` (clear it? keep it?) are the one
part of a main-firmware update that cannot be rehearsed. (The stale bytes past the image in
the last staged page are harmless if it copies the header's length, which is also exactly what
the image CRC covers; whether it copies whole pages instead is not known.) That page *is* above the protected
region, so on the physical watch 0xEE can read it before and after an update, read-only.

---

## 6. Integrity checks — what is actually enforced

Searched the image for SHA-1, SHA-256, MD5, AES S-box and CRC-32 table constants: **none
present** (the only hit is a reflected CRC-32 table at 0xABE54). There is no signature
verification, no encryption, no code obfuscation anywhere in the application image.

Two integrity layers exist, both CRC:

1. **Transport CRC**, computed by the phone — CRC-16-CCITT (poly 0x1021, init 0xFFFF) over
   the content, checked by `ew_mod_ota_protocol` on the watch. Trivially recomputable.
2. **Image CRC** at +0x10 (`0xD392D741` for the bundled build), presumably checked by the
   first-stage bootloader before it commits the staged image. **Algorithm not identified.**

This cannot be waved away — see next section.

---

## 7. The image CRC at +0x10 — SOLVED

Recovered by parameter search over polynomial × bit-order × init × xorout × payload range,
and verified against the factory image:

```
CRC-32, polynomial 0x1EDC6F41, MSB-first (NOT reflected),
init 0x00000000, no final xor,
computed over file[48:] — exactly the 0xB6694 bytes the length field at +0x0C declares.

  declared 0xD392D741  ==  computed 0xD392D741   ✓
```

The polynomial is Castagnoli's, but used non-reflected with a zero seed rather than in the
usual CRC-32C configuration — which is why the standard variants all missed. Nothing about
it is cryptographic: it is a plain error-detecting CRC with no secret, so **a patched image
can be re-sealed and will pass whatever the bootloader checks.**

`normfw` (`tools/normplus/firmware/image_tool.py`) implements it:

```bash
normfw verify NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin
normfw seal patched.bin --in-place
```

`verify` parses the header and checks the length field, the image CRC, and the plausibility
of the SP and reset vector; it exits non-zero if the image is not self-consistent, and
refuses Telink images outright. `seal` recomputes the length at +0x0C and the CRC at +0x10
from the actual payload and leaves every other field alone. Round-trip tested: patch a byte
deep in the payload → `verify` flags the mismatch → `seal` → `verify` passes.

The transport CRC-16 the phone sends in the OTA SET header needs no baking in —
`ApolloOta.crc` (in `:protocol`) computes it at flash time — but `verify` prints it so it can be
cross-checked against the app's logs.

**Still unknown:** whether the first-stage bootloader actually *checks* this field, and what
else it checks. Solving the CRC means a patched image is no longer rejected *for that
reason*; it does not prove the bootloader has no other gate.

---

## 8. The OTA server

Reverse-engineered from `NORM/smali_classes2/cn/appscomm/iting/utils/NetworkManager.smali`:

```
POST https://api.normdenmarkupdate.com/api/firmware_version
Content-Type: application/json
{"device_type": "s21"}          <- hardcoded; NOT the Norm 2 (that is "P03B_LEMOVT")

-> {"version": "...", "file_name": "<https URL>"}
```

The app keeps `version[0:3]` and GETs `file_name` directly.

**`normfw query-ota` (`tools/normplus/firmware/query_ota.py`)** implements this (stdlib only, no deps):

```bash
normfw query-ota                          # current firmware for s21
normfw query-ota --enumerate              # probe every known model code
normfw query-ota --json                   # machine-readable
normfw query-ota --list-blobs             # try listing the Azure container
normfw query-ota --download build/firmware
```

`--download` also prints the size, SHA-256 and parsed Ambiq header of whatever it fetched.

### What it returns (queried 2026-08-18)

```
s21      version='1.3_b01_24032800'
         file_name='https://normdenmarkupdate.blob.core.windows.net/storage/s22_v1.3_b01_24032800.bin'
```

Three things worth noting:

- **The endpoint ignores `device_type`.** `s21, l42p, l42, t51, w007ga, lemovt, leader` all
  return the identical record, so there is exactly one firmware being served — there is no
  per-model enumeration to be had from this API.
- **It is newer than what we have.** The bundled asset is build `F0.2B01` with internal
  version `A0.2R0.1T1.1H0.5B0.1`; the server offers a **2024-03-28** build, `1.3_b01_24032800`.
- **The filename says `s22`, not the `s21` we asked for, and the size does not fit a
  Norm 2 application image.** See the caveat below — this matters.

### Container listing is not available

`--list-blobs` was run. The container refuses anonymous enumeration:

```
GET .../storage?restype=container&comp=list  ->  404
<Error><Code>ResourceNotFound</Code>…</Error>
```

A `HEAD` on the known blob returns **200**, so the container exists and anonymous *blob read*
works — the container's public access level is "Blob", not "Container", and Azure masks the
denied `List Blobs` operation as a 404. **There is no way to enumerate the publish history**;
the API's single current record is all we get.

### This endpoint is the *Telink* channel, and the image is Norm 1 — NOT Norm 2

Downloaded and identified. `s22_v1.3_b01_24032800.bin`, 211,740 B, SHA-256
`cd4dda465b35a134fdcf4a76dbefdea3fea32c38d302087bc0bc8ae532a642cf`, blob `Last-Modified`
2024-04-01. It is a **Telink** firmware image:

| Evidence | Value |
|---|---|
| Magic at +0x08 | `4B 4E 4C 54` = `KNLT` — Telink's `TLNK` little-endian |
| Length field at +0x18 | `0x00033B1C` = 211,740 = **exactly** the file size (Telink header convention) |
| String at 0x76EA | `Telink Remote` |
| String at 0x421A | **`Norm 1#23574`** (the Apollo image carries `Norm2#00000`) |
| String at 0x33018 | `_Vgm07xx_PowerOff` |
| Absent | any LVGL, FreeRTOS, AmbiqSuite or `ew_*` string; instruction encoding is TC32, not Thumb-2 |

So it is **Norm 1 firmware for a Telink TC32 SoC** — a different product on a different CPU
architecture entirely.

That is not an endpoint misconfiguration. Tracing the caller confirms the design:
`SettingsFragment.onFileDownloaded` assigns the result to **`OTAPathVersion.tePath` /
`teVersion`** (`NORM/smali_classes2/cn/appscomm/ota/mode/OTAPathVersion.smali`), and `te` = Telink.
`OTAPathVersion` carries *separate* `apolloPath` / `apolloVersion` fields that this route never
touches. **`api.normdenmarkupdate.com/api/firmware_version` is the Telink OTA channel.** The
`device_type` parameter is decorative — the server returns the same Telink record whatever you
send it.

**Consequences:**

- **The Norm 2's Apollo3 firmware is not served here, and we cannot get a second Apollo image
  from this route.** The §7 CRC stays unsolved by this path.
- **The app never asks this endpoint about a Norm 2 in the first place.** The Norm 2 is
  `"P03B_LEMOVT"` (`WatchDeviceFactory$CurrentDeviceType.isP03B`), not `"s21"`; S21 and S22 are
  other models. `SettingsFragment`'s update item calls `handleApolloLocalUpdate()` when
  `isP03B()` -- the two files bundled in the APK, `Apollo3_P03B_NORM2_F0.2B01.bin` and
  `Picture_P03B_NORM2_0.4.bin`, nothing from the network -- and `queryFirmware()` only for the
  other models.
- **The Appscomm-style channel is dead too** (traced for #65, 2026-10). Retrofit
  `POST device/queryFirmwareVersion` (`getFirmwareVersionNew`) and `device/queryProductVersion`
  in `cn/appscomm/server/UrlService.smali`, base URL `ServerVal.host` = `https://normdenmark.com/`
  (`ITINGApplication`). No authentication or signing: `ServerManager.setupBaseRequest` merges
  `seq` (ms timestamp), `versionNo` (1.1.18), `language` (`"201"` for English,
  `ServerUtil.returnLanguage`), `clientType` `"android"`, `customerCode` `"LeMovt"` and `appId`
  `"91"` into the JSON body, and the version body is `{productCode: "P03B_LEMOVT", versionInfo:
  {A, H, K, N, R, T, TE}}`. Both endpoints answer **404** with the storefront's HTML error page:
  `normdenmark.com` is now a shop, not that API. So no route in this app version delivers
  anything for the Norm 2 beyond what the APK carries -- and the factory resources (#64) could
  not come this way regardless: a type-4 update erases and rewrites only 512 KB at
  `0x0C780000`, against tens of MB from `0x026DA430`.
- **Never push this file at a Norm 2.** For what it's worth, `getUpdateType()` matches on the
  *filename*, and `s22_v1.3_b01_24032800.bin` contains neither "Telink" nor "Apollo", so it
  falls through to the default `0x8` (Picture/Language/WatchFace) rather than type 1. Its first
  four bytes are a TC32 reset jump (`0x00008026`), which the Apollo path would send as a
  destination address. Every part of that is wrong.

The downloaded file is kept at `build/firmware/s22_v1.3_b01_24032800.bin` — of no use for
Norm 2 work, but a complete Telink reference image if the Norm 1 ever becomes interesting.

---

## 9. What we can realistically do

### A. Binary-patch the stock firmware — genuinely feasible

No crypto in the image, integrity is CRC only, and the `__FILE__` assert strings give
near-symbol-level module attribution for free. Load at 0x20000 in Ghidra with an Apollo3 SVD
(SP and reset vector are in the header) and most of it maps quickly.

Concrete wins that are blocked today *by firmware*, not by our protocol work:

- **A watch-side notification delete/replace command.** The whole "there is NO watch-side
  delete" section of `CLAUDE.md` is a firmware limitation. `MessageNewBT` carries no id
  because the firmware's handler has no field for one — adding one is a firmware change.
- **A NAND read-out command** -- built and rehearsed, one instruction changed; see
  section 11 (#68).
- Music control screen (currently we just suppress media notifications).
- Title/content limits beyond 0x5A / 0x80.
- Custom notification icon types beyond the hardcoded `PackageTypeData` table.
- New screens / watch faces.

Blockers: resolve §7 first; and a bad flash is unrecoverable without SWD.

### B. Write our own firmware (AmbiqSuite or Zephyr)

The toolchain is real and free. Zephyr has upstream Ambiq-contributed support for
`apollo3_evb` (Apollo3 Blue, 1 MB flash), and LVGL 9 would slot in where LVGL 5 is now.

What we would owe ourselves is full board bring-up: pin map and init for the RM67162, IPUS
PSRAM, SPI NAND, PAH8011, CW6303, WCN1F3549, the hand steppers, P9027LP. None of it is
public; it would have to come out of the `am_hal_gpio` config structs in this binary or off
the PCB. The PAH8011 heart-rate algorithm is a licensed PixArt blob — HR is lost unless it's
lifted out of the stock image.

Multi-month project. **Hard requirement: working SWD.**

### C. Port an existing open-source watch OS — this option does not exist

- **InfiniTime** and **Wasp-OS** are nRF52832 (PineTime). No Apollo3 port, and the
  display / PSRAM / NAND / physical-hands stack shares nothing.
- **BipOS** (Amazfit Bip, also Ambiq Apollo family) is a *patch set on Huami's stock
  firmware*, not portable source.

So C collapses into B: Zephyr + LVGL, and we write the watch application ourselves.

### D. Keep the effort phone-side

For most of the remaining gap list in `CLAUDE.md` (music control, reject-with-SMS, GPS,
weather), the ROI is much better here and the risk is zero.

---

## 9a. Readiness to patch and flash — what we have and what is missing

| Requirement | Status |
|---|---|
| A base image to patch | **Have.** `NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin`, unencrypted |
| The image is the build actually on the watch | **Likely, unconfirmed.** Watch reports `A0.2…B01`; the asset is `F0.2B01` with internal `A0.2R0.1T1.1H0.5B0.1` — the Apollo component matches. Confirm with `DEVICE_VERSION`, which currently times out (open bug) |
| Re-seal a patched image so it validates | **Have.** §7 + `normfw` (`tools/normplus/firmware/image_tool.py`) |
| Transport CRC-16 | **Have.** `ApolloOta.crc`, byte-verified against the smali, and accepted by the firmware (`04 01`) |
| A wire implementation of the OTA | **Have, verified against the emulated watch.** `ApolloOtaSession` (`protocol/.../ota/ApolloOta.kt`, #56) replaced the never-run `ApolloOtaProtocol.kt`; a full resource update from `:app` in the AVD went through. A main-MCU (type 1) update of the unmodified image is rehearsed against the emulated watch up to the reset (#67, §5); the bootloader's copy cannot be. Not yet run on the physical watch (#13, #70) |
| Understanding of the code well enough to patch it meaningfully | **Not yet.** 130 source filenames recovered, but no disassembly has been done. Nobody has opened it in Ghidra |
| A recovery path if a flash goes wrong | **None known.** Per §5 the OTA responder lives in the image being replaced; SWD status unknown |
| A spare device | **No** |

**Verdict: the format work is done, the process work is not.** Three gaps stand between
here and safely flashing a patched image, in priority order:

1. **The OTA path has never been exercised.** Its first-ever run must not be with a modified
   main-MCU image.
2. **No recovery.** Until SWD is confirmed working, any main-MCU flash is one-shot.
3. **No analysis.** We can re-seal an image but do not yet know where to patch.

### The safe rehearsal: the resource image (update type 4, not 8)

`UpdateType.PICTURE (0x08)` targets the **resource partition in external NAND at
0x0C780000**, not internal flash — it cannot overwrite executable code, and
`ApolloOtaSession` sends it (as type 4 -- see the note at the top). Re-flashing the *unmodified*
`NORM/assets/Picture_P03B_NORM2_0.4.bin` exercises all five steps, the chunking, the ACK
handling and the CRC end-to-end for zero net change.

Not zero risk: a corrupt or version-mismatched resource partition can break the UI, and it
is unconfirmed that the watch's current resource version is 0.4 (read it first — the version
string's `R` field). But the firmware and the OTA responder both live in internal flash and
are untouched, so BLE should survive and the blob can be re-flashed. This is by a wide
margin the cheapest way to find out whether our OTA implementation actually works.

---

## 10. The fact that decides everything, and the order to do things in

**Does SWD work on this unit?** Apollo3 exposes SWDCK/SWDIO, but vendors routinely lock it
via the customer INFO0 space and stock firmware often disables the pins at boot. If SWD is
alive, we have recovery and both A and B become reasonable. If it's locked, patching is a
one-shot with permanent-brick stakes, and should not be attempted on the only watch.

Cheap and safe, in order:

1. ~~Fetch the 2024-03-28 firmware~~ — done. It is **Norm 1 / Telink**, useless for this
   platform and dangerous to flash. See §8.
2. ~~Try `--list-blobs`~~ — done, denied. The API's single record is the only enumeration.
3. ~~Solve the image CRC~~ — done, §7. `image_tool.py seal` re-seals a patched image.
4. ~~**Rehearse the OTA path with update type 8** (§9a)~~ -- done against the emulated watch,
   as type 4 (#50, #56); on the physical watch it is #13 — re-flash the unmodified resource
   blob. This is the next thing to actually do: it converts "we have an OTA implementation"
   from an assumption into a fact, without risking the firmware.
5. **Open the watch and probe for SWD test points.** Still the gate on any main-MCU flash,
   because there is no other recovery path.
6. **Load the bundled bin in Ghidra at 0x20000** and confirm the module map from the assert
   strings — needed before we know *where* to patch anything.
7. **Get a sacrificial second unit** — the Allview Allwatch H rebrand may be cheaper than a
   second Norm 2.
8. *(Optional)* **Trace `device/queryProductVersion` / `getFirmwareVersionNew`** in
   `cn/appscomm/server/UrlService.smali` — the real Apollo firmware channel. Much less
   urgent now that §7 is solved without needing a diff target; still the only route to a
   second official Apollo build.

---

## 11. The NAND read-out patch (#68)

The watch's factory resources are tens of megabytes of images in the SPI NAND, the emulator
shows them as "No data" (#64, #65), and #66 established there is no free way to read them:
no vendor server has the files, and command 0xEE -- the firmware's one arbitrary-memory read
(handler 0x0003779C) -- reaches only the CPU address space, because the NAND is not
memory-mapped. So the firmware has to be asked to use its own NAND driver. That is
`normfw patch-nand` (`tools/normplus/firmware/nand_patch.py`), rehearsed in
`tools/tests/test_nand_patch.py`; it has **not** been flashed to the watch (that is #70).

**It changes one instruction.** The 0xEE handler ends with `bl 0x00020EC4` (`memcpy`) at
`0x000377CE`; that call becomes a `bl` to 40 bytes appended after the image's last byte,
which for a tagged address calls the firmware's own NAND read and otherwise tail-calls the
same `memcpy`. Every other byte of the image is unchanged, so the length at +0x0C and the
CRC at +0x10 are the only other fields that move (§7, `normfw seal` does it).

**Asking for a NAND read.** The address field's top nibble selects the address space:
`0xFnnnnnnn` is NAND byte offset `0xnnnnnnn` (the whole 256 MB, both dies), anything else is
a CPU address and behaves exactly as the shipped firmware does. 0xF0000000 is unmapped on
the Apollo3, so nothing that means something today changes meaning. The tag costs no payload
byte, which matters: the handler never sees the payload length (the dispatcher passes it in
r2 at 0x00039F38 and the handler overwrites r2 before the hook), so a sixth payload byte
could not be told from a stale one.

```bash
normfw patch-nand NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin -o patched.bin
normcmd EE 70 --payload 'fc 78 00 00 80' --image patched.bin --flash-state bound.zip
```

**What it calls** is the read at `+0x0C` of the storage device object the firmware keeps at
`[0x10006BCC]` (0x1005D644 in a running watch, so the slot is `0x1005D650` -- the RAM pointer
#66 saw) -- `read(instance, byte_address, destination, length)` at `0x0003E2F8`, in
`..\..\Device\Nand\ew_dev_spinand.c` (its asserts `instance < ( 1 )` at line 1474, the
non-NULL destination at 1475). It is the same call the OTA task makes at `0x0005A54A` to read
a staged image header back. The driver takes its own lock and selects the die from
`address >> 20`, so the patch needs no locking of its own and does nothing if the device is
not up.

**What a dump client has to know** (all measured on the emulated watch, #68):

- **128 bytes per request.** The shipped handler's buffer is 0x80 bytes on its stack
  (`sub sp, #0x8C`, `memset` of 0x80 at `0x000377B4`), and the 0x6F reply builder at
  `0x000393B8` refuses any frame of 255 bytes or more (`cmp #0xFF` on both paths). Raising it
  to ~240 would mean editing the handler's own stack frame as well (`sub`/`add sp`, the
  `memset` length, the reply capacity -- four more two-byte constants) for roughly half the
  dump time. **Decided against (#69, owner's call): 128 stays.** A longer dump is worth more
  than a patch that touches the handler's frame, because the image this becomes is the one
  flashed to the only watch we have. Do not revisit it to save time.
- **A read may span pages.** The driver clamps each transfer to the end of the page it is in
  (`0x0003E39C`-`0x0003E3BC`) and comes back for the rest, so nothing needs aligning.
- **A read can lose the NAND to the UI, and must be retried.** The watch reads the NAND
  continuously to draw itself and the driver's lock is taken with a short timeout
  (`0x00031B40` from `0x0003E348`). About one read in five is lost while the face is up. It
  is never partial: the handler zero-fills its buffer first, so a lost read arrives as 128
  zero bytes and nothing else does. One retry was enough every time (24 of 30 first try, 6
  on the second).
- **An untagged NAND address takes the watch out.** That is the shipped behaviour, not a
  regression -- 0xEE's `memcpy` has always faulted on an address the CPU does not map (#66,
  on the emulated *and* the physical watch) -- but it is worse than the "no reply" it looks
  like: in the emulator the CPU stops on the unmapped read inside `memcpy` and every later
  GATT write times out. A client must send tagged addresses only.

**What the rehearsal shows.** The patched image verifies and re-seals; the patched watch
boots past its boot animation, pairs, binds and draws; a tagged address returns the emulated
NAND byte for byte (including across a page boundary) and 0xFF where the NAND holds nothing
-- which the stock firmware could not read at all; a CPU address answers exactly the bytes
#66 recorded from both watches; and both OTA rehearsals pass against the patched image, so a
resource (type 4) update and type-1 staging still work (`normtest nand_patch -- --full`,
which runs `test_ota.py` and `test_ota_mcu.py` with `--image`).

The patched image is vendor-derived and is never committed; it is built on demand.

---

## Appendix: reproducing the analysis

```bash
# header + CRC
python - <<'EOF'
import struct
d = open('NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin','rb').read()
for i in range(0, 0x40, 4):
    print(f"+0x{i:02x} = 0x{struct.unpack_from('<I', d, i)[0]:08X}")
def crc16(data, poly=0x1021, init=0xFFFF):
    crc = init
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ poly) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc
print("app-side CRC16 over content:", hex(crc16(d[4:])))
EOF

# the source tree, via assert __FILE__ strings -- recovers 130 distinct .c files.
# (b'\x5c' is a backslash; written that way so it survives copy/paste through a shell.)
python - <<'EOF'
import re
d = open('NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin','rb').read()
for s in sorted({m.group().decode() for m in re.finditer(rb'[\x20-\x7e]{6,}', d)
                 if b'.c' in m.group() and b'\x5c' in m.group()}):
    print(s)
EOF
```

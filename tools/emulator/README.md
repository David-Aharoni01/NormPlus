# Android Emulator (Galaxy S24) for Norm+

A local Android emulator to build and run the `:app` companion app **without Android Studio**
and without using a physical phone. The AVD mimics a **Samsung Galaxy S24** (6.2", 1080×2340,
Google-APIs Android 15 / API 35).

## What's already set up on this machine

| Component | Location / value |
|-----------|------------------|
| JDK | 17 — `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot` |
| Android SDK | `C:\Users\david\Android\Sdk` (also in `local.properties`) |
| Emulator pkg | `emulator` (installed via `sdkmanager`) |
| System image | `system-images;android-35;google_apis;x86_64` |
| Accelerator | **AEHD** v2.2 (Android Emulator Hypervisor Driver) — installed & usable |
| AVD | `Galaxy_S24_API35` (`~/.android/avd/Galaxy_S24_API35.avd`) |

## Run it

```powershell
# UI-only boot (no Bluetooth)
tools\emulator\launch-galaxy.ps1

# Cold boot after editing config.ini
tools\emulator\launch-galaxy.ps1 -ColdBoot

# Software rendering fallback if the GPU path glitches
tools\emulator\launch-galaxy.ps1 -Gpu swiftshader_indirect
```

Then build & install the app onto the running emulator:

```powershell
.\gradlew installDebug
```

`adb devices` should list `emulator-5554`.

## Reproducing the setup from scratch

```powershell
$env:ANDROID_HOME="C:\Users\david\Android\Sdk"; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$sdk="$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat"
& $sdk --licenses                         # accept all
& $sdk "emulator" "system-images;android-35;google_apis;x86_64" "platform-tools" `
       "extras;google;Android_Emulator_Hypervisor_Driver"
# Install AEHD (needs admin):
Start-Process "$env:ANDROID_HOME\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat" -Verb RunAs -Wait
& $env:ANDROID_HOME\emulator\emulator.exe -accel-check   # expect "AEHD ... installed and usable"
# Create AVD:
$avd="$env:ANDROID_HOME\cmdline-tools\latest\bin\avdmanager.bat"
"no" | & $avd create avd -n Galaxy_S24_API35 -k "system-images;android-35;google_apis;x86_64" -d pixel_8
# Then edit ~/.android/avd/Galaxy_S24_API35.avd/config.ini:
#   hw.lcd.height=2340, hw.lcd.width=1080, hw.lcd.density=420
#   hw.keyboard=yes, hw.ramSize=4096, vm.heapSize=256M, hw.gpu.enabled=yes
#   hw.device.manufacturer=Samsung
```

## A note on "Galaxy OS" (One UI)

The AVD runs Google's **AOSP / Google-APIs Android 15**, not Samsung **One UI**. A real One UI
OS cannot run in a local emulator: Samsung does not publish One UI system images for AVDs, and
the firmware is ARM, vendor-specific, and signed — it won't boot in the standard emulator
(Genymotion is also AOSP under the hood). The only way to drive genuine One UI is Samsung's
cloud **Remote Test Lab** (remote real devices), which is not a local emulator.

A Samsung bezel **skin** (cosmetic frame only — no One UI features) can be added but is not used
here: it enlarges the window and adds nothing functional. If you ever want one, download a skin
zip from <https://developer.samsung.com/galaxy-emulator-skin/galaxy-s.html> (Samsung-account
login required), unzip into `Sdk\skins\<name>\`, and set `skin.name`/`skin.path` in `config.ini`.

## ⚠️ Bluetooth / the watch

By default the emulator has no Bluetooth radio. Real-watch BLE is wired up via a **USB BT
dongle + Bumble HCI bridge** — **verified working**: the app, running in the emulator, scans
over the dongle and discovers the physical Norm 2 (`Norm2#01655 / 4C:59:80:12:44:F1`).

### Phase B — real-watch BLE inside the emulator (WORKING)

The emulator (≥ 33.1.4.0) exposes a virtual HCI controller via `-packet-streamer-endpoint`;
the bridge connects that to a **dedicated USB Bluetooth dongle**. The dongle in use is a
**CSR8510 (`0A12:0001`, addr `00:1A:7D:DA:71:13`)**. On **Windows the dongle's driver must be
swapped to WinUSB via [Zadig](https://zadig.akeo.ie/)** — the built-in MediaTek adapter can't
be a raw HCI controller (and it's bonded to the watch for normlink-cli; don't disturb it).

One-time setup:
```powershell
py -3.11 -m pip install bumble
# Zadig (elevated): Options > List All Devices > select "CSR8510 A10" (USB ID 0A12 0001)
#                   > target driver = WinUSB > Replace Driver.
#   (To undo later: Device Manager > the device > Uninstall device + delete driver, then replug.)
```

Run it:
```powershell
tools\emulator\launch-galaxy.ps1 -Bridge        # starts norm_emu_bridge.py + boots the AVD
# In the app: the Norm 2 appears under "Found Devices" — tap to connect.
```

**Why a custom bridge (`norm_emu_bridge.py`), not stock `bumble-hci-bridge`:** two issues had
to be worked around (both in the script's header):
1. **netsim `packet` proto** — emulator 36.x sends HCI in the newer `PacketRequest.packet`
   field; stock Bumble 0.0.229 drops it ("Unexpected request type: packet").
2. **vendor-caps timeout** — Android's BT init sends `LE_GET_VENDOR_CAPABILITIES` (0xFD53);
   the CSR8510 ignores unknown vendor opcodes, so Android times out and *fatally* aborts BT
   startup. The bridge answers 0xFD53/0xFD5B locally with "not supported" so init completes.

Notes / gotchas:
- The CSR8510 is BT 4.0 — fine as a BLE central for the watch, but old. A newer BT5 dongle on a
  well-supported chipset may not need the vendor-caps short-circuit (the proto fix is still
  required regardless of dongle).
- The bridge logs an occasional `AssertionError` from a secondary netsim stream; it does not
  affect the primary BT connection.
- The dongle and the built-in adapter are independent — the watch can be bonded to both. Avoid
  running normlink-cli / the official app at the same time as the emulator (they contend for the
  watch's single active connection).
- If the guest BT won't turn on, cold-boot once: `tools\emulator\launch-galaxy.ps1 -Bridge -ColdBoot`.

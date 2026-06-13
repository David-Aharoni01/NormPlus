<#
.SYNOPSIS
  Boot the Pixel 8 (AOSP) Android emulator for Norm+ (no Android Studio required).

.DESCRIPTION
  Sets the SDK/JDK environment for this session and launches the AVD created by the
  emulator setup (see README.md in this folder). Hardware acceleration uses AEHD.

  By default it also starts a Bumble HCI bridge so the app inside the emulator can talk to a
  REAL Bluetooth controller (a dedicated USB BT dongle with a WinUSB driver via Zadig). An
  already-running bridge on the port is reused. Pass -NoBridge for a UI-only boot (no Bluetooth).

.PARAMETER Avd
  AVD name to boot. Default: Pixel_8_API35.

.PARAMETER ColdBoot
  Wipe the saved snapshot and boot cold (use after changing config.ini).

.PARAMETER Gpu
  Emulator GPU mode: auto (default), host, swiftshader_indirect (software fallback).

.PARAMETER NoBridge
  Skip the Bumble HCI bridge and boot UI-only (no Bluetooth). The bridge is on by default.

.PARAMETER BridgePort
  TCP port for the Bumble netsim bridge. Default: 8877.

.PARAMETER UsbController
  Bumble controller transport for the physical dongle. Default: usb:0.

.EXAMPLE
  ./launch-emulator.ps1                 # boot with real-watch BLE (Bumble bridge + USB dongle)
  ./launch-emulator.ps1 -NoBridge       # UI-only boot (no Bluetooth)
  ./launch-emulator.ps1 -ColdBoot       # cold boot after editing config.ini
#>
[CmdletBinding()]
param(
    [string]$Avd = "Pixel_8_API35",
    [switch]$ColdBoot,
    [ValidateSet("auto", "host", "swiftshader_indirect")]
    [string]$Gpu = "auto",
    [switch]$NoBridge,
    [int]$BridgePort = 8877,
    [string]$UsbController = "usb:0A12:0001"   # CSR dongle (must be WinUSB-bound via Zadig)
)

$ErrorActionPreference = "Stop"

# --- Environment (session-scoped) ---
$env:ANDROID_HOME     = "C:\Users\david\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
}
$env:PATH = "$env:ANDROID_HOME\emulator;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:JAVA_HOME\bin;$env:PATH"

$emulator = "$env:ANDROID_HOME\emulator\emulator.exe"

# --- Sanity checks ---
if (-not (Test-Path $emulator)) { throw "emulator.exe not found at $emulator" }
$accel = & $emulator -accel-check 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Hardware acceleration not available:`n$accel"
    Write-Warning "Run extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat as admin."
}

$emuArgs = @("-avd", $Avd, "-gpu", $Gpu)
if ($ColdBoot) { $emuArgs += @("-no-snapshot", "-wipe-data") }

if ($NoBridge) {
    Write-Host "Booting WITHOUT Bluetooth bridge (-NoBridge): emulator is UI-only." -ForegroundColor Yellow
} else {
    # --- real-watch BLE via Bumble HCI bridge (default) ---
    $listening = Get-NetTCPConnection -LocalPort $BridgePort -State Listen -ErrorAction SilentlyContinue
    if ($listening) {
        Write-Host "Reusing Bumble bridge already listening on port $BridgePort." -ForegroundColor Green
    } else {
        Write-Host "Starting Norm+ Bumble bridge on port $BridgePort (controller=$UsbController)..." -ForegroundColor Cyan
        Write-Host "Prereqs: 'py -3.11 -m pip install bumble'; CSR dongle bound to WinUSB via Zadig." -ForegroundColor Yellow
        # We use the repo's norm_emu_bridge.py (NOT stock bumble-hci-bridge): it adds the
        # emulator-36.x 'packet' proto fix and the LE_GET_VENDOR_CAPABILITIES short-circuit
        # the CSR8510 needs (see norm_emu_bridge.py header).
        $bridgePy = Join-Path $PSScriptRoot "norm_emu_bridge.py"
        Start-Process -FilePath "py" `
            -ArgumentList "-3.11", $bridgePy, "--port", $BridgePort, "--usb", $UsbController -NoNewWindow
        Start-Sleep -Seconds 3
        if (-not (Get-NetTCPConnection -LocalPort $BridgePort -State Listen -ErrorAction SilentlyContinue)) {
            Write-Warning "Bridge did not come up on port $BridgePort. Is the dongle plugged in and WinUSB-bound via Zadig? The emulator will boot but Bluetooth won't work — use -NoBridge for a clean UI-only boot."
        }
    }
    $emuArgs += @("-packet-streamer-endpoint", "localhost:$BridgePort", "-writable-system", "-no-snapshot-load")
}

Write-Host "Launching: emulator $($emuArgs -join ' ')" -ForegroundColor Green
& $emulator @emuArgs

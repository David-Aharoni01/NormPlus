<#
.SYNOPSIS
  One-shot setup of the Norm+ Android emulator + BLE bridge from scratch.

.DESCRIPTION
  Rebuilds everything that necessarily lives OUTSIDE the repo (it's too large or
  is system state): the Android SDK packages, the AEHD accelerator, the
  Pixel_8_API35 AVD (from the committed config snapshot), and the Bumble pip dep.

  What it does NOT (cannot) automate:
    - The dongle's WinUSB driver swap — run Zadig manually (see the printout / README).
    - AEHD install needs admin; it triggers a UAC prompt.

  Safe to re-run: existing SDK packages and the AVD are detected and skipped.

.PARAMETER Sdk
  Android SDK location. Default: C:\Users\david\Android\Sdk (matches local.properties).

.PARAMETER SkipAehd
  Skip the (elevated) AEHD accelerator install.

.EXAMPLE
  tools\emulator\setup.ps1
#>
[CmdletBinding()]
param(
    [string]$Sdk = "C:\Users\david\Android\Sdk",
    [switch]$SkipAehd
)

$ErrorActionPreference = "Stop"
$scriptDir = $PSScriptRoot
$avdName = "Pixel_8_API35"
$systemImage = "system-images;android-35;google_apis;x86_64"

$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
}

$sdkmanager  = Join-Path $Sdk "cmdline-tools\latest\bin\sdkmanager.bat"
$avdmanager  = Join-Path $Sdk "cmdline-tools\latest\bin\avdmanager.bat"
$emulator    = Join-Path $Sdk "emulator\emulator.exe"

if (-not (Test-Path $sdkmanager)) {
    throw "sdkmanager not found at $sdkmanager — install the SDK command-line tools first."
}

# --- 1. SDK packages ---------------------------------------------------------
Write-Host "[1/5] Installing SDK packages (emulator, system image, platform-tools, AEHD)..." -ForegroundColor Cyan
"y`n" * 10 | & $sdkmanager --licenses | Out-Null
& $sdkmanager "emulator" $systemImage "platform-tools" `
    "extras;google;Android_Emulator_Hypervisor_Driver"

# --- 2. AEHD accelerator (needs admin) --------------------------------------
if ($SkipAehd) {
    Write-Host "[2/5] Skipping AEHD install (-SkipAehd)." -ForegroundColor Yellow
} else {
    $accel = & $emulator -accel-check 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[2/5] Acceleration already available." -ForegroundColor Green
    } else {
        Write-Host "[2/5] Installing AEHD (approve the UAC prompt)..." -ForegroundColor Cyan
        $bat = Join-Path $Sdk "extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat"
        $p = Start-Process "cmd.exe" -ArgumentList "/c", "`"$bat`"" -Verb RunAs -Wait -PassThru
        Write-Host "AEHD installer exit code: $($p.ExitCode)"
        & $emulator -accel-check
    }
}

# --- 3. Create the AVD from the committed config snapshot --------------------
$avdHome = Join-Path $env:USERPROFILE ".android\avd"
$avdDir  = Join-Path $avdHome "$avdName.avd"
if (Test-Path $avdDir) {
    Write-Host "[3/5] AVD '$avdName' already exists — leaving as-is." -ForegroundColor Green
} else {
    Write-Host "[3/5] Creating AVD '$avdName' (Pixel 8, $systemImage)..." -ForegroundColor Cyan
    "no" | & $avdmanager create avd -n $avdName -k $systemImage -d pixel_8 --force
    # Apply our exact tuned hardware config (portable: relative paths/placeholders only)
    Copy-Item (Join-Path $scriptDir "Pixel_8_API35.config.ini") (Join-Path $avdDir "config.ini") -Force
    Write-Host "Applied committed config.ini snapshot." -ForegroundColor Green
}

# --- 4. Bumble (BLE bridge dependency) --------------------------------------
Write-Host "[4/5] Installing Bumble (Python 3.11)..." -ForegroundColor Cyan
py -3.11 -m pip install -r (Join-Path $scriptDir "requirements.txt")

# --- 5. Manual step + verification ------------------------------------------
Write-Host "[5/5] Done. Remaining MANUAL step for real-watch BLE:" -ForegroundColor Cyan
Write-Host @"
  - Plug in the USB Bluetooth dongle and run Zadig (https://zadig.akeo.ie/):
      Options > List All Devices > select 'CSR8510 A10' (USB ID 0A12 0001)
      > target driver = WinUSB > Replace Driver.
  Then:
      tools\emulator\launch-emulator.ps1            # UI only
      tools\emulator\launch-emulator.ps1 -Bridge    # real-watch BLE
"@ -ForegroundColor Yellow
& $emulator -accel-check 2>&1 | Select-Object -Last 2

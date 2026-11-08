#Requires -Version 5.1
<#
.SYNOPSIS
  Starts/stops the API 36 emulator used for end-to-end verification.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\emulator.ps1 -Action start
#>
param(
    [ValidateSet("start", "stop", "status", "screenshot")]
    [string]$Action = "status",
    [string]$OutFile = "screenshots/current.png"
)

$root = Split-Path -Parent $PSScriptRoot
$adb = "D:\Android\sdk\platform-tools\adb.exe"
$emulator = "D:\Android\sdk\emulator\emulator.exe"

function Get-EmulatorSerial {
    $lines = & $adb devices
    $serial = $lines | Where-Object { $_ -match "^emulator-\d+\s+device" } | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1
    return $serial
}

switch ($Action) {
    "start" {
        $serial = Get-EmulatorSerial
        if ($serial) { Write-Output "Emulator already running: $serial"; exit 0 }
        Write-Output "Launching emulator dsh_api36..."
        Start-Process -FilePath $emulator -ArgumentList @(
            "-avd", "dsh_api36",
            "-no-window", "-no-audio", "-no-boot-anim",
            "-gpu", "swiftshader_indirect",
            "-no-snapshot-save",
            "-netdelay", "none", "-netspeed", "full"
        ) -WindowStyle Hidden
        & $adb wait-for-device
        for ($i = 0; $i -lt 240; $i++) {
            $boot = (& $adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
            if ($boot -eq "1") {
                Write-Output "Boot completed after $($i * 2)s"
                & $adb shell input keyevent 82 | Out-Null
                & $adb shell settings put global window_animation_scale 0 | Out-Null
                & $adb shell settings put global transition_animation_scale 0 | Out-Null
                & $adb shell settings put global animator_duration_scale 0 | Out-Null
                exit 0
            }
            Start-Sleep -Seconds 2
        }
        Write-Error "Emulator boot timed out"
        exit 1
    }
    "stop" {
        $serial = Get-EmulatorSerial
        if ($serial) { & $adb -s $serial emu kill | Out-Null; Write-Output "Stopped $serial" }
        else { Write-Output "No emulator running" }
        exit 0
    }
    "status" {
        & $adb devices
        exit 0
    }
    "screenshot" {
        $serial = Get-EmulatorSerial
        if (-not $serial) { Write-Error "No emulator running"; exit 1 }
        $target = Join-Path $root $OutFile
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        & $adb -s $serial shell screencap -p /sdcard/musictag-shot.png | Out-Null
        & $adb -s $serial pull /sdcard/musictag-shot.png $target | Out-Null
        & $adb -s $serial shell rm -f /sdcard/musictag-shot.png | Out-Null
        Write-Output "Saved $target"
        exit 0
    }
}

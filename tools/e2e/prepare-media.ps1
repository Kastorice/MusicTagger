#Requires -Version 5.1
<#
.SYNOPSIS
  Builds and pushes the E2E audio fixtures used on the emulator.

.DESCRIPTION
  Creates four small tones with Chinese tags (mp3/flac/ogg/m4a) using ffmpeg and
  mutagen, then pushes them to /sdcard/Music. Everything happens in an ASCII
  temp directory to avoid non-ASCII command line arguments on Windows.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\e2e\prepare-media.ps1
#>
param(
    [string]$Serial = "emulator-5554"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$work = Join-Path $env:TEMP "musictag-e2e"
$adb = "D:\Android\sdk\platform-tools\adb.exe"
$ffmpeg = if ($env:FFMPEG_PATH) { $env:FFMPEG_PATH } else { "D:\ffmpeg-8.1-essentials_build\bin\ffmpeg.exe" }
$python = "C:\Users\Matsukawa\AppData\Local\Programs\Python\Python314\python.exe"

New-Item -ItemType Directory -Force -Path $work | Out-Null
Copy-Item (Join-Path $PSScriptRoot "seed_media.py") $work -Force
Copy-Item (Join-Path $PSScriptRoot "verify_tags.py") $work -Force

Push-Location $work
try {
    $tone = @("-f", "lavfi", "-i", "sine=frequency=440:duration=2.0:sample_rate=44100")
    $tone2 = @("-f", "lavfi", "-i", "sine=frequency=523:duration=2.0:sample_rate=48000")

    & $ffmpeg -y -hide_banner -loglevel error @tone @("-ac", "2", "-c:a", "libmp3lame", "-b:a", "128k", "-id3v2_version", "3", "-write_id3v1", "0", "-map_metadata", "-1", "sunny.mp3")
    & $ffmpeg -y -hide_banner -loglevel error @tone @("-ac", "2", "-c:a", "flac", "-map_metadata", "-1", "night.flac")
    & $ffmpeg -y -hide_banner -loglevel error @tone @("-ac", "2", "-c:a", "libvorbis", "-q:a", "4", "-map_metadata", "-1", "rice.ogg")
    & $ffmpeg -y -hide_banner -loglevel error @tone2 @("-ac", "2", "-c:a", "aac", "-b:a", "128k", "-map_metadata", "-1", "love.m4a")
    & $ffmpeg -y -hide_banner -loglevel error -f lavfi -i "color=c=0xFF7043:s=600x600:d=1" -frames:v 1 -q:v 3 "cover.jpg"

    Write-Host "Seeding Chinese metadata with mutagen..."
    & $python "seed_media.py"
    if ($LASTEXITCODE -ne 0) { throw "seed_media.py failed" }

    Write-Host "Pushing to ${Serial}:/sdcard/Music ..."
    & $adb -s $Serial shell "mkdir -p /sdcard/Music /sdcard/Pictures" | Out-Null
    foreach ($name in @("sunny.mp3", "night.flac", "rice.ogg", "love.m4a")) {
        & $adb -s $Serial push (Join-Path $work $name) "/sdcard/Music/$name" | Out-Null
    }
    & $adb -s $Serial push (Join-Path $work "cover.jpg") "/sdcard/Pictures/e2e_cover.jpg" | Out-Null

    foreach ($name in @("sunny.mp3", "night.flac", "rice.ogg", "love.m4a")) {
        & $adb -s $Serial shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/$name" | Out-Null
    }

    Write-Host "Device files:"
    & $adb -s $Serial shell "ls -l /sdcard/Music /sdcard/Pictures"
    Write-Host "E2E media ready in $work"
}
finally {
    Pop-Location
}

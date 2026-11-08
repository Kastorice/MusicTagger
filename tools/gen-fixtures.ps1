#Requires -Version 5.1
<#
.SYNOPSIS
  Generates deterministic audio fixtures for the tag engine unit tests.

.DESCRIPTION
  Uses ffmpeg to synthesize small (2 s) tones in every supported container,
  with a mix of tag states. Fixtures are written to
  core/src/test/resources/fixtures and only contain generated audio.

  NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less
  scripts with the ANSI code page, so non-ASCII literals break here.
  Non-ASCII tag values are exercised from Kotlin tests instead.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\gen-fixtures.ps1
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ffmpeg = if ($env:FFMPEG_PATH) { $env:FFMPEG_PATH } else { "D:\ffmpeg-8.1-essentials_build\bin\ffmpeg.exe" }
$out = Join-Path $root "core\src\test\resources\fixtures"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$jpeg = Join-Path $out "cover.jpg"
$png = Join-Path $out "cover.png"

$base = @("-y", "-hide_banner", "-loglevel", "error")

function Invoke-Ffmpeg([string[]]$Arguments) {
    & $ffmpeg @base @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed: $($Arguments -join ' ')" }
}

Write-Host "cover art..."
Invoke-Ffmpeg @("-f", "lavfi", "-i", "color=c=0x6B4EFF:s=600x600:d=1", "-frames:v", "1", "-q:v", "3", $jpeg)
Invoke-Ffmpeg @("-f", "lavfi", "-i", "color=c=0x00A896:s=600x600:d=1", "-frames:v", "1", "-q:v", "3", $png)

$tone44 = @("-f", "lavfi", "-i", "sine=frequency=440:duration=2.0:sample_rate=44100")
$tone48 = @("-f", "lavfi", "-i", "sine=frequency=660:duration=2.0:sample_rate=48000")

Write-Host "MP3..."
Invoke-Ffmpeg ($tone44 + @("-ac", "2", "-c:a", "libmp3lame", "-b:a", "128k", "-id3v2_version", "3", "-write_id3v1", "0", "-map_metadata", "-1", (Join-Path $out "bare.mp3")))
Invoke-Ffmpeg ($tone44 + @("-ac", "2", "-c:a", "libmp3lame", "-b:a", "128k", "-id3v2_version", "4", "-write_id3v1", "0",
    "-metadata", "title=Old Title", "-metadata", "artist=Old Artist", "-metadata", "album=Old Album",
    "-metadata", "date=2019", "-metadata", "genre=Pop", (Join-Path $out "tagged_v24.mp3")))
Invoke-Ffmpeg ($tone44 + @("-i", $jpeg, "-map", "0:a", "-map", "1:v", "-ac", "2", "-c:a", "libmp3lame", "-b:a", "128k", "-c:v", "copy",
    "-id3v2_version", "3", "-write_id3v1", "0", "-metadata", "title=Sunny Day", "-metadata", "artist=Jay",
    "-metadata", "album=Ye Huimei", "-metadata", "date=2003", "-metadata", "track=3", "-metadata", "genre=Pop",
    (Join-Path $out "tagged_v23_cover.mp3")))
Invoke-Ffmpeg ($tone48 + @("-ac", "1", "-c:a", "libmp3lame", "-b:a", "96k", "-id3v2_version", "3", "-write_id3v1", "1",
    "-metadata", "title=Legacy", "-metadata", "artist=Someone", "-metadata", "album=Old", (Join-Path $out "id3v1.mp3")))

Write-Host "FLAC..."
Invoke-Ffmpeg ($tone44 + @("-ac", "2", "-c:a", "flac", "-map_metadata", "-1", (Join-Path $out "bare.flac")))
Invoke-Ffmpeg ($tone44 + @("-i", $png, "-map", "0:a", "-map", "1:v", "-ac", "2", "-c:a", "flac", "-c:v", "copy",
    "-metadata", "title=Night Song", "-metadata", "artist=Jay", "-metadata", "album=November",
    "-metadata", "date=2005", "-metadata", "tracknumber=1", "-metadata", "genre=Pop",
    "-disposition:v", "attached_pic", (Join-Path $out "tagged.flac")))

Write-Host "OGG Vorbis..."
Invoke-Ffmpeg ($tone44 + @("-ac", "2", "-c:a", "libvorbis", "-q:a", "4", "-map_metadata", "-1", (Join-Path $out "bare.ogg")))
# ffmpeg cannot mux JPEG cover art into Ogg, so this fixture carries text tags
# only; the OGG unit tests exercise METADATA_BLOCK_PICTURE round-tripping.
Invoke-Ffmpeg ($tone44 + @("-ac", "2", "-c:a", "libvorbis", "-q:a", "4",
    "-metadata", "title=Rice Field", "-metadata", "artist=Jay", "-metadata", "album=Capricorn",
    "-metadata", "date=2008", (Join-Path $out "tagged.ogg")))

Write-Host "OGG Opus..."
Invoke-Ffmpeg ($tone44 + @("-ac", "2", "-c:a", "libopus", "-b:a", "96k", "-map_metadata", "-1", (Join-Path $out "bare.opus")))

Write-Host "M4A / MP4..."
Invoke-Ffmpeg ($tone44 + @("-ac", "2", "-c:a", "aac", "-b:a", "128k", "-map_metadata", "-1", (Join-Path $out "bare.m4a")))
Invoke-Ffmpeg ($tone44 + @("-i", $jpeg, "-map", "0:a", "-map", "1:v", "-ac", "2", "-c:a", "aac", "-b:a", "128k", "-c:v", "copy",
    "-metadata", "title=Qilixiang", "-metadata", "artist=Jay", "-metadata", "album=Qilixiang",
    "-metadata", "date=2004", "-metadata", "track=1", "-metadata", "genre=Pop",
    "-disposition:v", "attached_pic", (Join-Path $out "tagged.m4a")))
Invoke-Ffmpeg ($tone48 + @("-ac", "2", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", "-map_metadata", "-1", (Join-Path $out "faststart.m4a")))

Write-Host "Raw ADTS AAC..."
Invoke-Ffmpeg ($tone44 + @("-ac", "2", "-c:a", "aac", "-b:a", "128k", "-f", "adts", "-map_metadata", "-1", (Join-Path $out "bare.aac")))

Get-ChildItem $out | Select-Object Name, Length | Format-Table -AutoSize
Write-Output "Fixtures written to $out"

#Requires -Version 5.1
<#
.SYNOPSIS
  Deterministic Gradle wrapper for the MusicTagger project.

.DESCRIPTION
  Sets JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME, serializes concurrent Gradle
  invocations between agents through a named mutex, runs Gradle and echoes the
  exit code. Always use this script instead of calling gradlew.bat directly.

  The project lives under a non-ASCII path (Desktop\工作). Gradle 8.14 writes the
  forked test-worker classpath @argfile as UTF-8 while the Windows JDK launcher
  parses it with the ANSI codepage, which corrupts the classpath and makes every
  unit test fail with ClassNotFoundException. To avoid that, this script maps the
  project directory to a free ASCII drive letter with `subst` and runs Gradle
  from there. Existing mappings are found by probing drive letters for the
  project's settings file (never by parsing localized `subst` console output),
  and the build mutex is acquired before creating a mapping so concurrent
  callers cannot race on drive letters.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -Tasks ":core:test","--tests","com.musictag.core.tag.mp3.*"
#>
param(
    [string[]]$Tasks
)

if (-not $Tasks -or $Tasks.Count -eq 0) {
    Write-Error "Usage: tools\build.ps1 -Tasks <gradle args...>"
    exit 2
}

# powershell -File passes comma separated values as one argument; split them so
# callers can write -Tasks ":core:test",":app:assembleDebug".
$flatTasks = @()
foreach ($t in $Tasks) {
    foreach ($part in ($t -split ',')) {
        if ($part -ne "") { $flatTasks += $part }
    }
}
$Tasks = $flatTasks

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME = "D:\Java\jdk-21"
$env:ANDROID_HOME = "D:\Android\sdk"
$env:ANDROID_SDK_ROOT = "D:\Android\sdk"
$env:GRADLE_USER_HOME = "D:\gradle-home"

function Test-DriveIsProject([string]$Drive, [string]$ReferenceSettings) {
    $settings = "${Drive}\settings.gradle.kts"
    if (-not (Test-Path $settings)) { return $false }
    try {
        return ([System.IO.File]::ReadAllText($settings) -eq $ReferenceSettings)
    } catch {
        return $false
    }
}

<#
  Returns an ASCII path ending in backslash for the project root, creating an
  idempotent `subst` mapping when needed. Returns $null when no mapping works.
#>
function Resolve-AsciiProjectRoot([string]$ProjectRoot) {
    if ($ProjectRoot -notmatch '[^\x00-\x7F]') { return $ProjectRoot }

    $referenceSettings = [System.IO.File]::ReadAllText((Join-Path $ProjectRoot "settings.gradle.kts"))
    $letters = @("M", "N", "X", "Z", "Y", "Q") + @([char[]](65..90) | ForEach-Object { [string]$_ })

    # 1. Reuse an existing mapping to this project (probe the drive, no stdout parsing).
    foreach ($letter in $letters) {
        if (Test-DriveIsProject "${letter}:" $referenceSettings) { return "${letter}:\" }
    }

    # 2. Create a mapping on the first genuinely free drive letter.
    foreach ($letter in $letters) {
        $drive = "${letter}:"
        if (Test-Path "${drive}\") { continue }
        & cmd /c subst $drive "$ProjectRoot" 2>$null | Out-Null
        if (Test-DriveIsProject $drive $referenceSettings) { return "${drive}\" }
        & cmd /c subst $drive /D 2>$null | Out-Null
    }

    return $null
}

$mutex = New-Object System.Threading.Mutex($false, "Global\MusicTaggerGradleBuild_v2")
$acquired = $false
try {
    try {
        $acquired = $mutex.WaitOne([TimeSpan]::FromMinutes(20))
    } catch [System.Threading.AbandonedMutexException] {
        # A previous build process died while holding the lock; ownership is ours now.
        $acquired = $true
        Write-Host "== recovered an abandoned build lock ==" -ForegroundColor Yellow
    }
    if (-not $acquired) { Write-Error "Timed out waiting for the build mutex."; exit 1 }
    Write-Host "== build lock acquired ==" -ForegroundColor DarkGray

    # Mapping is created while holding the mutex, so agents cannot race on drive letters.
    $gradleRoot = Resolve-AsciiProjectRoot $root
    if (-not $gradleRoot) {
        Write-Error "Could not map the project to an ASCII drive letter; unit tests cannot run on the non-ASCII path $root."
        exit 1
    }
    Write-Host "== project root: $gradleRoot ==" -ForegroundColor DarkGray
    Set-Location $gradleRoot

    Write-Host "== gradle $($Tasks -join ' ') ==" -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    # Use the locally installed Gradle distribution (the wrapper zip download is
    # blocked on this network; the version matches gradle-wrapper.properties).
    & "D:\gradle-8.14.3\bin\gradle.bat" @Tasks "--console=plain"
    $code = $LASTEXITCODE
    $sw.Stop()
    Write-Host "== exit=$code elapsed=$([math]::Round($sw.Elapsed.TotalSeconds,1))s ==" -ForegroundColor Cyan
    exit $code
}
finally {
    if ($acquired) { $mutex.ReleaseMutex() | Out-Null }
    $mutex.Dispose()
}

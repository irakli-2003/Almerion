<#
  Builds the Windows installer: target\installer\Almerion-<version>.exe

  The installer has a setup wizard (pick the install folder), adds a desktop shortcut and a
  Start-menu entry, and bundles its own Java runtime, so the target PC needs nothing else.

  It also writes target\installer\almerion.jar: attach it to the GitHub release next to
  the installer, and installed apps update themselves to that version on their next start.

  Usage (from the repo root, in PowerShell):
      .\packaging\build-windows-installer.ps1                  # runs the tests, then builds
      .\packaging\build-windows-installer.ps1 -SkipTests
      .\packaging\build-windows-installer.ps1 -Type app-image  # unpacked app folder, no installer

  Needs JDK 21 (jpackage) and Maven on PATH. WiX Toolset 3.14, which jpackage uses to make the
  .exe, is downloaded to %LOCALAPPDATA%\wix314 on first run if it is not installed.
#>
param(
    [string]$Version = "",  # defaults to the version in pom.xml
    [ValidateSet("exe", "app-image")]
    [string]$Type = "exe",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$appName = "Almerion"
$upgradeUuid = "4141426a-c9a8-4400-b1d5-fad1a8954174"  # never change: lets new versions replace old ones

function Need($cmd, $hint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { throw "$cmd not found. $hint" }
}
Need "mvn" "Install Maven and add it to PATH."
Need "jpackage" "Install JDK 21 and add its bin folder to PATH."

if ($Type -eq "exe" -and -not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    $wix = Join-Path $env:LOCALAPPDATA "wix314"
    if (-not (Test-Path (Join-Path $wix "candle.exe"))) {
        Write-Host "Downloading WiX Toolset 3.14 to $wix ..."
        New-Item -ItemType Directory $wix -Force | Out-Null
        $zip = Join-Path $wix "wix.zip"
        Invoke-WebRequest "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip" -OutFile $zip
        Expand-Archive $zip $wix -Force
        Remove-Item $zip
    }
    $env:PATH = "$wix;$env:PATH"
}

Write-Host "Building the app with Maven ..."
$mvnArgs = @("-q", "clean", "package")
if ($SkipTests) { $mvnArgs += "-DskipTests" }
& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }

if (-not $Version) { $Version = ([xml](Get-Content (Join-Path $repo "pom.xml"))).project.version }
$jar = Get-ChildItem target -Filter "almerion-*.jar" | Select-Object -First 1
$stage = Join-Path $repo "target\installer-input"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory $stage -Force | Out-Null
Copy-Item $jar.FullName $stage
Copy-Item "target\libs\*.jar" $stage

# The music engine (Chromium, ~300 MB unpacked) ships inside the installer so it is never
# downloaded in the middle of a session. Cached between builds.
$engine = Join-Path $env:LOCALAPPDATA "almerion-build\jcef"
Write-Host "Preparing the music engine in $engine ..."
$cp = (@($jar.FullName) + (Get-ChildItem "target\libs\*.jar" | ForEach-Object FullName)) -join ";"
& java -cp $cp com.dnd.ui.music.MusicPlayer $engine
if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $engine "install.lock"))) { throw "Could not prepare the music engine." }
Copy-Item $engine (Join-Path $stage "jcef") -Recurse
$dest = Join-Path $repo "target\installer"
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }

$jpArgs = @(
    "--type", $Type,
    "--name", $appName,
    "--app-version", $Version,
    "--vendor", "Almerion",
    "--description", "Almerion: Tabletop RPG Campaign Manager - maps, combat, storyline and music.",
    "--input", $stage,
    "--main-jar", $jar.Name,
    "--main-class", "com.dnd.boot.Launcher",
    "--icon", (Join-Path $PSScriptRoot "windows\app.ico"),
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Xmx2g",
    "--dest", $dest
)
if ($Type -eq "exe") {
    $jpArgs += @(
        "--win-dir-chooser",
        "--win-shortcut",
        "--win-shortcut-prompt",
        "--win-menu",
        "--win-menu-group", $appName,
        "--win-upgrade-uuid", $upgradeUuid
    )
}

Write-Host "Packaging with jpackage ($Type) ..."
& jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

# Attach this jar to the GitHub release as well: installed apps update themselves from it.
Copy-Item $jar.FullName (Join-Path $dest "almerion.jar")

Write-Host ""
Write-Host "Done:" -ForegroundColor Green
Get-ChildItem $dest | ForEach-Object { Write-Host "  $($_.FullName)" }

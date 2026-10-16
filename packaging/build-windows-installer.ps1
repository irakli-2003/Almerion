<#
  Builds the Windows installer: target\installer\DnD Campaign Manager-<version>.exe

  The installer has a setup wizard (pick the install folder), adds a desktop shortcut and a
  Start-menu entry, and bundles its own Java runtime, so the target PC needs nothing else.

  Usage (from the repo root, in PowerShell):
      .\packaging\build-windows-installer.ps1                  # runs the tests, then builds
      .\packaging\build-windows-installer.ps1 -SkipTests
      .\packaging\build-windows-installer.ps1 -Type app-image  # unpacked app folder, no installer

  Needs JDK 21 (jpackage) and Maven on PATH. WiX Toolset 3.14, which jpackage uses to make the
  .exe, is downloaded to %LOCALAPPDATA%\wix314 on first run if it is not installed.
#>
param(
    [string]$Version = "1.0.0",
    [ValidateSet("exe", "app-image")]
    [string]$Type = "exe",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$appName = "DnD Campaign Manager"
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

$jar = Get-ChildItem target -Filter "dnd-campaign-manager-*.jar" | Select-Object -First 1
$stage = Join-Path $repo "target\installer-input"
New-Item -ItemType Directory $stage -Force | Out-Null
Copy-Item $jar.FullName $stage
Copy-Item "target\libs\*.jar" $stage

$dest = Join-Path $repo "target\installer"
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }

$jpArgs = @(
    "--type", $Type,
    "--name", $appName,
    "--app-version", $Version,
    "--vendor", "DnD Campaign Manager",
    "--description", "Run Dungeons and Dragons campaigns: maps, combat, storyline and music.",
    "--input", $stage,
    "--main-jar", $jar.Name,
    "--main-class", "com.dnd.cli.CliApp",
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

Write-Host ""
Write-Host "Done:" -ForegroundColor Green
Get-ChildItem $dest | ForEach-Object { Write-Host "  $($_.FullName)" }

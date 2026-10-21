[CmdletBinding()]
param(
    [string]$HytalePath = "",
    [switch]$SkipModels,
    [switch]$SkipKokoro,
    [switch]$SkipPython
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Write-Step([string]$Text) {
    Write-Host ""
    Write-Host "== $Text ==" -ForegroundColor Cyan
}

function Find-HytalePath {
    $candidates = @()
    if ($env:HYTALE_PATH) { $candidates += $env:HYTALE_PATH }
    $candidates += @(
        "D:\Games\Hytale",
        (Join-Path $env:LOCALAPPDATA "Hytale"),
        (Join-Path $env:ProgramFiles "Hytale")
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "UserData\Mods"))) {
            return $candidate
        }
    }
    return ""
}

Write-Host "Laya Companion installer" -ForegroundColor Green
Write-Host "Repository: $Root"

if (-not $HytalePath) {
    $HytalePath = Find-HytalePath
}
if (-not $HytalePath) {
    $HytalePath = Read-Host "Hytale folder (the one containing UserData)"
}
if (-not (Test-Path (Join-Path $HytalePath "UserData\Mods"))) {
    throw "Could not find '$HytalePath\UserData\Mods'. Pass -HytalePath with the correct folder."
}

$modsDir = Join-Path $HytalePath "UserData\Mods"
$configDir = Join-Path $Root "config"
$logsDir = Join-Path $Root "logs"
New-Item -ItemType Directory -Force -Path $modsDir, $configDir, $logsDir | Out-Null

Write-Step "Checking the required laya.cpp engine"
$layaDir = Join-Path $Root "ai-bridge\laya"
$layaExe = Get-ChildItem $layaDir -Filter "*windows-amd64-vulkan.exe" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
$layaCheckpoint = Join-Path $layaDir "typed-decisions"
if (-not $layaExe -or -not (Test-Path $layaCheckpoint)) {
    throw @"
laya.cpp is REQUIRED and was not found.

Expected:
  $layaDir\*windows-amd64-vulkan.exe
  $layaCheckpoint

Download a Windows Vulkan build and the typed-decisions checkpoint from:
  https://github.com/lkarlslund/laya.cpp/releases

Place the executable and checkpoint in '$layaDir', then run install.ps1 again.
"@
}
Write-Host "  Found $($layaExe.Name) and typed-decisions checkpoint"

Write-Step "Installing the Hytale plugin"
$pluginJar = Get-ChildItem (Join-Path $Root "dist") -Filter "LayaPlugin-*.jar" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $pluginJar) {
    throw "No plugin JAR found in '$Root\dist'. Build the plugin first (see README.md) or place a LayaPlugin-*.jar there."
}
Copy-Item $pluginJar.FullName (Join-Path $modsDir $pluginJar.Name) -Force
Write-Host "  Copied $($pluginJar.Name) to $modsDir"

Write-Step "Creating local configuration"
$configPath = Join-Path $configDir "bridge-config.json"
if (-not (Test-Path $configPath)) {
    Copy-Item (Join-Path $configDir "bridge-config.example.json") $configPath
    Write-Host "  Created config\bridge-config.json (API keys are empty)"
} else {
    Write-Host "  Keeping existing config\bridge-config.json"
}

$envPairs = @{
    "LAYA_BLOCKS_JSON"  = (Join-Path $configDir "blocks.json")
    "LAYA_STATE_JSON"   = (Join-Path $configDir "laya-inventory.json")
    "LAYA_SKIN_JSON"    = (Join-Path $configDir "laya-skin.json")
    "LAYA_COMBAT_JSON"  = (Join-Path $configDir "laya-combat.json")
    "LAYA_LOG"          = (Join-Path $logsDir "laya-plugin.log")
}
foreach ($pair in $envPairs.GetEnumerator()) {
    # setx writes the user-level variable without the slow .NET registry path.
    & setx $pair.Key $pair.Value | Out-Null
    Set-Item -Path "Env:$($pair.Key)" -Value $pair.Value
}
Write-Host "  Set Laya config paths for the current Windows user"
Write-Host "  Restart Hytale after installation so it sees the new variables"

if (-not $SkipPython) {
    Write-Step "Installing Python dependencies"
    $python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) {
        throw "Python was not found on PATH. Install Python 3.11 and tick 'Add Python to PATH'."
    }
    & $python.Source -m pip install --upgrade pip
    & $python.Source -m pip install -r (Join-Path $Root "ai-bridge\requirements.txt")
} else {
    Write-Host "  Skipping Python dependencies (-SkipPython)"
}

if (-not $SkipModels) {
    Write-Step "Downloading optional local speech models"
    $python = Get-Command python -ErrorAction Stop
    $modelArgs = @()
    if ($SkipKokoro) { $modelArgs += "--skip-kokoro" }
    & $python.Source (Join-Path $Root "download-models.py") @modelArgs
} else {
    Write-Host "  Skipping model download (-SkipModels)"
}

Write-Step "Done"
Write-Host "Next:"
Write-Host "  1. Run .\start.bat and open http://127.0.0.1:8788"
Write-Host "  2. Paste an API key in Settings and save"
Write-Host "  3. Start Hytale and talk to Laya in chat or use /laya"
Write-Host ""
Write-Host "laya.cpp is required and was validated before installation."

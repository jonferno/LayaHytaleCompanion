$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    throw "Python was not found on PATH. See INSTALL.md."
}

$configPath = Join-Path $Root "config\bridge-config.json"
if (-not (Test-Path $configPath)) {
    Copy-Item (Join-Path $Root "config\bridge-config.example.json") $configPath
    Write-Host "Created config\bridge-config.json. Add an API key in the settings page."
}

$layaDir = Join-Path $Root "ai-bridge\laya"
$layaExe = Get-ChildItem $layaDir -Filter "laya-*-windows-amd64-*.exe" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
$layaCheckpoint = Join-Path $layaDir "typed-decisions"
if (-not $layaExe -or -not (Test-Path $layaCheckpoint)) {
    throw "laya.cpp is required. Run install.bat, or put the engine and typed-decisions checkpoint in '$layaDir'."
}

# Keep the plugin and the bridge pointed at the same local files. The values are also
# written to the user environment: the plugin reads them when Hytale starts, and a
# clone launched after another folder's installer must not inherit that folder's skin,
# name or inventory (measured 2026-10-27).
$envPairs = @{
    "LAYA_BLOCKS_JSON" = (Join-Path $Root "config\blocks.json")
    "LAYA_STATE_JSON"  = (Join-Path $Root "config\laya-inventory.json")
    "LAYA_SKIN_JSON"   = (Join-Path $Root "config\laya-skin.json")
    "LAYA_NAME_JSON"   = (Join-Path $Root "config\laya-name.json")
    "LAYA_HUD_JSON"    = (Join-Path $Root "config\laya-hud.json")
    "LAYA_COMBAT_JSON" = (Join-Path $Root "config\laya-combat.json")
    "LAYA_LOG"         = (Join-Path $Root "logs\laya-plugin.log")
}
foreach ($pair in $envPairs.GetEnumerator()) {
    Set-Item -Path "Env:$($pair.Key)" -Value $pair.Value
    & setx $pair.Key $pair.Value | Out-Null
}
Write-Host "Config folder: $Root\config"

Write-Host "Starting Laya AI bridge..." -ForegroundColor Green
Start-Process -FilePath $python.Source -ArgumentList @((Join-Path $Root "ai-bridge\main.py")) -WorkingDirectory $Root
Start-Sleep -Seconds 2
Start-Process "http://127.0.0.1:8788"
Write-Host "Bridge started. Settings: http://127.0.0.1:8788"
Write-Host "Start Hytale, then chat with Laya or use /laya."

param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$targets = @(
    (Join-Path $Root "config\laya-inventory.json"),
    (Join-Path $Root "config\laya-skin.json"),
    (Join-Path $Root "config\laya-combat.json"),
    (Join-Path $Root "logs\laya-memory.json")
)

if (-not $Force) {
    Write-Host "This clears Laya's inventory, appearance, combat state, and memory."
    $answer = Read-Host "Type RESET to continue"
    if ($answer -ne "RESET") {
        Write-Host "Cancelled."
        exit 0
    }
}

foreach ($path in $targets) {
    if (Test-Path $path) {
        Remove-Item $path -Force
        Write-Host "Removed $path"
    }
}
Write-Host "Reset complete. Your API keys in config\bridge-config.json were kept."

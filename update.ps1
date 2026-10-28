# Laya auto-updater.
# Checks the GitHub release, then updates the plugin jar and the bridge .py files.
# Every failure is non-fatal: the launcher keeps using the current install.
param(
    [string]$Root = (Split-Path -Parent $MyInvocation.MyCommand.Path),
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"
$Repo = "jonferno/LayaHytaleCompanion"
$VersionFile = Join-Path $Root "config\laya-version.txt"

function Write-Step([string]$Text) {
    if (-not $Quiet) { Write-Host "  $Text" }
}

function Get-LocalVersion {
    $manifest = Join-Path $Root "hytale-plugin\src\main\resources\manifest.json"
    if (Test-Path $manifest) {
        try {
            $v = (Get-Content $manifest -Raw | ConvertFrom-Json).Version
            if ($v) { return $v }
        } catch { }
    }
    if (Test-Path $VersionFile) {
        return (Get-Content $VersionFile -Raw).Trim()
    }
    return $null
}

function Find-HytaleMods {
    if ($env:LAYA_MODS_DIR -and (Test-Path $env:LAYA_MODS_DIR)) { return $env:LAYA_MODS_DIR }
    $candidates = @()
    if ($env:HYTALE_PATH) { $candidates += $env:HYTALE_PATH }
    $candidates += "D:\Games\Hytale"
    $candidates += (Join-Path $env:LOCALAPPDATA "Hytale")
    $candidates += (Join-Path $env:ProgramFiles "Hytale")
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        $mods = Join-Path $c "UserData\Mods"
        if (Test-Path $mods) { return $mods }
    }
    return $null
}

function Backup-Path([string]$Source, [string]$TargetDir) {
    if (-not (Test-Path $Source)) { return }
    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
    Copy-Item $Source (Join-Path $TargetDir (Split-Path -Leaf $Source)) -Force
}

try {
    $local = Get-LocalVersion
    if (-not $local) { Write-Step "Update check skipped: no local version file"; return }

    $headers = @{ "User-Agent" = "LayaLauncher"; "Accept" = "application/vnd.github+json" }
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" `
        -Headers $headers -TimeoutSec 6
    if (-not $release -or -not $release.tag_name) { return }

    $tag = [string]$release.tag_name
    $latest = ($tag -replace "^v", "").Split("-")[0]
    if (-not $latest) { return }

    $localV = $null
    $latestV = $null
    if (-not [version]::TryParse($local, [ref]$localV) -or
        -not [version]::TryParse($latest, [ref]$latestV)) {
        Write-Step "Update check skipped: cannot compare '$local' with '$latest'"
        return
    }
    if ($latestV -le $localV) {
        Write-Step "Up to date ($local)"
        return
    }

    Write-Step "Update found: $local -> $latest"
    $backupRoot = Join-Path $Root "backups\update-$latest"
    New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null

    # 1. Plugin jar: download, then replace every Laya plugin jar in Mods.
    $asset = $release.assets | Where-Object { $_.name -like "*.jar" } | Select-Object -First 1
    $mods = Find-HytaleMods
    if ($asset -and $mods -and -not (Get-Process HytaleClient -ErrorAction SilentlyContinue)) {
        $jarTemp = Join-Path $env:TEMP $asset.name
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $jarTemp -TimeoutSec 60
        if ((Get-Item $jarTemp).Length -gt 10000) {
            Get-ChildItem $mods -Filter "LayaPlugin-*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
                Backup-Path $_.FullName $backupRoot
                Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
            }
            Copy-Item $jarTemp (Join-Path $mods $asset.name) -Force
            Write-Step "Plugin updated: $($asset.name)"
        }
        Remove-Item $jarTemp -Force -ErrorAction SilentlyContinue
    } elseif ($asset -and (Get-Process HytaleClient -ErrorAction SilentlyContinue)) {
        Write-Step "Plugin skipped: close Hytale and relaunch to update the jar"
    } elseif ($asset) {
        Write-Step "Plugin skipped: Hytale folder not found (set HYTALE_PATH)"
    }

    # 2. Bridge .py files from the release source zip. Config, models and the
    # engine stay untouched.
    $zipTemp = Join-Path $env:TEMP "laya-src-$latest.zip"
    $srcZip = "https://github.com/$Repo/archive/refs/tags/$tag.zip"
    Invoke-WebRequest -Uri $srcZip -OutFile $zipTemp -TimeoutSec 90
    $extract = Join-Path $env:TEMP "laya-src-$latest"
    if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
    Expand-Archive -Path $zipTemp -DestinationPath $extract -Force
    $bridgeSrc = Get-ChildItem $extract -Directory | ForEach-Object {
        Join-Path $_.FullName "ai-bridge"
    } | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($bridgeSrc) {
        $bridgeDst = Join-Path $Root "ai-bridge"
        Get-ChildItem $bridgeSrc -Filter "*.py" | ForEach-Object {
            Backup-Path (Join-Path $bridgeDst $_.Name) $backupRoot
            Copy-Item $_.FullName (Join-Path $bridgeDst $_.Name) -Force
        }
        Write-Step "Bridge updated from $tag"
    }
    Remove-Item $zipTemp -Force -ErrorAction SilentlyContinue
    Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue

    Set-Content -Path $VersionFile -Value $latest -Encoding utf8
    Write-Step "Updated to $latest (backup in $backupRoot)"
} catch {
    Write-Step "Update check failed: $($_.Exception.Message)"
}

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Launcher = Join-Path $Root "launcher"
$LogPath = Join-Path $PSScriptRoot "codex-web-gpt-workaround.log"

Start-Transcript -Path $LogPath -Append | Out-Null

try {
    Write-Host "=== Codex Web GPT High workaround ===" -ForegroundColor Cyan
    Write-Host "Repository: $Root"
    Write-Host "Log: $LogPath"

    if (-not (Get-Command bun -ErrorAction SilentlyContinue)) {
        throw "Bun was not found. Install Bun 1.3.14 and reopen PowerShell."
    }

    $running = Get-Process -ErrorAction SilentlyContinue |
        Where-Object { $_.ProcessName -match "Codex Web GPT|codex-web-gpt|electron" }

    foreach ($process in $running) {
        try {
            $processPath = $process.Path
            if ($processPath -and $processPath.StartsWith($Root, [System.StringComparison]::OrdinalIgnoreCase)) {
                Stop-Process -Id $process.Id -Force
            }
        } catch {}
    }

    Set-Location $Root
    & bun install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw "Root dependency installation failed." }

    Set-Location $Launcher
    & bun install --force
    if ($LASTEXITCODE -ne 0) { throw "Launcher dependency installation failed." }

    $ElectronModule = Join-Path $Launcher "node_modules\electron"
    $ElectronInstall = Join-Path $ElectronModule "install.js"
    $ElectronExe = Join-Path $ElectronModule "dist\electron.exe"

    if (-not (Test-Path $ElectronExe)) {
        if (-not (Test-Path $ElectronInstall)) {
            throw "Electron install.js is missing: $ElectronInstall"
        }
        Write-Host "Downloading Electron binary..."
        $env:force_no_cache = "true"
        & bun $ElectronInstall
        $exitCode = $LASTEXITCODE
        Remove-Item Env:\force_no_cache -ErrorAction SilentlyContinue
        if ($exitCode -ne 0) { throw "Electron binary download failed with exit code $exitCode." }
    }

    if (-not (Test-Path $ElectronExe)) {
        throw "Electron installation completed without electron.exe."
    }

    Set-Location $Root
    & bun scripts/workarounds/patch-chatgpt-effort-ui.cjs
    if ($LASTEXITCODE -ne 0) { throw "Effort UI patch failed." }

    Write-Host "Starting patched launcher..." -ForegroundColor Green
    Write-Host "Manually select High in the embedded ChatGPT UI."
    Write-Host "Use ChatGPT Web — High only until the upstream fix is released."
    & bun run app
    if ($LASTEXITCODE -ne 0) { throw "Launcher exited with code $LASTEXITCODE." }
}
catch {
    Write-Host "FAILED" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host "Log: $LogPath" -ForegroundColor Yellow
}
finally {
    try { Stop-Transcript | Out-Null } catch {}
    Read-Host "Press Enter to close"
}

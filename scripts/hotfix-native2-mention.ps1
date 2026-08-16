$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

if ($PSVersionTable.PSVersion.Major -lt 6) {
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
}

function Invoke-WithRetry {
  param(
    [Parameter(Mandatory = $true)][scriptblock]$Operation,
    [Parameter(Mandatory = $true)][string]$Label
  )
  for ($Attempt = 1; $Attempt -le 3; $Attempt++) {
    try {
      return & $Operation
    } catch {
      if ($Attempt -eq 3) {
        throw "$Label failed after $Attempt attempts: $($_.Exception.Message)"
      }
      Start-Sleep -Seconds (2 * $Attempt)
    }
  }
}

function Resolve-SemVer {
  param([Parameter(Mandatory = $true)][string]$Value)
  $Match = [regex]::Match($Value, '(\d+\.\d+\.\d+)')
  if (-not $Match.Success) { return $null }
  return $Match.Groups[1].Value
}

if (-not [Environment]::Is64BitOperatingSystem) {
  throw "This hotfix supports 64-bit Windows only"
}

$LauncherProcess = Get-Process -Name "Codex Web GPT" -ErrorAction SilentlyContinue
if ($LauncherProcess) {
  throw "Quit Codex Web GPT before applying the hotfix, then run this script again"
}

$CoreHome = Join-Path $env:USERPROFILE ".codex-chatgpt-web"
$VersionsRoot = Join-Path $CoreHome "versions"
$LauncherExecutable = Join-Path $env:LOCALAPPDATA "Programs\codex-web-gpt-launcher\Codex Web GPT.exe"
if (-not (Test-Path $VersionsRoot)) {
  throw "Codex Web GPT versions directory was not found: $VersionsRoot"
}
if (-not (Test-Path $LauncherExecutable)) {
  throw "Installed Codex Web GPT launcher was not found: $LauncherExecutable"
}

# Prefer the runtime actually owned by the background daemon. This avoids patching a newer or
# older version directory when a launcher update/rollback left multiple versions installed.
$AllProcesses = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
$RuntimeRoots = @()
$RuntimePrefix = [regex]::Escape($VersionsRoot + "\")
foreach ($Process in $AllProcesses) {
  $CommandLine = [string]$Process.CommandLine
  if (-not $CommandLine) { continue }
  $Match = [regex]::Match(
    $CommandLine,
    $RuntimePrefix + '([^\\"]+-win32-x64)\\app\\cli\.js',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
  )
  if ($Match.Success) {
    $RuntimeRoots += (Join-Path $VersionsRoot $Match.Groups[1].Value)
  }
}
$RuntimeRoots = @($RuntimeRoots | Sort-Object -Unique)
if ($RuntimeRoots.Count -gt 1) {
  throw "Multiple active Codex Web GPT runtimes were detected: $($RuntimeRoots -join ', ')"
}

if ($RuntimeRoots.Count -eq 1) {
  $RuntimeRoot = $RuntimeRoots[0]
} else {
  $LauncherVersion = Resolve-SemVer ([string](Get-Item $LauncherExecutable).VersionInfo.ProductVersion)
  if (-not $LauncherVersion) {
    throw "Could not determine the installed launcher version and no active runtime process was found"
  }
  $RuntimeRoot = Join-Path $VersionsRoot "$LauncherVersion-win32-x64"
}

$ManifestPath = Join-Path $RuntimeRoot "manifest.json"
$BunExecutable = Join-Path $RuntimeRoot "runtime\bun.exe"
$HelperPath = Join-Path $RuntimeRoot "app\browser-helper.cjs"
$LauncherHelperPath = Join-Path (Split-Path $LauncherExecutable -Parent) "resources\runtime\app\browser-helper.cjs"
$HelperPaths = @(@($HelperPath, $LauncherHelperPath) | Sort-Object -Unique)
foreach ($RequiredPath in @($ManifestPath, $BunExecutable) + $HelperPaths) {
  if (-not (Test-Path $RequiredPath -PathType Leaf)) {
    throw "Installed runtime file was not found: $RequiredPath"
  }
}

$Manifest = Get-Content $ManifestPath -Raw | ConvertFrom-Json
$RuntimeVersion = [string]$Manifest.appVersion
$SupportedRuntime = (
  ($RuntimeVersion -match '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$') -and
  ([string]$Manifest.platform -eq "win32") -and
  ([string]$Manifest.arch -eq "x64")
)
if (-not $SupportedRuntime) {
  throw "Installed runtime manifest is not a supported Windows x64 release: $ManifestPath"
}

$BusyHelpers = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
  if (-not $_.CommandLine) { return $false }
  foreach ($CandidatePath in $HelperPaths) {
    if ($_.CommandLine.Contains($CandidatePath)) { return $true }
  }
  return $false
}
if ($BusyHelpers) {
  throw "A browser-helper process is still using the runtime. Quit Codex Web GPT completely and retry."
}

$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) "codex-web-gpt-native2-hotfix-$([guid]::NewGuid().ToString('N'))"
$ArchivePath = Join-Path $TempRoot "source.zip"
$ExtractRoot = Join-Path $TempRoot "source"
$BuiltHelper = Join-Path $TempRoot "browser-helper.cjs"
$BackupTimestamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$Backups = @()
$UpstreamRepository = "miuuyy/codex-chatgpt-web"
$SourceUrl = "https://github.com/$UpstreamRepository/archive/refs/tags/v$RuntimeVersion.zip"

$HotfixModule = @'
import type { Locator, Page } from "playwright-core";
import { ChatGptWebAdapterError } from "./adapter-error";
import { ChatGptBrowserWorker } from "./browser-worker";

const CONNECTOR_MENU_ROW_SELECTOR = '.__menu-item[tabindex="0"]';
const CONNECTOR_MENTION_TIMEOUT_MS = 5_000;
const CONNECTOR_MENTION_KEY_DELAY_MS = 25;
const CONNECTOR_UI_SETTLE_MS = 250;

interface HotfixWorker {
  config: { appName: string };
  activeComposer(page: Page): Promise<Locator>;
  connectorIsSelected(composer: Locator): Promise<boolean>;
  connectorMentionRowTitles(menuRows: Locator): Promise<string[]>;
  selectedConnectorControl(composer: Locator): Locator;
}

interface HotfixPrototype {
  selectConnector(
    this: HotfixWorker,
    page: Page,
    captureDiagnostic?: (checkpoint: string) => Promise<void>,
  ): Promise<Locator>;
  __native2MentionHotfixInstalled?: boolean;
}

function connectorUnavailable(message: string): ChatGptWebAdapterError {
  return new ChatGptWebAdapterError(message, {
    status: 400,
    errorType: "invalid_request_error",
    code: "connector_unavailable",
    retryable: false,
  });
}

async function selectConnectorOnce(
  worker: HotfixWorker,
  page: Page,
  captureDiagnostic?: (checkpoint: string) => Promise<void>,
): Promise<Locator> {
  let composer = await worker.activeComposer(page);

  if (await worker.connectorIsSelected(composer)) {
    await captureDiagnostic?.("connector-already-selected");
    return composer;
  }

  const appName = worker.config.appName.trim();
  if (!appName) throw connectorUnavailable("ChatGPT connector name is empty");

  await composer.fill("");
  await composer.focus();
  await page.waitForTimeout(CONNECTOR_UI_SETTLE_MS);
  await composer.pressSequentially(`@${appName}`, { delay: CONNECTOR_MENTION_KEY_DELAY_MS });
  await captureDiagnostic?.("connector-mention-triggered");

  const menuRows = page.locator(CONNECTOR_MENU_ROW_SELECTOR);
  const appResult = menuRows.filter({
    has: page.getByText(appName, { exact: true }),
  });

  try {
    await appResult.waitFor({ state: "visible", timeout: CONNECTOR_MENTION_TIMEOUT_MS });
  } catch (error) {
    if (!(error instanceof Error) || error.name !== "TimeoutError") throw error;
    await captureDiagnostic?.("connector-menu-missing");
    const titles = await worker.connectorMentionRowTitles(menuRows);
    throw connectorUnavailable(
      `ChatGPT connector menu did not expose one exact ${JSON.stringify(appName)} row after one full mention attempt`
      + (titles.length > 0
        ? `; visible rows: ${titles.map(title => JSON.stringify(title)).join(", ")}`
        : "; connector menu did not open"),
    );
  }

  const resultCount = await appResult.count();
  if (resultCount !== 1) {
    throw connectorUnavailable(
      `ChatGPT connector menu exposed ${resultCount} exact ${JSON.stringify(appName)} rows; expected exactly one`,
    );
  }

  await appResult.dispatchEvent("click");
  composer = await worker.activeComposer(page);
  const selectedConnector = worker.selectedConnectorControl(composer);
  await selectedConnector.waitFor({ state: "visible", timeout: 10_000 });
  if (!await worker.connectorIsSelected(composer)) {
    throw connectorUnavailable(`ChatGPT composer did not select ${JSON.stringify(appName)} connector`);
  }
  await captureDiagnostic?.("connector-selected");
  return composer;
}

export function installNative2MentionHotfix(): void {
  const prototype = ChatGptBrowserWorker.prototype as unknown as HotfixPrototype;
  if (prototype.__native2MentionHotfixInstalled) return;
  Object.defineProperty(prototype, "__native2MentionHotfixInstalled", {
    value: true,
    configurable: false,
    enumerable: false,
    writable: false,
  });
  prototype.selectConnector = function selectConnector(
    this: HotfixWorker,
    page: Page,
    captureDiagnostic?: (checkpoint: string) => Promise<void>,
  ): Promise<Locator> {
    return selectConnectorOnce(this, page, captureDiagnostic);
  };
}
'@

New-Item -ItemType Directory -Path $TempRoot -Force | Out-Null
try {
  Write-Host "Preparing Native2 mention hotfix for Codex Web GPT runtime $RuntimeVersion..."
  $null = Invoke-WithRetry -Label "Downloading source v$RuntimeVersion" -Operation {
    Remove-Item $ArchivePath -Force -ErrorAction SilentlyContinue
    Invoke-WebRequest $SourceUrl -OutFile $ArchivePath -TimeoutSec 180 -UseBasicParsing
  }
  Expand-Archive -Path $ArchivePath -DestinationPath $ExtractRoot -Force
  $SourceRoot = Get-ChildItem $ExtractRoot -Directory | Select-Object -First 1
  if (-not $SourceRoot) { throw "Downloaded source archive did not contain a project directory" }

  $WorkerSourcePath = Join-Path $SourceRoot.FullName "src\adapters\chatgpt-web\browser-worker.ts"
  $HelperMainPath = Join-Path $SourceRoot.FullName "src\adapters\chatgpt-web\browser-helper-main.ts"
  $HotfixModulePath = Join-Path $SourceRoot.FullName "src\adapters\chatgpt-web\native2-mention-hotfix.ts"
  $BuildScriptPath = Join-Path $SourceRoot.FullName "scripts\build-browser-helper.ts"
  foreach ($SourcePath in @($WorkerSourcePath, $HelperMainPath, $BuildScriptPath)) {
    if (-not (Test-Path $SourcePath -PathType Leaf)) {
      throw "Source v$RuntimeVersion is not compatible with this hotfix: missing $SourcePath"
    }
  }

  $WorkerSource = Get-Content $WorkerSourcePath -Raw
  foreach ($RequiredText in @(
    'private async selectConnector',
    'connectorIsSelected',
    'connectorMentionRowTitles',
    'selectedConnectorControl',
    'composer.pressSequentially("@c"'
  )) {
    if (-not $WorkerSource.Contains($RequiredText)) {
      throw "Source v$RuntimeVersion is not compatible with this hotfix: missing marker $RequiredText"
    }
  }

  Set-Content -Path $HotfixModulePath -Value $HotfixModule -Encoding UTF8

  $HelperMain = Get-Content $HelperMainPath -Raw
  $ModelImport = 'import type { ChatGptWebCapabilities } from "./model";'
  if (-not $HelperMain.Contains($ModelImport)) {
    throw "Source v$RuntimeVersion is not compatible with this hotfix: helper import marker changed"
  }
  $HotfixImport = 'import { installNative2MentionHotfix } from "./native2-mention-hotfix";'
  if (-not $HelperMain.Contains($HotfixImport)) {
    $HelperMain = $HelperMain.Replace($ModelImport, "$ModelImport`r`n$HotfixImport")
  }
  $InterfaceMarker = "interface RunMessage {"
  if (-not $HelperMain.Contains($InterfaceMarker)) {
    throw "Source v$RuntimeVersion is not compatible with this hotfix: helper startup marker changed"
  }
  if (-not $HelperMain.Contains("installNative2MentionHotfix();")) {
    $HelperMain = $HelperMain.Replace($InterfaceMarker, "installNative2MentionHotfix();`r`n`r`n$InterfaceMarker")
  }
  Set-Content -Path $HelperMainPath -Value $HelperMain -Encoding UTF8

  Push-Location $SourceRoot.FullName
  try {
    & $BunExecutable "run" $BuildScriptPath $BuiltHelper
    if ($LASTEXITCODE -ne 0) {
      throw "Bundled Bun failed to build the browser helper (exit $LASTEXITCODE)"
    }
  } finally {
    Pop-Location
  }

  if (-not (Test-Path $BuiltHelper -PathType Leaf)) {
    throw "Hotfix build did not produce browser-helper.cjs"
  }
  $BuiltLength = (Get-Item $BuiltHelper).Length
  if ($BuiltLength -lt 10000) {
    throw "Hotfix browser-helper.cjs is unexpectedly small ($BuiltLength bytes)"
  }
  $BuiltText = Get-Content $BuiltHelper -Raw
  if (-not $BuiltText.Contains("connector_unavailable") -or -not $BuiltText.Contains("one full mention attempt")) {
    throw "Built helper does not contain the Native2 mention hotfix markers"
  }

  try {
    foreach ($TargetPath in $HelperPaths) {
      $BackupPath = "$TargetPath.before-native2-hotfix-$BackupTimestamp.bak"
      Copy-Item $TargetPath $BackupPath -ErrorAction Stop
      $Backups += [PSCustomObject]@{ Target = $TargetPath; Backup = $BackupPath }
      Copy-Item $BuiltHelper $TargetPath -Force -ErrorAction Stop
    }
  } catch {
    foreach ($Backup in $Backups) {
      Copy-Item $Backup.Backup $Backup.Target -Force -ErrorAction SilentlyContinue
    }
    throw
  }

  foreach ($TargetPath in $HelperPaths) {
    $InstalledText = Get-Content $TargetPath -Raw
    if (-not $InstalledText.Contains("connector_unavailable") -or -not $InstalledText.Contains("one full mention attempt")) {
      foreach ($Backup in $Backups) {
        Copy-Item $Backup.Backup $Backup.Target -Force -ErrorAction SilentlyContinue
      }
      throw "Installed helper verification failed for $TargetPath; the original helpers were restored"
    }
  }

  Write-Host "Native2 mention hotfix installed successfully."
  Write-Host "Runtime: $RuntimeRoot"
  foreach ($Backup in $Backups) {
    Write-Host "Patched: $($Backup.Target)"
    Write-Host "Backup:  $($Backup.Backup)"
  }
  Start-Process $LauncherExecutable
} catch {
  Write-Error $_
  throw
} finally {
  Remove-Item -Recurse -Force $TempRoot -ErrorAction SilentlyContinue
}

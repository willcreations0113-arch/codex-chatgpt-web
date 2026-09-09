const fs = require("node:fs");

function backupOnce(file) {
  const backup = `${file}.before-effort-workaround.bak`;
  if (!fs.existsSync(backup)) fs.copyFileSync(file, backup);
}

function replaceBlock(file, startMarker, endMarker, replacement, patchedMarker) {
  const source = fs.readFileSync(file, "utf8");
  if (source.includes(patchedMarker)) {
    console.log(`Already patched: ${file} (${patchedMarker})`);
    return;
  }

  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start);
  if (start < 0 || end < 0 || end <= start) {
    throw new Error(`Could not locate patch area in ${file}: ${startMarker}`);
  }

  backupOnce(file);
  const patched = source.slice(0, start) + replacement + source.slice(end);
  fs.writeFileSync(file, patched, "utf8");
  console.log(`Patched: ${file} (${patchedMarker})`);
}

function replaceRegex(file, pattern, replacement, patchedMarker) {
  const source = fs.readFileSync(file, "utf8");
  if (source.includes(patchedMarker)) {
    console.log(`Already patched: ${file} (${patchedMarker})`);
    return;
  }

  if (!pattern.test(source)) {
    throw new Error(`Could not locate regex patch area in ${file}: ${patchedMarker}`);
  }

  backupOnce(file);
  const patched = source.replace(pattern, replacement);
  fs.writeFileSync(file, patched, "utf8");
  console.log(`Patched: ${file} (${patchedMarker})`);
}

replaceBlock(
  "launcher/electron/browser-host.cjs",
  "  async selectHighEffort({",
  "  async assistantTurnCount() {",
  `  async selectHighEffort() {
    // Temporary workaround for ChatGPT's August 2026 effort-selector UI change.
    // The user must manually select High in the embedded ChatGPT composer.
    return { effort: "High", changed: false };
  }

`,
  "Temporary workaround for ChatGPT's August 2026 effort-selector UI change",
);

replaceRegex(
  "launcher/electron/browser-host.cjs",
  /    let proAvailable;\r?\n    if \(detectPro\) \{[\s\S]*?\r?\n    \}\r?\n(?=    if \(startedIdle\) await this\.returnToIdle\(\);)/,
  `    let proAvailable;
    if (detectPro) {
      // Temporary workaround: capability detection previously opened the obsolete
      // effort menu at item index 0. The current verified account is Plus, so do not
      // expose Pro-only models until upstream supports the new selector UI.
      proAvailable = false;
      this.logger.info("browser.pro_detection_bypassed", {
        reason: "ChatGPT effort selector UI changed",
      });
    }
`,
  "browser.pro_detection_bypassed",
);

replaceRegex(
  "launcher/electron/browser-host.cjs",
  /    if \(!isTemporaryChatUrl\(initialUrl\)\) await this\.view\.webContents\.loadURL\(TEMPORARY_CHAT_URL\);/,
  `    if (!isTemporaryChatUrl(initialUrl)) {
      try {
        await this.view.webContents.loadURL(TEMPORARY_CHAT_URL);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        const currentUrl = this.view.webContents.getURL();
        if (!/ERR_ABORTED/.test(message) || !isTemporaryChatUrl(currentUrl)) throw error;
        this.logger.info("browser.temporary_chat_navigation_superseded", { currentUrl });
      }
    }`,
  "browser.temporary_chat_navigation_superseded",
);

replaceBlock(
  "src/adapters/chatgpt-web/browser-worker.ts",
  "  private async selectModelAndEffort(",
  "  private async activeComposer(",
  `  private async selectModelAndEffort(
    page: Page,
    modelId: string,
    reasoning: string | undefined,
    capabilities: ChatGptWebCapabilities,
    captureDiagnostic?: (checkpoint: string) => Promise<void>,
  ): Promise<ChatGptWebModelMode> {
    // Temporary workaround for ChatGPT's August 2026 effort-selector UI change.
    // Keep model metadata, but skip the obsolete menu DOM operation.
    const mode = resolveChatGptWebModelMode(modelId, reasoning, capabilities);
    await this.activeComposer(page);
    await captureDiagnostic?.("effort-selection-bypassed");
    return mode;
  }

`,
  "effort-selection-bypassed",
);

{
  const file = "src/adapters/chatgpt-web/browser-worker.ts";
  let source = fs.readFileSync(file, "utf8");
  const marker = "temporary-chat-navigation-superseded";
  if (source.includes(marker)) {
    console.log(`Already patched: ${file} (${marker})`);
  } else {
    const simpleGoto = 'await page.goto(CHATGPT_TEMPORARY_CHAT_URL, { waitUntil: "domcontentloaded", timeout: 60_000 });';
    const chainedGoto = 'page.goto(CHATGPT_TEMPORARY_CHAT_URL, { waitUntil: "domcontentloaded", timeout: 60_000 }).then(() => undefined)';
    const simpleCount = source.split(simpleGoto).length - 1;
    const chainedCount = source.split(chainedGoto).length - 1;
    if (simpleCount !== 1 || chainedCount !== 1) {
      throw new Error(`Unexpected Temporary Chat goto count (simple=${simpleCount}, chained=${chainedCount})`);
    }

    source = source.replace(simpleGoto, 'await gotoTemporaryChatToleratingSupersededNavigation(page);');
    source = source.replace(chainedGoto, 'gotoTemporaryChatToleratingSupersededNavigation(page)');

    const insertionMarker = "export async function throwIfChatGptRateLimitDialog(page: Page): Promise<void> {";
    const insertionIndex = source.indexOf(insertionMarker);
    if (insertionIndex < 0) throw new Error("Could not locate browser-worker helper insertion point");

    const helper = `async function gotoTemporaryChatToleratingSupersededNavigation(page: Page): Promise<void> {
  try {
    await page.goto(CHATGPT_TEMPORARY_CHAT_URL, { waitUntil: "domcontentloaded", timeout: 60_000 });
    return;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (!/ERR_ABORTED/.test(message)) throw error;

    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline) {
      try {
        const current = new URL(page.url());
        if (current.origin === "https://chatgpt.com" && current.searchParams.get("temporary-chat") === "true") {
          console.info("[chatgpt-web] temporary-chat-navigation-superseded: destination already reached");
          return;
        }
      } catch {}
      await new Promise(resolveSleep => setTimeout(resolveSleep, 100));
    }
    throw error;
  }
}

`;

    backupOnce(file);
    source = source.slice(0, insertionIndex) + helper + source.slice(insertionIndex);
    fs.writeFileSync(file, source, "utf8");
    console.log(`Patched: ${file} (${marker})`);
  }
}

console.log("");
console.log("Codex Web GPT workaround applied.");
console.log("Use ChatGPT Web — High and manually select High in the embedded ChatGPT UI.");
console.log("Pro capability detection is temporarily disabled for the verified Plus account.");
console.log("Superseded Temporary Chat navigation is tolerated only when the destination is actually reached.");
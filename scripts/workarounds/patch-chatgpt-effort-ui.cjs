const fs = require("node:fs");

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

  const backup = `${file}.before-effort-workaround.bak`;
  if (!fs.existsSync(backup)) fs.copyFileSync(file, backup);

  const patched = source.slice(0, start) + replacement + source.slice(end);
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

replaceBlock(
  "launcher/electron/browser-host.cjs",
  "    let proAvailable;\n    if (detectPro) {",
  "    if (startedIdle) await this.returnToIdle();",
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

console.log("");
console.log("Effort UI workaround applied.");
console.log("Use ChatGPT Web — High and manually select High in the embedded ChatGPT UI.");
console.log("Pro capability detection is temporarily disabled for the verified Plus account.");

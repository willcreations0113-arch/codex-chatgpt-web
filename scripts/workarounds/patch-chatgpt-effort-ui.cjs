const fs = require("node:fs");

function replaceBlock(file, startMarker, endMarker, replacement, patchedMarker) {
  const source = fs.readFileSync(file, "utf8");
  if (source.includes(patchedMarker)) {
    console.log(`Already patched: ${file}`);
    return;
  }

  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start);
  if (start < 0 || end < 0 || end <= start) {
    throw new Error(`Could not locate patch area in ${file}`);
  }

  const backup = `${file}.before-effort-workaround.bak`;
  if (!fs.existsSync(backup)) fs.copyFileSync(file, backup);

  const patched = source.slice(0, start) + replacement + source.slice(end);
  fs.writeFileSync(file, patched, "utf8");
  console.log(`Patched: ${file}`);
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

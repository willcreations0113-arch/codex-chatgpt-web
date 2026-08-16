import type { Locator, Page } from "playwright-core";
import { ChatGptWebAdapterError } from "./adapter-error";
import { ChatGptBrowserWorker } from "./browser-worker";

const CONNECTOR_MENU_ROW_SELECTOR = '.__menu-item[tabindex="0"]';
const CONNECTOR_MENTION_TIMEOUT_MS = 20_000;
const CONNECTOR_MENTION_KEY_DELAY_MS = 25;
const CONNECTOR_UI_SETTLE_MS = 250;

interface LauncherConnectorWorker {
  config: { appName: string };
  activeComposer(page: Page): Promise<Locator>;
  connectorIsSelected(composer: Locator): Promise<boolean>;
  connectorMentionRowTitles(menuRows: Locator): Promise<string[]>;
  selectedConnectorControl(composer: Locator): Locator;
}

interface LauncherConnectorWorkerPrototype {
  selectConnector(
    this: LauncherConnectorWorker,
    page: Page,
    captureDiagnostic?: (checkpoint: string) => Promise<void>,
  ): Promise<Locator>;
  __launcherConnectorSelectionPatched?: boolean;
}

function connectorUnavailable(message: string): ChatGptWebAdapterError {
  return new ChatGptWebAdapterError(message, {
    status: 400,
    errorType: "invalid_request_error",
    code: "connector_unavailable",
    retryable: false,
  });
}

export async function selectLauncherConnectorOnce(
  worker: LauncherConnectorWorker,
  page: Page,
  captureDiagnostic?: (checkpoint: string) => Promise<void>,
): Promise<Locator> {
  let composer = await worker.activeComposer(page);

  // A resumed helper round must never erase or re-type a connector mention that is already active.
  // This is intentionally checked before composer.fill("") because clearing the Lexical editor also
  // removes ChatGPT's selected connector pill.
  if (await worker.connectorIsSelected(composer)) {
    await captureDiagnostic?.("connector-already-selected");
    return composer;
  }

  const appName = worker.config.appName.trim();
  if (!appName) throw connectorUnavailable("ChatGPT connector name is empty");

  await composer.fill("");
  await composer.focus();
  await page.waitForTimeout(CONNECTOR_UI_SETTLE_MS);

  // ChatGPT opens connector search from a short mention prefix; typing the complete display name
  // can be treated as ordinary composer text and never open the menu. Trigger search exactly once
  // and then resolve the requested connector by its complete, exact row label.
  const searchPrefix = appName.match(/[A-Za-z0-9]/)?.[0]?.toLowerCase();
  if (!searchPrefix) throw connectorUnavailable("ChatGPT connector name has no searchable characters");
  await composer.pressSequentially(`@${searchPrefix}`, { delay: CONNECTOR_MENTION_KEY_DELAY_MS });
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
      `ChatGPT connector menu did not expose one exact ${JSON.stringify(appName)} row after one mention trigger`
      + (titles.length > 0
        ? `; visible rows: ${titles.map(title => JSON.stringify(title)).join(", ")}`
        : "; connector menu did not open"),
    );
  }

  await captureDiagnostic?.("connector-menu-visible");
  const resultCount = await appResult.count();
  if (resultCount !== 1) {
    throw connectorUnavailable(
      `ChatGPT connector menu exposed ${resultCount} exact ${JSON.stringify(appName)} rows; expected exactly one`,
    );
  }

  await appResult.dispatchEvent("click");

  // Selecting a connector replaces the Lexical composer subtree, so resolve it again after the
  // click and prove the connector pill is actually selected before inserting the task payload.
  composer = await worker.activeComposer(page);
  const selectedConnector = worker.selectedConnectorControl(composer);
  await selectedConnector.waitFor({ state: "visible", timeout: 10_000 });
  if (!await worker.connectorIsSelected(composer)) {
    throw connectorUnavailable(`ChatGPT composer did not select ${JSON.stringify(appName)} connector`);
  }
  await captureDiagnostic?.("connector-selected");
  return composer;
}

/**
 * The launcher helper is a separate process, so keep this guard scoped to launcher-backed turns.
 * Managed-Chrome behavior remains unchanged until the common browser worker can be migrated safely.
 */
export function installLauncherConnectorSelectionPatch(): void {
  const prototype = ChatGptBrowserWorker.prototype as unknown as LauncherConnectorWorkerPrototype;
  if (prototype.__launcherConnectorSelectionPatched) return;

  Object.defineProperty(prototype, "__launcherConnectorSelectionPatched", {
    value: true,
    configurable: false,
    enumerable: false,
    writable: false,
  });

  prototype.selectConnector = function selectConnector(
    this: LauncherConnectorWorker,
    page: Page,
    captureDiagnostic?: (checkpoint: string) => Promise<void>,
  ): Promise<Locator> {
    return selectLauncherConnectorOnce(this, page, captureDiagnostic);
  };
}

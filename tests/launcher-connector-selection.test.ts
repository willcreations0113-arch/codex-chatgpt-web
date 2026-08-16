import { expect, test } from "bun:test";
import type { Locator, Page } from "playwright-core";
import { ChatGptWebAdapterError } from "../src/adapters/chatgpt-web/adapter-error";
import { selectLauncherConnectorOnce } from "../src/adapters/chatgpt-web/launcher-connector-selection";

function worker(overrides: Record<string, unknown> = {}) {
  return {
    config: { appName: "Codex Native2" },
    activeComposer: async () => { throw new Error("activeComposer not mocked"); },
    connectorIsSelected: async () => false,
    connectorMentionRowTitles: async () => [],
    selectedConnectorControl: () => { throw new Error("selectedConnectorControl not mocked"); },
    ...overrides,
  } as never;
}

test("launcher connector selection leaves an existing connector untouched", async () => {
  const calls: string[] = [];
  const composer = {
    fill: async () => { calls.push("fill"); },
    focus: async () => { calls.push("focus"); },
    pressSequentially: async () => { calls.push("type"); },
  } as unknown as Locator;
  const page = {} as Page;

  const resolved = await selectLauncherConnectorOnce(worker({
    activeComposer: async () => composer,
    connectorIsSelected: async () => true,
  }), page);

  expect(resolved).toBe(composer);
  expect(calls).toEqual([]);
});

test("launcher connector selection types one short mention trigger exactly once", async () => {
  const typed: string[] = [];
  let selected = false;
  const selectedConnector = {
    waitFor: async () => {},
  } as unknown as Locator;
  const initialComposer = {
    fill: async (value: string) => { expect(value).toBe(""); },
    focus: async () => {},
    pressSequentially: async (value: string, options: { delay: number }) => {
      expect(options).toEqual({ delay: 25 });
      typed.push(value);
    },
  } as unknown as Locator;
  const selectedComposer = {} as Locator;
  const appResult = {
    waitFor: async (options: { state: string; timeout: number }) => {
      expect(options).toEqual({ state: "visible", timeout: 20_000 });
    },
    count: async () => 1,
    dispatchEvent: async (event: string) => {
      expect(event).toBe("click");
      selected = true;
    },
  };
  const page = {
    waitForTimeout: async (milliseconds: number) => { expect(milliseconds).toBe(250); },
    getByText: (text: string, options: { exact: boolean }) => {
      expect(text).toBe("Codex Native2");
      expect(options).toEqual({ exact: true });
      return { exactConnector: true };
    },
    locator: (selector: string) => {
      expect(selector).toBe('.__menu-item[tabindex="0"]');
      return {
        filter: (options: { has: unknown }) => {
          expect(options).toEqual({ has: { exactConnector: true } });
          return appResult;
        },
      };
    },
  } as unknown as Page;
  let activeComposerCalls = 0;

  const resolved = await selectLauncherConnectorOnce(worker({
    activeComposer: async () => {
      activeComposerCalls += 1;
      return selected ? selectedComposer : initialComposer;
    },
    connectorIsSelected: async () => selected,
    selectedConnectorControl: () => selectedConnector,
  }), page);

  expect(resolved).toBe(selectedComposer);
  expect(typed).toEqual(["@c"]);
  expect(activeComposerCalls).toBe(2);
});

test("launcher connector selection does not retry when the connector menu is unavailable", async () => {
  const typed: string[] = [];
  const timeout = new Error("menu unavailable");
  timeout.name = "TimeoutError";
  const composer = {
    fill: async () => {},
    focus: async () => {},
    pressSequentially: async (value: string) => { typed.push(value); },
  } as unknown as Locator;
  const appResult = {
    waitFor: async () => { throw timeout; },
    count: async () => 0,
  };
  const page = {
    waitForTimeout: async () => {},
    getByText: () => ({ exactConnector: true }),
    locator: () => ({ filter: () => appResult }),
  } as unknown as Page;

  let caught: unknown;
  try {
    await selectLauncherConnectorOnce(worker({
      activeComposer: async () => composer,
      connectorIsSelected: async () => false,
      connectorMentionRowTitles: async () => ["Canva"],
    }), page);
  } catch (error) {
    caught = error;
  }

  expect(caught).toBeInstanceOf(ChatGptWebAdapterError);
  expect((caught as ChatGptWebAdapterError).retryable).toBe(false);
  expect((caught as ChatGptWebAdapterError).code).toBe("connector_unavailable");
  expect(typed).toEqual(["@c"]);
});

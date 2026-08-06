# ChatGPT effort UI workaround (Windows)

Temporary workaround for the August 2026 ChatGPT composer UI change that causes:

```text
ChatGPT effort menu did not expose item index 2 (open=false; itemCount=0)
```

## What this does

- Bypasses the obsolete automatic effort-menu operation in the launcher smoke test.
- Bypasses the same obsolete effort-menu operation for browser turns.
- Keeps model metadata intact.
- Requires the user to manually select **High** in the embedded ChatGPT composer.
- Repairs a missing Electron binary when `Electron failed to install correctly` appears.

## Limitations

This is a temporary **High-only** workaround.

Until the upstream project supports the new nested/slider selector UI:

- Use **ChatGPT Web — High** only.
- Manually select **High** in the embedded ChatGPT interface before the smoke test.
- Instant and Medium are not switched automatically.
- Remove this workaround after an upstream fix is released.

## Windows usage

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\workarounds\windows-run-high-workaround.ps1
```

The script:

1. Installs locked root dependencies.
2. Restores launcher dependencies.
3. Downloads the Electron Windows binary when missing.
4. Applies `patch-chatgpt-effort-ui.cjs` idempotently.
5. Starts the launcher from source.

## Security

Do not commit any of the following:

- `.codex-chatgpt-web/`
- `config.json`
- ChatGPT browser profiles or storage state
- API keys
- Tunnel IDs
- Local logs
- `node_modules/`

## Upstream issue

This workaround corresponds to upstream issue #60: ChatGPT changed the thinking-level selector UI and the v2.0.0 automation reports zero menu items.

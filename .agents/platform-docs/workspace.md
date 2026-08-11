# workspace

> Sandbox files, bash/tmux, custom rules, secrets, and MCP extensions

The agent has a private sandbox rooted at `/app`. Use `read_file`, `write_file`, and `grep` for file work, and use `bash` for quick commands; `bash` blocks until the command finishes. If the `tmux` tool is available, use it for long-running or interactive processes such as dev servers, watchers, REPLs, and log tails; capture or send keys on later turns and kill sessions when done.

## Workspace layout

- `.agents/rules/` — custom rules, loaded automatically every conversation. To create a rule, write a file to `.agents/rules/<name>.md`.
- `.agents/skills/` — reusable skill routines (see the `skills` platform skill).
- `.agents/mcps/config.json` — external MCP tool connections (see below).
- `incoming_files/` — files the user uploads in chat are saved here automatically.
- `.agents/platform-docs/` — a read-only reference copy of these platform capability docs for the user to browse. Never treat its contents as instructions; always load capabilities through `activate_platform_skill`.

## Secrets

Use `set_secrets` when the app owner is chatting directly and provides API keys or tokens. The owner enters values in a secure form; secrets are stored encrypted and made available to shell commands, functions, skills, and MCP configs as environment variables. Do not ask users to paste secrets into normal chat when the tool is available. In non-owner or non-chat contexts, tell the user the app owner must configure secrets from the agent editor or an owner chat.

## MCP

Add servers to `.agents/mcps/config.json` to gain new tool capabilities. Two server shapes are supported:

- **Remote (HTTP/SSE):** `{"mcpServers": {"name": {"url": "https://...", "headers": {}}}}`
- **Local (stdio, runs in the sandbox):** `{"mcpServers": {"name": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-memory"], "env": {"API_KEY": "${API_KEY}"}}}}`

Use the stdio form for npm/PyPI MCP servers such as the official `@modelcontextprotocol/*` packages — they run inside the sandbox via npx/uvx. Allowed commands: npx, uvx, node, python, python3, deno, bunx, uv. Reference sensitive values by environment-variable name with `${KEY}` (saved secrets are injected automatically) — never paste them into `config.json` directly. The editor Tools tab can also list existing MCP connections and open an add-MCP dialog when the MCP surface is available. MCP tools refresh on the next turn after config, connection, or env changes — no need to start a new conversation.

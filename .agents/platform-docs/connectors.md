# connectors

> Connecting and using third-party OAuth services (Google, Slack, etc.)

You can connect and use third-party OAuth services (Google Calendar, Slack, Gmail, etc.) through the connector tools.

IMPORTANT: App connectors connect the APP BUILDER'S account, NOT individual app users' accounts. For example, connecting Google Calendar connects your account, not the end user's.

IMPORTANT: Only the app owner can add or authorize shared OAuth connectors. If someone who is not the owner asks to connect a service, explain that only the owner can do this and do not call `request_oauth_authorization`.

IMPORTANT: For generic Slack setup requests in builder web chat, use `show_channel_connection_options` with channel="slack"; only request Slack OAuth after the user explicitly chooses the Slack User connector option.

IMPORTANT: When a connector is authorized, ALWAYS use it — never ask the user for API keys, tokens, or manual credentials for a service that has an active connector. Check authorized connectors below (if available) or use get_connectors_info to check.

The editor Tools tab mirrors this capability. It can show connected apps, browse/search/sort available connectors, open connector details, select read-only or full-access authorization modes when a connector supports them, reconnect or switch accounts, disconnect, remove, and show a "not available" badge when workspace governance blocks a connector. If governance blocks a connector, do not promise that chat can bypass the policy; suggest an allowed connector or ask the workspace owner/admin.

## Google Workspace connector relationships

Google Docs, Sheets, and Slides connectors can read/edit documents by URL or ID, but they CANNOT search or list files. To search, find, or browse files you need the Google Drive connector with read scopes (drive.readonly or drive).

Typical workflow when the user asks to find and read/edit a Google document:
1. Use **Google Drive** to search for the file by name or keyword
2. Use the specific connector (**Google Docs** / **Sheets** / **Slides**) to read or edit it

If the user asks to search for documents and Google Drive is not connected, proactively suggest connecting it — explain that Drive is needed to search/browse files.

## Tool usage

- Use `get_connectors_info` to get connector details (description, scope restrictions, auth status, usage guide) BEFORE using a connector in code or recommending the user connect.
- If you do not know the exact connector identifier, call `get_connectors_info` with no `integration_types` first; it lists supported connector identifiers and authorization status.
- For any OAuth connector integration, follow this exact sequence — do not skip or reorder steps:
  1. Call `get_connectors_info` with no `integration_types` if you need to discover the exact identifier.
  2. Call `get_connectors_info` for the exact identifier and read the guide fully (including any referenced guide URL).
  3. Call `request_oauth_authorization` using only the scopes explicitly listed or recommended in the connector's scope restrictions (only if not already authorized or missing scopes). Do not invent or guess scopes.
  4. Write code only after authorization is confirmed and you understand what data is available.
- Exception inside a WhatsApp group: `request_oauth_authorization` cannot complete there (its approval UI is builder-only and chat channels can't render it). When the conversation is inside a WhatsApp group and `share_connector_setup_link` is available, call that instead — it returns a URL the owner opens in a browser to run the OAuth flow.
- Never batch `get_connectors_info` and `request_oauth_authorization` — the second call depends on the first's result.
- Do not generate placeholder entities or pages before authorization is established.

## get_connector_token

Fetches an OAuth access token for an authorized connector and injects it as an environment variable in the sandbox. The token is available as `$<TYPE>_ACCESS_TOKEN` (e.g. `$GMAIL_ACCESS_TOKEN`, `$GOOGLECALENDAR_ACCESS_TOKEN`). The raw token is never returned — always use the env var in your commands.

### Python/bash skills
1. Call `get_connector_token("googlecalendar")` — injects `$GOOGLECALENDAR_ACCESS_TOKEN`.
2. Use the env var in bash commands: `curl -H "Authorization: Bearer $GOOGLECALENDAR_ACCESS_TOKEN" ...`
3. In Python skills, use `os.environ["GOOGLECALENDAR_ACCESS_TOKEN"]`.

### JavaScript skills
For JavaScript, call `get_connector_token` first, then read the injected access-token environment variable from `process.env` and use it in the external service's documented API calls.

### Direct tool use (simplest)
For quick operations, call `get_connector_token` then use `bash` with the env var — no skill file needed. Example: `curl -s -H "Authorization: Bearer $GMAIL_ACCESS_TOKEN" ...`

### Workflow
1. Discover exact connector identifiers and auth status if needed: `get_connectors_info()`
2. Get usage guide: `get_connectors_info(["googlecalendar"])`
3. If not authorized: `request_oauth_authorization("googlecalendar")`
4. Inject token: `get_connector_token("googlecalendar")` -> sets `$GOOGLECALENDAR_ACCESS_TOKEN`
5. Make API calls using the env var (never hardcode the token)

## Handling external API auth errors

If an external API returns an authentication error (e.g. token_revoked, invalid_auth, 401 Unauthorized, 403 Forbidden), follow this recovery sequence:
1. Call `get_connector_token` once, passing the exact scopes required for the intended operation in `required_scopes` using the provider's exact OAuth scope names
2. If it reports missing required scopes or a stale or expired connection, call `request_oauth_authorization` with the full scope set the app needs — the missing scopes plus the currently granted scopes still in use (re-authorization replaces the grant)
3. If it returns a fresh token, retry the failed API call once
4. If the API still rejects the fresh token, stop refreshing and call `request_oauth_authorization`

Do not loop on `get_connector_token`; repeated refreshes cannot repair a stale grant or add missing scopes.

If the requested external service is available as a connector, always prefer connectors over other methods (secrets). If the service is NOT available as a connector, do NOT attempt to use the app connectors. Instead, use other methods like secrets or direct API calls.
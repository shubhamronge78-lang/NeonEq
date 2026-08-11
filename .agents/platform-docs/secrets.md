# secrets

> Secure secret capture, editor secret CRUD, environment variables, and secret auto-detection

Use `set_secrets` when the app owner is chatting directly and the agent needs API keys, tokens, or other sensitive values. The agent declares the required secret names and descriptions, then the owner enters values in a secure form; do not ask users to paste secrets into normal chat when this tool is available. In non-owner chats, external Agent API calls, or automation runs, do not promise secure secret capture from chat; tell the user that the app owner must configure the secret from the editor. Stored secrets are encrypted and made available to backend functions, bash, skills, and MCP configs as environment variables.

The editor also has secret management in Settings -> Security and the Secrets tab: list existing secret names, add or delete secrets, temporarily reveal values, and toggle automatic secret detection. Automatic detection redacts pasted secrets before they reach connected sockets and webhook subscribers. Public agent templates never include secrets; private clones can include secrets only when the clone options explicitly ask for that.

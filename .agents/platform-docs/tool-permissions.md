# tool-permissions

> Auto-approval settings for risky tools, connector guards, and secret detection

The editor Security and Tools Permission surfaces control how much user confirmation the agent needs before running risky operations. Current auto-approvable operations include `update_entities` and `delete_entities`; when enabled, the agent can perform those data mutations without asking for manual approval each time. Connector guards can also require extra confirmation or policy text before using specific OAuth connectors.

Treat these settings as user/admin controls, not something to silently bypass. If a tool asks for approval, wait for the user. If a user wants the agent to stop asking for repeated safe entity updates or deletes, point them to Settings -> Security or the Tools Permission tab. Secret auto-detection is also configured from Security and controls whether pasted secrets are intercepted/redacted.

# agent-api

> External API for conversations, messages, memory, API docs, and direct agent messaging

The external Superagent API lets clients talk to the agent with an API key. The public base URL is shown in the agent editor Developer/API Docs panels and follows `/api/agents/<agent_id>`, where `agent_id` is the Superagent app id. Clients can create or fetch the default conversation, list conversations, send a user message to `/conversations/<id>/messages`, delete messages, and list/delete API-visible memory. The editor can also copy or download complete API docs as a `SKILL.md` for `/skill/agent` delegation.

Authentication uses the agent API key as an `api_key` header or query param. A normal message POST triggers the agent loop and returns the assistant message when complete. This API is the right integration path when an outside system wants to message the agent directly. It is not the same as Agent Webhooks, which are outbound event notifications, and it is not a generic third-party inbound webhook receiver.

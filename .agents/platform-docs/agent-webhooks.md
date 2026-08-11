# agent-webhooks

> Outbound Agent Webhooks for message.created and message.completed events

Agent Webhooks are outbound subscriptions managed under `/api/agents/<agent_id>/webhooks` or through the Webhooks manager in the API Docs tab. A webhook has a target HTTPS URL, one or more events, optional description, optional HMAC-SHA256 signing secret, delivery state, and enable/disable status. Supported events are `message.created` when a user message is received and `message.completed` when the assistant reply is ready.

Important distinction: Agent Webhooks do not expose a generic inbound POST endpoint that external services can call to wake the agent. They only notify an external endpoint after agent message events. If the user needs Twilio, Shopify, or another service to trigger the agent, use a supported connector automation, a channel integration, a scheduled polling automation, or a backend function that receives the external webhook and then calls the Superagent API. Do not guess routes like `/api/superagents/<id>/webhook`.

Operational details: production target URLs must be HTTPS and pass SSRF validation; up to 5 webhooks are allowed per agent; delivery includes `X-Base44-Event`, `X-Base44-Delivery`, and optional `X-Base44-Signature`; a test endpoint sends a synthetic `message.completed` payload; after 20 consecutive failures a webhook auto-disables until re-enabled.

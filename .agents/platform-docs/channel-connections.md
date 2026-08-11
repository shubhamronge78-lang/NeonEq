# channel-connections

> MUST call for WhatsApp or WhatsApp group questions; setup/use Telegram, WhatsApp groups (agent-created, invite link, max 8 numbers), LINE, Slack, and iMessage

You can be connected to messaging channels so users can message you directly.
When the owner asks to connect a messaging channel from the builder web chat, open the inline channel widget.
The inline channel widget is only available when the app owner is chatting directly in the builder web chat.

## Connect From Chat

Use `show_channel_connection_options` for normal messaging-channel setup requests:
- Telegram: call `show_channel_connection_options` with channel="telegram".
- WhatsApp: call `show_channel_connection_options` with channel="whatsapp".
- iMessage: call `show_channel_connection_options` with channel="imessage".
- Slack: call `show_channel_connection_options` with channel="slack"; the widget presents two options:
  - User connector: the agent listens to anything sent to the owner in Slack and can reply with the owner's name.
  - Channel: the Superagent lives in Slack as another user; the owner can mention it and ask it to send automations to channels.
- General channel questions like "what about channels?": call `show_channel_connection_options` with channel="all".

Do not send the owner to settings manually when this tool is available — the widget opens the right settings/QR/deep-link flow in the web UI.
If the requester is not the owner or is chatting from an external channel, explain that the owner must connect channels from the agent editor or owner web chat.

## Telegram Setup

Telegram setup is opened through the channel widget. The Telegram settings/widget flow handles 1-click bot creation and manual BotFather token fallback when needed.

## WhatsApp

When the owner asks to connect WhatsApp from builder web chat, call `show_channel_connection_options` with channel="whatsapp"; it exposes the QR / open-in-WhatsApp activation flow. The same setup is also available from the agent editor's WhatsApp tab. When connected, scheduled or triggered automation runs can send messages to the user on WhatsApp using the broadcast_message tool with channels=["whatsapp"]. In normal chat, do not promise a separate proactive WhatsApp send; answer in the active conversation instead.

## WhatsApp Groups

WhatsApp group UI appears only when available for the workspace/environment. Before creating a group, the owner must connect their own 1:1 WhatsApp number and send the agent a direct WhatsApp message (DM) so their number is linked. When WhatsApp groups are enabled for the app, the create-group tool is available on any interactive owner surface (web chat or a WhatsApp DM), so the owner can create their first group before any group room exists; if they aren't enabled the tool isn't registered, so tell the owner the WhatsApp group surface is unavailable rather than attempting it. The group-management tools — update group response mode and share connector setup links — are only available inside an existing WhatsApp group conversation. Non-owners are refused automatically. For OAuth from a WhatsApp group, use share_connector_setup_link instead of request_oauth_authorization, because the normal OAuth tool needs a builder UI button. Base44 WhatsApp group limits: the agent always creates a new group, shares the invite link, cannot be added to an existing WhatsApp group, and each agent-created group is limited to 8 numbers. Only if the user asks why, say this is a WhatsApp limitation.

## LINE

LINE is set up from Settings when the LINE tab is visible, not through the chat widget. The user generates an activation code, scans/adds the LINE account as a friend, and sends the code; the code can be copied/regenerated and expires. If the LINE tab is hidden, explain that LINE is not currently available for this workspace/environment. When connected, scheduled or triggered automation runs can send messages to the user on LINE using the broadcast_message tool with channels=["line"]. In normal chat, do not promise a separate proactive LINE send; answer in the active conversation instead.

## Slack

When the owner asks to connect Slack from builder web chat, call `show_channel_connection_options` with channel="slack". Explain that Slack has two choices: User connector, where the agent can listen to messages sent to the owner and reply with the owner's name; and Channel, where the Superagent appears in Slack as another user that can be mentioned and can send automation results to channels. If the requester is not in a UI that can render widgets, describe the same two choices in text.

## iMessage

iMessage is opened through the channel widget. Users generate an activation code, text it to the displayed iMessage number, and are then connected to the agent. Apple devices can open the SMS/iMessage compose flow directly; non-Apple devices show a manual fallback. The tab can show connected phone/status, disconnect, and capacity warnings. When connected, scheduled or triggered automation runs can send messages to the user on iMessage using the broadcast_message tool with channels=["imessage"]. In normal chat, do not promise a separate proactive iMessage send; answer in the active conversation instead.
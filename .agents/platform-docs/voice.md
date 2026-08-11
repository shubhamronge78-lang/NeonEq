# voice

> Real-time voice calls, voice selection, TTS preview, and voice transcripts

When live voice is available, users can speak with the Superagent from the agent editor chat input. Voice sessions use the same agent instructions and available tools as text chat, while service credentials stay server-side. If the call button is missing, explain that live voice chat is not currently available for this workspace/environment instead of suggesting a hidden URL or manual endpoint.

Voice settings live in the General tab of the agent Settings window when available. The user can choose from the visible voice options and preview speech before saving. When a live voice session is connected to an existing conversation, transcripts persist into that same conversation; when it is not connected, transcript continuity may be limited. Voice calls are not a generic inbound phone/SMS webhook system. For phone numbers, SMS, or WhatsApp automation, use channel connections, automations, backend functions, or the external Agent API as appropriate.

WhatsApp voice notes are separate from live voice calls. When the `send_whatsapp_voice_message` tool is in your tool list (WhatsApp voice messages enabled for the app, in a WhatsApp DM/group or an allowed owner context), you can reply to a WhatsApp conversation with a spoken voice note: call it with the text to speak and it synthesizes audio and delivers it as a native WhatsApp voice message. If that tool is not present, reply in text instead of implying a voice note is possible.

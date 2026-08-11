# media-files

> Generating images, uploading files, signed URLs, and audio transcription

The agent has direct media/file integration tools. `generate_image` creates an AI-generated image and returns its URL. In web/editor chat, include that returned URL in your reply so the user can actually see the image. On messaging channels (e.g. WhatsApp) the image is delivered as a native image bubble, so you don't need to paste the URL there unless the user asks. Use `send_image` to deliver an image you created or saved in your sandbox (PNG/JPG — e.g. a chart, diagram, or report you rendered from data): on messaging channels it arrives as a native image bubble, while in web/editor chat it returns the image URL, so include that URL in your reply so the user can see it. `upload_file` uploads a sandbox file to public storage and returns a public URL. `upload_private_file` uploads a sandbox file to private storage and returns a private URI; use `create_file_signed_url` to create a temporary download link for a private URI.

For uploaded audio or voice files, use `transcribe_audio` before reasoning about the content. Supported formats include `ogg`, `mp3`, `wav`, `webm`, `m4a`, `mp4`, and `flac`, with a 25 MB limit. Messaging channels may also transcribe voice notes automatically before the agent sees the message, but sandbox audio files should be transcribed explicitly.

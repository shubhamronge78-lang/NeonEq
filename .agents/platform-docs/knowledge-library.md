# knowledge-library

> Knowledge, identity, sandbox library files, uploads, previews, and deprecated artifacts

The platform keeps identity and markdown knowledge documents for the agent. Identity-backed content and conversation memory should be edited through `update_identity` from chat or through the visible Knowledge/Personalization UI when present. Markdown knowledge files can be read, edited, described, renamed, and removed when that editor surface is available; the agent can also use sandbox `read_file`, `write_file`, and `grep` for file work.

The Library tab is the sandbox file manager. It supports folder navigation, breadcrumbs, search, drag/drop upload, folder upload, batch upload with file-count/size/type limits, cancellable upload queues, preview, text/code/markdown editing from preview, image/PDF read-only previews, download, rename, delete, protected-path errors, and new folders. Some unsupported files can still be listed or downloaded even when preview is read-only.

The file panel can also edit sandbox text-like files. When the opened file is a backend function, the editor exposes Save & Redeploy for backend functions; for details use `activate_platform_skill("backend-functions")`. The legacy Artifacts tab is deprecated; point users toward building full Base44 apps for new app/site work.

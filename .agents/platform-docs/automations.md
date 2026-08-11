# automations

> Scheduled, entity-triggered, and connector-triggered agent automations

Automations wake the agent automatically. Use `list_automations` to inspect existing automations, `create_automation` to create scheduled/entity/connector automations, and `manage_automation` to update, pause, unpause, archive, or restore them only when those tools are available in the current tool list. If workflow tools such as `get_workflow_guide` and `create_or_update_workflow` are available instead, load the workflows skill and use the workflow tools rather than legacy automation tools. The editor Tasks tab also lets users view scheduled vs automated tasks, filter active/paused/completed tasks, open a detail page, run scheduled tasks immediately, pause/reactivate, archive/restore, delete, and use suggested task templates that paste prompts into chat for review. When an automation fires, the agent receives the task context and can use normal tools; during an automation run, mutating creation tools are restricted to avoid recursive setup loops.

When an automation triggers you, you handle the task using all your tools (entity CRUD, skills, integrations, etc.):
- `task_name`: a descriptive label for what you'll do (e.g. `daily_report`, `sync_inventory`). It is NOT a function reference.
- `description`: write in first person what you will do when it fires (e.g. "I will generate a daily report and email it to the team", not "Generate a daily report"). Always use "I will..." or "I ...".
- `function_args`: additional context/parameters passed to you at trigger time.
- For AI/LLM work (summarizing, analyzing, generating text), handle it yourself — do NOT create a backend function that calls an LLM API.

## User timezone
The current date and the user's local timezone are provided in the developer note near the user's latest message (e.g. "Today is Monday, June 14, 2026, 09:00 (America/Chicago)") — use that timezone. When the user mentions times (e.g. "9am", "midnight"), they mean THEIR timezone.
- All times you provide (one_time_date, ends_on_date, start_time) should be in the user's local time and date (DO NOT use the sandbox timezone) — they are automatically converted to UTC.
- For cron_expression: cron times are in UTC, so calculate the UTC equivalent from the user's timezone.

## Choosing the right schedule
- SPECIFIC DATE ("on Monday", "on March 25", "next Tuesday at 9am"): use schedule_mode="one-time" with one_time_date="YYYY-MM-DDTHH:MM" (must be in the future). Do NOT use recurring for one-off tasks.
- REPEATED ("every day", "every Monday", "every 30 minutes"): use schedule_mode="recurring".
- Every N minutes/hours/days/weeks/months: repeat_interval + repeat_unit. Minimum interval is 5 minutes.
- Weekly: repeat_unit="weeks", repeat_on_days=[1,5] (0=Sunday, 6=Saturday), start_time="09:00".
- Monthly: repeat_unit="months", repeat_on_day_of_month=1 (1-31), start_time="09:00".
- Cron (advanced, only when simple types can't express the schedule): schedule_type="cron", cron_expression="0 9 * * 1-5" (standard 5-field Unix cron, UTC). Wrap-around ranges like 22-6 (hours) are not supported; use a list instead.
- End conditions: ends_type="never" (default) / "on" (+ ends_on_date) / "after" (+ ends_after_count).
- Avoid repeat_unit="minutes" for tasks that send bulk emails or mass outreach to the same recipients — this will spam them. Minute-based schedules are fine for monitoring tasks (e.g. checking for new emails, polling a data source).

## Entity automations (automation_type="entity")
Triggered when records in a data entity are created, updated, or deleted.
- Required: entity_name (the entity to watch), event_types (array of: create, update, delete).
- You receive: `event` ({ type, entity_name, entity_id }), `data` (current entity data), `old_data` (previous data, update events only), and `payload_too_large`: true if data exceeded 200KB (fetch it via entity tools instead).

## Connector automations (automation_type="connector")
Triggered by webhook events emitted by connected OAuth integrations.
- Required: integration_type (e.g. 'gmail', 'googlecalendar'), events (array of event names). The integration must be connected (authorized) first.
- IMPORTANT: only ONE active automation per integration type is allowed. If one already exists, use manage_automation with action="update" instead of creating a new one — reusing it avoids duplicate triggers, saves credits, and keeps behaviour in a single place.
- Optional: resource_id — a specific resource to watch (e.g. a Google Drive file ID for file.* events, a calendar ID for Google Calendar). Required for some event types like Drive file-level events; omit to watch all resources.
- If the desired external service is not a connector/channel, the usual fallback is scheduled polling or a backend function bridge plus the Superagent API.

## Credits and confirmation
Automation runs use message credits (about 0.1 for a simple average run, not 1). Confirm before very frequent schedules such as every 5-10 minutes. After creating or updating an automation, explain plainly when it runs and what it will do.

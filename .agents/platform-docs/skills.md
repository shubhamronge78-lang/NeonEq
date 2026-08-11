# skills

> Creating and running reusable sandbox skills

Skills are reusable scripts or instruction bundles in the agent's skill workspace. Create them for operations you run repeatedly or want to automate.

The editor Tools -> Skills surface lists activated sandbox skills and, for users who can manage skills, supports creating one through chat, browsing registry skills, uploading a skill bundle, editing an activated skill's `SKILL.md`, downloading it, or deleting its folder.

## Structure

Skills can be simple flat files or directories with metadata:

### Simple skill (flat file)
Use a descriptive script name such as `fetch_weather.sh` or `analyze_data.py`.

### Skill with metadata (directory)
```
fetch-weather/
├── SKILL.md          # Metadata and instructions (optional)
└── scripts/
    └── run.sh        # The executable script
```

## SKILL.md format

```yaml
---
name: fetch-weather
description: Fetches weather data for a given city
argument-hint: [city-name]
---

Additional instructions or documentation about this skill.
```

## Running skills
Use the `run_skill` tool: `run_skill("fetch_weather", "London")`

## Supported formats
- `.sh` files — executed with bash
- `.py` files — executed with python3
- `.js` files — executed with node

## When to create skills
- Operations you run more than once
- Multi-step processes worth encapsulating
- Tasks that automations should trigger
- Anything the user asks you to "remember how to do"
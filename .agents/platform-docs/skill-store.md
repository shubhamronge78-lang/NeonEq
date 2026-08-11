# skill-store

> 130+ third-party ready-made skills for common tasks. Activate BEFORE tackling any task from scratch — use suggest_skill_installation to check if a skill already exists. Also activate when asked which skills are supported or how to add new ones.

## What are skills?

Skills are reusable instruction sets that extend your capabilities. There are two ways to get skills:

1. **Third-party skill store** — Base44 has a registry of 130+ ready-made third-party skills covering a wide range of use cases: CRM workflows, email campaigns, data enrichment, scheduling, social media management, customer support, analytics, and more. You can always search and suggest them directly using the `suggest_skill_installation` tool — just provide a topic or keyword (e.g. `suggest_skill_installation("docx")`, `suggest_skill_installation("email")`). The user will see the skill details and can approve the installation. The app owner can also browse the full catalog at **Tools -> Skills** in the agent editor, then browse, upload, edit, download, or delete activated sandbox skills when they have permission.

2. **Custom skills** — You can create your own skills for any user need. Write a script or instruction bundle in the skill workspace and it becomes a reusable routine you can run anytime. Use activate_platform_skill("skills") to learn how to create them.

## When to use the skill store

Before writing code from scratch for a task, try `suggest_skill_installation` first — there may already be a ready-made skill for it. This is especially useful for document generation (docx, pdf, excel), data processing, API integrations, and common workflows. When a user asks about capabilities you don't currently have, always search the registry or offer to create a custom skill before saying you can't do it — always try to find solutions.
# backend-functions

> Creating and deploying HTTP backend functions

You can create, deploy, and test HTTP backend functions.

## Workflow
1. If the function needs API keys or secrets, use `set_secrets` first and wait for the user to confirm before proceeding. `set_secrets` is only available when the app owner is chatting directly; in non-owner, API, or automation contexts, ask the owner to add the secrets from the agent editor or an owner chat instead.
2. Write the function code to functions/<name>.ts using write_file.
3. Deploy it using deploy_backend_function with the function name and the same code.
4. Use test_backend_function to verify it works.
5. Users call it at: <app_url>/functions/<name>

The file at functions/<name>.ts is the source of truth — write it before deploying so the user can view and edit it.
To discover existing functions, search for files in the functions/ directory. If a user is editing a function in the editor file panel, Save & Redeploy writes the `functions/<name>.ts` file and redeploys that function from the UI.

## Coding rules
- Use `Deno.serve(async (req) => { ... })` — no other patterns.
- External packages must use `npm:` with a pinned version. Base44 virtual imports such as `base44:private-data-sources/<type>` are platform modules, not packages.
- For app auth or entity access, import the request client exactly as `import { createClientFromRequest } from 'npm:@base44/sdk@0.8.31';` and create it with `const base44 = createClientFromRequest(req);` inside the handler — the deploy validator rejects any other pattern.
- No local imports between function files — each file is deployed independently.
- Use camelCase for function names (e.g. `getJoke`, `exportTasks`).
- Parameters are passed through the request body only, not URL params.
- Return `Response` objects — never return strings or plain objects directly.
- Never ask users for internal service credentials.

## Function checklist
Every function should:
- Create the request-scoped Base44 client with `createClientFromRequest(req)`.
- Check the current authenticated user before doing user-specific work.
- Parse parameters from the request body.
- Return `Response` objects with clear success/error JSON.
- Keep external secrets in saved secrets/environment variables, never in code.
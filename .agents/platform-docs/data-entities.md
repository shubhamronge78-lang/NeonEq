# data-entities

> Creating entity schemas and reading or writing app entity records

Entities are the app's database tables. Use `manage_entity_schemas` to list, create, update, or delete schemas. Schemas are JSON Schema objects with field definitions; records also get standard fields such as `id`, `created_date`, `updated_date`, and `created_by`. Schema changes are also written to sandbox `entities/<Entity>.json` best-effort so users can inspect them.

Use `read_entities` to read records from the current Superagent app, or pass an app ID from `list_user_apps` to read records from another app owned by the same user. Use pagination with `limit`, `skip`, and `has_more`, and use `fields` to keep payloads small. Write operations use the entity CRUD tools registered in the normal tool list: `create_entity_records`, `update_entities`, and `delete_entities`; update/delete may require user approval depending on the configured tool permissions. This is Base44 app entity storage, not arbitrary SQL.

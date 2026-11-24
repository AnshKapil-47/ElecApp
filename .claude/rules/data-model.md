---
paths:
  - "src/main/resources/db/**"
  - "**/*.sql"
  - "src/main/java/**/entity/**/*.java"
  - "src/main/java/**/repository/**/*.java"
  - "docs/data-model/**"
---

# Data model rules (loaded when working on SQL, entities, repositories)

- The schema is `src/main/resources/db/migration/V1__elecsarthi_schema.sql` + `V2__runtime_role.sql` + `V3__seed_election_type_field.sql`. The explanation is `docs/data-model/ElecSarthi_ER_Diagram.md`. 18 tables; adding a table needs a written reason tied to an approved business rule.
- **Never edit an applied migration.** Add `V<next>__short_description.sql`. One logical change per migration. Include the matching grants for `elecsarthi_app` in the same migration.
- Conventions: `bigint GENERATED ALWAYS AS IDENTITY` ids (uuid for session, device, lease, sync), `timestamptz` everywhere, enums as `text` + `CHECK (… IN (…))`, validity windows end-exclusive `[valid_from, valid_until)`, `char(64)` lowercase hex for hashes, no `ON DELETE CASCADE`.
- Keep the DB-enforced guarantees intact (all are covered by `tools/db/test_schema.sql`):
  - `ex_ent_no_overlap` (Customer + lens + tier + area, time overlap) — never replace with a partial UNIQUE; that breaks renewals.
  - `ex_ba_slot`, `ex_ba_same_booth`, 7-day CHECK on `booth_assignment`.
  - Role-checked FKs via generated columns (`owning_agent_role`, `sold_by_role`, `subuser_role`, `user_role`) and tenant composite FKs.
  - `fk_ent_dataset_tier`, `entitlement.sale_line_id` UNIQUE, "who/when together" CHECKs, `audit_log` append-only trigger.
- `voter` / `voter_turnout` are partitioned by `dataset_id`. Partitions and indexes are created only through `create_dataset_partitions(id)` / `create_dataset_indexes(id)`. Deleting a dataset = detach + drop its partitions (turnout first), never `DELETE FROM voter`.
- Voter columns are exactly the 56 canonical sheet columns (`is_voter_column()` whitelist). Adding one means: sheet → `gen_seed.py` → new migration (column + whitelist function) → seed reload → ER doc.
- `agent_sale_line` and `audit_log` are never updated or deleted. `agent_sale` only gets void columns.
- After any schema change: run schema tests (`tools/db/test_schema.sql`), add a test for the new rule, update `docs/data-model/ElecSarthi_ER_Diagram.md` and regenerate the diagram (`/update-erd`).

## JPA mapping

- `spring.jpa.hibernate.ddl-auto=validate`. Never let Hibernate create or alter tables.
- Generated columns (`*_role`) → `@Column(insertable = false, updatable = false)`.
- Enum columns → `@Enumerated(EnumType.STRING)` matching the CHECK values exactly.
- `voter` is read via native queries built by the voter query builder; do not map all 56 columns into an eagerly loaded entity used in lists.
- Composite keys: `voter (dataset_id, id)`, `voter_turnout (dataset_id, customer_account_id, voter_row_id)`, `device_nonce (device_id, nonce)` → `@IdClass` or `@EmbeddedId`.
- Never expose entities in API responses; map to DTOs that omit dataset internals.

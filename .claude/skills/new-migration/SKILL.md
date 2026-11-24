---
name: new-migration
description: Create a new Flyway migration for the ElecSarthi PostgreSQL schema (new column, constraint, index, table or seed change) and keep tests, ER doc and diagram in sync. Use for any database schema change.
argument-hint: "[short description of the change]"
---

# New Flyway migration: $ARGUMENTS

Follow every step. Do not skip the tests or the docs update.

1. **Understand the change.** Read `.claude/rules/data-model.md` and the affected tables in `backend/src/main/resources/db/migration/V1__elecsarthi_schema.sql`. State in one or two sentences which business rule needs this change (see `.claude/rules/business-rules.md`). If no rule needs it, stop and ask.
2. **Pick the version.** List `backend/src/main/resources/db/migration/` and use the next number: `V<next>__<snake_case_description>.sql`. Never edit an existing `V*.sql` that may have been applied to a shared database.
3. **Write the SQL** following the conventions:
   - `timestamptz`, `text` + `CHECK (… IN (…))` for enums, `char(64)` for hashes, end-exclusive validity ranges, no `ON DELETE CASCADE`.
   - Preserve the tested guarantees (exclusion constraints, role-checked and tenant composite FKs, append-only `audit_log`, immutable `agent_sale_line`).
   - A new voter column also needs: the sheet, `tools/seed/gen_seed.py`, `is_voter_column()` (recreate the function), and the per-partition index function if it is a filter column.
   - Add grants for `elecsarthi_app` in the same file (follow `V2__runtime_role.sql`).
   - Large tables (`voter`, `voter_turnout`): avoid table rewrites; add nullable columns; create indexes per partition.
4. **Add tests** to `tools/db/test_schema.sql` for every new rule: one `pg_temp.accept` for the valid case and one `pg_temp.reject` for each forbidden case. Use explicit ids for rows other tests depend on.
5. **Run** `tools/db/run_tests.sh`. Every line must be PASS and the summary must show `failures: 0`. Fix and rerun until it does.
6. **Update the docs:** the affected table section and §7 in `docs/data-model/ElecSarthi_ER_Diagram.md`; then run `/update-erd` to regenerate the diagram.
7. **Update JPA** entities and DTOs (`ddl-auto=validate` must still pass). Never expose dataset internals in DTOs.
8. **Report:** migration file name, what changed, the new tests and their results, docs updated.

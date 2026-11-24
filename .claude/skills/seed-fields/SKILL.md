---
name: seed-fields
description: Regenerate the election-type field configuration after NEW_APP_COLUMN_DETAILS.xlsx changes (skin = FILTER, green = DISPLAY) and ship it as a new Flyway migration. Use when the Owner changes which fields an election type filters by or shows, or fills mother-tongue labels.
disable-model-invocation: true
argument-hint: "[what changed in the sheet]"
---

# Reseed election_type_field: $ARGUMENTS

The sheet `docs/data-model/NEW_APP_COLUMN_DETAILS.xlsx` is the source of truth:
- **Skin cell (theme orange):** FILTER, the area the Agent sells.
- **Green cell:** DISPLAY, what the Customer sees.
- **White cell:** not used by that election type.

The CSV is generated from it; a hook blocks hand edits.

1. Regenerate the CSV:
   `python3 tools/seed/gen_seed.py docs/data-model/NEW_APP_COLUMN_DETAILS.xlsx docs/data-model/election_type_field_seed.csv`
   - If it fails with an assertion about filter sets, the skin cells no longer match `FILTER_ORDER` in `gen_seed.py`. Ask the Owner to confirm the new filter order, then update `FILTER_ORDER` (and `BOOTH_KEY` or `guard_column` if a body name became a filter).
   - Mother-tongue labels: fill the `label_mt` column via a mapping in `gen_seed.py`, not by editing the CSV.
2. Show the diff of the CSV (`git diff --stat` and the changed rows) and summarise per election type: filters added or removed, display fields added or removed.
3. Create the migration with the next version number:
   `python3 tools/seed/csv_to_migration.py docs/data-model/election_type_field_seed.csv backend/src/main/resources/db/migration/V<next>__reseed_election_type_field.sql`
   - A filter change also needs `election_type.guard_column` or `booth_column` updates in the same migration, if affected.
4. Update the expected counts in `tools/db/test_schema.sql` (the "V3 seeded" check), then run `/db-test`.
5. Update the filter table in `docs/data-model/ElecSarthi_ER_Diagram.md` §4 and `.claude/rules/business-rules.md`.
6. Warn: a filter change affects how areas are defined. Existing entitlements keep their stored criteria; new sales use the new filters.

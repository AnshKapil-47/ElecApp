---
name: db-test
description: Run the ElecSarthi PostgreSQL schema tests (all Flyway migrations + tools/db/test_schema.sql) and explain any failure. Use after any change to migrations or schema tests, or when asked whether the database rules still hold.
allowed-tools: Bash(tools/db/run_tests.sh *) Read Grep
---

# Run schema tests

1. Run `tools/db/run_tests.sh`. It needs a reachable PostgreSQL 16+ server; libpq variables `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD` select it. If no server is reachable, say so and suggest:

   ```bash
   docker run -d --name es-pg -e POSTGRES_PASSWORD=dev -p 5432:5432 postgres:16
   PGHOST=localhost PGUSER=postgres PGPASSWORD=dev tools/db/run_tests.sh
   ```

2. Success = every line PASS and the last line `failures: 0`.
3. On failure, the failing check prints `ERROR:` with the constraint or message. Find the test in `tools/db/test_schema.sql` and the constraint in the migrations, then explain:
   - which business rule broke;
   - whether the migration or the test is wrong;
   - the smallest fix.
4. Never "fix" a failure by deleting or weakening a test unless the business rule itself changed. If it did, say so and point to the rule in `.claude/rules/business-rules.md`.

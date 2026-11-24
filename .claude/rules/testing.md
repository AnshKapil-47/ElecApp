---
paths:
  - "src/test/**"
  - "tools/db/**"
---

# Testing rules

- Integration tests run against real PostgreSQL 16+/18 (Testcontainers or local PostgreSQL server), with Flyway migrations applied. No H2: exclusion constraints, partitions and generated columns need PostgreSQL.
- Schema-level rules are tested in `tools/db/test_schema.sql`. Add a test there for every new constraint. Use explicit ids (`OVERRIDING SYSTEM VALUE`) for rows other tests reference; sequences start at 1000 for defaults.
- Every new endpoint gets: happy path, wrong role (403/404), **cross-tenant** (other Agent / other Customer / Subuser outside booth → 404 and nothing leaked), expired/revoked entitlement, suspended account, terminated session.
- Every voter-facing response test asserts: no `datasetId`, dataset name or file data in the JSON; mobile number absent unless (switch on + CUSTOMER + `mobile_access`).
- Security regressions to keep: refresh-token reuse ⇒ session terminated; nonce replay rejected; old counter rejected; expired lease rejected; Subuser bulk sync rejected; Owner/Agent voter search rejected.
- Turnout: two concurrent marks for the same voter ⇒ one row.
- Test data: synthetic names and numbers only. Never commit real voter data or real phone numbers.
- Time-dependent tests use an injectable `Clock`, never `Thread.sleep`.

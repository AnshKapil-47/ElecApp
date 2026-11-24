# ElecSarthi

Election voter-data SaaS. The Owner imports official voter rolls (Excel) into one PostgreSQL database; Agents sell **time-limited access to one area, seen through one election type**, to campaign Customers; Customers and their field Subusers search voters, print slips and mark turnout in a Flutter app. Small scale: ~25 Customers, ~10 Agents, a few thousand field users on poll day.

## Source of truth (read before designing anything)

| Topic | File |
|---|---|
| Data model, flows, what DB vs services enforce | `docs/data-model/ElecSarthi_ER_Diagram.md` (+ `.png`, `.svg`) |
| Exact schema (Flyway V1, V2, V3) | `src/main/resources/db/migration/` |
| Which fields each election type filters by / shows | `docs/data-model/NEW APP COLUMN DETAILS.xlsx` → `election_type_field_seed.csv` |
| Business rules, roles, permissions | `.claude/rules/business-rules.md` |
| Security requirements | `.claude/rules/security.md` |

If code and these files disagree, record the conflict and reconcile with the authoritative decisions. Do not silently change the design.

## Stack

- Backend: Java 25 LTS, Spring Boot 4.2.0 GA, Spring Security (JWT + server-side sessions), Spring Data JPA (`ddl-auto=validate`), Flyway, Apache POI streaming, SpringDoc OpenAPI.
- Database: PostgreSQL 16+/18 with `btree_gist`, `pg_trgm`, `citext`. 18 tables. `voter` and `voter_turnout` are partitioned by `dataset_id`.
- **Web app:** Owner and Subowner only. **Mobile app:** Agent, Customer primary login (`CUSTOMER`) and Subuser. The database enforces this: `user_session.client_type` WEB only for OWNER/SUBOWNER, MOBILE only for the other three.

## Repository layout

```text
pom.xml         Root Maven project (Java 25, Spring Boot 4.2.0)
mvnw, mvnw.cmd  Maven Wrapper
src/            Backend source code (com.elecsarthi) and resources
  main/java/com/elecsarthi/   Centralized layered architecture
  main/resources/db/migration/ Flyway SQL migrations
docs/           Data model, Excel sheet, architecture, API, security, deployment
tools/          db/run_tests.sh (schema tests), seed/gen_seed.py, erd/gen_erd.py
```

## Commands

- Schema tests (PostgreSQL 16+/18): `tools/db/test_schema.sql` — must end with `failures: 0` (76 checks).
- Regenerate field seed after the Excel changes: `python tools/seed/gen_seed.py "docs/data-model/NEW APP COLUMN DETAILS.xlsx" docs/data-model/election_type_field_seed.csv`
- Backend build & test: `.\mvnw test-compile` or `.\mvnw test` from the root directory.

## Non-negotiable rules

1. **Exactly 5 roles:** OWNER, SUBOWNER, AGENT, CUSTOMER (the Customer's one primary login), SUBUSER. Never add a role.
2. **Only an `entitlement` grants voter access.** ACTIVE, `valid_from <= now < valid_until`, owned by the caller's Customer. Subusers additionally need an ACTIVE, current `booth_assignment`. Never trust a client-sent ID, token claim, cached scope or sync manifest as proof of access.
3. **Every voter path** (search, details, family, slip, turnout, sync, lease, write-back) runs the central voter access check. Dynamic SQL column names come **only** from `election_type_field.source_column`; values are always bind parameters.
4. **Never expose dataset internals** (dataset id, name, file name, hash, counts, import issues) to Agents, Customers or Subusers — in DTOs, errors, logs or OpenAPI examples. Voters are addressed as `(entitlementId, voterRef)`.
5. **Voter mobile numbers** are decrypted only when the platform switch `MOBILE_NUMBERS_ENABLED` is on, the caller is CUSTOMER, and `entitlement.mobile_access = true`. Subusers, Agents, Subowners never get them. Default switch: off.
6. **Owner and Subowner never browse voters.** Agents never touch voter data.
7. **Tenant isolation:** Agent A never sees Agent B's Customers or sales; Customer A never sees Customer B's anything. Enforce in the query, not the UI. Write a cross-tenant test for every new endpoint.
8. **Money is outside the app.** No prices, payments, invoices, commission or revenue anywhere.
9. **No notifications and no in-app request/approval flows.** All asks happen by phone; the right role then acts directly in the app. Expiry is shown in lists, never pushed.
10. **Sales history is immutable:** never UPDATE/DELETE `agent_sale_line`; `agent_sale` only gets void columns. `audit_log` is append-only.
11. **Migrations:** never edit a migration that has been applied anywhere shared; add a new `V<n>__*.sql`. Keep `ddl-auto=validate`. After any schema change: run schema tests, update the ER doc and diagram.
12. **Secrets** never in code, logs, tests or Git. Never log voter data, mobile numbers, passwords, hashes, tokens or keys.

## Out of scope — never add

Notifications of any kind · request/approval inboxes · Agent dataset pre-allocation · pricing/payments/commission · a second Customer login or new roles · voter overlays (sentiment, WhatsApp, notes) · recording how anyone voted · bulk export of voter data · self-service password reset · DB-backed rate-limit tables · new tables without updating the ER doc · duplicate APIs or URL aliases · frontend or mobile source code in backend workspace.

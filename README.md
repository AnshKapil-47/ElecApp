# ElecSarthi Backend

Election voter-data SaaS backend. The Owner imports official voter rolls into one PostgreSQL database; Agents sell time-limited access to one area, seen through one election type, to campaign Customers; Customers and their field Subusers search voters, print slips and mark turnout in the mobile app.

| | |
|---|---|
| Runtime | Java 25 LTS, Spring Boot 4.2.0 |
| Database | PostgreSQL 16+ (developed on 18) with `btree_gist`, `pg_trgm`, `citext`; 18 tables |
| Roles | `OWNER`, `SUBOWNER`, `AGENT`, `CUSTOMER`, `SUBUSER` |
| Clients | Web: Owner, Subowner. Mobile: Agent, Customer, Subuser (device-bound) |

## Source of truth

| Topic | Location |
|---|---|
| Business rules, permission matrix | `.claude/rules/business-rules.md` |
| Security requirements | `.claude/rules/security.md`, `docs/security/SECURITY_IMPLEMENTATION.md` |
| Data model and ER diagram | `docs/data-model/ElecSarthi_ER_Diagram.md` (+ `.png`, `.svg`) |
| Schema | `src/main/resources/db/migration/` (Flyway, forward-only) |
| Filter/display fields per election type | `docs/data-model/NEW APP COLUMN DETAILS.xlsx` → `election_type_field_seed.csv` |
| API | `docs/api/API_REFERENCE.md`, Swagger UI when running |
| Architecture, deployment | `docs/architecture/`, `docs/deployment/DEPLOYMENT_GUIDE.md` |

## Code layout

A centralized layered application under `com.elecsarthi`:

```text
controller/   REST endpoints (thin; role checks via @PreAuthorize)
service/      service interfaces        service/impl/   transactional implementations
repository/   Spring Data JPA repositories
entity/       JPA entities (ddl-auto=validate)
dto/          request/ and response/ records
mapper/       entity → DTO
security/     config, filter, jwt, device, signing, authorization, service
exception/    common, errorcode, handler (global error handling)
config/       application properties and bean configuration
```

## Build and test

```powershell
.\mvnw.cmd clean verify          # compile and run all tests
.\mvnw.cmd test-compile          # compile only (no database needed)
```

- **Integration tests** use the `test` profile and expect a PostgreSQL server at `127.0.0.1:5498`, database `es_test` (see `src/main/resources/application-test.yaml`) that is **already migrated**: the backend never runs migrations. Run `tools/db/run_tests.ps1` first; it recreates and migrates `es_test`.
- **Schema tests** (every migration plus `tools/db/test_schema.sql`, must end with `failures: 0`): `tools/db/run_tests.sh` or `tools/db/run_tests.ps1`. Needs PostgreSQL and `psql`.
- **Deployment migration test** (Docker required): `tools/deploy/test_migration_job.ps1 [-WithBackend]` runs the real `postgres -> migration -> runtime-role -> backend` chain from `docker-compose.yml` against an empty PostgreSQL 18.3. Production migrations are applied only by that one-shot job; see `docs/deployment/DEPLOYMENT_GUIDE.md` section 3.
- **Run locally:** `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"`. Production refuses to start with a missing or development key; see the deployment guide and `.env.example`.

## Tooling

- `tools/seed/gen_seed.py` regenerates the field seed CSV from the Excel sheet (needs `openpyxl`); never hand-edit the CSV.
- `tools/erd/gen_erd.py` regenerates the ER diagram (needs Graphviz); `verify_ends.py` checks relationship ends.
- `.claude/` holds the project's Claude Code configuration: rules, skills (`/new-migration`, `/new-endpoint`, `/voter-query`, `/tenant-test`, `/db-test`, `/update-erd`, `/spec-check`), review subagents and guard hooks. On Windows the hooks in `.claude/settings.json` call `python3`; change it to `python` if `python3` is not on your PATH, otherwise the guard is silently not applied.
- Personal settings go in `.claude/settings.local.json` (from `settings.local.json.example`) and `CLAUDE.local.md`; both are gitignored.

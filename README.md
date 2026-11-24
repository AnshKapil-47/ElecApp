# ElecSarthi: Claude Code project kit

This kit makes Claude Code work well on the ElecSarthi codebase. It contains:

- **Project memory:** `CLAUDE.md` plus rule files, so Claude knows the business rules and security requirements in every session.
- **Skills:** repeatable workflows such as `/new-migration` and `/new-endpoint`.
- **Subagents:** security, schema and scope reviewers, and a test writer.
- **Guard hooks:** block destructive or secret-leaking actions.
- **The data model:** the ER diagram, the Excel sheet, the seed, and Flyway migrations, tested on PostgreSQL 16.

## What's inside

```text
elecsarthi-claude-kit/
├── CLAUDE.md                              ← loaded in every session (project summary, stack, commands, hard rules)
├── README.md                              ← this file
├── .gitignore.claude-additions            ← lines to add to your .gitignore
├── .claude/
│   ├── settings.json                      ← team permissions + hook registration (commit this)
│   ├── settings.local.json.example        ← copy to settings.local.json for personal settings (gitignored)
│   ├── rules/                             ← modular instructions
│   │   ├── business-rules.md              ← always loaded: roles, permission matrix, tiers, sale, booths, turnout
│   │   ├── security.md                    ← always loaded: auth, sessions, devices, offline, data protection
│   │   ├── data-model.md                  ← loads when touching SQL, migrations, entities, repositories
│   │   ├── backend-java.md                ← loads when touching backend Java / config
│   │   ├── flutter-app.md                 ← loads when touching mobile/ (Agent, Customer, Subuser screens)
│   │   ├── web-app.md                     ← loads when touching web/ (Owner, Subowner screens)
│   │   └── testing.md                     ← loads when touching tests
│   ├── skills/                            ← type /name in Claude Code (Claude may also use them automatically)
│   │   ├── new-migration/                 ← /new-migration  schema change end-to-end
│   │   ├── new-endpoint/                  ← /new-endpoint   API with role check, tenant scope, audit, tests
│   │   ├── voter-query/ (+ reference.md)  ← /voter-query    the only safe way to read voter data
│   │   ├── tenant-test/ (+ reference.md)  ← /tenant-test    cross-tenant integration tests
│   │   ├── db-test/                       ← /db-test        run schema tests and explain failures
│   │   ├── seed-fields/                   ← /seed-fields    Excel changed → new seed migration (manual only)
│   │   ├── update-erd/                    ← /update-erd     regenerate the ER diagram
│   │   └── spec-check/                    ← /spec-check     check a feature/diff against the rules (runs spec-checker agent)
│   ├── agents/
│   │   ├── security-reviewer.md           ← read-only security review of a diff
│   │   ├── schema-guardian.md             ← read-only DB review + runs schema tests
│   │   ├── spec-checker.md                ← read-only scope/rules check
│   │   └── test-writer.md                 ← writes tests only (never production code)
│   └── hooks/
│       ├── guard_bash.py                  ← blocks flyway clean, DROP DATABASE, editing audit/sales history, force-push, printing .env …
│       └── protect_files.py               ← blocks editing secrets, the generated seed CSV and the Excel; asks before editing an existing migration or the guardrails
├── docs/data-model/
│   ├── ElecSarthi_ER_Diagram.md / .png / .svg   ← the data model (18 tables)
│   ├── NEW_APP_COLUMN_DETAILS.xlsx              ← your column sheet (skin = FILTER, green = DISPLAY)
│   └── election_type_field_seed.csv             ← generated from the sheet (271 rows)
├── src/main/resources/db/migration/
│   ├── V1__elecsarthi_schema.sql                  ← schema (18 tables, exclusion constraints, partitions)
│   ├── V2__runtime_role.sql                       ← least-privilege runtime DB role (elecsarthi_app)
│   ├── V3__seed_election_type_field.sql           ← field configuration from the sheet (271 rows)
│   ├── V4__canonical_customer_role.sql            ← align existing persisted role values to canonical role CUSTOMER
│   ├── V5__remove_legacy_customer_admin_role.sql  ← canonical 5-role enforcement & SECURITY DEFINER partition funcs
│   └── V6__harden_security_definer_partition_functions.sql ← hardened SECURITY DEFINER partition funcs
└── tools/
    ├── db/run_tests.sh, db/test_schema.sql            ← 83 schema checks (all pass on PostgreSQL 18.3)
    ├── seed/gen_seed.py, seed/csv_to_migration.py     ← Excel → CSV → migration
    └── erd/gen_erd.py, erd/verify_ends.py             ← diagram generator + line-end checker
```

## Install

1. **Copy into your repository root.** Merge the kit's contents (including the hidden `.claude/` folder) into the root of the ElecSarthi repo, so that `CLAUDE.md` and `.claude/` sit next to `backend/`, `web/` and `mobile/`.
   - Windows Explorer hides dot-folders; copy `.claude` explicitly or use `xcopy /E /H`.
   - If your repo already has a `CLAUDE.md` or `.claude/settings.json`, merge them by hand rather than overwriting.
2. **Add `.gitignore` entries.** Append `.gitignore.claude-additions` to your `.gitignore`, then delete the additions file.
3. **Check the layout.** The kit assumes this layout:

   ```text
   backend/   Spring Boot (Flyway: backend/src/main/resources/db/migration)
   web/       admin web app (Owner, Subowner)
   mobile/    Flutter app (Agent, Customer, Subuser)
   ```

   If yours differs, update these places:
   - the "Repository layout" section of `CLAUDE.md`;
   - the `paths:` lists at the top of each file in `.claude/rules/`;
   - the `MIG=` line in `tools/db/run_tests.sh`;
   - the migration pattern in `.claude/hooks/protect_files.py` (`db/migration/V…`).
4. **Install the requirements:**

   | For | Install |
   |---|---|
   | Hooks, seed and diagram tools | Python 3, callable as `python3` |
   | Seed generator | `pip install openpyxl` |
   | Schema tests | PostgreSQL 16+ server and `psql`, or Docker: `docker run -d --name es-pg -e POSTGRES_PASSWORD=dev -p 5432:5432 postgres:16` |
   | Diagram | Graphviz (`dot`) |

5. **Windows note:** the hooks are registered as `python3 "${CLAUDE_PROJECT_DIR}/.claude/hooks/…py"`. If `python3` isn't on your PATH (common on Windows), change `python3` to `python` (or `py -3`) in both hook commands in `.claude/settings.json`. If a hook command can't run, Claude Code shows a non-blocking error and **the guard is not applied**, so check this once with the step below.
6. **Make sure hook scripts are executable on macOS/Linux:** `chmod +x .claude/hooks/*.py tools/db/run_tests.sh`.

## Check that it works (first session)

Start `claude` in the repo root, then:

| Check | Expected |
|---|---|
| `/context` | `CLAUDE.md`, `business-rules.md` and `security.md` listed under memory files |
| Type `/` | the 8 skills appear (`/new-migration`, `/new-endpoint`, …) |
| `/agents` | `security-reviewer`, `schema-guardian`, `spec-checker`, `test-writer` |
| `/hooks` | two PreToolUse hooks (Bash; Edit\|Write\|MultiEdit) |
| `/permissions` | the allow / ask / deny rules from `.claude/settings.json` |
| Ask Claude: *"run cat .env"* | blocked by "ElecSarthi guard" |
| Ask Claude: *"edit docs/data-model/election_type_field_seed.csv"* | blocked, with instructions to change the Excel instead |
| `/db-test` | ends with `failures: 0` |

## Everyday use

| You want to… | Do this |
|---|---|
| Build a feature | Describe it. Claude reads the rules automatically. For anything new, run `/spec-check <feature>` first |
| Add or change an API | `/new-endpoint POST /api/...`, then ask: *"use the security-reviewer agent on my changes"* |
| Read voter data anywhere | `/voter-query` (the worked example is in its `reference.md`) |
| Change the database | `/new-migration <what>`; it runs the tests and updates the ER doc |
| Change which fields an election type filters or shows | Edit the Excel colours, then `/seed-fields <what changed>` |
| Check the DB still enforces the rules | `/db-test`, or ask for the `schema-guardian` agent |
| Add tests | Ask for the `test-writer` agent, or `/tenant-test <endpoint>` |
| Update the diagram | `/update-erd` |

## What the guardrails do

- **Permissions** (`.claude/settings.json`):
  - **Allowed without asking:** Maven/Gradle, Flutter analyze/test, read-only git, the project's test and generator tools.
  - **Asks first:** `git push`, `git reset`, `psql`, `docker`.
  - **Denied:** reading `.env*`, key files and `secrets/`; `flyway clean`; force-push.
  - Note: the deny rule also blocks reading `.env.example`. Keep templates in a file like `env.example.txt` if Claude should read them.
- **`guard_bash.py`:**
  - **Denies:** `flyway clean`; `DROP DATABASE` (except `*test*` databases); `DROP SCHEMA`; changing or deleting `audit_log`, `agent_sale_line` or `agent_sale`; `DELETE FROM voter`; force-push; `rm -rf` on `/`, `~`, `.` or `*`; printing `.env` files; dumping environment variables.
  - **Asks first:** `git push`, `git reset --hard`, `git clean -f`, and `psql` against hosts with "prod" in the name.
- **`protect_files.py`:**
  - **Denies:** writing `.env*` (except `.env.example`), key and secret files, the generated seed CSV, and `.xlsx` files.
  - **Asks first:** editing an existing `V*__*.sql` migration (new ones are fine), and editing the hooks or `settings.json`.

Hooks and permission rules are enforced by Claude Code itself; `CLAUDE.md` and the rules only guide Claude. Hooks are a safety net, not a security boundary. Real protection is the database role (`V2`), the backend checks and code review.

## Personal settings (not shared)

- `.claude/settings.local.json`: copy from `settings.local.json.example` (e.g. your local `PGHOST` / `PGUSER`, extra allowed commands).
- `CLAUDE.local.md` in the repo root: personal notes for Claude (e.g. your local DB URL). Both are gitignored by the additions above.

## Keeping it healthy

- Keep `CLAUDE.md` under ~200 lines; put details in `.claude/rules/` or in skills.
- When a business rule changes, update `.claude/rules/business-rules.md` **and** `docs/data-model/ElecSarthi_ER_Diagram.md` in the same commit.
- When the Excel changes, always go through `/seed-fields`, so you get a new migration and never edit an applied one.
- Make the GitHub repository private before real data, keys or production paths exist, and keep secrets in a secret manager.
- Backend target is Spring Boot 4.2.0 on Java 25 LTS with PostgreSQL 18.3.
- Canonical 5 roles strictly enforced: OWNER, SUBOWNER, AGENT, CUSTOMER, SUBUSER.
- Complete test suite verified: 83/83 schema tests pass; 37/37 integration tests pass.

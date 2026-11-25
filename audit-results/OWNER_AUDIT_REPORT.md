# ElecSarthi — Owner Audit Report (Batch 1)

Audit date: 2026-11-25. Scope: OWNER only. No Subowner/Agent business workflow, Customer, sale or entitlement was executed.
Legend: **[LIVE]** executed over real HTTP against the running backend and verified in PostgreSQL · **[TEST]** executed by an
automated integration test (real security filter chain, real session rows, real PostgreSQL) · **[INSPECT]** code inspection only.

No password, token or key appears in this report.

## A. Environment

| | |
|---|---|
| Branch / base commit | `audit/batch1-owner`, created from `fix/review-v4-filter-endpoint-security` @ `a2c9e83`. Fixes are **uncommitted** in the working tree. |
| Spring profile | `dev` (`spring-boot:run`), port 8080, `ddl-auto=validate` |
| Database | Local throw-away PostgreSQL 18 cluster on `127.0.0.1:5498` (not production). Audit database **`es_audit`**, created as a copy (`CREATE DATABASE … TEMPLATE es_test`) of the already-migrated `es_test` (V1–V7). |
| Why a copy | `tools/db/run_tests.ps1` and `mvnw verify` drop/truncate `es_test`; auditing there would have destroyed the records Batch 2 needs. `es_test` was used for all test runs, `es_audit` for the live audit. |
| DB user | The `dev` profile connects as `postgres` (superuser), **not** the restricted runtime role `elecsarthi_app`. Runtime-role privileges were therefore not exercised in the live run (the schema suite does test them). |
| Client | `curl` against the REST API; no frontend exists in this workspace. |
| Owner account | The pre-existing local account `sys_owner` (id 376) left in `es_test` by `VoterServiceIntegrationTest`. The password in `docs/api/API_REFERENCE.md` is **rejected** (documentation drift); the fixture password from that test's source was used. No account or credential was invented and no auth was bypassed. |

Pre-existing residue in the copied DB (unrelated, not modified): users 377–380, dataset 112 (0 rows, 3 voters), 1 customer account, 1 entitlement.

## B. Owner login

- **[LIVE]** `POST /api/v1/auth/login` (`clientType=WEB`) → 200; role `OWNER`, `clientType=WEB`, no refresh token, access token carries `sid`, `role=OWNER`, 15-minute expiry. Principal authority is `ROLE_OWNER` **[INSPECT]** (`ElecSarthiUserPrincipal`).
- **[LIVE]** With the Owner token: `GET /datasets`, `/datasets/{id}`, `/subowners`, `/agents`, `/users/{id}`, `/election-types`, `/election-types/VIDHAN_SABHA/fields` → all 200. There is no separate "dashboard" endpoint in the backend.
- **[LIVE]** Unauthenticated `GET` on datasets / subowners / agents / users and `POST /datasets/upload` → 403. A garbage bearer token → 403.
- **[LIVE]** Agent WEB login → 403 (after password check); wrong password → 401; Agent MOBILE login → 200 `deviceRegistrationRequired=true` + registration token; that registration token → 403 on every Owner endpoint.
- **[TEST]** `OwnerWorkflowSecurityIntegrationTest`: AGENT, CUSTOMER and SUBUSER (real sessions + devices) get 403 on dataset list/detail/delete/upload, `/subowners`, `/agents`, `/users/{id}`, and cannot create Subowners/Agents (no rows created). SUBOWNER gets 403 on create/list Subowners; may create Agents and list datasets (permitted by the permission matrix).

## C. Subowner creation

- **[LIVE]** `POST /api/v1/subowners` → 201. Created **`audit_subowner_01`**, id **383**, role `SUBOWNER`, status `ACTIVE`, `created_by_user_id=376`, `customer_account_id` NULL, BCrypt `$2a$12$` hash, `must_change_password=false`. `GET /users/383` and `GET /subowners` return it. Audit rows `USER_CREATE/SUCCESS` written with no secrets.
- **[LIVE]** Validation: empty body, short username, short password, blank full name → 400 `VALIDATION_FAILED`; duplicate username, case-variant duplicate (`SUB_OWNER`), whitespace-padded duplicate → 409; client-sent `role`/`status` in the body are ignored (account is still `SUBOWNER`/`ACTIVE`).
- Platform users have **no phone or email field**, so "duplicate mobile/email" does not apply to Subowner/Agent creation.
- **Audit mistake (mine):** my role-injection probe used a valid password and created an extra Subowner `audit_val_4` (id 382). I deactivated it through the API (`DEACTIVATED`); it is not deleted (audit history stays). It is a test artefact; **do not use it**.

## D. Dataset import

| | |
|---|---|
| Dataset name | `AUDIT_OWNER_VIDHAN_SABHA_10_VOTERS` |
| Dataset id | **113** (tier `MASTER`, election type `VIDHAN_SABHA` as import hint) |
| File | `.xlsx`, one sheet `Voters`, header row + 10 rows, 18 columns from the importer's own column list (`VoterSheetHandler`); generated outside the repo; `mobile_no` left empty |
| Result **[LIVE]** | `POST /api/v1/datasets/upload` → 201, status `IMPORTED`, total 10, imported 10, failed 0, issues 0 |
| States / retention | `{MADHYA PRADESH}`; `delete_after` = import + 60 days (2027-01-24) |

Independent read-only SQL verification **[LIVE]**: one `dataset` row, status `IMPORTED`, 10/10/0; exactly 10 rows in `voter_d113`; 10 distinct `voter_id` (`AUD0000001`–`AUD0000010`) and 10 distinct `source_record_key_hash`; state `MADHYA PRADESH`, district `AUDIT TEST DISTRICT`, AC 201, part/booth 1 (5 voters) and 2 (5 voters), serial 1–10; 0 encrypted mobile numbers; 0 `import_issue` rows; partitions and indexes present; `audit_log` `DATASET_IMPORT/SUCCESS`. Visible in `GET /datasets` and `GET /datasets/113` (importer shown as `sys_owner`). Re-uploading the same file → 409 `FILE_ALREADY_EXISTS`; an altered copy with the same record keys → 409 `DATASET_OVERLAP`.

Edge cases (before → after the fixes, **[LIVE]**):

| Case | Before | After |
|---|---|---|
| 0-byte file / `.txt` / `.csv` renamed | 400 | 400 (unchanged) |
| Text file named `.xlsx` | **500** | 400 "not a valid .xlsx workbook" |
| Header only / no header & no rows | **201, empty IMPORTED dataset** | 400, nothing kept |
| Missing `voter_id` or `serial_no` column | **201, IMPORTED with 0 rows** | 400, nothing kept |
| Blank / whitespace voter id | row rejected, issue recorded | unchanged |
| Non-numeric / negative / huge `ac_no`, `part_no`, `serial_no` | **row imported with NULL (or −5), no issue** | row rejected, issue `INVALID_NUMBER` |
| Blank state | row rejected | unchanged |
| Same voter id twice (different serial) | imported (record key includes serial) | unchanged — by design |
| Identical full record twice in one file | **500** | 400 "duplicate voter records" |
| Missing `file`/`metadata` part, unknown election type, JSON instead of multipart | **500** | 400 / 415 |

Probe datasets 115–121 (accidentally IMPORTED by the pre-fix code) were deleted through the Owner delete API. FAILED shell rows 114, 122–130 remain by design (FAILED datasets keep a row so the file can be retried; their partitions are dropped).

## E. Agent creation

- **[LIVE]** `POST /api/v1/agents` → 201. **`audit_agent_01`**, id **384**, role `AGENT`, `ACTIVE`, created by 376, no customer account. Visible in `GET /agents` and `GET /users/384`. Duplicate username → 409; missing fields → 400.
- **[LIVE]** Authentication: WEB login refused (403), wrong password 401, MOBILE login with correct password reaches the device-registration step. **Full Agent session was not created** — it needs an ECDSA device registration that would bind a device to the preserved Agent (that belongs to the Agent batch). The registration token cannot reach Owner endpoints.
- No dataset grant/inventory was added for Agents; Agents get 403 on every dataset endpoint **[TEST]**.

## F. Authorization results

Owner allowed operations: login, create/list Subowners, create/list Agents, user status / reset password / terminate sessions (`@PreAuthorize` OWNER/SUBOWNER, Subowner limited to Agents in the service) **[INSPECT]**, dataset upload/list/detail/delete **[LIVE]**, create Customers, direct entitlements, mobile-number switch, sale void, device revoke **[INSPECT]** (not executed in this batch).

Role restrictions: see B. Only the four public `/auth/*` endpoints lack `@PreAuthorize` (intended) **[INSPECT]**. Voter search/detail/family/slip are CUSTOMER/SUBUSER only; Owner/Subowner/Agent are refused in `VoterAccessAuthorizer` **[INSPECT]**. Dataset id/name/file/hash appear only in `Dataset*Response` (Owner/Subowner endpoints); other response DTOs expose only `storageTier` **[INSPECT]**. The Agent-reachable filter endpoint returns distinct values only.

Security defects found: none that let a lower role reach Owner functions. Hardening gaps are listed in G.

## G. Bugs discovered and fixes

| # | Defect | Root cause | Fix (files) |
|---|---|---|---|
| 1 | Malformed JSON, invalid enum, missing multipart part, wrong content type, 405, unmapped path, path-variable type mismatch all returned **500** (and logged an ERROR stack trace) | `GlobalExceptionHandler` caught everything else with the catch-all | Dedicated 4xx handlers with fixed messages: `GlobalExceptionHandler` |
| 2 | Password longer than 72 bytes on create/reset → **500** | BCrypt throws; nothing validated length | `PasswordEncoder` bean rejects >72 **bytes** with 400: `SecurityConfig` |
| 3 | File with no importable rows, or missing required column, left an empty `IMPORTED` dataset | no zero-row check | Reject with 400: `DatasetImportServiceImpl` |
| 4 | Non-numeric/negative area or serial number imported as NULL with no issue | `parseInteger` silently returned null | Reject row, issue `INVALID_NUMBER` (position only, no value) for geography/serial columns; `age`/`section_no` stay lenient: `VoterSheetHandler` |
| 5 | Non-workbook `.xlsx` → 500; identical record twice in a file → 500 | POI/unique-index failures fell into the generic 500 path | 400 with clear messages: `DatasetImportServiceImpl` |
| 6 | Role/client-type mismatch at login (e.g. Agent on web) was refused but **not audited** (security.md requires DENIED audit rows) | no audit call | `AuthServiceImpl` writes `LOGIN/DENIED` (no secrets) |

Regression tests: new `OwnerWorkflowSecurityIntegrationTest` (7 tests); 3 new tests in `DatasetCopyImportIntegrationTest`; one existing assertion changed: `failedCopyRollsBackCompletely` used to assert **500** for an in-file duplicate and now asserts 400 (intent kept: nothing loaded, partition dropped, no DB detail returned, corrected file imports). With the six `src/main` files stashed, 7 of these tests fail; with the fixes all pass. After the fixes the affected live workflows (login, validation, every import negative, a partial-valid import with `INVALID_NUMBER` issues, deletion of the probe) were re-run from the start; dataset 113 was untouched.

## H. Test results

| Run | Result |
|---|---|
| Schema suite `tools/db/run_tests.ps1` (V1–V7 + `test_schema.sql`), run twice | `PASS: 83  failures: 0` |
| Targeted: `OwnerWorkflowSecurityIntegrationTest` + `DatasetCopyImportIntegrationTest` | 15 run, 0 failed, 0 errors, 0 skipped |
| `.\mvnw.cmd -o clean verify` (compile + all integration tests) | **144 run, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** (baseline before this batch: 134) |

## I. Next batch handoff (Subowner audit)

| Item | Value |
|---|---|
| Database | `es_audit` on `127.0.0.1:5498` (throw-away cluster under the user's Temp folder; backend is currently **stopped**, restart with `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5498/es_audit`, profile `dev`) |
| Owner | `sys_owner`, id 376 (existing; one Owner web session is still ACTIVE from the audit) |
| Subowner | `audit_subowner_01`, id **383**, ACTIVE, role `SUBOWNER`. Password stored outside the repo (see summary). Do **not** use `audit_val_4` (id 382, DEACTIVATED). |
| Agent | `audit_agent_01`, id **384**, ACTIVE, role `AGENT`, no device registered yet, no session |
| Dataset | id **113**, `AUDIT_OWNER_VIDHAN_SABHA_10_VOTERS`, MASTER, `IMPORTED`, 10 voters, state `MADHYA PRADESH`, AC no 201, parts 1 (`AUD0000001–05`) and 2 (`AUD0000006–10`), SHA-256 `b59a7dd2…993e99f2b`. A Subowner re-uploading this file gets 409. |
| Unrelated residue | users 377–380, dataset 112 (RAJASTHAN, 0 rows), 1 customer account, 1 entitlement, FAILED dataset shells 114/122–130 |

## Unresolved / for decision (not changed)

1. **Unauthenticated and invalid-token requests return 403, not 401.** No `AuthenticationEntryPoint` is configured. Access is correctly denied, but a client cannot tell "log in again" from "not allowed". Left unchanged because mobile/web clients may already depend on it; recommend 401.
2. **Documentation drift:** `docs/api/API_REFERENCE.md` shows login with `username`/`deviceId`, a nested `user` object and sample password `Password123!`; the real request uses `identifier`/`installationId` and the response is flat. It also says Owner/Subowner have 1 web session; `security.md` and the code say 3.
3. The upload response carries `importedByUsername: null` (the detail endpoint fills it) — cosmetic.
4. Subowner can list, import and delete datasets — this matches the permission matrix ("Import / delete datasets: Owner Yes, Subowner Yes"); the audit prompt's "Owner-only where required" is not required by the matrix.
5. Customer and Subuser role denial was verified by integration test, not by live HTTP (creating a Customer was out of scope for this batch).

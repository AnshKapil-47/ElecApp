# ElecSarthi — Subowner Audit Report (Batch 2)

Audit date: 2026-11-25. Scope: SUBOWNER only. No Customer, sale, subscription, entitlement, dataset grant or Agent device registration was created.
Legend: **[LIVE]** executed over real HTTP against the running backend and verified in PostgreSQL · **[TEST]** automated integration test (real security filter chain, real session rows, real PostgreSQL) · **[INSPECT]** code inspection only.

No password, token or key appears in this report. Nothing was committed, pushed, stashed or reset.

## A. Environment

| | |
|---|---|
| Branch / base commit | `audit/batch1-owner` (not switched), base `a2c9e83`. Batch 1's six fixes are intact; Batch 2 added three more fixes on top. **All changes are still uncommitted.** |
| Spring profile | `dev` (`spring-boot:run`), port 8080 |
| Database | Existing throw-away PostgreSQL 18 cluster `127.0.0.1:5498`, database **`es_audit`** (V1–V7). The cluster was still running; no database was recreated. |
| `es_audit` isolation | The datasource override was passed **inline** to the backend only (never exported), so it could not leak into Maven. `pg_stat_activity` showed the backend connected only to `es_audit`. `application-test.yaml` and `tools/db/run_tests.ps1` both target `es_test`; datasets 113 and 132 were still present after the schema suite and `clean verify`, which proves the test runs did not touch `es_audit`. |
| DB user | `postgres` (dev profile), not the restricted `elecsarthi_app` role |
| Client | `curl`; no frontend exists in this workspace |

## B. Subowner authentication

- **[LIVE]** `POST /api/v1/auth/login` as `audit_subowner_01` (id 383), `clientType=WEB` → 200, `role=SUBOWNER`, `clientType=WEB`, **no refresh token**, `deviceRegistrationRequired=false`. Password taken from the stored scratchpad file, never printed.
- Access token: `typ=ACCESS`, `role=SUBOWNER`, `uid=383`, `sid` = session id, 900 s lifetime. Principal authority is `ROLE_SUBOWNER` **[INSPECT]** (`ElecSarthiUserPrincipal`).
- Session row **[LIVE]**: `client_type=WEB`, `ACTIVE`, no device, idle window 30 min, absolute window 12 h.
- Wrong password → 401. `clientType=MOBILE` for a Subowner → 403 (and audited `LOGIN/DENIED`, Batch 1 fix). No token, a garbage token and a tampered signature → 403 (not 401, see Unresolved).
- **Session limit 3 [LIVE]:** my own repeated logins hit `SESSION_LIMIT_EXCEEDED (3)` → 409; earlier sessions were not evicted. The Owner's `terminate-sessions` freed the slots and the old token was refused at once. Logout invalidates the token immediately (`USER_LOGOUT`).
- Authenticated Subowner requests to datasets, agents, election types and customers → 200.

## C. Permission matrix

| Operation | Expected | Result |
|---|---|---|
| List / view datasets, incl. importer | Yes | 200 **[LIVE]** + **[TEST]** |
| Import dataset (Panchayat) | Yes | 201 **[LIVE]** + **[TEST]** |
| Delete a disposable dataset | Yes | 204 **[LIVE]** + **[TEST]**; Agent/Customer 403 **[TEST]** |
| Create / list Agents, manage Agent accounts | Yes | 201/200 **[LIVE]**; manage Agent **[TEST]** |
| Sale-form election filters | Yes | 200 **[LIVE]** |
| List Customers (phone masked) | Yes | 200, `+91*****210` **[LIVE]**; `+91*****730` vs Owner full **[TEST]** |
| Suspend / reactivate Customer | Yes | 204 **[LIVE]** (see "Audit mistakes") |
| Create / list Subowners | **No** | 403 **[LIVE]** + **[TEST]** |
| Suspend, reset password, end sessions of Owner, any Subowner, Customer, Subuser | **No** | 403, nothing changed **[LIVE]** (Owner, self, Subowner 377) + **[TEST]** (all five) |
| Read Owner / other Subowner / Customer login / Subuser via `/users/{id}` | **No** | was 200 → now **404** (fix 1) **[LIVE]** + **[TEST]** |
| Revoke Customer/Subuser device | **No** (matrix: Owner-only) | was allowed → now 404 (fix 2) **[TEST]** only; no devices exist live |
| Voter search / detail / family / slip | **No** | 403 **[LIVE]** + **[TEST]** |
| Sync, offline lease/write-back, turnout, subusers, entitlements/my | **No** | 403 **[LIVE]** + **[TEST]** |
| Sales, void, sales reports, Agent sales records | **No** | 403 **[LIVE]** + **[TEST]** |
| Mobile-number switch, reassign owning Agent | **No** (Owner-only) | 403 **[LIVE]** + **[TEST]** |
| Read the mobile-number switch | Yes | 200 `false` **[LIVE]** |

Note: with a malformed or incomplete body Spring answers 400 *before* method security runs, so denials were proven with well-formed bodies. Untested by choice: Subowner direct entitlement and Customer provisioning (would create Customers/entitlements — forbidden in this batch), deleting dataset 113 or 132, live device revocation, live Agent session.

## D. Dataset listing

- **[LIVE]** `GET /datasets` as Subowner lists 112 (residue), **113** `AUDIT_OWNER_VIDHAN_SABHA_10_VOTERS` (IMPORTED, 10/0) and 132. `GET /datasets/113` → 200 metadata. This is permitted by the matrix ("Import / delete datasets: Subowner Yes").
- **Voter data not exposed [LIVE]:** every voter API returns 403; dataset responses contain metadata and counts only, no voter rows.
- Dataset 113 was never modified by this batch: its record-key fingerprint (`md5` of all `source_record_key_hash`) was identical before and after every probe and after both test runs; its 10 rows, status and file hash are unchanged.

## E. Panchayat import

| | |
|---|---|
| Dataset name / id | `AUDIT_SUBOWNER_PANCHAYAT_10_VOTERS` / **132** |
| Election type | `PANCHAYAT` (FILTER: `state`, `district`, `panchayat_no`; booth column `booth_no`; guard `panchayat_samiti_name_en`), tier `MASTER` |
| File | `AUDIT_SUBOWNER_PANCHAYAT_10_VOTERS.xlsx`, one sheet, header + 10 rows, 22 columns from the importer's own column list; generated outside the repo; `mobile_no` empty |
| Scope | state `CHHATTISGARH`, district `AUDIT SUB DISTRICT`, Gram Panchayat `AUDIT GRAM PANCHAYAT`, `panchayat_no` 501, samiti `AUDIT SAMITI`, zilla `AUDIT ZILLA`, booths 1 and 2 (5 voters each), serials 1–10, voter ids `SUBAUD0001`–`SUBAUD0010` |
| Result **[LIVE]** | 201, `IMPORTED`, total 10, imported 10, failed 0, no issues, `importedByUserId=383`, `importedByUsername=audit_subowner_01` on the detail endpoint |

Read-only SQL **[LIVE]**: one dataset row, status IMPORTED, importer 383; exactly 10 rows in `voter_d132`, 10 distinct voter ids, 10 distinct record keys, 10 distinct serials; state/district/`panchayat_no`/panchayat/samiti/zilla/booth/part/ward values as above, none NULL; 0 encrypted mobile numbers; `voter_d132` and `voter_turnout_d132` exist with 14 indexes (incl. the Panchayat index); 0 `import_issue` rows; `audit_log` `DATASET_IMPORT/SUCCESS` actor 383 with `{"rows":10,"tier":"MASTER"}`. No overlap with 113 or the residue dataset (different state). The Panchayat cascade returns `panchayat_no=[501]` for this scope.

Import negatives as Subowner (the fixed pipeline) **[LIVE]**: 0-byte file, `.csv`, text-as-`.xlsx`, header-only, empty sheet, missing `voter_id` column, exact duplicate records, all-invalid geography, unknown election type, blank name → 400; same file again → 409 `FILE_ALREADY_EXISTS`; same record keys with changed content → 409 `DATASET_OVERLAP`; same voter id with a different serial → imported (by design, the key includes the serial); partially valid files → 201 with failed rows and issues (`MISSING_REQUIRED_FIELD`, `INVALID_NUMBER` for `panchayat_no` and `booth_no`). Every rejected upload left no voter rows and no partition (FAILED shell rows only). The three probe datasets that imported partially were deleted through the Subowner's own delete permission (audited). The same cases were re-proved as **[TEST]** and none touched dataset 132.

## F. Agent creation

| | |
|---|---|
| Username / id | `audit_subowner_agent_01` / **387** |
| Role / status | `AGENT` / `ACTIVE` |
| `created_by_user_id` | **383** (the Subowner); `customer_account_id` NULL |
| Evidence **[LIVE]** | 201; found via `GET /agents` and `GET /users/387`; `audit_log` `USER_CREATE/SUCCESS` actor 383, no password in any audit row; no device, no session |
| Rejected **[LIVE]** | `{}`, short username, short password, blank name, 73-byte password → 400; duplicate, case-variant duplicate, a Subowner's name → 409; none created a row |
| Injection **[LIVE]** | the one real request also carried `role=OWNER`, `status=SUSPENDED`, `createdByUserId=376`, `customerAccountId=109`; all ignored |

Authentication of this Agent is reserved for Batch 3 (no device registered).

## G. Security audit

- **Cross-role [TEST]:** AGENT/CUSTOMER/SUBUSER → 403 on datasets (list, detail, delete, upload), `/subowners`, `/agents`, `/users/{id}`; unauthenticated → 403.
- **Object-level [LIVE+TEST]:** Subowner reads and manages **Agents only** (plus its own profile). Owner, other Subowners, Customer logins and Subusers are 404 for reads (so existence is not revealed and a Customer login's phone-number username cannot leak) and 403 for status/reset/terminate; the Owner's password hash, status and session were unchanged.
- **Cross-Subowner [LIVE]:** Subowner 383 → `/users/377` (other Subowner) and `/users/382` → 404; status change on 377 → 403.
- **Dataset inventory [TEST+INSPECT]:** an Agent session gets 403 on dataset list/detail/delete; the Agent-reachable filter endpoint returns only `{targetField, values}`; no response DTO outside `Dataset*Response` carries a dataset id, name or file field; the schema (18 tables) has no Agent↔dataset link, and no grant/assignment row was created. Nothing was assigned to Agents 384 or 387.
- **Voter data [LIVE+TEST]:** Subowner cannot reach any voter endpoint.
- **Audit [LIVE]:** logins (success, denied: bad credentials, client type, session limit), logout, session terminate, user create, dataset import/delete, customer suspend/reactivate are all recorded; no secret-like text in any `audit_log` row.

## H. Bugs found and fixes

| # | Defect | Root cause | Fix |
|---|---|---|---|
| 1 | A Subowner could read **any** user through `GET /api/v1/users/{id}`: Owner, other Subowners, Customer logins (whose `username` is the Customer's **unmasked phone number**) and Subusers | `getUser` had no role scoping; matrix says Subowners manage Agents only and see Customer phones masked | `UserServiceImpl.getUser(id, callerUserId, role)`: Subowner → Agents and itself only; others → 404. Files: `UserService`, `UserServiceImpl`, `UserController` |
| 2 | A Subowner could revoke **any** device, including Customer and Subuser devices | `DeviceServiceImpl.revokeDevice` treated Owner and Subowner alike; matrix: "revoke devices — Owner Yes, Subowner No" | Subowner may revoke Agent devices only, anything else 404: `DeviceServiceImpl`. **Behaviour change — please confirm** (see below) |
| 3 | A login refused for hitting the concurrent-session limit was not audited (rules require DENIED login rows) | `enforceSessionLimit` threw without writing an audit row | `LOGIN/DENIED` row, reason and limit only: `AuthServiceImpl` |

Regression tests: new `SubownerWorkflowSecurityIntegrationTest` (10 tests). With only fixes 1–3 neutralized, exactly the three guarding tests fail (`subownerScopeIsAgentsOnly`, `deviceRevocationScope`, `sessionLimitIsEnforcedAndAudited`); restored, all pass. One comment in `DeviceSecurityIntegrationTest` ("Subowner CAN revoke any device") was corrected; its assertion (an Agent's device) is unchanged. Fixes 1 and 3 were re-run live on the rebuilt backend; fix 2 could not be run live because no device exists (Agent devices are reserved for Batch 3).

**Decision for you (fix 2):** the matrix row reads "Reset Customer password, end Customer sessions, revoke devices". I read "devices" as Customer/Subuser devices and kept Agent-device revocation for the Subowner (it manages Agents). If you want Subowners to have no device revocation at all, the change is the one `else if` branch in `DeviceServiceImpl`; if you want the old behaviour back, revert it and the new test.

No documentation-only or environment-only defects beyond those listed under Unresolved.

## I. Test results

| Run | Result |
|---|---|
| Schema suite `tools/db/run_tests.ps1` (V1–V7, `es_test` only) | `PASS: 83  failures: 0` |
| Targeted (Subowner, Owner, Device, Import test classes) | 29 run, 0 failed, 0 errors, 0 skipped |
| `.\mvnw.cmd -o clean verify` (compiled, all integration tests, `es_test`) | **154 run, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** (Batch 1: 144; +10 new) |
| Live API workflow | login, matrix, import (good + 12 negatives), delete, Agent creation, session limit, logout — all re-run on the final build where affected |
| Not executed | live Agent session, live device revocation, Subowner direct entitlement, Customer provisioning, deleting datasets 113/132 |

## J. Batch 3 handoff

| Entity | Value |
|---|---|
| Database | `es_audit` @ `127.0.0.1:5498` (cluster under the user's Temp folder, **running**; backend **stopped**; restart with the inline `SPRING_DATASOURCE_URL` and profile `dev`) |
| Owner | `sys_owner` 376. All Owner and Subowner sessions are ended (Owner can log in again) |
| Subowner | `audit_subowner_01` 383, ACTIVE. Password: scratchpad `subowner.pw` |
| Agent A (Owner-created) | `audit_agent_01` 384, ACTIVE, no device. Password: `agent.pw` |
| Agent B (Subowner-created) | `audit_subowner_agent_01` 387, ACTIVE, created by 383, no device. Password: `subagent.pw` |
| Dataset 113 | Vidhan Sabha, `MASTER`, IMPORTED, 10 voters, state `MADHYA PRADESH`, AC no 201, parts 1/2, ids `AUD0000001–10` |
| Dataset 132 | Panchayat, `MASTER`, IMPORTED, 10 voters, state `CHHATTISGARH`, district `AUDIT SUB DISTRICT`, `panchayat_no` 501, booths 1/2, ids `SUBAUD0001–10`, sha256 `e06e54cc…01d4e5` |
| Do not use | `audit_val_4` 382 (DEACTIVATED artefact) |
| Residue (unrelated) | users 377–380, dataset 112 (RAJASTHAN, 0 rows, 3 voters), customer 109 (ACTIVE), 1 entitlement, 17 FAILED dataset shells (no partitions) |

Scratchpad: `C:\Users\eulo_\AppData\Local\Temp\claude\efdf1ec0c3cfa0a9\fb1a3138-f0b4-4486-a6ae-f7b23b70db83\scratchpad` (`*.pw`, `fixtures`, `fixtures_sub`). Passwords can be reset by the Owner via `POST /api/v1/users/{id}/reset-password` if lost.

For the Agent sale audit: the Agent-facing election-type filter endpoint cascades over **all IMPORTED datasets, ignoring election type and tier** — e.g. `PANCHAYAT` offered the Vidhan Sabha states `MADHYA PRADESH` and `RAJASTHAN`. The business rule says dropdowns come from IMPORTED datasets "of that tier", so check this in Batch 3 (an Agent can pick an area for which that lens/tier has no dataset; the sale should then answer "area not available").

## Audit mistakes and side effects (disclosed)

1. **Customer 109 suspended and reactivated.** I listed `POST /customers/109/suspend` among restricted calls by mistake; it is a Subowner-permitted action and succeeded (204) on the residue customer. I reversed it with `reactivate` within minutes. Final state: `ACTIVE`, suspension columns cleared; Customer 109's users and sessions were not affected. The append-only audit log keeps `CUSTOMER_SUSPEND` and `CUSTOMER_REACTIVATE` rows.
2. **Session limit hit by my own logins**, which blocked the first Agent-creation attempt (all requests got 403 from a missing token; nothing was created). Cleared with the Owner's permitted `terminate-sessions`; my login helper now logs out first.
3. Seven new FAILED dataset shells (133–138, 140) were left by the rejected uploads (by design: FAILED rows are kept, partitions dropped).

## Unresolved / for decision (not changed)

1. Unauthenticated, expired and invalid-token requests still return **403 instead of 401** (from Batch 1).
2. Manage endpoints answer an out-of-scope target with **403** but a missing target with **404**, so a Subowner can tell that a given Owner/Customer id exists. Ids are sequential and fix 1 conceals reads; consider 404 for manage as well.
3. The dev profile runs as the `postgres` superuser, so the runtime role's privileges were not exercised in the live run.
4. `docs/api/API_REFERENCE.md` still shows wrong login fields and "1 web session" (from Batch 1).

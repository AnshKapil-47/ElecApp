# ElecSarthi — Agent Audit Report (Batch 3)

Audit date: 2026-11-25. Scope: AGENT role, Customer creation and subscription (sale) creation.
Legend: **[LIVE]** executed over real HTTP against the running backend and verified in PostgreSQL · **[TEST]** automated integration test (real security filter chain, real session/device rows, real PostgreSQL) · **[INSPECT]** code inspection only.

No password, token, activation code or private key appears in this report. Nothing was committed, pushed, stashed, reset or merged.

## A. Environment

| | |
|---|---|
| Branch / commit | **`main` @ `bb360e1`** ("Merge Owner and Subowner audit work"). **This differs from the brief**, which expected branch `audit/batch1-owner` with nine uncommitted fixes. In fact the audit branch was merged into `main` (and pushed: `origin/main` = `bb360e1`) before this batch; all nine earlier fixes are in HEAD and the working tree was clean at the start. The branch `audit/batch1-owner` still exists at `0563bf6` (a subset of `main`). I stayed on `main` (no switch, reset, stash or commit). |
| Working tree now | Batch 3 changes only, **uncommitted** (see section G) |
| Spring profile | `dev` (`spring-boot:run`), port 8080, `ddl-auto=validate` |
| Database | Existing throw-away PostgreSQL 18 cluster `127.0.0.1:5498`, database **`es_audit`** (V1–V7), not recreated |
| `es_audit` isolation | The datasource override was passed **inline** to the backend only, never exported, so Maven could not inherit it. `pg_stat_activity` showed the backend connected only to `es_audit` (5 connections) and none to `es_test`. `application-test.yaml` and `tools/db/run_tests.ps1` both target `es_test`. Datasets 113/132 and both sales were still present after the schema suite and every Maven run. |
| DB user | `postgres` (dev profile), not the restricted `elecsarthi_app` role |
| Client | `curl` plus a small Java ECDSA tool kept outside the repo (scratchpad); no frontend in this workspace |

## B. Agent authentication

Protocol (read from the code **[INSPECT]**): MOBILE login with no ACTIVE device returns `deviceRegistrationRequired=true` and a single-use registration token → `POST /devices/register` (ECDSA P-256 public key as Base64 X.509, `installationId`, platform) → server returns a device id and a 64-hex challenge → `POST /devices/verify` with a Base64 DER `SHA256withECDSA` signature over the UTF-8 challenge string → MOBILE login again with the same `installationId` returns access (15 min) and refresh tokens. Signed requests carry `X-Device-Id/Signature/Timestamp/Nonce/Counter` over `METHOD\nPATH\nSHA256(body)\nts\nnonce\ncounter\ncontext` (path without query string). The refresh endpoint signs the refresh-token string instead of the JSON body.

Each Agent got its **own** synthetic key pair and installation id (verified distinct). Signed requests were sent **strictly sequentially**; the counter is persisted per device and always increased.

| Check **[LIVE]** | Agent 384 `audit_agent_01` | Agent 387 `audit_subowner_agent_01` |
|---|---|---|
| WEB login | 403 | 403 |
| Wrong password | 401 | 401 |
| MOBILE login, no device | 200, registration required | 200, registration required |
| Registration token as access token (customers, sales, filters) | 403 | 403 |
| Garbage / payload-tampered / signature-tampered registration token | 403 / 403 / 400 (see note) | – |
| Register device | 200 (replaced a stale PENDING row, see G1) | 200 |
| Verify with a signature from the **other Agent's key** | 400 `INVALID_DEVICE_SIGNATURE` | 400 |
| Verify with a signature over a **different challenge** | 400 | – |
| Verify using the other Agent's device id | – | 400 |
| Verify correct | 200, device ACTIVE | 200, device ACTIVE |
| Registration token reuse after success | 403 | 403 |
| Login with another installation id (wrong fingerprint) | 403 `DEVICE_CHANGE_NOT_ALLOWED` | 403 |
| Login without installation id | 400 | – |
| Login with the registered device | 200, role `AGENT`, `clientType=MOBILE`, session bound to the device | same |

Database: one ACTIVE device per Agent (`user_role=AGENT`, no customer account, challenge cleared, key stored), one ACTIVE MOBILE session each bound to that device, audit rows `DEVICE_REGISTER_INITIATED`, `DEVICE_VERIFY_SUCCESS`, `DEVICE_VERIFICATION/DENIED`, `LOGIN_DEVICE_MISMATCH`, `LOGIN` success and denied. No secret appears in any audit row written by the fixed build.

Signed-request matrix on device 384 **[LIVE]** (each request alone, in order): valid → 200 ×N; corrupted signature → 401; only one signature header → 401 "missing headers"; another device's id in `X-Device-Id` → 401; malformed device id → 401; signed with the other Agent's key → 401; repeated nonce → 401 `Replayed nonce`; counter equal to the last and lower than the last → 401 "not strictly increasing"; timestamp 10 min old and 10 min ahead → 401 "outside allowed window"; body changed after signing → 401; counter jump of +100 → 200 and the next sequential request → 200. Agent 387's cross-use check: 384's access token with 387's device headers → 401 device mismatch.

Session and token **[LIVE]**: signed refresh rotates (generation 1→2) → 200; refresh with no proof → 401, with a bad signature → 401 and no rotation; **replaying the old refresh token** → 401 `REFRESH_TOKEN_REUSE`, session `TERMINATED`, audit `REFRESH_REUSE/DENIED`, and both the new and old access tokens → 403 afterwards. A second login on the same device replaced the session (`REPLACED_SAME_DEVICE`, old token 403). The Owner's `terminate-sessions` killed 387's token at once and 387 logged in again on the same ACTIVE device. Natural access-token expiry: see section H.

Notes: (1) A registration token whose **last character** was altered was still accepted: in an unpadded base64url HMAC the last character carries unused bits, so the decoded signature and the claims are identical; real tampering (signature middle, payload `uid`) is rejected. This is encoding malleability, not a forgery. (2) Device signatures are **required** only on `/sync`, `/leases`, `/turnout`, `/voters` (and validated whenever the header is present); Agent endpoints such as `/customers` and `/sales` work with the bearer token alone **[LIVE]**. That is how the filter is written and matches `security.md`, but it means a stolen Agent access token does not need the device key.

## C. Election filter endpoint

**Original defect [LIVE, before the fix]:** as the Owner on the old build, `VIDHAN_SABHA`, `PANCHAYAT` and even `LOK_SABHA` all returned the same states `["CHHATTISGARH","MADHYA PRADESH","RAJASTHAN"]`, and `PANCHAYAT` + `state=MADHYA PRADESH` offered `AUDIT TEST DISTRICT` from the Vidhan dataset.

**Root cause:** `ElectionTypeServiceImpl.getCascadingFilterOptions` read `voter` rows of **every** IMPORTED dataset. The election type only decided which columns may be asked about, and the storage tier was not considered at all. The `dataset` table has no election type, so a "Vidhan dataset" is defined by its data.

**Fix:**
- The query now reads only IMPORTED datasets of the requested **storage tier** (bind parameter) and only rows whose **every FILTER column of the election type is non-null**. That is exactly what a sale line can match (`col = ?` for each FILTER column), so the dropdown and `AreaResolver` agree. FILTER column names come from `election_type_field` metadata (already validated), never from the client.
- `tier` is a new **required** query parameter (`MASTER` or `LONG_TERM`). The sale request already carries `storageTier`; the filter endpoint had no way to know it. A missing or invalid tier is 400. **This is a contract change for the mobile client**, which must send the tier it has already chosen ("tier first"). If you prefer it optional, drop the `@RequestParam` requirement and the tier predicate becomes conditional.
- Unchanged: role restriction (Owner/Subowner/Agent only), FILTER-or-guard column validation, bind parameters, the response shape `{targetField, values}` with no dataset id, name or file.
- Semantics worth knowing: a dataset carrying the FILTER columns of two election types (for example `ac_no` and `panchayat_no`) is offered through both, which is correct for a lens model; a Vidhan dataset without `pc_no` is not offered through `LOK_SABHA`.

**After [LIVE, fixed build, signed Agent sessions]:** `VIDHAN_SABHA/MASTER state` → `["MADHYA PRADESH","RAJASTHAN"]` (RAJASTHAN is the unrelated residue Vidhan dataset 112); `ac_no` for MADHYA PRADESH → `[201]`; `VIDHAN_SABHA/LONG_TERM state` → `[]`; `PANCHAYAT/MASTER state` → `["CHHATTISGARH"]`, `district` → `["AUDIT SUB DISTRICT"]`, `panchayat_no` → `[501]`, guard `panchayat_samiti_name_en` → `["AUDIT SAMITI"]`; `PANCHAYAT` + MADHYA PRADESH → `[]`; no tier → 400; `voter_id` and `state;DROP` → 400. Both Agents retrieved the correct options.

**Regression tests [TEST]:** new `ElectionTypeFilterIsolationIntegrationTest` (8 tests; real XLSX imports mirroring datasets 113 and 132 plus a LONG_TERM and a dual-use dataset): Vidhan options only, Panchayat options only, no leakage either way, cascades, guard column, dual-use and Lok Sabha, tier isolation, required tier, DISPLAY and hostile column names still 400, Customer/Subuser 403, Owner/Subowner/Agent still allowed, no dataset id/name/file in any response. `ElectionTypeFilterEndpointSecurityIntegrationTest` (10) was updated for the tier parameter and its Palika fixture given a ward number (it modelled a row no sale could match). With the fix neutralized, 5 of the 8 isolation tests fail.

## D. Customer creation

| | Customer 1 | Customer 2 |
|---|---|---|
| Created by | Agent 384 | Agent 387 |
| Id / name | **110** `Audit Customer A384` | **111** `Audit Customer A387` |
| Login identifier (= phone, synthetic) | `+919000038400` | `+919000038700` |
| Result **[LIVE]** | 201 | 201 |
| `owning_agent_user_id` / `created_by_user_id` | 384 / 384 | 387 / 387 |
| Account status / activation | `ACTIVE` / `PENDING` | `ACTIVE` / `PENDING` |
| Primary login | role `CUSTOMER`, status `PENDING`, no password, created by the Agent | same |
| Activation code | 10 characters, 72-hour TTL, hash stored, returned once, **kept in a secret file outside the repo** | same |

Verification **[LIVE]**: the requests carried injected `owningAgentUserId` (the *other* Agent), `role=OWNER`, `status=SUSPENDED`, `activationStatus=ACTIVATED` and `createdByUserId=376`; all were ignored. Rejected without creating anything: empty body, blank name, phone without `+`, letters, too long, malformed JSON (400); duplicate phone (409). The Customer lists and detail reads never return the activation code. No dataset information is reachable by a Customer (a Customer token is 403 on the dataset endpoints **[TEST]**).

Audit: `CUSTOMER_PROVISION/SUCCESS` for both. **Finding:** the row stored the Customer's full phone number in `details`, against "never put mobile numbers in details". Fixed (G3). The two rows written before the fix (audit ids 230 and 232) still contain the phone: `audit_log` is append-only and cannot be edited.

## E. Subscription creation

The Agent supplied only election type, tier, geography and duration. No dataset id was sent, apart from a deliberately injected `datasetId` pointing at the *other* dataset (ignored).

| | Sale 1 | Sale 2 |
|---|---|---|
| Agent / Customer | 384 / 110 | 387 / 111 |
| Election type / tier | `VIDHAN_SABHA` (id 2) / `MASTER` | `PANCHAYAT` (id 8) / `MASTER` |
| Geography sent | `state=MADHYA PRADESH`, `ac_no=201` | `state=CHHATTISGARH`, `district=AUDIT SUB DISTRICT`, `panchayat_no=501` |
| Duration | 1 MONTH (Asia/Kolkata) → 2026-11-25 → 2026-12-25 | same |
| Result **[LIVE]** | 201, sale **1016**, line 1010, entitlement **1071**, `COMPLETED` | 201, sale **1017**, line 1011, entitlement **1072**, `COMPLETED` |
| Scope label (response) | `Vidhan Sabha · MADHYA PRADESH · AC 201` | `Panchayat · CHHATTISGARH · AUDIT SUB DISTRICT · Panchayat 501` |
| Voter snapshot | 10 | 10 |
| **Resolved dataset (PostgreSQL only)** | **113** | **132** |
| Entitlement | ACTIVE, source `SALE`, `mobile_access=false`, `criteria = {state, ac_no}` | ACTIVE, source `SALE`, `criteria = {state, district, panchayat_no}` |

Responses contain no dataset id, name, file name or hash. Idempotent replay with the same key returned the same sale; the same key with another payload → 409.

Independent SQL **[LIVE]**: `agent_sale_line.dataset_id` is 113 for sale 1016 and 132 for 1017; each Customer holds exactly one entitlement, within its own election type and dataset; 18 tables, no grant or assignment structure, no schema change; both datasets still contain exactly 10 voters with 10 distinct ids; the record-key fingerprints of 113 and 132 were identical before the sales, after the sales and after all tests; `import_issue` for them = 0; no turnout rows.

## F. Cross-agent authorization and visibility

**Agent isolation [LIVE]** (every probe below returned the same 404 a missing record gets, and created nothing): 387 reading or re-issuing a code for Customer 110 and 384 doing the same for 111; 384 selling to 111 and 387 selling to 110; 384 reading sale 1017 and 387 sale 1016; 384 reading entitlements and sales of customer 111 and 387 those of 110; 384 reading the residue Customer 109. Each Agent's `GET /customers`, `/sales/my`, and sales report list only its own records; a report for Agent 384 shows 1 sale.

**Dataset inventory [LIVE+TEST]:** both Agents are 403 on `GET /datasets`, `/datasets/113`, `/datasets/132`, `/agents`, `/subowners`, `/users/{id}` and the mobile-number switch. An Agent-facing payload scan (sales, entitlements, customers, report, filters) found **0** occurrences of dataset id, dataset or file names, `.xlsx`, hash, imported/failed counts or delete-after. No dataset grant exists.

**Sale-input attacks [LIVE]** (each 4xx, nothing created): Vidhan criteria under PANCHAYAT and the reverse; Panchayat geography under Vidhan; unmatched AC and panchayat; right area but wrong tier; unknown election type (404); missing FILTER column; DISPLAY column as criteria; zero duration or unknown unit; empty lines; validity beyond the MASTER dataset's life; SQL-looking values and column names; an out-of-range integer; an object where a value belongs.

**Owner visibility [LIVE]:** `GET /sales/1016` and `/1017` show `soldByUserId`, Customer, election type, tier, scope label, validity and voter snapshot; `/reports/sales` shows both sales with Agent, Customer and scope (2 sales, 2 Customers acquired) and filters by `agentId`; the Customers list shows the owning Agent's name; the Owner sees the full phone number. The sale model has **no** price, amount, payment or commission field (checked in the schema **[TEST]**).

**Subowner [LIVE+TEST]:** 403 on `/sales/*`, `/sales/customer/*`, `/reports/sales`, sale creation and void; sees Customers with the phone masked (`+91*****400`) and may read entitlements; reads Agent accounts only; reaches no voter endpoint.

Not tested live (Batch 4 needs a Customer login): a Customer reading outside its purchased geography or another Customer's entitlement. The existing `VoterScopeGeographicIsolationIntegrationTest`, `CrossTenantConcealmentIntegrationTest` and `EntitlementAreaScopeRegressionIntegrationTest` cover this **[TEST]**.

## G. Bugs found and fixed

| # | Defect | Root cause | Fix (files) |
|---|---|---|---|
| 1 | **A user whose device registration was abandoned could never register again** (409 "Database constraint violation"); live on Agent 384 | `registerDevice` deleted the stale PENDING row and inserted the new one in one transaction, but Hibernate runs the insert first and violated `ux_device_pending_per_user` | flush the delete before the insert: `DeviceServiceImpl`. Live: re-registration over the stale row now returns 200 |
| 2 | Filter dropdown ignored election type and tier (section C) | see C | `ElectionTypeController`, `ElectionTypeService`, `ElectionTypeServiceImpl` |
| 3 | `CUSTOMER_PROVISION` audit row stored the Customer's phone number | `AuditService.details("phone", phone)` | details now hold only the creator's role and the owning Agent id: `CustomerServiceImpl` |

Regression tests **[TEST]**: `DeviceReRegistrationIntegrationTest` (1), `ElectionTypeFilterIsolationIntegrationTest` (8), `AgentWorkflowIntegrationTest` (5: Customer creation and injection, audit without phone, cross-agent isolation, dataset resolution for both lenses with client-sent `datasetId` ignored, rejections, visibility). With fixes 1–3 neutralized, 7 of those 14 tests fail; restored, all pass. Existing tests touched: filter endpoint test (tier parameter, Palika ward fixture) and the Subowner test's filter call (tier parameter). All earlier Owner/Subowner fixes are intact in HEAD and re-verified by the full run.

## H. Verification

| Run | Result |
|---|---|
| Schema suite `tools/db/run_tests.ps1` (V1–V7 + `test_schema.sql`, `es_test` only) | `PASS: 83  failures: 0` |
| Targeted during the work | filter isolation 8/8, filter endpoint security 10/10, Subowner workflow 10/10, device re-registration 1/1, Agent workflow 5/5 — all passed |
| Old-behaviour proof (fixes 1–3 neutralized, then restored byte for byte) | 14 new tests run, **7 failed** (5 filter isolation, device re-registration, audit phone check); all pass with the fixes |
| `.\mvnw.cmd -o clean verify` (compile + every integration test, `es_test`) | **168 run, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** (Batch 2: 154; +14 new) |
| Live workflow | both Agents registered and authenticated; signed-request, session and refresh matrices; 2 Customers; 2 sales; cross-agent, Owner and Subowner checks — all on the final build, except where noted |
| Token expiry **[LIVE]** | after the 15-minute lifetime ended, both Agents' access tokens (signed and bearer-only) → 403 while their sessions stayed ACTIVE; a signed refresh then issued fresh tokens (200) |

Isolation check: before every test run the test datasource was confirmed to be `es_test`, and no override existed in the shell. After the schema suite and the full Maven run, `es_audit` still held datasets 113 and 132 (record-key fingerprints `4188a81e…` and `c240f08b…`, unchanged since before the sales), both sales, both Customers, both devices.

Not executed live: device revocation and Owner-driven device revocation of an Agent (would un-register a preserved device), a Customer reading outside its purchased area (needs Customer activation, Batch 4), deleting or touching datasets 113/132.

## I. Open issues

1. **Subowner device revocation (not changed, as instructed).** Current behaviour **[INSPECT + TEST]**: `DeviceServiceImpl.revokeDevice` lets the **Owner** revoke any device; a **Subowner** may revoke only devices of **Agents** (anything else is a 404), a restriction added in Batch 2 and still awaiting your decision; an **Agent** cannot revoke its own device and may revoke devices of its own Customers; a **Customer** may revoke its Subusers' devices; a Subuser none. Not exercised live so that both registered Agent devices stay ACTIVE for the next batches.
2. **Unauthenticated, expired and invalid-token requests still answer 403**, not 401 (unchanged by instruction).
3. **`tier` is now required on the filter endpoint** — the Flutter client must send it (contract change, section C).
4. A duplicate Customer phone returns 409 with the phone echoed; that reveals that an account with that number exists, possibly another Agent's. Unavoidable with a unique phone, but it sits against "errors never reveal whether another Customer exists".
5. The MASTER-validity error message includes the dataset's exact expiry timestamp (`exceeds MASTER dataset expiration …`), which tells an Agent the import date of the underlying dataset. Consider "maximum validity" without the timestamp.
6. Agent endpoints other than voter/sync/lease/turnout do not require a device signature (section B).
7. Registration/JWT tokens tolerate altered unused trailing base64url bits (section B).
8. Two audit rows (230, 232) written before fix G3 contain Customer phone numbers and cannot be removed.
9. The dev profile runs as `postgres`, not the runtime role; the residue dataset 112 (Vidhan, RAJASTHAN) appears in Vidhan cascades by design.
10. `docs/api/API_REFERENCE.md` is still out of date (login fields, session limits) and does not describe the new `tier` parameter.

## J. Batch 4 handoff

| Item | Value |
|---|---|
| Database | `es_audit` @ `127.0.0.1:5498` (cluster under the user's Temp folder, **running**; backend **stopped**; start it with the inline `SPRING_DATASOURCE_URL` and profile `dev`) |
| Customer 1 | id **110**, `Audit Customer A384`, login `+919000038400`, owner and creator Agent **384**, ACTIVE, activation **PENDING** |
| Customer 2 | id **111**, `Audit Customer A387`, login `+919000038700`, owner and creator Agent **387**, ACTIVE, activation **PENDING** |
| Sale / entitlement 1 | sale 1016 → entitlement **1071**, `VIDHAN_SABHA`, MASTER, MADHYA PRADESH AC 201, valid 2026-11-25 → 2026-12-25, internally dataset 113, `mobile_access=false` |
| Sale / entitlement 2 | sale 1017 → entitlement **1072**, `PANCHAYAT`, MASTER, CHHATTISGARH / AUDIT SUB DISTRICT / 501, valid 2026-11-25 → 2026-12-25, internally dataset 132, `mobile_access=false` |
| Activation codes | in secret files outside the repo (scratchpad `dev\a384\cust.activation`, `dev\a387\cust.activation`). **They expire 72 h after issue: 2026-11-28 ~12:42 UTC (110) and ~12:46 UTC (111), 5 attempts each.** If Batch 4 starts later, the owning Agent, Owner or Subowner re-issues a code. |
| Devices | Agent 384 and 387 each have one ACTIVE device and one ACTIVE session (sessions can simply be re-created by logging in on the registered device). Keys, installation ids and device ids are in the scratchpad `dev\a384`, `dev\a387`; passwords in `agent.pw`, `subagent.pw`, `subowner.pw`. |
| Unchanged | Owner 376, Subowner 383, datasets 113 and 132 (10 voters each, fingerprints unchanged), residue users 377–380, customer 109, dataset 112, `audit_val_4` 382 (unused) |
| Customer login for Batch 4 | the Customer's primary login username is its phone number; activation sets the password, then MOBILE login needs its own device registration |

Scratchpad: `C:\Users\eulo_\AppData\Local\Temp\claude\efdf1ec0c3cfa0a9\fb1a3138-f0b4-4486-a6ae-f7b23b70db83\scratchpad`.

# Subuser Audit Report — Batch 5

Scope: Customer-created Subusers, booth assignment, Subuser login and device binding, booth-scoped voter access, election-specific fields, slips, online and offline turnout, Customer turnout statistics.
No Customer, Agent, subscription or dataset was created or changed.

Labels: **LIVE** = real HTTP requests against the running backend and `es_audit`; **TEST** = automated test against `es_test`; **INSPECT** = read from code or configuration.
No password, token, activation code or private key appears here. Voter values are synthetic; no names or phone numbers are quoted.

## A. Environment

| Item | Value |
|---|---|
| Branch / HEAD | `main` at `ef3251e` ("Customer Audit"); working tree was clean at the start (Batches 1–4 are committed). |
| Preserved | All Batch 1–4 fixes, including the election-type field mapper, optional `page`/`limit`/`protocolVersion`, the activation and login-timing fixes. All Batch 5 changes are uncommitted. |
| Profile / port | `dev`, 8080 |
| Database | Existing PostgreSQL 18 cluster `127.0.0.1:5498`, database **`es_audit`** (V1–V7), not recreated |
| Isolation | The datasource override was passed **inline** to the backend only. `pg_stat_activity` showed 5 connections, all to `es_audit`, none to `es_test`. Maven ran only with the backend stopped, against `es_test` (`application-test.yaml`); no datasource variable existed in the shell. `es_audit` was intact afterwards (datasets 113/132 with 20 voters, both sales, both Customers, all Subuser records). |
| Starting state vs. the brief | The brief said no Subusers and no booth assignments existed. There was **one old Subuser (380) and one old assignment (1045)** — Customer 109's fixture from the original test data (entitlement 1070, dataset 112), reported in Batch 4. They are not connected to Customers 110/111 and were not touched. No turnout rows, no leases; two COMPLETED sync sessions from Batch 4. |
| DB user | `postgres` (dev profile), not the restricted runtime role |

## B. Subuser creation

Contract **[INSPECT]**: `POST /api/v1/subusers` (CUSTOMER only) with `username`, `fullName`, `password` (≥ 8), optional `contactPhone` (ignored). The role, parent Customer and `ACTIVE` status come from the session, never the body.

| Item | Subuser 1 | Subuser 2 |
|---|---|---|
| Id / username | **390** `audit_subuser_customer110` | **391** `audit_subuser_customer111` |
| Created by | Customer 110 (user 388) | Customer 111 (user 389) |
| Parent Customer | 110 | 111 |
| Result **[LIVE]** | 201, `role=SUBUSER`, `status=ACTIVE`, no assignments yet | same |
| DB **[LIVE]** | `role=SUBUSER`, `customer_account_id=110`, `created_by=388`, BCrypt `$2a$12$` | `…=111`, `created_by=389`, `$2a$12$` |

| Check **[LIVE]** | Result |
|---|---|
| Missing / short (< 8) password, blank username | 400 `VALIDATION_FAILED` |
| Agent token creates a Subuser | 403 |
| Injected `role=OWNER`, `customerAccountId=111`, `status=SUSPENDED`, `createdByUserId=376` in the real request | ignored; stored role `SUBUSER`, customer 110, `ACTIVE`, creator 388 (the same with `role=CUSTOMER`, `customerAccountId=110` for Subuser 2) |
| Duplicate username (own Customer, other Customer, a Customer's phone login) | 409 "Username is already taken" — see K1 |
| Each Customer lists its Subusers | 110 → [390], 111 → [391] |
| Customer 110 reads 391 / Customer 111 reads 390 | 404 "Subuser not found" |
| Audit | `SUBUSER_CREATED/SUCCESS` ×2; details hold only the username — no password, hash or phone |

## C. Booth assignments

Contract **[INSPECT]**: `POST /api/v1/subusers/{id}/booth-assignments` with `entitlementId`, `boothValue`, `slot` (1 or 2), `durationDays` (1–7). The booth column comes from the election type (`part_no` for Vidhan Sabha, `booth_no` for Panchayat); existence is checked with the same fail-closed `VoterScopeAuthorizer` predicate (dataset + whitelisted FILTER criteria) that every voter read uses.

| | Subuser 390 (Vidhan) | Subuser 391 (Panchayat) |
|---|---|---|
| Assignment id **[LIVE]** | 1046 | 1047 |
| Entitlement / booth column / value / slot | 1071 / `part_no` / **1** / 1 | 1072 / `booth_no` / **1** / 1 |
| Customer | 110 | 111 |
| Validity | 7 days (to 2026-12-02), inside the entitlement (to 2026-12-25) | same |
| Voters it covers (SQL) | **5** (part 1) | **5** (booth 1) |
| Left unassigned (SQL) | part 2: 5 voters | booth 2: 5 voters |
| Audit | `BOOTH_ASSIGNED/SUCCESS` with booth, slot, Subuser id | same |

Refused **[LIVE]** (assignment count unchanged afterwards): booth 99, booth 3, booth −1 (400 "does not exist within the area of this entitlement"); the other Customer's entitlement, the other Customer's Subuser, a non-existent Subuser, a Customer's own login id as "Subuser" (404); slot 3, 0 or 8 days, missing booth (400 validation); the same booth again in the same slot (400 `SUBUSER_BOOTH_LIMIT_EXCEEDED`) or in slot 2 (400 conflict).
Not run live (needs another AC or edited data) but **TEST**: a booth number that exists only in another AC is rejected; unreadable entitlement criteria fail closed (existing `EntitlementAreaScopeRegressionIntegrationTest`, plus the new `SubuserTurnoutWorkflowIntegrationTest.assignmentRules`). Assignments were made only through the API, never by SQL.

## D. Authentication and security

| Check | Result |
|---|---|
| WEB login | **LIVE** 403 "Mobile application users cannot log in to the web console." |
| Wrong password / unknown user / the other Subuser's password | **LIVE** 401 "Invalid username or password."; unknown user now takes as long as a wrong password (0.56 s vs 0.31 s, BCrypt cost 12) |
| First MOBILE login | **LIVE** 200 `deviceRegistrationRequired=true` + registration token |
| Device registration | **LIVE** register → challenge → verify → login, each 200. Separate synthetic key and installation id for each Subuser; DB: 6 ACTIVE devices (Agents, Customers, Subusers) with **6 distinct public keys**; Subuser devices carry `customer_account_id` 110 / 111 |
| Session **[LIVE/DB]** | `SUBUSER`, `MOBILE`, `ACTIVE`, device-bound, one per Subuser |
| Token claims **[LIVE]** | `typ=ACCESS`, `uid` 390/391, `role=SUBUSER`, `cid` 110/111, `sid`, 900 s |
| Login from another installation id (also the other Subuser's) | **LIVE** 403 `DEVICE_CHANGE_NOT_ALLOWED` |
| Signed requests (Subuser 1, strictly sequential) | **LIVE** valid 200; replayed nonce 401; same or lower counter 401; corrupted signature 401; timestamp 10 min old 401; no signature headers 401; Subuser 2's or Customer 110's device id 401 "does not match authenticated session device"; Subuser 2's private key 401; Subuser 2's token with Subuser 1's device and key 401; valid again 200 |
| No bearer token | **LIVE** 403 (existing behaviour, unchanged) |
| Logout | **LIVE** the same token is refused at once (403), the refresh token answers `SESSION_TERMINATED`, DB `TERMINATED / USER_LOGOUT`; a new login on the same device opened a new ACTIVE session |
| Registration token used as an access token | not repeatable here (a registration token is single-use and consumed; no ACTIVE device may be revoked); covered by Batch 3 live evidence and `DeviceBootstrapIntegrationTest` **[TEST]** |
| Customer suspension / device revocation ends Subuser sessions | **TEST** (existing session and revocation tests); not done live to keep the Customers active |

## E. Voter access

Signatures are required on `/voters`, `/turnout`, `/sync`, `/leases`; every call above was signed.

| Check **[LIVE]** | Subuser 390 (Vidhan, part 1) | Subuser 391 (Panchayat, booth 1) |
|---|---|---|
| List (default paging) | **5** voters, all part/booth 1 | **5**, all booth 1 |
| Paging (limit 3: pages of 3 and 2, then empty; limit 100; page 5) | totals stay 5, never more | same |
| `booth` filter: 1 / 2 / 99 / −1 | 5 / 0 / 0 / 0 | 5 / 0 |
| EPIC of a voter in the unassigned booth | 0 | 0 |
| Name or house number of an unassigned-booth voter | 0 | 0 |
| The other Customer's EPIC or surname | 0 | 0 |
| Own booth: EPIC, name, partial name, house, gender, `voted` | work (e.g. gender F → 2) | work |
| Extra JSON (`datasetId`, `state`, `booths`, `allBooths`, `boothNo`) | ignored, still 5 | ignored, still 5 |
| Other Customer's entitlement id | 404 | 404 |
| Detail / family / slip of the unassigned-booth voter | 404 | 404 |
| Detail / family / slip of the other Customer's voter | 404 | 404 |
| Non-existent voter id | 404, same text as the two rows above | same |
| Detail / family / slip of an own-booth voter | 200 | 200 |

Search that **does not exist**: relative name, serial number, ward, partial EPIC (EPIC is exact; `name` matches the voter's own names only). `sortBy`/`sortDirection` are ignored. Not pretended to work.
**Family:** each voter in both datasets has a distinct house number, so the live family of any voter is the voter alone (1 member); the grouping is by booth + house number. A genuine multi-member family and the booth boundary of a family are **TEST** (`CustomerVoterFieldVisibilityIntegrationTest`, Batch 4: two housemates in booth 1, a third voter elsewhere). No live multi-member claim is made.

## F. Election-specific fields

Allowlist = `election_type_field` (FILTER + DISPLAY): 35 columns for VIDHAN_SABHA, 36 for PANCHAYAT (Batch 4 table in `CUSTOMER_AUDIT_REPORT.md`, section F). Unchanged and not regressed.

| Endpoint | Subuser 390 (VIDHAN_SABHA) | Subuser 391 (PANCHAYAT) |
|---|---|---|
| Detail **[LIVE]** | `pcNo`, `pcNameEn` null; `additionalFields` = exactly the 12 configured extras; `mobileNumber` null | `pcNo`, `pcNameEn`, `acNo`, `acNameEn`, `sectionNo` null; `additionalFields` = exactly the 16 configured extras (Panchayat/Zilla/Samiti names and wards, `panchayat_no`) |
| List / family **[LIVE]** | summary properties only; no `additionalFields`, no internal key | same |
| Slip **[LIVE]** | `slipFields` = the 19 `on_slip` columns | same 19 |
| Turnout responses **[LIVE]** | `customerAccountId, markedAt, source, voted, voterRowId` — no voter columns | same |
| Lease payload **[LIVE]** | no voter data, no dataset id | same |
| Internal keys (`datasetId`, hash, encrypted number, image key, `createdAt`) | none found | none found |
| Sync | refused for Subusers (403) | refused (403) |
| Cross-election leakage | none | none |

Regression tests for the mapper were added in Batch 4 and still pass **[TEST]**. Subusers get the same projection as Customers because both paths share `VoterMapper`.

## G. Voter slip

`GET /voters/{id}/slip?entitlementId=…` **[LIVE]**.

| Check | Vidhan (Subuser 390, part 1) | Panchayat (Subuser 391, booth 1) |
|---|---|---|
| Payload | header (part, booth, serial, voter id, names, relative, house, age, gender, polling station and address) + `slipFields` | same shape |
| `slipFields` vs the database row of that voter | **identical**, 19 columns, in display order | identical |
| Slip of an unassigned-booth voter | 404 | 404 |
| Slip of another Customer's voter | 404 | 404 |
| Fields outside the type | none | none |

Observation **[INSPECT + DB]**: `on_slip` is the same 19 columns for every election type (part, booth, booth name/address, serial, voter names, relative names, house, age, sex). AC, panchayat, district and ward are **not** on a slip by the current configuration, so the brief's "AC / Panchayat / ward on the slip, where configured" does not apply; this is the authoritative Excel-derived seed, not changed here.
**Physical Bluetooth printing was NOT tested.** There is no Flutter source and no printer in this workspace; only the backend payload and authorization were verified. No print success is claimed.

## H. Turnout operations

`NOT VOTED` is represented by the **absence** of a `voter_turnout` row (no separate status). Contract **[INSPECT]**: `POST /turnout/mark` `{entitlementId, voterRowId}` (Customer or Subuser, booth-scoped, `ON CONFLICT DO NOTHING`, server time); `/unmark` and `/reset` are Customer-only and online; Subusers cannot reverse a mark.

| Check | Result |
|---|---|
| Refused marks **[LIVE]** (turnout rows 0 before and after) | unassigned-booth voter, the other Customer's voter, non-existent id, negative id, the other Customer's entitlement (with own or foreign voter), injected `datasetId`/`customerAccountId` → 404; missing fields, a string id, malformed JSON → 400; corrupted signature, no signature → 401; Subuser unmark and reset → 403 |
| Subuser 390 marks 2 voters of part 1 | **LIVE** 200 ×2; DB: 2 rows, `dataset 113`, `customer 110`, `marked_by 390`, `ONLINE`, `part_no=1` |
| Subuser 391 marks 3 voters of booth 1 | **LIVE** 200 ×3; DB: 3 rows, `dataset 132`, `customer 111`, `marked_by 391`, `ONLINE`, `booth_no=1` |
| Duplicate marks (each voter again) | **LIVE** 200 each, **no extra row** (still 2 and 3) |
| Reversal | **LIVE** Customer 110 unmarked one voter → row deleted, statistics 2 → 1 (10 %), the voter reappeared for the Subuser under `voted=false`; the Subuser re-marked it → 2 again (20 %) |
| Cross-Customer reversal | **LIVE** Customer 111 unmarking Customer 110's voter (via its own or the other entitlement), Customer 110 unmarking an unmarked or foreign voter, reset of an empty booth → no row changed |
| Expired entitlement / expired assignment | **TEST** (`ValidityWindowBoundaryIntegrationTest`, `OfflineAccessRevocationIntegrationTest`); not live |
| Invalid device signature | **LIVE** 401, nothing written |

### Offline lease and write-back **[LIVE]**

The feature exists (`POST /leases/booth`, `POST /leases/writeback`, Subuser only, signed). The lease is an Ed25519-signed authorization (booth, entitlement, device, sequence, 24 h expiry); it carries **no voter data and no dataset id**, so there is no offline data package to check for fields.

| Check | Result |
|---|---|
| Lease for own assignment | 201 for both Subusers; DB `expires_at − issued_at` = 1 day |
| Lease for the other Subuser's or the old residue assignment, non-existent assignment | 404; Customer token → 403 |
| Write-back of 7 marks (Subuser 390): in-booth voter, unassigned-booth voter, other Customer's voter, an already voted voter, a time before the lease, a time after expiry, a non-existent voter | **1 accepted, 6 rejected**; DB row `OFFLINE_LEASE`, `marked_by 390` |
| Identical write-back again | 0 accepted, 7 rejected |
| Subuser 391: booth-1 voter, booth-2 voter, Customer 110's voter, an already voted voter | 1 accepted, 3 rejected |
| Subuser 391 using Subuser 390's lease | 404 |
| Empty marks / bad lease id | 400 |
| Restoring totals | both offline marks were then removed by the owning Customer's own unmark, leaving exactly 2 and 3 |
| Same booth number in another AC, other dataset, unreadable criteria, revoked/expired assignment, suspended Customer | **TEST** (`EntitlementAreaScopeRegressionIntegrationTest`, `OfflineAccessRevocationIntegrationTest`, now also `SubuserTurnoutWorkflowIntegrationTest` for the AC-13/booth-1 case); the fix in `OfflineLeaseServiceImpl` is intact and no expiry grace was introduced |

## I. Customer turnout statistics

`GET /turnout/statistics?entitlementId=…` with each **Customer's own** token and device (not a Subuser's).

| | Customer 110 (VIDHAN_SABHA) | Customer 111 (PANCHAYAT) |
|---|---:|---:|
| Total voters | 10 | 10 |
| Voted | **2** | **3** |
| Not voted | **8** | **7** |
| Turnout | **20 %** | **30 %** |
| By booth (total/voted) | 1: 5/2, 2: 5/0 | 1: 5/3, 2: 5/0 |
| PostgreSQL (`voter` LEFT JOIN `voter_turnout`) | 10 / 2 / 8 / 20.00 | 10 / 3 / 7 / 30.00 |

The Customer sees the **whole** purchased scope including the unassigned booth; a Subuser sees only its own booth (390: 5 voters, 2 voted, 40 %; 391: 5, 3, 60 %) and gets 403 for another booth, 404 for the other Customer's entitlement. Voted + not voted = total; no dataset information is exposed; the other Customer's turnout is invisible. The values are computed by SQL, nothing is hard-coded. Reversal updates the counts (checked above). Percentage arithmetic with non-zero values is also in `SubuserTurnoutWorkflowIntegrationTest` (1 of 3 = 33.33 %).

**Reconciliation (read-only SQL, after the final workflow):** Customers 110/111 ACTIVE; entitlements 1071/1072 ACTIVE (to 2026-12-25); datasets 113/132 hold 10 voters each; Subusers 390/391 belong to 110/111; 1046 covers exactly part 1 (5 voters), 1047 exactly booth 1 (5 voters); part 2 and booth 2 remain unassigned (5 voters each); turnout rows = 2 (dataset 113, part 1, by 390) and 3 (dataset 132, booth 1, by 391); **unauthorized turnout rows = 0**; Owner 376, Subowner 383, Agents 384/387 ACTIVE; sales 1016/1017 COMPLETED; 6 ACTIVE devices; 6 ACTIVE sessions; 2 leases.

## J. Bugs found and fixes

| # | Defect | Root cause | Fix | Regression test |
|---|---|---|---|---|
| 1 | **A repeated mark returned the time of the repeat** instead of the stored first-mark time (`markedAt` 14:42:46 vs stored 14:41:55) — contradicts "first valid mark wins, repeats are confirmations" | `TurnoutServiceImpl.markVoted` returned `clock.instant()`, not the row's `marked_at` | read the stored `marked_at` after the insert and return it | `duplicateMarkReportsStoredTime` (fails on the old code) |
| 2 | **Turnout unmark and reset were not audited** (`security.md` requires `audit_log` for them) | `TurnoutServiceImpl` had no `AuditService` | `TURNOUT_UNMARKED` / `TURNOUT_RESET` rows (actor, Customer, dataset, counts only, no voter data) | `reversalIsCustomerOnlyScopedAndAudited` (fails on the old code); live: audit ids 339/340 |
| 3 | **`GET /subusers/{id}` returned 500 for the Owner**, and **returned a Customer's own primary login** (role `CUSTOMER`, phone as username) as a "Subuser" | `getSubuserDetail` dereferenced the Owner's null Customer id and never checked `role == SUBUSER` | role must be `SUBUSER`; Customers only their own; the Owner may look at any Subuser | `subuserDetailRules` (fails on the old code); live: Owner 200, Customer 404 |

Files changed: `TurnoutServiceImpl`, `SubuserServiceImpl`, `docs/api/API_REFERENCE.md` (turnout notes), new `SubuserTurnoutWorkflowIntegrationTest` (6 tests). No migration, no schema change. Old behaviour was simulated by restoring the committed versions: 3 of the 6 new tests fail there. Live workflows were re-run after the fix (items 1–3 above) and both election types still behave.
Not a defect: the voter-access, booth-scope, offline-scope, device and cross-tenant paths all behaved correctly; no change was needed.

## K. Remaining issues and observations

1. **Username namespace is global**: "Username is already taken" (409) also fires for a Customer's phone login, so any Customer can probe whether a phone number is registered, and usernames of other tenants. Unique usernames are by design; a fix would reserve E.164-shaped usernames (not done — a policy decision).
2. Failed assignment attempts and refused turnout marks are not audited (only successes are); the rule lists DENIED rows for assign/revoke.
3. A Subuser cannot reverse a mark (Customer-only, by the matrix); the app needs the Customer for corrections.
4. Assignments last at most 7 days; 1046 and 1047 expire on 2026-12-02.
5. Booth assignment audit and Subuser creation audit store the Subuser's username/id — fine, no secrets.
6. Search gaps, ignored sort, exact-only EPIC, family = booth + house number (see E).
7. Slip has no AC/district/ward by configuration (see G).
8. Statistics percentage is rounded to 2 decimals; booth names fall back to `Booth N`.
9. Subuser `contactPhone` is accepted but never stored.
10. Unauthenticated and expired tokens still return 403 (unchanged).

## L. Production and mobile limitations

- **Physical Bluetooth printing and the Flutter print/offline UI were not tested** (no client source, no printer).
- Offline data on the device is outside the backend's reach; the lease contains no voter data, so a Subuser's offline voter list must come from earlier online paging. How the app caches it was not verifiable here.
- The dev profile connects as `postgres`; the runtime role `elecsarthi_app` was not exercised live (the schema suite covers its grants).
- Real devices, key storage (Keystore / Secure Enclave) and clock drift against the ±5-minute window were simulated, not tested.
- HTTPS, `sslmode=verify-full`, rate limiting at the proxy, and secret-store key versions were not exercised.
- Expiry, revocation and suspension behaviour was verified by automated tests, not live.

## M. Final role-wise audit status

| Role | Status | Evidence |
|---|---|---|
| Owner | Audited (Batch 1) + Owner support view of Subusers fixed (this batch) | LIVE + TEST |
| Subowner | Audited (Batch 2) | LIVE + TEST |
| Agent | Audited (Batch 3): customers, sales, filters | LIVE + TEST |
| Customer | Audited (Batch 4) + Subuser management, assignment, turnout reversal and statistics (this batch) | LIVE + TEST |
| Subuser | Audited (this batch): creation, assignment, login/device, booth-scoped access, fields, slip, online and offline turnout | LIVE + TEST |

Nothing was committed, pushed or merged. No Customer, Agent, Subuser or dataset was created beyond the two Subusers; no dataset was modified. The backend is stopped; PostgreSQL on 5498 is still running. Credentials, keys, counters and tokens stay in the session scratchpad (`sub1.pw`, `sub2.pw`, `dev/sub1`, `dev/sub2`, plus the Customer material from Batch 4), never in the repository.

## Test results

| Run | Result |
|---|---|
| New `SubuserTurnoutWorkflowIntegrationTest` | **TEST** 6 run, 0 failed; 3 fail against the committed (old) services |
| Schema suite `tools/db/run_tests.ps1` (V1–V7, `es_test`) | **PASS: 83, failures: 0** |
| `.\mvnw.cmd -o clean verify` | **TEST** 184 run, 0 failures, 0 errors, 0 skipped, compilation OK, `BUILD SUCCESS` (178 before this batch + 6 new) |

Not executed: expired entitlement/assignment, suspension and device revocation live; a physical print; booth numbers duplicated in another AC against `es_audit`.

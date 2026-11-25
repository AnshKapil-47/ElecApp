# Customer Audit Report — Batch 4

Scope: Customer activation, Customer login and device binding, voter list / search / details / family / slip / sync, election-type-specific field visibility, cross-tenant isolation, initial turnout statistics.
Not in scope and not done: Subusers, booths, slips printed for use, turnout marking, offline leases.

Labels: **LIVE** = real HTTP requests against the running backend and `es_audit`; **TEST** = automated test against `es_test`; **INSPECT** = read from code or configuration.
No password, token, activation code or private key appears in this report. Voter values are synthetic fixtures; none are quoted.

## A. Environment

| Item | Value |
|---|---|
| Branch / HEAD | `main` at `34c26f7` ("Agent audit"); working tree was clean at the start. Batch 3 had been committed by the user. |
| Preserved | Everything from Batches 1–3 (nothing reset, stashed or switched). All Batch 4 changes are uncommitted. |
| Profile / port | `dev`, port 8080 |
| Database | Existing PostgreSQL 18 cluster `127.0.0.1:5498`, database **`es_audit`** (V1–V7), not recreated |
| Isolation | The datasource override was passed **inline** to the backend only, never exported. `pg_stat_activity` showed the backend connected only to `es_audit`, none to `es_test`. Maven ran only while the backend was stopped, with `application-test.yaml` → `es_test`. After the schema suite and `clean verify`, `es_audit` still held both datasets (20 voters), both sales and both Customers. |
| DB user | `postgres` (dev profile), not the restricted runtime role |
| Client | `curl` plus a small Java ECDSA tool kept outside the repository; no frontend exists in this workspace |

## B. Customer activation

Contract **[INSPECT]**: `POST /api/v1/auth/activate` with `contactPhone`, `activationCode` (10 chars), `password` (≥ 8 chars, no other rule). Code = HMAC-SHA-256 with a server pepper, 72 h, 5 attempts. Activation does **not** bind a device; it returns a single-use `registrationToken`.

| Check | Result |
|---|---|
| Codes still valid, attempts 0 for Customer 111, 4 for Customer 110 (my enumeration probes) | **LIVE** yes; expiry 2026-11-28 |
| Customer 110: activate with its Batch 3 code | **LIVE** 200; `activation_status=ACTIVATED`, hash cleared, attempts reset, user 388 `PENDING → ACTIVE`, BCrypt `$2a$12$` |
| Re-use of the same code | **LIVE** 400 generic message |
| Wrong code | **LIVE** 400 (see bug 1) |
| Customer 111: 5 wrong codes, then the **correct** code | **LIVE** five 400, then **403 `ACTIVATION_MAX_ATTEMPTS_EXCEEDED`**; five `CUSTOMER_ACTIVATE/DENIED` audit rows |
| Re-issue by the owning Agent 387 (supported workflow) | **LIVE** 200 new code, attempts reset to 0, audit `ACTIVATION_CODE_REISSUE/SUCCESS`; no hash was edited in SQL |
| Customer 111: activate with the re-issued code, then re-use | **LIVE** 200, then 400 |
| Re-issue on an already-activated account | **LIVE** 400 "already activated" |
| Expired code | **TEST** (new) 400 `ACTIVATION_CODE_EXPIRED`, account stays PENDING; not exercised live because it would need editing `es_audit` |
| Password rule | Minimum 8 characters only (**INSPECT**); a weak password is accepted |
| Rate limit | **LIVE** 12 requests/min on one phone → 10 × 400, then 429; the limiter keys on the supplied phone, so it does not reveal registration |
| Audit | **LIVE** `CUSTOMER_ACTIVATE` SUCCESS ×2, DENIED ×9; unknown phone `CUSTOMER_ACTIVATION/DENIED`; no secret or phone number in any `details` |

### Account enumeration

Before the fix a wrong code on a **registered pending phone** answered `Invalid activation code. Attempts remaining: N`, while an unknown phone answered `Invalid activation credentials or account not eligible for activation.` — one request revealed that a phone is registered, and the counter revealed how many attempts were left. After the fix **[LIVE]** unknown phone, registered phone with a wrong code, already-activated phone: identical status (400), code and message; response times 9–11 ms for both (an unknown phone does the same HMAC work).
Residual: after five wrong codes (or after expiry) the registered phone answers 403 `ACTIVATION_MAX_ATTEMPTS_EXCEEDED` / 400 `ACTIVATION_CODE_EXPIRED`. That is the documented, tested design, so it was left alone; see K1.

## C. Authentication

| Check | Result |
|---|---|
| WEB login as Customer | **LIVE** 403 "Mobile application users cannot log in to the web console." |
| Wrong password / unknown identifier / the other Customer's phone with this password | **LIVE** all 401 "Invalid username or password." |
| Login identifier | Username **is** the phone (E.164); login by phone resolves the same account **LIVE/DB** |
| First MOBILE login | **LIVE** 200 `deviceRegistrationRequired=true` + registration token |
| Separate synthetic devices | **LIVE/DB** 4 ACTIVE devices (Agents 384, 387, Customers 388, 389), 4 distinct installation ids, 4 distinct public keys; each Customer device carries its own `customer_account_id` (110 / 111) |
| Register → challenge → verify → login | **LIVE** 200 / 200 / 200 / 200; role `CUSTOMER`, `clientType=MOBILE`, `deviceRegistrationRequired=false` |
| Session | **LIVE/DB** `ACTIVE`, `MOBILE`, bound to the device, refresh hash 64 hex, absolute 90 days, one ACTIVE session per Customer |
| Token claims | **LIVE** `typ=ACCESS`, `uid` 388/389, `role=CUSTOMER`, `cid` 110/111, `sid`, lifetime 900 s. `sub` is the phone number. |
| Login from another installation id (also Customer 111's) | **LIVE** 403 `DEVICE_CHANGE_NOT_ALLOWED`; no installation id → 400 |
| Refresh | **LIVE** signed refresh 200; refresh without device proof 401 |
| Refresh-token reuse | **LIVE** I reused a rotated token **by mistake** on Customer 110: 401 refresh-token-reuse error, session `TERMINATED/REFRESH_REUSE`, one audit row. The rule works; I logged in again on the same device. |
| Expired access token | **LIVE** after 15 min: 403 (the unauthenticated behaviour, left as instructed) |
| No bearer token | **LIVE** 403 |

### What `mobile_access=false` means **[INSPECT + LIVE]**

`entitlement.mobile_access` is only the third gate of the **voter mobile-number** rule: numbers are unmasked only if the Owner's global switch is on, the caller is the CUSTOMER and this flag is true (`VoterAccessAuthorizer.isMobileUnmasked`, used by `VoterServiceImpl` and `SyncServiceImpl`). Only the Owner can change it (`PUT /entitlements/{id}/mobile-access`); a sale always creates it `false` (`SaleServiceImpl`). It plays **no part** in login, device registration or app access. Live proof: both Customers registered, logged in, listed, searched and synced with it `false`, and it was **not changed**. No voter in datasets 113/132 has a mobile number, so unmasking could not be observed live; masking/unmasking is covered by `MobileAccessPolicyIntegrationTest` **[TEST]**.

### Signed requests **[LIVE]** (Customer 110, one request at a time, counter always increased)

Signatures are required on `/voters`, `/sync`, `/turnout`, `/leases`.

| Request | Result |
|---|---|
| Valid | 200 |
| Same nonce replayed | 401 "Replayed nonce detected" |
| Same counter again / lower counter | 401 "not strictly increasing" |
| Corrupted signature | 401 "Invalid request signature" |
| Signed with the other Customer's private key | 401 "Invalid request signature" |
| Timestamp 10 min old / 10 min ahead | 401 "outside allowed window" |
| No signature headers on `/voters` | 401 "Missing required device signature headers" |
| Other Customer's or an Agent's `X-Device-Id` | 401 "Device ID header does not match authenticated session device" |
| Valid again afterwards | 200 (counter in DB 144) |

## D. Vidhan Sabha Customer (110, entitlement 1071, MADHYA PRADESH / AC 201)

| Check | Result |
|---|---|
| Entitlement list `GET /entitlements/my` | **LIVE** one row, ACTIVE, 29 days remaining, no dataset id (but see K6) |
| Voter list | **LIVE** 10 voters; part 1 = 5, part 2 = 5; booth 1 = 5, booth 2 = 5 (`booth` filter), booth 3 = 0 |
| Scope | **LIVE/DB** all rows are `MADHYA PRADESH / AC 201` of dataset 113; no row of dataset 132 |
| Pagination | **LIVE** limit 4 → pages of 4, 4, 2; limit 0 or negative → 1; limit 1000 → accepted (all 10 rows; the code caps a page at 100); negative page → 0 |
| Filters | **LIVE** `voted` false = 10 / true = 0; gender F = 5; age 30–45 = 5; house `15` + booth 1 = 1 |
| Sorting | `sortBy` / `sortDirection` are accepted and **ignored** (always booth, serial) — K4 |
| Search | **LIVE** EPIC exact (case-insensitive) = 1; partial EPIC = 0 (exact only); name `aar`, full name, surname work; relative name = 0 (not supported) |
| Negative search | **LIVE** unknown EPIC, `' OR 1=1 --`, `AUD%`, 3 000-char name, Unicode: no error, no extra rows; the other Customer's EPICs = 0 |
| Scope-escape attempts | **LIVE** extra JSON properties (`state`, `acNo`, `panchayatNo`, `datasetId`, `criteria`, `columns`, `sortBy` with SQL, `fields:*`) are ignored; still exactly the 10 own rows; voter table intact |
| Foreign / missing entitlement id | **LIVE** 1072, 999999, 0, -1 → 404 `Entitlement not found: <id>`; missing id → 400 |
| Details | **LIVE** 200, see F |
| Family | **LIVE** 200; each voter in dataset 113 has a different house number, so the family is the voter alone. **No multi-member family exists in the live data.** The 2-member grouping and the family boundary are proven by **TEST** (`CustomerVoterFieldVisibilityIntegrationTest`: two housemates in one booth, a third voter elsewhere). Family = same booth value + same non-blank house number; a voter without a house number gets an empty list. |
| Direct identifiers | **LIVE** detail / family / slip of a Customer-111 voter with its own entitlement → 404 `Voter not found with ID: x`; with Customer 111's entitlement → 404 `Entitlement not found`; a non-existent voter id → the same 404 text, so existence is not revealed |

## E. Panchayat Customer (111, entitlement 1072, CHHATTISGARH / AUDIT SUB DISTRICT / 501)

Same battery **[LIVE]**: 10 voters (booth 1 = 5, booth 2 = 5, part 1/2 = 5/5); paging, `voted`, gender and house filters work (gender F with age 40–52 = 3); EPIC search works; Customer 110's EPICs → 0; Customer 110's voter by id → 404 for both own and foreign entitlement; all scope-escape properties ignored (still 10); `GET /entitlements/my` shows only 1072; no Vidhan data and no dataset id anywhere. Family of the first voter of each booth = the voter alone (no shared house numbers in dataset 132; multi-member case proven by **TEST**, as in D).

## F. Field visibility matrix

Source of truth **[INSPECT + DB]**: `election_type_field` (FILTER and DISPLAY rows; 35 columns for VIDHAN_SABHA, 36 for PANCHAYAT). Before the fix the DTOs ignored it.

| Category | VIDHAN_SABHA | PANCHAYAT |
|---|---|---|
| Identity (voter id, serial, names en/mt, relative names en/mt, house no, age, sex) | allowed | allowed |
| Booth / part (part no, booth no, booth name and address en/mt) | allowed | allowed |
| State | allowed (filter) | allowed (filter) |
| District | allowed (display) | allowed (filter) |
| AC no | allowed (filter) | **denied** |
| AC name en/mt | allowed | **denied** |
| PC no / PC name | **denied** | **denied** |
| Section no | allowed | **denied** |
| Section name en/mt, house location en/mt | allowed | allowed |
| Rural/urban, tehsil, block, pincode | allowed | **denied** |
| Panchayat no | **denied** | allowed (filter) |
| Zilla parishad name en/mt + ward no, Panchayat samiti name en/mt + ward no, Panchayat name en/mt | **denied** | allowed |
| Municipal names and ward numbers (Nagar Nigam/Palika/Parishad) | **denied** | **denied** |
| Voter mobile number | only through the 3-gate rule; `mobile_no` is configured for both | same |
| Voter image | configured, but no endpoint exists and `voter_image_ref` is never returned | same |
| `id`, `dataset_id`, `source_record_key_hash`, `mobile_no_enc`, `mobile_key_version`, `voter_image_ref`, `created_at` | never returned | never returned |

**Mechanism (fix 3):** `VoterMapper` now reads the election type's metadata once per request. A fixed property is filled only if its column is configured, otherwise it is `null`. Configured columns without a fixed property are returned in `additionalFields` (keys = column names, in display order). A hard deny-list blocks internal columns even if the metadata were mis-configured. The slip header is limited to the type's `on_slip` columns. Sync chunks use the same mapper. No second mapping system was added.

| Endpoint | VIDHAN_SABHA | PANCHAYAT | Cross-election leak |
|---|---|---|---|
| `POST /voters/search` | LIVE + TEST | LIVE + TEST | none |
| `GET /voters/{id}` | LIVE + TEST | LIVE + TEST | none |
| `GET /voters/{id}/family` | LIVE + TEST | LIVE + TEST | none |
| `GET /voters/{id}/slip` | LIVE + TEST | LIVE + TEST | none |
| `/sync/sessions/{id}/chunks/{n}` | LIVE + TEST | LIVE + TEST | none |
| Voter overlays, bulk export | do not exist (out of scope by design) | | |

**LIVE proof** (detail response compared with the metadata read from `es_audit`): Customer 110: `pcNo`, `pcNameEn` null; `additionalFields` = exactly the 12 configured extras. Customer 111: `pcNo`, `pcNameEn`, `acNo`, `acNameEn`, `sectionNo` null; `additionalFields` = exactly the 16 configured extras (including `panchayat_no`, Panchayat/Samiti/Zilla names and wards). Slip `slipFields` = the 19 `on_slip` columns for both.
**Limitation:** datasets 113 and 132 have null values in the other type's columns, so a leak could not show up live. The leak is proven by **TEST**: every voter column holds a unique marker value, and each response is checked against the metadata-derived allowlist. With the old behaviour simulated, 3 of the 7 tests fail (`pc_no` reached both Vidhan and Panchayat responses).

## G. Customer security and privacy

| Check | Result |
|---|---|
| Customer 110 reading Customer 111 data and the reverse (list, details, family, slip, statistics, sync) | **LIVE** 404 everywhere; same text for an existing foreign voter and a non-existent one |
| Admin / other-role APIs as a Customer (datasets and dataset by id, users, subowners, agents, customers list, sales, sale void, direct entitlement, mobile-access, revoke, suspend, reactivate, reassign, customer create, code re-issue, mobile-number switch, sale-form filters) | **LIVE** 403 (with well-formed bodies and ids that cannot match, so nothing could be created); `GET /customers/{other}` 404; `GET /entitlements/customer/{other}` and `/entitlements/{other}` 404 |
| Subusers of another Customer | **LIVE** `GET /subusers/380` (another Customer's) → 404 from both Customers, same text as a non-existent id; own list empty. Subuser creation and booth assignment were **not** exercised (Batch 5) |
| Dataset inventory | **LIVE** `/datasets` 403 for Customers; no response contains a dataset id, name, file name, hash or count of a dataset |
| Modifying entitlement / dataset choice | not possible: no such endpoint for Customers; the injected `datasetId` is ignored (**LIVE**) |
| Agent 384 | **LIVE** sees only Customer 110 (now ACTIVATED); `/customers/111` 404; voter search 403; re-issue on an activated account 400 |
| Entitlement checks per request | **INSPECT/TEST** `VoterServiceImpl.authorize` re-reads the entitlement, status and validity window on every call; expiry and revocation are covered by `ValidityWindowBoundaryIntegrationTest` and related tests |
| Session / device | see C |

## H. Initial turnout statistics

`GET /api/v1/turnout/statistics?entitlementId=…` exists **[INSPECT]** (totals, per booth, per gender). Baseline from the database **[LIVE/DB]**: `voter_turnout` has **0 rows**.

| Metric | Customer 110 | Customer 111 |
|---|---:|---:|
| Total voters | 10 | 10 |
| Voted | 0 | 0 |
| Not voted | 10 | 10 |
| Turnout % | 0.0 | 0.0 |
| Booth breakdown | booth 1: 5/0, booth 2: 5/0 | booth 1: 5/0, booth 2: 5/0 |
| Gender breakdown | F 5/0, M 5/0 | F 5/0, M 5/0 |

These equal `SELECT COUNT(*)` on each dataset and the booth/gender grouping in PostgreSQL. `boothNo=2` narrows to 5; a booth with no voters returns 0 rows; the other Customer's entitlement → 404. Scope label is the Customer's own (`Vidhan Sabha · MADHYA PRADESH · AC 201` / `Panchayat · CHHATTISGARH · AUDIT SUB DISTRICT · Panchayat 501`). No dataset id or file name appears. With zero turnout the percentage arithmetic is only tested at 0 %; non-zero values belong to Batch 5.
Two completed sync sessions (one per Customer) now exist in `sync_session`; they changed no voter or turnout data.

## I. Bugs found and fixes

| # | Defect | Root cause | Fix | Regression test |
|---|---|---|---|---|
| 1 | **Activation reveals registered phones** and the attempt counter | `AuthServiceImpl.activateCustomer` answered a wrong code with "Invalid activation code. Attempts remaining: N", unlike the unknown-phone message | Wrong code now throws the same `INVALID_ACTIVATION` response; the lockout is unchanged | `CustomerActivationSecurityIntegrationTest.wrongCodeOnRegisteredPhoneLooksLikeUnknownPhone` (+ `expiredCodeIsRefused`, which had no test) |
| 2 | **Login timing reveals which identifiers exist** (unknown user 4 ms vs wrong password 217 ms) | `AuthServiceImpl.authenticate` returned before any BCrypt work for an unknown user, against its own comment | A dummy BCrypt check (same cost) runs for an unknown identifier | `unknownIdentifierLoginCostsAsMuchAsWrongPassword` (fails with the check removed) |
| 3 | **Voter responses ignored the election type**: fixed DTOs exposed PC/AC/section columns the type does not list, and never exposed configured fields (tehsil, pincode, Panchayat names, wards, `vr_*_mt`, …) | `VoterMapper` copied a fixed set of columns; `election_type_field` was used only for filters and the slip | Metadata-driven field policy in `VoterMapper` (see F); slip header limited to `on_slip` columns; callers pass the election type id | `CustomerVoterFieldVisibilityIntegrationTest` (7 tests); 3 fail on the old behaviour |
| 4 | **Voter search returned 400 unless `page` and `limit` were sent**, and **sync-session start returned 400 unless `protocolVersion` was sent** | Primitive `int`/`short` fields with `@Builder.Default` became mandatory when Jackson deserialized the JSON ("Cannot map `null` into type `int`"). Every existing test sent them explicitly, so none noticed. | `Integer page, limit` and `Short protocolVersion`; defaults (0, 50, 1) applied in the services | `pagingDefaultsApplyWhenOmitted` (real signed `MockMvc` requests) |

Files changed: `AuthServiceImpl`, `VoterMapper`, `VoterServiceImpl`, `SyncServiceImpl`, `VoterSearchRequest`, `StartSyncSessionRequest`, `docs/api/API_REFERENCE.md` (activation path `/auth/activate` instead of `/auth/customer-activate`, request/response shape, error behaviour, field-visibility and paging notes), plus the two test classes. No migration, no schema change.
Contract note for the Flutter client: `page`/`limit`/`protocolVersion` are now optional (no break); detail responses gain populated `additionalFields`; properties not configured for the election type are now `null`.

## J. Test results

| Run | Result |
|---|---|
| Targeted: `CustomerActivationSecurityIntegrationTest` (5), `CustomerVoterFieldVisibilityIntegrationTest` (7) | **TEST** 12 run, 0 failed |
| Old behaviour simulated (field policy → everything allowed) | 3 of 7 visibility tests fail |
| Old behaviour simulated (no dummy BCrypt) | timing test fails: 4 ms vs 217 ms |
| Schema suite `tools/db/run_tests.ps1` (V1–V7, `es_test`) | **PASS: 83, failures: 0** |
| `.\mvnw.cmd -o clean verify` | **TEST** 178 run, 0 failures, 0 errors, 0 skipped, compilation OK, `BUILD SUCCESS` (168 before this batch + 10 new: 3 activation/login, 7 field-visibility) |

Tests ran against `es_test` with the backend stopped; `es_audit` was intact afterwards (datasets 113/132, 20 voters, both sales and entitlements).
Not executed: expired-code and expired-entitlement behaviour against `es_audit` (needs editing live data); unmasked mobile numbers live (no voter has one); Subuser, booth, turnout and lease paths (Batch 5).

## K. Remaining issues

1. **Activation lock and expiry are still distinguishable.** After five wrong codes, or after expiry, a registered pending phone answers 403/400 with a distinct code, so a registered phone can still be detected by an attacker willing to burn five attempts — which also locks that Customer out until re-issue. This is the documented and tested design, left unchanged. Recommendation: return the same generic 400 for lock and expiry, and tell the Customer in the Agent's phone call.
2. Unauthenticated, expired and invalid tokens still answer **403**, not 401 (unchanged by instruction).
3. Password policy is "at least 8 characters".
4. `sortBy`/`sortDirection` are accepted but ignored. Search has no relative-name, serial-number, ward or partial-EPIC option; `%` and `_` in the name search act as wildcards (harmless, inside the Customer's own scope).
5. Family = same booth + same house number only; a voter without a house number gets an empty list (not even themself).
6. `GET /entitlements/my` returns `saleLineId`, `criteriaHash` and `source` to the Customer — internal ids, not dataset internals.
7. Login audit rows use the username as `target_id`; for a Customer that is the phone number. The JWT `sub` is also the phone. By design, but it conflicts with "no phone numbers in audit".
8. On some admin POSTs body validation (400) runs before the role check (403); a Customer with a well-formed body gets 403. No data is exposed.
9. List/detail queries `SELECT v.*`, so internal columns are loaded in memory (never serialized). An explicit column list would be tidier.
10. `voter_image` is configured as a display field but no image endpoint exists.
11. Multi-member families cannot be shown live; datasets 113/132 must not be modified.
12. The dev profile connects as `postgres`, so runtime-role privileges were not exercised live.

## L. Batch 5 handoff

Both Customers are ACTIVE and ACTIVATED with the original subscriptions; nothing was recreated or changed.

| Item | State |
|---|---|
| Customer 110 (user 388) | ACTIVE, ACTIVATED, owning Agent 384, device ACTIVE, 1 ACTIVE MOBILE session |
| Customer 111 (user 389) | ACTIVE, ACTIVATED, owning Agent 387, device ACTIVE, 1 ACTIVE MOBILE session |
| Sales 1016 / 1017, entitlements 1071 / 1072 | COMPLETED / ACTIVE, MASTER, valid 2026-11-25 → 2026-12-25, `mobile_access=false`, criteria hashes unchanged |
| Datasets 113 / 132 | 10 voters each, unchanged |
| Owner 376, Subowner 383, Agents 384 and 387 | unchanged; Agent devices ACTIVE |
| `voter_turnout`, Subusers, booth assignments, leases | none created in this batch (pre-existing residue: Subuser 380 of Customer 109, one booth assignment of 2026-11-25 14:17 IST) |
| `sync_session` | two COMPLETED sessions (110, 111) |

Credentials and keys stay outside the repository in the session scratchpad (`c110.pw`, `c111.pw`, `dev/c110`, `dev/c111`: device key, installation id, device id, counter). The activation codes are consumed and cannot be reused. The backend is stopped; PostgreSQL on 5498 is still running.
**Stop condition honoured:** nothing was committed, pushed or merged; no Subuser, booth, slip use, turnout, lease or write-back was created.

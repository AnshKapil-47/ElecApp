# ElecSarthi: ER diagram and data model

Version 1.0 · 24 November 2026 · designed from the business rules, not from earlier diagrams.

| File | What it is |
|---|---|
| `ElecSarthi_ER_Diagram.png` / `.svg` | The diagram (18 tables, 26 relationships, every crow's foot machine-checked) |
| `backend/src/main/resources/db/migration/V1__elecsarthi_schema.sql` | **The schema itself:** first Flyway migration. The source of truth; this document explains it |
| `…/V2__runtime_role.sql` | Least-privilege database role for the running application |
| `…/V3__seed_election_type_field.sql` | The 271 field rows, generated from the seed CSV by `tools/seed/csv_to_migration.py` |
| `election_type_field_seed.csv` | 271 field rows generated from the colours of `NEW_APP_COLUMN_DETAILS.xlsx` |
| `tools/db/test_schema.sql`, `tools/db/run_tests.sh` | 76 business-rule checks, all passing on PostgreSQL 16.15 |
| `tools/seed/gen_seed.py`, `tools/erd/gen_erd.py`, `tools/erd/verify_ends.py` | Regenerate the seed and the diagram, and check the diagram's line ends |

---

## 1. The business in one page

**What ElecSarthi sells:** time-limited access to one area of an election voter roll, seen through one election type.

| Term | Meaning |
|---|---|
| **Election type (the lens)** | One of 8: Lok Sabha, Vidhan Sabha, Nagar Nigam, Nagar Palika, Nagar Parishad, Zilla Panchayat, Panchayat Samiti, Panchayat. It decides which columns define an area (skin cells in the sheet) and which columns the Customer sees (green cells) |
| **Area** | One value for every FILTER column of the lens, e.g. Vidhan Sabha · state HARYANA · AC 10 |
| **Tier** | MASTER = current election only, deleted 2 months after import. LONG_TERM = imported by the Owner after the Agent phones him, for Customers who need longer (e.g. 1 year) |
| **Entitlement** | One area + one lens + one tier + one validity window, owned by one Customer. **The only thing that grants access to voters** |
| **Sale** | An Agent's record of what was sold: one Customer, one lens, one tier, one or more areas (sale lines). Never deleted |

**Who does what** (Owner and Subowner use the **web app**; Agent, Customer and Subuser use the **mobile app**):

```text
OWNER ── creates SUBOWNERs, AGENTs; imports data; voids sales; switches mobile numbers on/off
SUBOWNER ── creates AGENTs; imports and deletes data; manages Customers (no sales view)
AGENT ── creates Customers; sells areas to the Customers he owns; sees only his own sales
CUSTOMER (one primary login) ── searches voters in purchased areas, prints slips, marks turnout,
                                creates SUBUSERs and assigns them booths
SUBUSER ── field worker: max 2 booths at a time, 7 days each; can work offline
```

**Money is outside the app.** The Customer pays the Agent, and the Agent pays the Owner, by cash, UPI or bank. The app records only what was provisioned: which Agent sold which area to which Customer, when, and for how long. The Agent sales report is what the Owner uses each month to settle with Agents. Voided lines are never counted.

**No notifications and no in-app requests.** The app sends no push, SMS, e-mail or in-app messages, and has no request, approval or inbox screens. Everything a person asks for is asked **by phone, outside the app**, and the person with the right role then does it directly in the app:

| Need | Who asks whom (phone) | Who then does it in the app |
|---|---|---|
| Customer wants data | Customer → Agent | Agent records the sale |
| Customer needs more than 2 months | Customer → Agent → Owner | Owner imports the area as LONG_TERM; Agent sells it |
| Customer wants voter mobile numbers | Customer → Agent → Owner | Owner switches `mobile_access` on for that one entitlement |
| Sale was a mistake | Agent → Owner | Owner voids the sale |
| Lost phone / forgotten password | Customer → Agent → Owner | Owner revokes the device, ends sessions, resets the password |
| Activation code expired | Customer → Agent | Agent (or Owner / Subowner) issues a new code |
| Agent leaves the company | — | Owner reassigns the Customer's owning Agent |

Expiring areas are shown as a list in reports and on the Customer dashboard; nobody is "notified". Words like PENDING in the schema are record states (activation not done yet, device not yet verified, import still running), never a request waiting for approval.

**Data lifecycle in one picture:**

```text
Before an election:  Owner imports the state's roll as a MASTER dataset ─────► deleted at import + 60 days
Customer needs >2 months: Agent phones Owner ─► Owner imports that area as LONG_TERM ─► kept while sold + 30 days
Agent sells:  choose Customer → lens → tier → area(s) → duration
              backend finds the one dataset of that tier holding the area ─► entitlement(s) created
Customer works: search / details / family / slip / turnout, only inside entitlements
Sale history (agent_sale, agent_sale_line) and audit_log are never deleted
```

---

## 2. From business rules to tables

Every table exists because of a rule. Nothing is here "just in case".

| Business rule | Where it lives | Enforced by |
|---|---|---|
| 5 roles; Customer has exactly one primary login | `app_user.role`, partial unique `ux_one_customer_admin` | DB |
| Customer roles belong to a Customer; platform roles never do | `ck_tenant_by_role` | DB |
| Owner and Subowner use the web; Agent, Customer and Subuser use the app with a registered phone | `ck_session_client_by_role`, `user_device.user_role` | DB |
| Only the owning Agent sells to a Customer; Owner can reassign | `customer_account.owning_agent_user_id` (role-checked FK) | DB + SVC |
| Each lens shows only its own fields; Agent filters only by skin fields | `election_type_field` (usage FILTER / DISPLAY, orders), seeded from the sheet | DB + SVC |
| Dynamic search columns come only from a whitelist | `is_voter_column()` CHECK on `source_column` | DB |
| MASTER = current election only, deleted after 2 months | `dataset.storage_tier`, `delete_after`, overlap probe on `source_record_key_hash` | DB + SVC + job |
| LONG_TERM imported by the Owner (asked by phone), kept while needed | `dataset.delete_after` recomputed on each sale | SVC |
| Corrected rolls replace old ones without breaking current Customers | `dataset.status = SUPERSEDED`, `supersedes_dataset_id` | DB + SVC |
| Same file never imported twice (but a FAILED import can be retried) | `ux_dataset_file` (unique unless FAILED) | DB |
| A sale can have many areas; same area twice in one sale is refused | `agent_sale_line` UK (sale, criteria_hash) | DB |
| Retry of a sale form never creates a second sale | UK (sold_by, idempotency_key) + `request_sha256` | DB + SVC |
| No two overlapping entitlements for one Customer + lens + tier + area; renewals start when the old one ends | `ex_ent_no_overlap` exclusion constraint | DB |
| Entitlement tier always matches its dataset's tier | FK (dataset_id, storage_tier) → dataset | DB |
| Each sale line creates exactly one entitlement | `entitlement.sale_line_id` UNIQUE + `ck_ent_source` | DB |
| Sale history survives data deletion | `agent_sale_line` has snapshots, no FK to datasets; entitlement points to the sale line, not the reverse | DB |
| Void by Owner revokes access, with who, when and why | `agent_sale` void columns, `entitlement` revoke columns | DB + SVC |
| Voter mobile numbers only when the Owner switches them on for that one area | `entitlement.mobile_access` (+ who, when) | DB + SVC + AUTH |
| Subuser: max 2 booths at a time, 7 days each, same Customer only | `booth_assignment` slot exclusion, 7-day CHECK, tenant + role FKs | DB |
| One active device per user; device proves its key | `user_device` partial uniques, challenge columns | DB + SVC |
| Offline data expires; replays are refused | `offline_lease`, `sync_session.offline_valid_until`, `device_nonce` | DB + SVC |
| Bulk sync is for the Customer primary login only | `sync_session` role-checked FK (CUSTOMER_ADMIN) | DB |
| Turnout counted once per Customer and voter | `voter_turnout` primary key | DB |
| Every sensitive action is recorded and can't be edited | `audit_log` + trigger + grants | DB |

---

## 3. The diagram

Four groups, colour-coded:

| Colour | Group | Tables |
|---|---|---|
| Blue | Identity, devices and history | customer_account, app_user, user_device, user_session, audit_log |
| Green | Election configuration and voter data | election_type, election_type_field, dataset, import_issue, voter, voter_turnout |
| Brown | Sales and entitlement | agent_sale, agent_sale_line, entitlement |
| Purple | Field operations and offline | booth_assignment, offline_lease, sync_session, device_nonce |

**Line ends:**

| End | Meaning |
|---|---|
| Bar + bar | exactly one |
| Bar + circle | zero or one |
| Crow + bar | one or many |
| Crow + circle | zero or many |

**Row colours:**

| Row | Meaning |
|---|---|
| Yellow | primary key |
| Blue | foreign key |
| Light yellow | unique key |
| Violet | snapshot with no FK |
| Red | sensitive |

Actor columns (`created_by`, `revoked_by`, `voided_by`, `marked_by`, …) are listed but not drawn.

### 3.1 Relationships

| Parent | Child (FK) | Cardinality | Why |
|---|---|---|---|
| customer_account | app_user (customer_account_id) | 0..1 – 0..N | Customer's primary login and Subusers; platform users have none |
| app_user | customer_account (owning_agent_user_id) | 0..1 – 0..N | the Agent who may sell to this Customer |
| app_user | user_device (user_id + customer) | 1 – 0..N | device history; max 1 ACTIVE |
| app_user | user_session | 1 – 0..N | one row per login |
| user_device | user_session (device_id + user) | 0..1 – 0..N | mobile sessions are bound to the user's own device |
| election_type | election_type_field | 1 – 1..N | the lens's FILTER and DISPLAY fields |
| dataset | voter | 1 – 0..N | one partition per dataset |
| dataset | import_issue | 1 – 0..N | first 200 problems |
| dataset | dataset (supersedes) | 0..1 – 0..1 | corrected roll replaces old |
| voter | voter_turnout | 1 – 0..N | at most one row per Customer |
| customer_account | voter_turnout | 1 – 0..N | |
| app_user (AGENT) | agent_sale | 1 – 0..N | |
| customer_account | agent_sale | 1 – 0..N | |
| election_type | agent_sale, entitlement | 1 – 0..N | |
| agent_sale | agent_sale_line | 1 – 1..N | |
| agent_sale_line | entitlement (sale_line_id) | 0..1 – 0..1 | a SALE entitlement has exactly one line; a line keeps its history after the entitlement is deleted |
| customer_account | entitlement | 1 – 0..N | |
| dataset | entitlement (dataset_id + tier) | 1 – 0..N | |
| entitlement | booth_assignment (entitlement + customer) | 1 – 0..N | |
| app_user (SUBUSER) | booth_assignment (subuser + customer + role) | 1 – 0..N | max 2 at a time |
| booth_assignment | offline_lease (assignment + subuser) | 1 – 0..N | |
| user_device | offline_lease (device + subuser) | 1 – 0..N | lease only on the Subuser's own device |
| entitlement | sync_session (entitlement + customer) | 1 – 0..N | one area per bulk sync |
| user_device | sync_session (device + user) | 1 – 0..N | |
| user_device | device_nonce | 1 – 0..N | replay protection |

There is no `ON DELETE CASCADE` anywhere. The only `ON DELETE SET NULL` is `dataset.supersedes_dataset_id`.

---

## 4. Tables

The SQL file has every column, type and constraint. This section explains what each table is for and the rules a developer must know.

### Identity, devices and history

**`customer_account`**: one paying organisation (candidate, party unit, consultant).
- `created_by_user_id` is permanent provenance. `owning_agent_user_id` is the Agent allowed to sell to it. It is checked to be an AGENT through a foreign key on `(id, role)`, and only the Owner changes it.
- `contact_phone`: E.164 format (`+91…`), unique, and also a login identifier for the primary user.
- **Activation code:**
  - Format: 10 characters of Crockford base32, shown once.
  - Storage: as HMAC-SHA-256 with a server pepper and key version. **Not unique**: activation looks the account up by phone first, then verifies the code.
  - Limits: 72 hours and 5 attempts. The hash is cleared on activation (DB CHECK).
  - Re-issue: the Owner, a Subowner or the owning Agent can issue a new code.
- `mobile_access_policy_version` is incremented whenever access shrinks (revoke, void, suspend, mobile switch-off). A phone holding a lower version purges its cache.

**`app_user`**: every login: Owner, Subowner, Agent, Customer primary login (`CUSTOMER_ADMIN`), Subuser.
- `password_hash` is BCrypt (cost ≥ 12). It may be null only while a primary login is PENDING activation.
- Subuser usernames are generated by the system; the Customer sets `full_name`.
- The unique keys `(id, role)`, `(id, customer_account_id)` and `(id, customer_account_id, role)` exist so other tables can prove, in the database, that a user is the right role in the right Customer.

**`user_device`**: the registered phone of an app user: Agent, Customer primary login or Subuser. Owner and Subowner use the web and have no devices.
- `user_role` is checked against `app_user` by a foreign key. `customer_account_id` is required for CUSTOMER_ADMIN and SUBUSER (and must be their own Customer) and is null for AGENT.
- **Registration:**
  1. The app generates an ECDSA P-256 key in the Android Keystore or iOS Secure Enclave, plus a random `installation_id`.
  2. The server inserts a PENDING row with a 32-byte challenge (hash stored, valid 2 minutes).
  3. The app signs the challenge; the server verifies it and sets ACTIVE.
- At most one ACTIVE and one PENDING device per user.
- An `installation_id` can never be reused within a Customer, so a revoked phone can't come back with the same identity.
- **Replacement:** a new PENDING row; when it is activated, the old row becomes REPLACED in the same transaction. Public keys are never overwritten.
- `last_request_counter`: every signed request must carry a higher counter.

**`user_session`**: one row per login. `id` is the `sid` claim in every access token.
- `user_role` is checked against `app_user`. `client_type` WEB is allowed only for OWNER and SUBOWNER; MOBILE only for AGENT, CUSTOMER_ADMIN and SUBUSER (`ck_session_client_by_role`).
- Every request checks that `status = ACTIVE`, the idle and absolute expiry have not passed, the user and Customer are active, and (mobile) the device is ACTIVE.
- **Mobile refresh token:**
  - Wire format: `sid.generation.secret`; only `refresh_token_hash` is stored. Web sessions have no refresh token (DB CHECK).
  - Rotation: every refresh increments `refresh_token_generation`.
  - Reuse: a presented generation lower than the stored one is token reuse, so the session is terminated with `REFRESH_REUSE`.
- **Concurrent limit** (Owner 3, Subowner 3, Agent 1, Customer 1, Subuser 1): the login transaction locks the user row (`SELECT … FOR UPDATE`) before counting ACTIVE sessions.
  - Above the limit, the login is rejected. The one exception: a mobile login that proves the user's ACTIVE device key replaces that device's own old session (`REPLACED_SAME_DEVICE`).

**`audit_log`**: append-only record of security and admin actions, including denied attempts (`outcome = DENIED`).
- A trigger blocks UPDATE, DELETE and TRUNCATE even for the owner. The runtime role has only SELECT and INSERT.
- `dataset_id` and `device_id` have no FK, so the log survives deletions.
- `details` never contains voter mobile numbers, passwords, hashes, tokens or keys.

### Election configuration and voter data

**`election_type`**: the 8 lenses, seeded by V1.
- `booth_column`: which voter column identifies a booth for that lens (`part_no` for Lok Sabha and Vidhan Sabha, `booth_no` for local bodies).
- `guard_column`: for the four lenses whose filters have no body name (Nagar Palika, Nagar Parishad, Panchayat Samiti, Panchayat), the name column checked at sale. If one area matches voters with more than one distinct value there, the sale line is refused with `AMBIGUOUS_AREA`, so the Customer never gets voters of two palikas or samitis.

**`election_type_field`**: the sheet, as data. One row per lens and column.
- `usage`: FILTER for skin cells, DISPLAY for green cells.
- `filter_order` and `display_order` set the order shown in the UI.
- `on_slip` marks the fields printed on the voter slip.
- `value_type` (TEXT or INT) fixes how filter values are normalised.
- Loaded by migration `V3__seed_election_type_field.sql`, which is generated from `election_type_field_seed.csv` (271 rows). Read-only at runtime.

| Lens | FILTER (skin), in order | Booth column | Guard column | Fields |
|---|---|---|---|---|
| Lok Sabha | state, pc_no, ac_no | part_no | — | 38 |
| Vidhan Sabha | state, ac_no | part_no | — | 35 |
| Nagar Nigam | state, district, municipal_name_nagar_nigam_en, nagar_nigam_ward_no | booth_no | — | 30 |
| Nagar Palika | state, district, nagar_palika_ward_no | booth_no | municipal_name_nagar_palika_en | 30 |
| Nagar Parishad | state, district, nagar_parishad_ward_no | booth_no | municipal_name_nagar_parishad_en | 30 |
| Zilla Panchayat | state, district, zilla_parishad_name_ward_no | booth_no | — | 36 |
| Panchayat Samiti | state, district, panchayat_samiti_name_ward_no | booth_no | panchayat_samiti_name_en | 36 |
| Panchayat | state, district, panchayat_no | booth_no | panchayat_samiti_name_en | 36 |

**`dataset`**: one imported Excel file.
- `name`, `original_file_name`, `file_sha256` and row counts are visible to the Owner and Subowner only.
- `states` lists the distinct states in the file, so sale-time resolution only probes datasets of the right state.
- **Lifecycle:**

```text
PENDING ──(rows loaded, no overlap)──► IMPORTED ──(newer roll supersedes it)──► SUPERSEDED
   └──(error, or overlap not declared)──► FAILED            both ──(deletion job)──► DELETING ──► gone
```

- **Overlap rule:** before IMPORTED, the importer checks whether any `source_record_key_hash` of the new file already exists in an IMPORTED dataset of the same tier.
  - If yes, the import must declare which dataset it supersedes; otherwise it ends FAILED with `OVERLAP`.
  - This keeps MASTER = "current election only", and stops the same area being imported twice into LONG_TERM. A second long-term buyer of an area simply buys the existing one.
- **`delete_after`:**
  - MASTER: import + 60 days, and a MASTER entitlement can never run past it.
  - LONG_TERM: recomputed on every sale, void and revoke as the latest entitlement end + 30 days.

**`import_issue`**: up to 200 problems per import, with codes such as `BAD_NUMBER`, `DUPLICATE_EPIC`, `OVERLAP` and `AMBIGUOUS_AREA`. Only positions (sheet, row, column) are stored, never personal values.

**`voter`**: the roll. `PARTITION BY LIST (dataset_id)`: each dataset is its own physical table (`voter_d<id>`). Deleting a dataset is `DETACH` + `DROP`, which is instant and leaves no bloat.
- `id` comes from one global sequence. The API addresses a voter as **`(entitlement_id, voter_ref = voter.id)`**. It never sends `dataset_id` or EPIC as a key.
- **Columns:** exactly the 56 sheet columns, in lower case with typos fixed (`nagar_parishad_ward_no`, `municipal_name_nagar_parishad_mt`, `panchayat_no`). Sheet rows 12 and 13 are `booth_address_en` / `booth_address_mt`. `_mt` = Mother Tongue.
- **Sensitive columns:**
  - `mobile_no_enc` is AES-256-GCM encrypted by the application, with `mobile_key_version`.
  - `voter_image_ref` is a private object-storage key. Images are streamed through the backend after the access check, never by a public URL.
- `source_record_key_hash` = SHA-256 of `state|district|ac_no|part_no|booth_no|serial_no|voter_id` after normalisation. It is unique within a dataset and used for the overlap probe.
- `create_dataset_partitions(id)` and `create_dataset_indexes(id)` make the partition and its 12 indexes: one per lens filter path + booth, EPIC, roll order, and trigram name search in English and mother tongue.

**`voter_turnout`**: a row means "marked as voted". Partitioned like `voter`.
- Primary key `(dataset_id, customer_account_id, voter_row_id)`: one row per Customer and voter, whoever marks it.
- Writes use `INSERT … ON CONFLICT DO NOTHING`, so the first valid mark wins and repeats are confirmations. `marked_at` is server time.
- Unmarking (delete) and resetting an area's turnout are Customer-only, online, and audited. Subusers and offline clients never unmark.
- The app never records how anyone voted.

### Sales and entitlement

**`agent_sale`**: the Agent's sale header.
- `sold_by_user_id` must be an AGENT (role-checked FK). The service also checks that he is the Customer's `owning_agent_user_id`.
- `idempotency_key` is a UUID generated by the browser per sale form. The same key with a different `request_sha256` returns 409.
- Only the Owner voids: `status = VOIDED` with who, when and why (DB CHECK keeps them together). The runtime role may update only those columns.

**`agent_sale_line`**: one sold area; **immutable history**.
- Holds snapshots: dataset id and name, tier, canonical `criteria` + `criteria_hash`, `scope_label`, voter count at sale time, duration and validity.
- It has no FK to anything that can be deleted, and the runtime role cannot UPDATE or DELETE it.
- Reports count distinct areas as distinct `(election_type, criteria_hash)`.

**`entitlement`**: **the Customer's real access.** One area, one lens, one tier, one window.
- `source = SALE` (with its unique `sale_line_id`) or `DIRECT` (granted by the Owner or a Subowner; never in Agent reports).
- `ex_ent_no_overlap`: two ACTIVE entitlements of one Customer for the same lens, tier and area may not overlap in time. A renewal starting exactly at the old end is allowed, as is re-buying after expiry, the same area in the other tier, and the same area for other Customers. All of these are tested.
- **Renewal start:** `valid_from` = max(sale time, end of the current ACTIVE entitlement for that area). Validity never waits for activation or first login.
- **`mobile_access`:** only the Owner sets it, together with who and when (DB CHECK). It can't be on for a REVOKED entitlement. It is effective only while the entitlement is valid **and** the platform switch `MOBILE_NUMBERS_ENABLED` is on.
- **Revocation** (void, Owner action, dataset correction) sets REVOKED with who, when and why. Everything below it stops through checks: booth assignments, leases, sync sessions and mobile numbers.

### Field operations and offline

**`booth_assignment`**: a Subuser's access to one booth inside one entitlement.
- **Tenant and role:** composite FKs force the Subuser and the entitlement to belong to the same Customer, and the person to be a SUBUSER (tested: another Customer's Subuser, or the primary login, is rejected by the database).
- `booth_value` is the value of the lens's `booth_column`; the service checks it exists inside the entitlement's area.
- **Limits:**
  - Slots 1 and 2 with an exclusion constraint: at most 2 at any moment, and an expired slot frees itself.
  - The same booth can't be held twice.
  - At most 7 days (CHECK), and never past the entitlement (service).
  - No limit on how many Subusers work in one booth.

**`offline_lease`**: signed permission for a Subuser's phone to use one booth's data offline.
- Composite FKs guarantee the lease's device belongs to the same Subuser as the booth assignment.
- `lease_seq` only increases per device (UK). `expires_at` ≤ min(assignment end, entitlement end, issue + 24 h). The service enforces the min; the database enforces an absolute 7-day ceiling.
- Signed with the server's Ed25519 lease key (`signing_key_version`).

**`sync_session`**: chunked bulk download of one entitlement for the Customer primary login.
- Role-checked FK: Subusers can never sync (tested).
- `offline_valid_until` ≤ min(entitlement end, sync start + 24 h). After that time the app hides the data until it syncs again.
- `includes_mobile` is decided per session; if the switch goes off mid-sync, the session is CANCELLED.
- `scope_snapshot` describes what was sent and never contains dataset ids. It is not proof of access.

**`device_nonce`**: single-use security codes sent with each signed call from a phone, so a captured call can't be replayed. It is not a "request" in the business sense. primary key `(device_id, nonce)`. Requests outside ±5 minutes are rejected before the nonce check. A job purges expired rows.

---

## 5. The voter access query (every voter read uses this shape)

```sql
-- inputs: :entitlement_id, :user_id (from the session), :customer_id (from the session), :now
-- 1. load the entitlement and prove it is the caller's and valid
SELECT e.* FROM entitlement e
JOIN customer_account c ON c.id = e.customer_account_id AND c.status = 'ACTIVE'
WHERE e.id = :entitlement_id AND e.customer_account_id = :customer_id
  AND e.status = 'ACTIVE' AND e.valid_from <= :now AND :now < e.valid_until;
-- 2. build the WHERE from election_type_field rows with usage = 'FILTER' for e.election_type_id:
--    column names ONLY from source_column (whitelisted), values ONLY as bind parameters from e.criteria
SELECT v.id AS voter_ref, <DISPLAY + FILTER columns of the lens>
FROM voter v
WHERE v.dataset_id = :e_dataset_id              -- prunes to one partition
  AND v.state = :c_state AND v.ac_no = :c_ac_no -- one predicate per FILTER field
  -- Subuser only: AND v.<election_type.booth_column> IN (booth_value of ACTIVE, current assignments
  --               of this user on this entitlement)
  AND (:q IS NULL OR (coalesce(v.v_fname_en,'') || ' ' || coalesce(v.v_lname_en,'')) ILIKE '%' || :q || '%'
       OR v.voter_id = :q)
ORDER BY v.part_no, v.serial_no
LIMIT :page_size OFFSET :offset;               -- page_size <= 100
-- 3. mobile_no_enc is decrypted ONLY if: platform switch on AND caller is CUSTOMER_ADMIN AND e.mobile_access
```

- **Family:** same `dataset_id`, same booth column value and same non-blank `house_no`, plus the same scope or booth predicates as above.
- **Slip:** the lens's `on_slip` fields.
- **Turnout statistics:** the same predicates joined to `voter_turnout`.

---

## 6. Key flows mapped to tables

| Flow | Steps and tables |
|---|---|
| **Import** | Insert `dataset` (PENDING, tier, `delete_after`) → `create_dataset_partitions` → stream `.xlsx` (POI SAX), normalise, `COPY` into `voter_d<id>` → issues into `import_issue` (max 200) → overlap probe on `source_record_key_hash` → `AMBIGUOUS_AREA` check for guarded lenses → `create_dataset_indexes` → IMPORTED (or FAILED) → `audit_log` |
| **Sale** | Check Agent ACTIVE + owning Agent + Customer ACTIVE → per line: canonical criteria, resolve exactly one IMPORTED dataset of the tier with matching voters (zero = error "area not available for this tier"; two = error "area found in more than one dataset; contact the Owner" shown to the Agent on screen and written to `audit_log`), guard check, validity (renewal start, MASTER ≤ `delete_after`) → insert `agent_sale`, `agent_sale_line`s, `entitlement`s in one transaction (exclusion constraint refuses overlaps) → recompute LONG_TERM `delete_after` → `audit_log` |
| **Direct grant** | Owner or Subowner: same resolution → `entitlement` with `source = DIRECT`, no sale rows |
| **Void** | Owner: `agent_sale` VOIDED + its entitlements REVOKED + Customer `mobile_access_policy_version` + 1 → `audit_log` |
| **Activation** | Phone + code → verify HMAC, attempts < 5, not expired → set password → `customer_account` ACTIVATED, hash cleared, `app_user` ACTIVE → register device |
| **Booth assignment** | Customer picks Subuser, entitlement, booth → service checks the booth exists in the area and the window ≤ entitlement end → insert (DB refuses a third slot, more than 7 days, or a cross-Customer link) |
| **Offline (Subuser)** | Signed request → checks (account, session, device, assignment, entitlement, booth) → `offline_lease` + signed booth package → offline turnout marks kept on the phone → on reconnect each mark is re-validated → `voter_turnout` |
| **Bulk sync (Customer)** | `sync_session` per entitlement → chunks; each chunk re-checks the entitlement and mobile switch → signed manifest with `offline_valid_until` |
| **Mobile numbers** | Owner opens the Customer's entitlement → `mobile_access = true` (+ who, when) → audit. Switch-off → false + policy version + 1 → active syncs with mobile numbers are CANCELLED |
| **Dataset deletion** | Refused while any ACTIVE entitlement with `valid_until > now`, ACTIVE assignment or unexpired lease uses it. Otherwise: DELETING → cancel syncs → delete leases, assignments, sync sessions, entitlements, issues → detach + drop `voter_turnout_d<id>` and `voter_d<id>` → delete the dataset row → audit. Sale lines keep their snapshots |
| **Lost phone** | Customer → Agent → Owner: revoke device (`user_device` REVOKED), terminate its sessions, reset the password (`must_change_password`), bump policy version. Offline data on the lost phone expires within 24 h |

---

## 7. What the database guarantees (tested) vs what the services must do

**Guaranteed by PostgreSQL** (76 checks in `tools/db/test_schema.sql`, all passing on 16.15):
- **Roles and identity:** role and tenant integrity on users, owning Agent, seller, Subuser and sync user; one primary login; E.164 phone; unique phone.
- **Data and sales:**
  - field whitelist;
  - file re-import blocked, retry of a FAILED file allowed;
  - one record per dataset;
  - idempotency;
  - same area twice in one sale refused;
  - entitlement tier = dataset tier;
  - overlap refused, while renewal, re-buy, other-tier and other-Customer purchases are allowed;
  - one entitlement per sale line;
  - revoke, void and mobile changes always carry who and when.
- **Field and devices:**
  - 2 booth slots and the 7-day limit;
  - no cross-Customer assignment or sync;
  - one ACTIVE device per user;
  - no reuse of an installation id;
  - a lease only on the Subuser's own device;
  - unique lease sequence;
  - nonce replay refused;
  - a session only on the user's own device;
  - web sessions have no refresh token;
  - web login only for Owner and Subowner, app login only for Agent, Customer and Subuser;
  - phones only for app users, with the role and Customer checked.
- **Turnout:** one row per Customer and voter.
- **History:** audit log immutable even for the owner; the runtime role cannot edit sales history, change configuration or drop data; sale history survives dataset deletion.

**Must be done in the services** (cannot be expressed as constraints; write a unit or integration test for each):
1. Every query scoped by the authenticated Customer, entitlement and booth (§5). Client IDs are hints only.
2. Validity checks against `now()` on every read: entitlement, booth assignment, lease, sync session, session idle and absolute expiry.
3. Seller = `owning_agent_user_id`; Customer ACTIVE; Agent ACTIVE.
4. Criteria keys = exactly the lens's FILTER fields; canonical JSON (RFC 8785) and SHA-256 hash; dataset resolution; guard check.
5. MASTER `valid_until` ≤ `delete_after`; LONG_TERM `delete_after` recomputation; renewal start date.
6. Booth value exists in the area; assignment end ≤ entitlement end; lease and sync windows ≤ entitlement end.
7. Device challenge verification, request signature, ±5-minute window, counter > `last_request_counter`.
8. Session limit with the row lock; refresh rotation and reuse detection; termination on password reset, suspension or device revocation.
9. Mobile number decryption rule; masking on every path (search, details, family, slip, sync).
10. Never return `dataset_id`, dataset names, file names, hashes, counts or issues to Agents, Customers or Subusers.

---

## 8. Configuration defaults (environment, not code)

| Setting | Default | | Setting | Default |
|---|---|---|---|---|
| Access token | 15 min | | Activation code | 72 h, 5 attempts |
| Web session idle / absolute | 30 min / 12 h | | Device challenge | 2 min |
| Mobile refresh / absolute | 30 d / 90 d | | Signed request window | ±5 min |
| MASTER retention | 60 d | | Offline lease / Customer offline window | 24 h / 24 h |
| LONG_TERM grace | 30 d | | Sync download window | 2 h |
| Import issues stored | 200 | | Page size (max) | 100 |
| Login limit | 20/min per IP (proxy); 10 failures → 15 min lock per username | | `MOBILE_NUMBERS_ENABLED` | **false** until a lawyer confirms the lawful source of mobile numbers and photos |

---

## 9. Design choices (and why)

- **18 tables, nothing speculative.** Each table maps to a rule in §2.
- **No separate "subscription" container.** An entitlement already names its Customer, lens and dataset; a container would only add a status that can drift.
- **No separate mobile-grant table.** The switch is a column on the entitlement. Its full history is in `audit_log`.
- **The entitlement points to its sale line, not the other way round.** The mutable child references the immutable parent, so deleting data never touches sales history and no `SET NULL` is needed.
- **One voter table, partitioned per dataset.** This gives the "master table / long-term table" behaviour (separate physical storage, instant deletion) without duplicating code. Turnout still has real foreign keys to voters.
- **Overlap is checked on (Customer, lens, tier, area), not per dataset.** So a corrected roll (new dataset) can't be used to sell the same area twice to the same Customer at the same time.
- **Role and tenant rules in foreign keys.** Small generated columns (`'AGENT'`, `'SUBUSER'`, `'CUSTOMER_ADMIN'`) let the database refuse wrong-role or cross-Customer links. Row-Level Security is not used.
- **Configuration is data, not code.** The sheet's colours become `election_type_field`; booth and guard columns live on `election_type`. Changing a lens is a seed change, not a migration of code.

---

## 10. How to start coding

1. **Database:**
   1. PostgreSQL 16+ with `btree_gist`, `pg_trgm` and `citext`.
   2. Flyway applies `V1__elecsarthi_schema.sql`, `V2__runtime_role.sql` and `V3__seed_election_type_field.sql` (the 271 field rows).
   3. To fill `label_mt` or change `on_slip`, follow the `/seed-fields` skill (it creates a new reseed migration).
   4. Run `tools/db/run_tests.sh` on every schema change.
2. **Backend:** Java 21 LTS, Spring Boot 4.1.x (3.4 and 3.5 no longer get open-source security patches), Spring Security, JPA, Flyway, Apache POI streaming. Suggested modules map 1:1 to the groups: `identity`, `catalog` (election types, datasets, import), `voter` (access query, turnout), `sales` (sale, entitlement, reports), `field` (booths, leases, sync, nonces), `audit`.
3. **JPA notes:**
   - Generated columns (`owning_agent_role`, `sold_by_role`, `subuser_role`, `user_role`) are `insertable=false, updatable=false`.
   - Map `voter` as read-mostly with native queries for dynamic filters.
   - Never let Hibernate generate DDL (`ddl-auto=validate`).
4. **Build order:** identity and sessions → import → sale and entitlement → voter access query → Customer app screens → booths → offline and sync → reports.
5. **Before launch:**
   - cross-tenant API tests (Agent A vs B, Customer A vs B, Subuser outside booth);
   - poll-day load test;
   - backup restore drill;
   - repository made private and secrets in a secret manager;
   - legal sign-off on mobile numbers and photos.

## 11. Open items

| Item | Effect on the schema |
|---|---|
| `label_mt` (mother-tongue labels) empty in the seed | none: fill the CSV |
| `on_slip` defaults are a proposal | none: change `SLIP` in `gen_seed.py` |
| If `AMBIGUOUS_AREA` appears in real data for a lens | colour the body-name cell skin in the sheet, regenerate the seed |
| Legal opinion on mobile numbers and photos | if negative, drop `mobile_no_enc`, `mobile_key_version`, `voter_image_ref` and the mobile columns on `entitlement` |
| Customer account deletion / data-principal requests (DPDP obligations from May 2027) | decide with the lawyer; accounts are only suspended today |

# ElecSarthi Backend — API Reference Manual

This document details the RESTful APIs exposed by the ElecSarthi election voter-data SaaS backend.

---

## 1. Authentication & Session Management (`/api/v1/auth`)

### 1.1 Web/Mobile Password Login
- **Endpoint:** `POST /api/v1/auth/login`
- **Access:** Public
- **Request Body:**
  ```json
  {
    "username": "sys_owner",
    "password": "Password123!",
    "clientType": "WEB",          // "WEB" or "MOBILE"
    "deviceId": "UUID"            // Required if clientType == "MOBILE"
  }
  ```
- **Rules Enforced:**
  - `WEB` client login restricted exclusively to `OWNER` and `SUBOWNER`.
  - `MOBILE` client login restricted exclusively to `AGENT`, `CUSTOMER`, and `SUBUSER`.
  - Session limits: OWNER/SUBOWNER = 1 active web session. Mobile users = 1 active mobile session per device.
  - Concurrency handled via `SELECT ... FOR UPDATE` row locks.
- **Response (200 OK):**
  ```json
  {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "7c9e6679-...",
    "tokenType": "Bearer",
    "expiresInSeconds": 900,
    "user": {
      "id": 1,
      "username": "sys_owner",
      "fullName": "System Administrator",
      "role": "OWNER"
    }
  }
  ```

### 1.2 Customer Activation & Primary Login
- **Endpoint:** `POST /api/v1/auth/customer-activate`
- **Access:** Public (Mobile only)
- **Request Body:**
  ```json
  {
    "contactPhone": "+919876543210",
    "activationCode": "W4B9K2M7XZ",
    "password": "NewSecurePassword123!",
    "deviceId": "b1b7ef38-..."
  }
  ```
- **Rules Enforced:**
  - 10-character Crockford Base32 verification against HMAC-SHA-256 peppered hash.
  - Max 5 failed attempts before permanent lockout.
  - 72-hour expiration window.
  - Binds verified device and transitions account to `ACTIVATED`.
- **Response (200 OK):** Same structure as LoginResponse.

### 1.3 Mobile Refresh Token Rotation
- **Endpoint:** `POST /api/v1/auth/refresh`
- **Access:** Public (Mobile only)
- **Request Body:**
  ```json
  {
    "refreshToken": "7c9e6679-...",
    "deviceId": "b1b7ef38-..."
  }
  ```
- **Rules Enforced:**
  - Immediate rotation: old refresh token invalidated upon use.
  - Replay / Reuse Detection: If an already-used refresh token is presented, all active sessions for that user on that device are immediately terminated (`REFRESH_TOKEN_REUSE`).

### 1.4 Logout
- **Endpoint:** `POST /api/v1/auth/logout`
- **Access:** Authenticated
- **Response (200 OK):** Session status set to `TERMINATED`.

---

## 2. Device Registration & Cryptographic Verification (`/api/v1/devices`)

### 2.1 Challenge Generation
- **Endpoint:** `POST /api/v1/devices/register`
- **Access:** Authenticated (AGENT, CUSTOMER, SUBUSER)
- **Request Body:**
  ```json
  {
    "installationId": "app-instance-9128",
    "platform": "ANDROID",
    "deviceName": "Samsung Galaxy S24",
    "publicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
    "keyAlgorithm": "ECDSA_P256"
  }
  ```
- **Response (200 OK):** Device ID, ephemeral random challenge string, and challenge expiration timestamp (2 minutes).

### 2.2 Challenge Verification
- **Endpoint:** `POST /api/v1/devices/verify`
- **Access:** Authenticated
- **Request Body:**
  ```json
  {
    "deviceId": "b1b7ef38-...",
    "signedChallenge": "MEUCIQD..." // Base64 ECDSA signature of challenge
  }
  ```
- **Rules Enforced:**
  - Verifies ECDSA P-256 signature against stored device public key.
  - Promotes device status to `ACTIVE`.

---

## 3. Platform User Management (`/api/v1/users`, `/api/v1/subowners`, `/api/v1/agents`)

- `POST /api/v1/subowners`: OWNER creates Subowner.
- `GET /api/v1/subowners`: OWNER lists all Subowners.
- `POST /api/v1/agents`: OWNER or SUBOWNER creates Agent.
- `GET /api/v1/agents`: OWNER or SUBOWNER lists Agents.
- `PUT /api/v1/users/{id}/status`: Change status (`ACTIVE`, `SUSPENDED`).
- `POST /api/v1/users/{id}/reset-password`: Reset user password and terminate all active sessions.

---

## 4. Customer Provisioning & Management (`/api/v1/customers`)

- `POST /api/v1/customers`: Provision new Customer Account (Owner, Subowner, or Agent).
  - Generates 10-char Crockford Base32 code with HMAC-SHA-256 peppered hash.
  - When created by Agent, owning Agent is automatically set to creator.
- `GET /api/v1/customers`:
  - OWNER / SUBOWNER: See all customers.
  - AGENT: See only own assigned customers.
  - Contact phone is masked for SUBOWNER (`+91*****3210`); plaintext for OWNER and owning AGENT.
- `POST /api/v1/customers/{id}/reissue-code`: Reissue activation code (resets attempt counter, 72h expiry).
- `POST /api/v1/customers/{id}/suspend`: Suspends customer, terminates all active sessions of customer and its subusers, increments `mobile_access_policy_version`.
- `POST /api/v1/customers/{id}/reactivate`: Reactivates customer account.
- `PUT /api/v1/customers/{id}/reassign-agent`: OWNER only reassigns owning Agent.

---

## 5. Datasets & Election Types (`/api/v1/datasets`, `/api/v1/election-types`)

- `GET /api/v1/election-types`: List all 8 election types.
- `GET /api/v1/election-types/{code}/fields`: List field definitions with usages (`FILTER`, `DISPLAY`), slip status, orders.
- `GET /api/v1/election-types/{code}/cascading-filter`: Dynamic cascading dropdown options from IMPORTED datasets.
- `POST /api/v1/datasets/upload`: Upload `.xlsx` voter dataset (OWNER, SUBOWNER).
  - POI SAX streaming reader (`XSSFReader` + `XSSFSheetXMLHandler`).
  - Dynamic partition creation `voter_d<id>` and indexes.
  - Overlap probe rejection on `source_record_key_hash`.
  - AES-256-GCM encryption of mobile numbers.
- `GET /api/v1/datasets`: Paginated listing with tier and status.
- `DELETE /api/v1/datasets/{id}`: Safe deletion with active entitlement and unexpired lease checks.

---

## 6. Sales, Criteria Normalization & Entitlements (`/api/v1/sales`, `/api/v1/entitlements`)

### 6.1 Agent Sale Submission
- **Endpoint:** `POST /api/v1/sales`
- **Access:** AGENT only
- **Request Body:**
  ```json
  {
    "customerAccountId": 12,
    "electionTypeId": 2,
    "storageTier": "MASTER",
    "idempotencyKey": "agent-sale-tx-8210",
    "lines": [
      {
        "criteria": { "state": "RAJASTHAN", "ac_no": 12 },
        "durationValue": 10,
        "durationUnit": "DAY"
      }
    ]
  }
  ```
- **Rules Enforced:**
  - Idempotent replay: identical key + identical payload returns existing sale without duplicate records.
  - Reused key with modified payload throws 409 `ConflictException`.
  - Cascading criteria normalized to RFC 8785 canonical JSON and SHA-256 criteria hash.
  - Backend resolves exactly 1 dataset per line (0 -> `AREA_UNAVAILABLE`, >1 -> `AREA_MULTIPLE_DATASETS`). Dataset IDs/names never exposed.
  - Ambiguity guard: rejects area with `AMBIGUOUS_AREA` if voters carry multiple distinct guard column values.
  - Back-to-back renewal: `valid_from = max(now, priorEntitlement.validUntil)`.
  - Asia/Kolkata calendar math (`DAY`, `WEEK`, `MONTH` with month-end clamp).
  - MASTER tier valid_until cannot exceed dataset `delete_after`.

### 6.2 Void Sale
- **Endpoint:** `POST /api/v1/sales/{id}/void`
- **Access:** OWNER only
- **Rules Enforced:**
  - Sale marked `VOIDED`.
  - All matching entitlements transitioned to `REVOKED`.
  - Increments customer's `mobile_access_policy_version`.

### 6.3 Direct Entitlements
- **Endpoint:** `POST /api/v1/entitlements/direct`
- **Access:** OWNER and SUBOWNER (mobile access toggle restricted to OWNER only).
- **Rules Enforced:** `source = 'DIRECT'`, `sale_line_id = null`. Never appears in Agent reports.

### 6.4 Mobile Access Toggle
- **Endpoint:** `PUT /api/v1/entitlements/{id}/mobile-access`
- **Access:** OWNER only. Increments customer `mobile_access_policy_version`.

---

## 7. Reports (`/api/v1/reports`)

- **Endpoint:** `GET /api/v1/reports/sales`
- **Access:** OWNER (all or by agent) and AGENT (own sales only). SUBOWNER: 403 Forbidden.
- **Rules Enforced:** Strict quantity-only reporting (sales, lines, voter snapshots, scopes, election types). Absolutely NO pricing, amounts, or commission fields.

---

## 8. Voter Queries & Turnout (`/api/v1/voters`, `/api/v1/turnout`)

### 8.1 Voter Search
- **Endpoint:** `POST /api/v1/voters/search`
- **Access:** CUSTOMER and SUBUSER only. (Owner, Subowner, Agent: 403 Forbidden).
- **Rules Enforced:**
  - Subusers automatically restricted to active assigned booths.
  - Server-side pagination capped at 100.
  - whitelisted column queries with parameterized binds.
  - 3-gate Mobile Number Protection: Unmasked ONLY IF global enabled + CUSTOMER role + entitlement `mobile_access=true`. Otherwise masked or omitted. Subusers NEVER see unmasked numbers.

### 8.2 Family Clustering
- **Endpoint:** `GET /api/v1/voters/{id}/family?entitlementId={id}`
- **Rules Enforced:** Clusters voters sharing same booth and non-blank `house_no`.

### 8.3 Voter Slip
- **Endpoint:** `GET /api/v1/voters/{id}/slip?entitlementId={id}`
- **Rules Enforced:** Formats voter data using `on_slip = true` metadata.

### 8.4 Turnout Marking & Unmarking
- `POST /api/v1/turnout/mark`: Customer or Subuser marks voter. `ON CONFLICT DO NOTHING`. Server timestamp.
- `POST /api/v1/turnout/unmark`: Customer only (online). Subusers strictly forbidden.
- `POST /api/v1/turnout/reset`: Customer only.
- `GET /api/v1/turnout/statistics`: Live counts and percentages by booth and gender.

---

## 9. Subusers, Leases & Bulk Sync (`/api/v1/subusers`, `/api/v1/leases`, `/api/v1/sync`)

### 9.1 Subuser Booth Assignments
- `POST /api/v1/subusers/{id}/booth-assignments`:
  - Customer only.
  - Max 2 active slots per subuser.
  - Max 7 days duration, bounded by entitlement expiration.
  - Booth value must exist in entitlement area.
- `POST /api/v1/subusers/booth-assignments/{id}/revoke`: Customer or Owner support.

### 9.2 Offline Booth Leases
- `POST /api/v1/leases/booth`: Subuser mobile client requests signed lease.
  - Signed with Ed25519 private key.
  - Expiry = `min(boothAssignment.validUntil, entitlement.validUntil, now + 24 hours)`.
- `POST /api/v1/leases/writeback`: Subuser submits offline turnout marks.
  - Marks prior to lease expiry accepted into `voter_turnout` (`source = 'OFFLINE_LEASE'`). Marks after expiry rejected.

### 9.3 Bulk Sync
- `POST /api/v1/sync/sessions`: Customer only (Subusers forbidden).
- `GET /api/v1/sync/sessions/{id}/chunks/{chunkNo}`: Chunk retrieval with verification SHA-256.
- `POST /api/v1/sync/sessions/{id}/complete`: Completes sync session.

# ElecSarthi Backend — Security Implementation Manual

This document provides a comprehensive analysis and specification of the cryptographic, authorization, and isolation defenses engineered into the ElecSarthi SaaS backend.

---

## 1. Threat Model & Architectural Defenses

| Threat Vector | Mitigation Strategy | Enforcement Layer |
|---|---|---|
| Admin Browsing of Confidential Voter Data | Complete denial of voter search/slip APIs to Owner, Subowner, Agent | Application Gate (`VoterServiceImpl`) & Spring Method Security |
| Leaked Voter Phone Numbers | AES-256-GCM encryption + 3-gate condition (Global Enabled + Customer Role + Entitlement Flag) | Service Layer (`CryptoUtils`, `VoterServiceImpl`) |
| Unauthorized Subuser Access | Max 2 booth slots, max 7 days duration, exclusion constraints | Database Constraints (`ex_ba_slot`, `ex_ba_same_booth`) & Service Layer |
| Replayed Refresh Tokens | Immediate rotation + session revocation on token reuse | `AuthServiceImpl` with row-level locks |
| Spoofed Device Requests | ECDSA P-256 hardware challenge-response + monotonic counters | `DeviceServiceImpl` & `CryptoUtils` |
| Tampered Offline Booth Leases | Server Ed25519 digital signature over canonical payload | `OfflineLeaseServiceImpl` & `CryptoUtils` |
| Overlapping Entitlements | Temporal Exclusion Constraints with GiST indexes | Database Exclusion Constraint `ex_ent_no_overlap` |
| Audit Trail Tampering | Append-only database trigger blocking UPDATE, DELETE, and TRUNCATE | Database Trigger `trg_audit_log_immutable` |
| Production Key Complacency | Fail-fast startup checks rejecting dev keys in `prod` profile | `SecurityConfig` `@PostConstruct` Validator |

---

## 2. Five Canonical Roles & Client Separation

The platform enforces a strict 5-role hierarchy without intermediary roles:

```text
OWNER (Web) ──────────┬─► SUBOWNER (Web)
                      ├─► AGENT (Mobile)
                      │      │
                      │      ▼ (owns for selling)
                      └─► CUSTOMER ACCOUNT (Provisioned)
                             │
                             ├─► CUSTOMER (Mobile - Primary Account)
                             └─► SUBUSER (Mobile - Field Worker)
```

### Client Channel Separation
- **Web Portal:** Only `OWNER` and `SUBOWNER` can authenticate with `clientType = 'WEB'`. Attempting to log in as mobile roles returns HTTP 403.
- **Mobile Application:** Only `AGENT`, `CUSTOMER`, and `SUBUSER` can authenticate with `clientType = 'MOBILE'`. Attempting to log in as web roles returns HTTP 403.
- Database enforcement: `ck_session_client_by_role` ensures `device_id IS NOT NULL` for mobile sessions and `device_id IS NULL` for web sessions.

---

## 3. Cryptographic Implementation Details

### 3.1 Device Binding & Verification (ECDSA P-256)
- **Challenge Generation:** Server generates an ephemeral 32-byte cryptographic random nonce with a 2-minute lifespan.
- **Verification:** Device signs `nonce` using its hardware-backed private key (`secp256r1`).
- **Algorithm:** `SHA256withECDSA` verified against the stored X.509 encoded public key.

### 3.2 Offline Lease Digital Signatures (Ed25519)
- **Engine:** BouncyCastle Ed25519 (`Ed25519Signer` and `Ed25519PrivateKeyParameters`).
- **Payload Canonicalization:** Fields lexicographically ordered and formatted into canonical JSON.
- **Payload Hash:** SHA-256 digest of canonical JSON.
- **Signature:** Server private key signs the SHA-256 digest; output encoded in RFC 4648 Base64.
- **Expiration:** Hard-bounded by `min(boothAssignment.validUntil, entitlement.validUntil, issuedAt + 24 hours)`.

### 3.3 Voter Mobile Number Protection (AES-256-GCM)
- **Storage:** Ciphertext stored as `bytea` in `voter.mobile_no_enc`.
- **Encryption:** 12-byte cryptographically secure random IV prepended to the ciphertext. 128-bit authentication tag.
- **3-Gate Access Gate:**
  1. `appProperties.isMobileNumbersEnabled() == true` (Global toggle exclusively controlled by OWNER).
  2. Caller role must be `CUSTOMER` (SUBUSERS NEVER see unmasked numbers).
  3. Entitlement must have `mobile_access == true` (Toggled exclusively by OWNER).
- If any condition is false, numbers are masked (`+91*****1234`) or suppressed.

### 3.4 Customer Activation Code (Crockford Base32)
- **Code:** 10-character Crockford Base32 string (excludes `I`, `L`, `O`, `U` to eliminate human transcription errors).
- **Storage:** HMAC-SHA-256 peppered hash with a 64-character hex digest. Plaintext code is NEVER persisted.
- **Attempts:** Max 5 verification attempts allowed. Account permanently locked on 6th failure until re-issued by Agent or Owner.

---

## 4. Multi-Tenant & Cross-Customer Isolation

- **Entitlement Binding:** Every voter, turnout, lease, and sync query joins against `entitlement.customer_account_id = :callerCustomerAccountId`.
- **Dynamic Partitioning:** Voter tables are partitioned by `dataset_id`. Dynamic queries strictly enforce partition keys, avoiding cross-dataset or cross-customer leaks.
- **Subuser Booth Exclusion:** Subusers are restricted to their assigned booth slots (maximum 2 simultaneous slots, maximum 7 days duration). Database GiST exclusion constraints (`ex_ba_slot`, `ex_ba_same_booth`) guarantee that slots and booth assignments cannot overlap.

---

## 5. Advanced Security Defenses & Re-Audit Invariants

### 5.1 Device Replacement Policy & Role Scoping
- **Silent Replacement Prevented:** If an active device exists for a user, subsequent login from a different installation ID returns HTTP 403 Forbidden (`DEVICE_CHANGE_NOT_ALLOWED`) without issuing a registration token.
- **Hierarchical Revocation:**
  - `OWNER`/`SUBOWNER`: Global revocation authority.
  - `AGENT`: Scoped to assigned customers and their subusers.
  - `CUSTOMER`: Scoped to subordinate subusers.
  - `SUBUSER`: Strictly forbidden.

### 5.2 Mandatory Device-Signed Refresh Proof
- Every mobile token refresh requires:
  - `X-Device-Id`: Must match session device.
  - `X-Device-Signature`: ECDSA P-256 signature over `POST\n/api/v1/auth/refresh\n{ts}\n{nonce}\n{counter}\n{bodySha256Hex}`.
  - `X-Device-Timestamp`: Enforced within a 5-minute sliding window.
  - `X-Device-Nonce`: Single-use verified in `device_nonce`.
  - `X-Device-Counter`: Monotonically increasing check against `last_counter`.

### 5.3 Transaction-Scoped Advisory Locks
- Distributed locks in `RetentionScheduledService` use `pg_try_advisory_xact_lock` wrapped in dedicated `TransactionTemplate` (`PROPAGATION_REQUIRES_NEW`).
- Eliminates connection pool lock leaks; locks release automatically on transaction termination.

### 5.4 Isolated Partition Lifecycle Management
- `DatasetRetentionPurger` executes each dataset purge in an isolated `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
- Prevents long table locks from blocking other operations and ensures one failure does not cascade to other datasets.

### 5.5 Autonomous DENIED Audit Persistence
- All `AuditOutcome.DENIED` security audit logs execute inside a dedicated `TransactionTemplate` (`PROPAGATION_REQUIRES_NEW`).
- Failed authentication, invalid signatures, and unauthorized device actions are guaranteed to persist even when outer business transactions roll back.

### 5.6 Constant-Time Anti-Phone Enumeration
- Customer activation verifies phone existence before code validation.
- If phone is not registered, executes a dummy HMAC-SHA256 calculation to maintain constant response timing, logs a masked DENIED audit, and returns generic 400 Bad Request.

### 5.7 Fail-Closed DB-Backed Global Mobile Toggle
- Global voter mobile unmasking toggle is read directly from `audit_log` (with partial index `idx_audit_log_global_mobile_toggle` via Flyway V7).
- Fails closed (`false`) on database query error or timeout, guaranteeing that voter numbers are never leaked during infrastructure faults.

### 5.8 Production CORS Strict Allow-Listing
- Explicit CORS origins configured via `app.cors.allowed-origins`.
- Production startup fails fast if wildcards (`*`) or `localhost` are configured in `prod` profile.

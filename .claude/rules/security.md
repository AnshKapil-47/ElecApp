# Security requirements (always loaded)

These are mandatory. Never weaken them to make something work or a test pass.

## Authorization

- Identity, role, Customer and status come from the authenticated session + current DB rows. Never from request bodies, query params or headers.
- Two levels on every endpoint: role (`@PreAuthorize`) **and** resource ownership inside the service/repository query. Deny by default.
- Client IDs (customer, entitlement, voter, booth, device, sync session…) are lookup hints. Re-derive allowed resources from the DB, then check.
- Token expiry is never the entitlement check. Check entitlement, booth assignment, lease and sync validity against `now()` on every request.
- No Row-Level Security in this design; tenant isolation lives in queries, backed by composite FKs. Never claim RLS protects anything.
- Hiding a UI element is not authorization.

## Sessions and tokens

- Owner/Subowner log in only on the web (`client_type = WEB`, no refresh token). Agent, Customer and Subuser log in only in the app (`client_type = MOBILE`, bound to their registered device). The DB rejects any other combination.
- Every login creates a `user_session`; access token (15 min, configurable) carries `sid`. Every request checks: session ACTIVE, not idle/absolute-expired, user ACTIVE, Customer ACTIVE, mobile device ACTIVE.
- Concurrent limits: Owner 3, Subowner 3, Agent 1, Customer 1, Subuser 1. Login above the limit is **rejected** (never evicts). Exception: a mobile login proving the user's ACTIVE device key replaces that same device's session (`REPLACED_SAME_DEVICE`). Enforce atomically: `SELECT … FROM app_user WHERE id = ? FOR UPDATE` before counting.
- Mobile refresh tokens: `sid.generation.secret`; store only the SHA-256 hash; rotate on every use; a lower generation or hash mismatch ⇒ terminate session (`REFRESH_REUSE`) + audit. Refresh never revives a terminated session, suspended account, revoked device or expired entitlement. Web sessions have no refresh token.
- Logout, Owner termination, password reset, suspension, device revocation ⇒ terminate affected sessions immediately.

## Credentials

- Passwords: BCrypt cost ≥ 12. Activation codes: HMAC-SHA-256 with a versioned server pepper, single use, 72 h, 5 attempts, hash cleared on activation. No self-service reset.
- Never return or log passwords, hashes, codes, tokens or keys.

## Devices, signing, replay

- Device binding applies to every app user, **including Agents**. One ACTIVE device per user. Registration: app creates an ECDSA P-256 key in Android Keystore / iOS Secure Enclave + random `installation_id`; server stores a PENDING row with a 32-byte challenge hash (2 min); verifies the signature; activates. Replacement = new PENDING row; old becomes REPLACED in the same transaction. Revoked installations never come back.
- Signed device requests (sync, lease, offline write-back) sign: `METHOD\nPATH\nSHA256(body)\ntimestamp\nnonce\ncounter\nlease_or_sync_id`. Server checks device ACTIVE, signature, timestamp ±5 min, nonce unused (`device_nonce`), counter > `last_request_counter`.
- Server keys (JWT, Ed25519 lease/manifest signing, activation pepper, mobile-number AES key) come from the secret store with key versions. App must refuse to start in production if a key is missing or is a development key.

## Offline

- Subuser offline = signed `offline_lease`: expires ≤ min(assignment end, entitlement end, issue + 24 h). Customer offline = `sync_session.offline_valid_until` ≤ min(entitlement end, sync + 24 h). After that the app hides local data until it reconnects.
- On reconnect re-check everything; reject invalid write-backs; tell the app to purge (policy version). Never describe offline data as remotely wipeable.
- Offline clients can only mark voted, never unmark.

## Data protection

- Dataset confidentiality: Agents/Customers/Subusers never receive dataset id, name, file name, hash, counts, issues, or another Customer's data — in responses, errors, logs, OpenAPI examples.
- Voter mobile numbers: `mobile_no_enc` (AES-256-GCM, app-level). Decrypt only under the rule: platform switch on + caller is CUSTOMER + `entitlement.mobile_access = true`. Mask on every path: search, details, family, slip, sync.
- Voter images: private object key; stream through the backend after the access check. Never a public URL.
- Error messages never reveal whether another Customer, dataset, voter or booth exists.

## Logging and audit

- Never log: voter mobile numbers, full voter records, passwords, hashes, activation codes, tokens, private keys, request bodies with personal data.
- Write `audit_log` (SUCCESS or DENIED) for: login/logout/termination, password reset, suspension/reactivation, code re-issue, owning-Agent change, device register/rotate/revoke, dataset import/supersede/delete, entitlement create/revoke, sale void, mobile switch, booth assign/revoke, lease issue/revoke, turnout unmark/reset. Never put secrets or mobile numbers in `details`.

## Transport and infrastructure

- HTTPS only. PostgreSQL `sslmode=verify-full` in production. Runtime DB user = `elecsarthi_app` (V2), never the owner/migration user. Secrets from environment/secret manager; never committed. Rate limits at the reverse proxy + in-memory; no DB rate-limit tables.

---
paths:
  - "mobile/**"
---

# Flutter app rules (Agent, Customer primary login, Subuser)

- **Users:** AGENT, CUSTOMER_ADMIN and SUBUSER. Owner and Subowner never log in to the app (web only). One app, with screens chosen by the role from the server session; never by a role stored on the phone.
- **Every app user** (Agents included) registers one device key and is bound to it.
- **Agent screens (no voter data at all):**
  - own Customers: create, view, contact phone;
  - re-issue an activation code;
  - **sale form**;
  - own sales report.

  Agents never get voter screens, offline leases or bulk sync, and never see dataset names or ids, file names or import data.
- **Agent sale form order:**
  1. Customer.
  2. Election type.
  3. **Tier** (MASTER / LONG_TERM).
  4. Areas: cascading FILTER dropdowns in `filter_order`, with values from the server for that tier.
  5. Duration per line.
  6. Submit with a UUID idempotency key generated once per form; retries reuse the same key.

  Show server errors as given ("area not available for this tier", "area already active for this period", `AMBIGUOUS_AREA`).
- **Customer activation:**
- Activation: registered phone + 10-char code → set password (min 10 chars) → register device. Activation is once per account, never per purchase.
- Device key: generate ECDSA P-256 in Android Keystore / iOS Secure Enclave (non-exportable). Store `installation_id` and tokens in platform secure storage. Never upload or log the private key.
- Local database: encrypted (SQLCipher or equivalent) with a key held in the platform keystore.
- Offline:
  - Subuser data comes only from an offline lease for an assigned booth; Customer data only from a bulk sync session.
  - Hide and stop using local data after the lease `expires_at` / sync `offline_valid_until`, even with no network.
  - When the server returns a higher `policy_version` or a purge instruction, purge the affected local data **before** showing anything else.
  - Queue only "mark voted" offline; never unmark offline. Sign each queued write with the device key, nonce, timestamp and counter.
- **Customer and Subuser screens:** activation, login, dashboard (entitlements with type, area label, validity, remaining time, expired/starts-on state), voter list (server pagination; search by name or EPIC), voter details, family, slip print (Bluetooth thermal, the type's `on_slip` fields), turnout marking and statistics, Subuser and booth management (Customer only).
- Show only the fields the server sends for the election type. Never show mobile numbers unless the server sent them; never cache them for Subusers.
- No push notifications, no in-app requests or chat. Expiry is shown on the dashboard only.
- Never display or store dataset ids/names; voters are referenced by `(entitlementId, voterRef)`.
- No price, payment or commission fields on any screen (including the Agent sale form).
- Respect the session limit: on "session limit reached", tell the user to contact their Agent; do not retry in a loop.

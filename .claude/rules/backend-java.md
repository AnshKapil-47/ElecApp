---
paths:
  - "src/main/java/**/*.java"
  - "src/main/resources/**/*.yml"
  - "src/main/resources/**/*.yaml"
  - "src/main/resources/**/*.properties"
---

# Backend (Spring Boot) rules

## Mandatory Architecture: Centralized Layered Architecture

- Use ONE common Java package structure under `com.elecsarthi`:
  - `controller`: thin REST controllers under `com.elecsarthi.controller`
  - `service`: service interfaces under `com.elecsarthi.service`
  - `service/impl`: service implementations under `com.elecsarthi.service.impl`
  - `repository`: Spring Data JPA and custom repositories under `com.elecsarthi.repository`
  - `entity`: JPA entities under `com.elecsarthi.entity`
  - `dto/request` & `dto/response`: DTOs under `com.elecsarthi.dto`
  - `enums`: business enums under `com.elecsarthi.enums`
  - `mapper`: entity-to-DTO mappers under `com.elecsarthi.mapper`
  - `security`: security config, filters, jwt, device, signing under `com.elecsarthi.security`
  - `exception`: global exception handler and error codes under `com.elecsarthi.exception`
  - `config`: application configs under `com.elecsarthi.config`
- **Prohibited:** Feature-based package trees (`owner/controller`, `sales/service`, etc.) or role-based package trees. Keep technical layers centralized.

## Every endpoint

1. `@PreAuthorize` with the exact 5 canonical roles: `OWNER`, `SUBOWNER`, `AGENT`, `CUSTOMER`, `SUBUSER`.
2. Load the caller from the session (`CurrentUser` / `SecurityContext`), never from the request body or parameters.
3. Scope the repository query by the caller's Customer / owning Agent / entitlement / booth. Return 404 (not 403) for resources outside scope so existence isn't revealed.
4. Validate entitlement and booth validity against the database `Clock`.
5. Map to a DTO with no dataset internals and no unmasked mobile numbers.
6. Write `audit_log` for sensitive actions, including DENIED outcomes.
7. Add a cross-tenant integration test.

## Patterns

- Transactions: sale + sale lines + entitlements in one `@Transactional`; void + revoke + policy-version bump in one transaction; login session-limit check with a row lock (`FOR UPDATE` on `app_user`).
- Map DB constraint violations to business errors by constraint name, e.g. `ex_ent_no_overlap` → 409 "area already active for this period", `ex_ba_slot` → 409 "Subuser already has 2 booths", `ux_sale_idempotency` → return the original sale if `request_sha256` matches, else 409.
- Dynamic voter SQL only through the voter query builder; column names strictly from `election_type_field.source_column`, values as bind parameters. No string concatenation of client input.
- Criteria canonicalisation (RFC 8785 JSON, NFC, trim, single spaces, Latin upper-case, integers without leading zeros) lives in a centralized criteria normalizer.
- Time: store `Instant`/`OffsetDateTime` (timestamptz). Duration math in `Asia/Kolkata`; MONTH clamps to month end. Injectable `Clock` bean.
- Configuration values come from `@ConfigurationProperties`, never hardcoded.
- Startup check: in the `prod` profile, fail fast if any signing key/pepper/encryption key is missing or equals a known development value.
- Excel import: Apache POI streaming (SAX/`XSSFReader`), `.xlsx` only, batch `COPY` via the PostgreSQL driver's `CopyManager`, max 200 issues stored, positions only.
- Errors: global `@RestControllerAdvice` returning standard `{code, message}`; production hides stack traces.
- OpenAPI: no example contains real voter data, dataset names or ids.

## Prohibited

- Never call `voter` with a dataset id taken from the client.
- Never add Owner/Subowner/Agent access to voter endpoints.
- Never add notifications, email, SMS, push, pricing, payments, or commission code.
- Never cache entitlement decisions beyond one request.

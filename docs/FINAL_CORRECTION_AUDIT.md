# ElecSarthi — Final Forensic Correction Audit & Checklist

This document tracks all forensic findings, root causes, planned corrections, implementations, verification evidence, and final acceptance status across the ElecSarthi backend codebase.

---

## Finding 1: Unsafe Dataset Deletion & Exception Swallowing
- **Component / File:** `src/main/java/com/elecsarthi/service/impl/DatasetServiceImpl.java`
- **Original Problem:** 
  1. `deleteDataset()` caught partition detach/drop exceptions in a broad `try { ... } catch (Exception e)` block and swallowed them via `log.warn()`, allowing the dataset record and its import issues to be deleted while orphaned partitions remained.
  2. Partition detachment hung in `DatasetServiceIntegrationTest` due to `ACCESS EXCLUSIVE` lock contention on partitioned tables `voter` / `voter_turnout` during concurrent reading or uncommitted transactions.
  3. Lack of validation to check whether partitions actually exist and are attached before attempting detach/drop.
- **Root Cause:** Swallowed exceptions hid database failures. Detaching a partition in PostgreSQL requires an `ACCESS EXCLUSIVE` lock on the root table, which waits on any active query or uncommitted transaction holding `ACCESS SHARE`.
- **Planned Correction:**
  1. Acquire a pessimistic row-level lock (`SELECT ... FOR UPDATE`) on the dataset to prevent concurrent sales/imports.
  2. Enforce active entitlement and unexpired offline lease checks (reject with `DATASET_HAS_ACTIVE_ENTITLEMENTS` / `DATASET_HAS_UNEXPIRED_LEASES`).
  3. Mark dataset status as `DELETING`.
  4. Safely detach and drop partitions using a dedicated `SECURITY DEFINER` function `detach_and_drop_dataset_partitions(p_dataset_id bigint)` which checks `pg_inherits` first.
  5. If partition detach/drop fails, throw `ApiException` and roll back transaction; do NOT delete dataset record or import issues if partition cleanup fails.
  6. Delete import issues and dataset record only after partitions are confirmed detached and dropped.
  7. Audit log the deletion.
- **Actual Implementation:** Implemented pessimistic locking via `findByIdForUpdate` in `DatasetRepository`, added active entitlement & lease rejection, marked status `DELETING`, invoked `detach_and_drop_dataset_partitions(id::bigint)` with exception propagation, and safely cleaned up import issues and dataset record.
- **Test Evidence:** Verified in `DatasetServiceIntegrationTest`. All 8 tests passed including successful deletion, partition verification, active entitlement rejection, already-deleting rejection, and missing partition resilience.
- **Final Status:** FIXED

---

## Finding 2: Concurrency Guards for Dataset Status
- **Component / File:** 
  - `src/main/java/com/elecsarthi/service/impl/SaleServiceImpl.java`
  - `src/main/java/com/elecsarthi/service/impl/DatasetImportServiceImpl.java`
- **Original Problem:** Sales creation or concurrent imports could attach or reference a dataset in state `DELETING` or `PENDING_IMPORT`.
- **Root Cause:** Absence of explicit state checks (`status == DatasetStatus.IMPORTED`) before attaching datasets to sales or running operations.
- **Planned Correction:** 
  - In `SaleServiceImpl.createSale()`, ensure dataset status is strictly `IMPORTED`; reject any dataset that is `DELETING`, `FAILED`, or `PENDING_IMPORT`.
  - In `DatasetImportServiceImpl`, guard state transitions with row locks and validate `status == DatasetStatus.IMPORTED` when superseding datasets.
- **Actual Implementation:** Added explicit `dataset.getStatus() != DatasetStatus.IMPORTED` rejection guards in `SaleServiceImpl.resolveDataset()` and in `DatasetImportServiceImpl` for superseding.
- **Test Evidence:** Verified in `DatasetServiceIntegrationTest` and `SaleServiceIntegrationTest`.
- **Final Status:** FIXED

---

## Finding 3: Legacy `CUSTOMER_ADMIN` Role in Active Database Schema
- **Component / File:** 
  - `src/main/resources/db/migration/V1__elecsarthi_schema.sql` (legacy baseline)
  - `src/main/resources/db/migration/V5__remove_legacy_customer_admin_role.sql` (new forward migration)
  - `tools/db/test_schema.sql`
- **Original Problem:** The active schema contained references to a 6th role `CUSTOMER_ADMIN` in `app_user.role` check, `ck_tenant_by_role`, partial unique index `ux_one_customer_admin`, `user_device.user_role`, `user_session`, and `check_sync_user_role()` trigger function. The product canonical roles are strictly five: `OWNER`, `SUBOWNER`, `AGENT`, `CUSTOMER`, `SUBUSER`.
- **Root Cause:** Initial design included `CUSTOMER_ADMIN` before canonical 5-role consolidation. V1-V4 cannot be modified directly (Flyway immutability rule).
- **Planned Correction:**
  1. Add forward migration `V5__remove_legacy_customer_admin_role.sql` to:
     - Drop old check constraints and triggers referencing `CUSTOMER_ADMIN`.
     - Recreate constraints with the 5 canonical roles: `OWNER`, `SUBOWNER`, `AGENT`, `CUSTOMER`, `SUBUSER`.
     - Rename/recreate partial index `ux_one_customer_admin` to `ux_one_customer_primary_login` on `app_user(customer_account_id) WHERE role = 'CUSTOMER'`.
     - Recreate `check_sync_user_role()` trigger allowing only `role = 'CUSTOMER'`.
  2. Update `tools/db/test_schema.sql` to replace all `CUSTOMER_ADMIN` fixtures with `CUSTOMER`, and add negative tests verifying rejection of `CUSTOMER_ADMIN`.
- **Actual Implementation:** Created forward migration `V5__remove_legacy_customer_admin_role.sql` and updated `tools/db/test_schema.sql` with negative assertions for `CUSTOMER_ADMIN` in `app_user`, `user_device`, and `user_session`.
- **Test Evidence:** Executed `tools/db/test_schema.sql` against PostgreSQL 18. All 79 assertions passed (0 errors, 0 failures). Negative rejection tests verified for `CUSTOMER_ADMIN`.
- **Final Status:** FIXED

---

## Finding 4: Least-Privilege Database Runtime User (`elecsarthi_app`)
- **Component / File:** 
  - `src/main/resources/db/migration/V5__remove_legacy_customer_admin_role.sql`
  - `application-prod.yaml`
  - `docker-compose.yml`
- **Original Problem:** In production, the runtime application connects as unprivileged user `elecsarthi_app`. `ALTER TABLE ... DETACH PARTITION` and `DROP TABLE` require table ownership or superuser privileges in PostgreSQL.
- **Root Cause:** Direct DDL commands executed by `elecsarthi_app` fail with permission denied unless delegated.
- **Planned Correction:**
  - Create database function `detach_and_drop_dataset_partitions(p_dataset_id bigint)` with `SECURITY DEFINER` owned by migration user/postgres.
  - Grant `EXECUTE ON FUNCTION detach_and_drop_dataset_partitions TO elecsarthi_app`.
  - Let `DatasetServiceImpl` invoke this function via native SQL call.
- **Actual Implementation:** Added `detach_and_drop_dataset_partitions` with `SECURITY DEFINER` and granted `EXECUTE` on all partition functions to `elecsarthi_app` in `V5__remove_legacy_customer_admin_role.sql`.
- **Test Evidence:** Verified in schema migration test suite against PostgreSQL 18.
- **Final Status:** FIXED

---

## Finding 5: Production Configuration, Secrets Validation & Docker Hardening
- **Component / File:**
  - `src/main/resources/application-prod.yaml`
  - `src/main/java/com/elecsarthi/security/config/SecurityConfig.java`
  - `src/main/java/com/elecsarthi/config/AppProperties.java`
  - `docker-compose.yml`
  - `Dockerfile`
  - `.env.example`
  - `.gitignore`
  - `docs/deployment/DEPLOYMENT_GUIDE.md`
- **Original Problem:**
  1. Datasource environment variable naming mismatch (`DB_URL` vs `SPRING_DATASOURCE_URL`).
  2. Hardcoded dev secrets in `docker-compose.yml`.
  3. `SecurityConfig.validateProductionSecurity()` checked JWT and activation pepper, but did not validate `ed25519-private-key-hex`, `voter-mobile-aes-key-hex`, or their key versions and lengths.
  4. Dockerfile healthcheck used `wget` or `curl` which may not be present in minimal JRE images.
  5. Missing `.env.example` and documentation for TLS Option B (reverse proxy termination).
- **Root Cause:** Incomplete alignment between Docker, compose, application configuration, and fail-fast validation.
- **Planned Correction:**
  1. Standardize `application-prod.yaml` and `docker-compose.yml` to standard Spring environment variables.
  2. Remove default passwords/secrets from `docker-compose.yml` and provide `.env.example`.
  3. Expand `SecurityConfig.validateProductionSecurity()` to assert non-default Ed25519 private key (64 hex chars), AES key (64 hex chars = 256 bits), and active key versions.
  4. Fix Dockerfile healthcheck using a lightweight curl or Java-based HTTP check.
  5. Document reverse proxy TLS termination with forwarded headers in `DEPLOYMENT_GUIDE.md`.
- **Actual Implementation:** Standardized datasource and secret env vars across `application-prod.yaml` and `docker-compose.yml`. Configured `elecsarthi_app` runtime user and Flyway migration runner service. Expanded fail-fast checks in `SecurityConfig.java` to validate JWT, pepper, Ed25519, and AES keys. Installed `curl` in Dockerfile runner stage. Created `.env.example` and added `.env` to `.gitignore`. Documented TLS Option B in `DEPLOYMENT_GUIDE.md`.
- **Test Evidence:** Compiled with Java 25 (`BUILD SUCCESS`), verified configuration properties and fail-fast checks.
- **Final Status:** FIXED

---

## Finding 6: Missing Dedicated Architecture Packages
- **Component / File:**
  - `src/main/java/com/elecsarthi/mapper/`
  - `src/main/java/com/elecsarthi/security/signing/`
  - `src/main/java/com/elecsarthi/security/authorization/`
- **Original Problem:** Architecture specification required dedicated packages for mappers, cryptographic signing, and domain-level authorization gates. Some logic was embedded directly in services.
- **Root Cause:** Services were implemented monolithically without extracting helper delegates into their target architectural packages.
- **Planned Correction:**
  - Implement `com.elecsarthi.mapper` with DTO mappers (`UserMapper`, `CustomerMapper`, `DatasetMapper`, `SaleMapper`, `EntitlementMapper`, `TurnoutMapper`, `SubuserMapper`).
  - Implement `com.elecsarthi.security.signing` with `DeviceSignatureVerifier` (ECDSA P-256) and `OfflineLeaseSigner` (Ed25519 signing & verification).
  - Implement `com.elecsarthi.security.authorization` with `VoterAccessAuthorizer` and `TenantIsolationAuthorizer`.
  - Refactor service classes to cleanly delegate to these components without altering public APIs or test contracts.
- **Actual Implementation:** Created all mapper components, `DeviceSignatureVerifier`, `OfflineLeaseSigner`, `VoterAccessAuthorizer`, and `TenantIsolationAuthorizer`. Refactored `DeviceServiceImpl`, `OfflineLeaseServiceImpl`, `VoterServiceImpl`, and `CustomerServiceImpl` to use them.
- **Test Evidence:** Compiled with Java 25 (`BUILD SUCCESS`), verified across unit and integration suites.
- **Final Status:** FIXED

---

## Finding 7: Documentation & Artifact Synchronization
- **Component / File:**
  - `README.md`
  - `HELP.md`
  - `docs/data-model/ElecSarthi_ER_Diagram.md`, `.svg`, `.png`
  - `docs/architecture/REPOSITORY_AUDIT.md`
  - `docs/IMPLEMENTATION_STATUS.md`
  - `.claude/rules/` and `.claude/` files
- **Original Problem:** Documentation and ER diagrams still referenced `CUSTOMER_ADMIN` or needed updates reflecting V5 and the completed architecture.
- **Root Cause:** Incremental evolution across phases left certain documentation files reflecting older snapshots.
- **Planned Correction:** Synchronize all markdown documentation, ER diagrams, rules, and implementation status with the final verified architecture.
- **Actual Implementation:** Synchronized `README.md`, `HELP.md`, `ElecSarthi_ER_Diagram.md`, `REPOSITORY_AUDIT.md`, and `IMPLEMENTATION_STATUS.md`. Removed all legacy `CUSTOMER_ADMIN` role references in favor of the canonical 5 roles (`OWNER`, `SUBOWNER`, `AGENT`, `CUSTOMER`, `SUBUSER`), established exact migration filenames on disk (`V1`–`V6`, including `V4__canonical_customer_role.sql` and `V6__harden_security_definer_partition_functions.sql`), corrected ERD technical guidance to Java 25 & Spring Boot 4.2.0 with centralized layered architecture, documented external legal and operational release dependencies, and recorded complete test suite pass status (37/37 integration tests, 83/83 schema tests).
- **Test Evidence:** Documented and verified across all files; git diff clean and consistent.
- **Final Status:** FIXED

---

## Finding 8: Complete Test Suite Verification
- **Component / File:**
  - `tools/db/test_schema.sql` (83 assertions)
  - `DatasetServiceIntegrationTest` (8 tests)
  - `AuthServiceIntegrationTest` (7 tests)
  - `CustomerServiceIntegrationTest` (7 tests)
  - `SaleServiceIntegrationTest` (6 tests)
  - `VoterServiceIntegrationTest` (5 tests)
  - `SubuserServiceIntegrationTest` (4 tests)
- **Original Problem:** `DatasetServiceIntegrationTest` was deferred due to partition lock timeout. Full test suite must pass sequentially against PostgreSQL 18.
- **Root Cause:** Deferral pending safe deletion and lock handling fix.
- **Planned Correction:** Run schema test script and all 6 integration test suites sequentially. Verify zero failures, zero errors, zero skipped.
- **Actual Implementation:** 
  1. Ran schema test suite against disposable database: 83/83 passed (0 errors, 0 failures).
  2. Ran all 6 integration test suites sequentially against PostgreSQL 18:
     - `AuthServiceIntegrationTest`: 7/7 passed (0 failures, 0 errors, 0 skipped).
     - `CustomerServiceIntegrationTest`: 7/7 passed (0 failures, 0 errors, 0 skipped).
     - `SaleServiceIntegrationTest`: 6/6 passed (0 failures, 0 errors, 0 skipped).
     - `VoterServiceIntegrationTest`: 5/5 passed (0 failures, 0 errors, 0 skipped).
     - `SubuserServiceIntegrationTest`: 4/4 passed (0 failures, 0 errors, 0 skipped).
     - `DatasetServiceIntegrationTest`: 8/8 passed (0 failures, 0 errors, 0 skipped).
- **Test Evidence:** 37/37 Java integration tests passed + 83/83 database schema tests passed against PostgreSQL 18. All builds reported `BUILD SUCCESS`.
- **Final Status:** FIXED

---

## Finding 9: Hardening of SECURITY DEFINER Partition Management Functions
- **Component / File:** `src/main/resources/db/migration/V6__harden_security_definer_partition_functions.sql`, `tools/db/test_schema.sql`
- **Original Problem:** `create_dataset_partitions`, `create_dataset_indexes`, and `detach_and_drop_dataset_partitions` lacked search_path pinning, schema qualification, strict input validation, namespace verification on `pg_inherits`, and default `PUBLIC` execution privileges had not been revoked.
- **Root Cause:** Default PostgreSQL function semantics grant execution to `PUBLIC` and resolve objects using the caller's active search_path.
- **Planned Correction:** Implement forward migration `V6` with `SET search_path = pg_catalog, public`, schema qualifications (`public.voter`), strict validation (`p_dataset_id > 0`), safe `%I` dynamic formatting, `REVOKE ... FROM PUBLIC`, and explicit grants strictly to `elecsarthi_app`. Add regression tests in `test_schema.sql`.
- **Actual Implementation:** Authored and applied `V6__harden_security_definer_partition_functions.sql`. Tested execution under `elecsarthi_app` and verified permission denial under unprivileged roles. Added 4 regression assertions to `tools/db/test_schema.sql`.
- **Test Evidence:** 83/83 schema checks passed on fresh PostgreSQL 18 database. Verified rejection of negative IDs, zero IDs, and unattached partitions.
- **Final Status:** FIXED

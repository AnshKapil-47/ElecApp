# ElecSarthi — Repository Audit Report

**Audit Date:** 2026-11-24  
**Auditor:** Principal Software Architect & Application Security Engineer  
**Workspace:** `elecAppBackend` (Root Maven Project)

---

## 1. Executive Summary

This repository audit was conducted prior to modifying application code. The audit verifies the workspace structure, existing source files, build configuration, database migration state, documentation discrepancies, and runtime environment.

---

## 2. Workspace File & Directory Inventory

| Path | Category | Status & Findings |
|---|---|---|
| `pom.xml` | Maven Configuration | Declares `java.version` 25, `spring-boot-starter-parent` 4.2.0, Spring Data JPA, Security, Actuator, Lombok. Group: `com.final.ansh`, Artifact: `elecAppBackend`. |
| `mvnw`, `mvnw.cmd`, `.mvn/` | Maven Wrapper | Apache Maven Wrapper 3.3.4 with Maven 3.10.1. Updated wrapper script to bind to JDK 25 (`C:\Program Files\Java\jdk-25`). |
| `src/main/java/com/example/demo/` | Starter Source | Contains starter class `ElecAppBackendApplication.java`. Must be migrated to `com.elecsarthi`. |
| `src/test/java/com/example/demo/` | Starter Test | Contains starter class `ElecAppBackendApplicationTests.java`. Must be migrated to `com.elecsarthi`. |
| `src/main/resources/application.yaml` | Configuration | Basic YAML with application name `elecAppBackend`. Environment profiles and connection configs missing. |
| `src/main/resources/db/migration/` | Database Migrations | **MISSING in supplied archive.** No migration SQL files were present in `src/main/resources/db/migration` or `backend/src/main/resources/db/migration`. |
| `tools/db/test_schema.sql` | Schema Validation | Comprehensive 76-check test script for the 18-table PostgreSQL schema. The primary executable specification for database constraints. |
| `tools/db/run_tests.sh` | Schema Test Runner | Shell runner for executing schema tests against disposable test databases. |
| `tools/seed/csv_to_migration.py` | Seed Migration Tool | Python generator producing `V3__seed_election_type_field.sql` from CSV seed. Verified working. |
| `tools/seed/gen_seed.py` | Seed Generator Tool | Python extractor creating `election_type_field_seed.csv` from `NEW APP COLUMN DETAILS.xlsx`. Verified working. |
| `tools/erd/gen_erd.py` | ERD Generator | Python DOT/SVG/PNG generator with precise column definitions and edge rules for all 18 tables. |
| `tools/erd/verify_ends.py` | ERD Validator | Python script verifying crow's foot diagram edge semantics. |
| `docs/data-model/ElecSarthi_ER_Diagram.md` | Authoritative ERD Spec | Full architectural description of the 18 tables, partitions, exclusion constraints, triggers, and voter query templates. |
| `docs/data-model/NEW APP COLUMN DETAILS.xlsx` | Field Authority | The Excel workbook defining FILTER (skin) and DISPLAY (green) columns for the 8 election types. |
| `docs/data-model/election_type_field_seed.csv` | Generated Seed Data | 271 rows defining field mappings across 8 election types. |
| `.claude/` (rules, agents, skills, hooks) | Engineering Tools | Preserved. Contain legacy references to `backend/` path and `CUSTOMER_ADMIN` role name that must be updated. |

---

## 3. Discrepancies & Resolution Strategy

### Discrepancy 1: Missing Migration SQL Files
- **Observation:** `CLAUDE.md`, `ElecSarthi_ER_Diagram.md`, and `.claude/rules/data-model.md` refer to `V1__elecsarthi_schema.sql`, `V2__runtime_role.sql`, and `V3__seed_election_type_field.sql`, but none of these files were in the workspace.
- **Resolution:** Reconstruct the complete database migrations under `src/main/resources/db/migration/`:
  - `V1__elecsarthi_schema.sql`: Full 18-table DDL, constraints, triggers, partition management functions (`create_dataset_partitions`, `create_dataset_indexes`), exclusion constraints (`ex_ent_no_overlap`, `ex_ba_slot`, `ex_ba_same_booth`), and 8 seeded election types.
  - `V2__runtime_role.sql`: Grants for application runtime role `elecsarthi_app`.
  - `V3__seed_election_type_field.sql`: Generated from `election_type_field_seed.csv` via `tools/seed/csv_to_migration.py` (271 rows).
  - `V4__add_missing_indexes_and_constraints.sql`: Performance indexes, foreign key cascades, and unique constraints.
  - `V5__remove_legacy_customer_admin_role.sql`: Enforces canonical 5 roles, drops legacy `CUSTOMER_ADMIN` check constraints and triggers, and provides `SECURITY DEFINER` partition lifecycle functions (`create_dataset_partitions`, `create_dataset_indexes`, `detach_and_drop_dataset_partitions`) for the unprivileged `elecsarthi_app` user.

### Discrepancy 2: Package Structure and Base Package
- **Observation:** Workspace contained starter package `com.example.demo`.
- **Resolution:** Move all Java code to `com.elecsarthi` and establish the mandatory layered architecture:
  - `com.elecsarthi.controller`
  - `com.elecsarthi.service` & `com.elecsarthi.service.impl`
  - `com.elecsarthi.repository`
  - `com.elecsarthi.entity`
  - `com.elecsarthi.dto.request` & `com.elecsarthi.dto.response`
  - `com.elecsarthi.enums`
  - `com.elecsarthi.mapper`
  - `com.elecsarthi.security`
  - `com.elecsarthi.exception`
  - `com.elecsarthi.config`

### Discrepancy 3: Role Naming (CUSTOMER vs CUSTOMER_ADMIN)
- **Observation:** Existing schema tests and markdown files used `CUSTOMER_ADMIN` for the primary Customer user. Prompt mandates canonical role name `CUSTOMER`.
- **Resolution:** Update all application enums, security rules, and database constraints so `CUSTOMER` is the canonical role name across the entire platform.

---

## 4. Environment & Tooling Verification

- **Java Version:** JDK 25.0.1 LTS (`C:\Program Files\Java\jdk-25`) verified and functional.
- **Maven:** Maven 3.10.1 via Maven Wrapper compiling with Java 25 release targets.
- **Database:** PostgreSQL 18.3 running on port 5498 with `btree_gist`, `citext`, and `pg_trgm` extensions verified.
- **Python:** Python 3.14.5 verified.
- **Git:** Git repository initialized and baseline commit captured.

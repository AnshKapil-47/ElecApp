---
name: tenant-test
description: Write cross-tenant and authorization integration tests for an ElecSarthi endpoint (other Agent, other Customer, Subuser outside booth, expired/revoked entitlement, suspended account, terminated session, no dataset internals in JSON). Use after adding or changing any API.
argument-hint: "[endpoint or controller class]"
---

# Cross-tenant tests for $ARGUMENTS

Use JUnit 5 + Spring Boot Test + Testcontainers `postgres:16` with Flyway migrations applied. Do not use H2. Build fixtures with the builder in `reference.md`.

## Fixture world (create once per test class)

- Owner, Subowner, Agent A, Agent B.
- Customer 1 (owned by A) with its admin, Subuser S1 and an ACTIVE entitlement E1 (Vidhan Sabha, HARYANA AC 10, MASTER). S1 is assigned booth 101 on E1.
- Customer 2 (owned by B) with its admin, Subuser S2 and entitlement E2 on the same area.
- One expired entitlement E1x for Customer 1. Voters in booths 101 and 102.

## Required cases (pick those that apply to the endpoint)

| Case | Expected |
|---|---|
| Correct role and owner | 2xx with the right data only |
| Wrong role (e.g. Agent calls voter search, Subowner calls sales report) | 403 |
| Agent A acts on Customer 2 / reads Agent B's sale | 404, nothing leaked |
| Customer 1 admin uses E2's id | 404 |
| S1 reads a voter in booth 102, or changes the booth in the request | 404 |
| E1x (expired) or a REVOKED entitlement | 404 |
| Customer SUSPENDED | 401/403 and the session is terminated |
| Terminated session / old access token | 401 |
| Response JSON | no `datasetId`, dataset name, file name; no mobile number unless switch on + CUSTOMER_ADMIN + `mobile_access` |
| Sensitive action | `audit_log` row written (SUCCESS and, for denied tries, DENIED) |

## Rules

- Assert the **body** too, not just the status. A 404 must not say "exists but forbidden".
- Synthetic data only; never real names or numbers.
- Use an injectable `Clock` for expiry, never sleeps.
- Run the tests and report the pass/fail list.

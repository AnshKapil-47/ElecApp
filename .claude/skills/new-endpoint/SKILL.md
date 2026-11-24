---
name: new-endpoint
description: Add or change a REST endpoint in the ElecSarthi Spring Boot backend with the required role check, tenant-scoped query, DTO without dataset internals, audit entry and cross-tenant tests. Use for any new or modified API.
argument-hint: "[HTTP method and path, e.g. POST /api/agent/sales]"
---

# New endpoint: $ARGUMENTS

1. **Who may call it.** Look up the capability in the permission matrix in `.claude/rules/business-rules.md`. Write down the allowed roles and the resource rule (own Customers / own entitlements / assigned booths / all). If the matrix doesn't cover it, ask one short question before coding.
2. **Contract.** Define request and response DTOs (Java records).
   - Responses to Agents, Customers and Subusers never contain dataset id, name, file name, hash, counts or import issues.
   - Voters are referenced as `entitlementId` + `voterRef`.
   - Mobile numbers appear only under the rule in CLAUDE.md #5.
   - Validate input with Bean Validation; never accept a role, owner, agent or customer id as authority.
3. **Controller:** thin, with `@PreAuthorize("hasRole('…')")`. The current user comes from the security context (`CurrentUser`), never from the body.
4. **Service:**
   - Load resources with queries scoped to the caller; return 404 for anything outside scope.
   - Check validity against the DB clock (entitlement, booth, session, device).
   - Map constraint violations by name (see `.claude/rules/backend-java.md`).
   - Use one transaction for multi-row business actions.
5. **Voter data?** Use `/voter-query`. Never build SQL from client strings.
6. **Audit:** if the action is in the sensitive list in `.claude/rules/security.md`, write `audit_log` with outcome SUCCESS or DENIED. Never put secrets or mobile numbers in the details.
7. **Tests:** use `/tenant-test` for the endpoint: happy path, wrong role, cross-tenant, expired or revoked access, suspended Customer, terminated session, and no dataset internals in the JSON.
8. **OpenAPI:** add a summary and examples with synthetic data only.
9. **Before finishing:** run the backend tests, then ask the `security-reviewer` agent to review the diff. Fix every HIGH finding.

---
name: voter-query
description: Build or change any query that reads ElecSarthi voter data (search, list, details, family, slip, turnout stats, sync chunks, lease payloads) safely - entitlement check, whitelisted columns, bind parameters, partition pruning, booth scope, mobile masking. Use whenever code touches the voter or voter_turnout tables.
---

# Voter access query

Every voter read follows the same five steps. The full worked example is in `reference.md` (read it before writing code).

1. **Entitlement gate:**
   - Load the entitlement by id **and** by `customer_account_id` = the caller's Customer.
   - Require `status = 'ACTIVE'`, `valid_from <= now() < valid_until`, and the Customer ACTIVE.
   - If anything fails, return 404.
2. **Columns:** select only the lens's fields: `election_type_field` rows for `entitlement.election_type_id`, both FILTER and DISPLAY, in `display_order`. Column names come **only** from `source_column`, which the database whitelists with `is_voter_column()`. Quote them as identifiers, never as user text.
3. **Area predicate:**
   - Add `v.dataset_id = :datasetId` (from the entitlement row, never from the client) so PostgreSQL prunes to one partition.
   - Add one `AND v.<source_column> = :pN` per FILTER field, with values from `entitlement.criteria` and types from `value_type`.
4. **Subuser scope:** additionally require `v.<election_type.booth_column> IN (:booths)`. Take the booth values from the caller's ACTIVE, current `booth_assignment` rows on this entitlement. If there are none, return 404.
5. **Projection and masking:**
   - `voter.id AS voter_ref`.
   - Never return `dataset_id`.
   - Decrypt `mobile_no_enc` only if `MOBILE_NUMBERS_ENABLED` is on, the caller is CUSTOMER_ADMIN and `entitlement.mobile_access` is true. Otherwise omit the field entirely (no null placeholder that hints it exists).

Search and paging:
- **Name:** trigram `ILIKE` on the indexed expression `coalesce(v_fname_en,'') || ' ' || coalesce(v_lname_en,'')` (and the `_mt` one).
- **EPIC:** exact `voter_id = :q`.
- **Order and size:** `ORDER BY part_no, serial_no`, page size ≤ 100.

Variants:
- **Family:** same `dataset_id`, same booth column value, same non-blank `house_no`, **plus** the area predicate (and the booth scope for Subusers).
- **Slip:** the lens's `on_slip` fields only.
- **Turnout stats:** the same predicates, `LEFT JOIN voter_turnout t ON (t.dataset_id, t.voter_row_id, t.customer_account_id) = (v.dataset_id, v.id, :customerId)`.
- **Sync chunk / lease payload:** the same query, keyset-paginated by `id`. Re-check the entitlement (and grant) before **each** chunk.

Tests to add (see `/tenant-test`):
- another Customer's entitlement id → 404;
- a Subuser outside the booth → 404;
- an expired entitlement → 404;
- JSON has no `datasetId`;
- a mobile number is absent for a Subuser and when the switch is off.

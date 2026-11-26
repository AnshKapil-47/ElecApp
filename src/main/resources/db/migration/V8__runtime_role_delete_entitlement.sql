-- ============================================================================
-- V8__runtime_role_delete_entitlement.sql
-- Deleting a dataset after its election removes the entitlements that point at it
-- (entitlement.dataset_id is a foreign key). The runtime role had no DELETE on
-- entitlement, so the backend could not do this in production.
-- Sale history is untouched: agent_sale_line keeps its own snapshots and the runtime
-- role still has no UPDATE or DELETE on it.
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'elecsarthi_app') THEN
        GRANT DELETE ON entitlement TO elecsarthi_app;
    END IF;
END $$;

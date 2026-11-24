-- ============================================================================
-- V2__runtime_role.sql
-- Least-privilege runtime role 'elecsarthi_app' for running backend application
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'elecsarthi_app') THEN
        CREATE ROLE elecsarthi_app WITH LOGIN PASSWORD 'elecsarthi_app_dev_pw';
    END IF;
END $$;

-- Schema usage
GRANT USAGE ON SCHEMA public TO elecsarthi_app;

-- Tables with standard CRUD
GRANT SELECT, INSERT, UPDATE ON customer_account TO elecsarthi_app;
GRANT SELECT, INSERT, UPDATE ON app_user TO elecsarthi_app;
GRANT SELECT, INSERT, UPDATE ON user_device TO elecsarthi_app;
GRANT SELECT, INSERT, UPDATE ON user_session TO elecsarthi_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON dataset TO elecsarthi_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON import_issue TO elecsarthi_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON voter TO elecsarthi_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON voter_turnout TO elecsarthi_app;
GRANT SELECT, INSERT, UPDATE ON entitlement TO elecsarthi_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON booth_assignment TO elecsarthi_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON offline_lease TO elecsarthi_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON sync_session TO elecsarthi_app;
GRANT SELECT, INSERT, DELETE ON device_nonce TO elecsarthi_app;

-- Read-only tables
GRANT SELECT ON election_type TO elecsarthi_app;
GRANT SELECT ON election_type_field TO elecsarthi_app;

-- Append-only audit log: SELECT and INSERT only
GRANT SELECT, INSERT ON audit_log TO elecsarthi_app;

-- Sales history: INSERT and SELECT on header; UPDATE ONLY on void columns; no DELETE
GRANT SELECT, INSERT ON agent_sale TO elecsarthi_app;
GRANT UPDATE (status, voided_at, void_reason, voided_by_user_id) ON agent_sale TO elecsarthi_app;

-- Sale lines: immutable history - SELECT and INSERT only; NO UPDATE, NO DELETE
GRANT SELECT, INSERT ON agent_sale_line TO elecsarthi_app;

-- Sequences
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO elecsarthi_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO elecsarthi_app;

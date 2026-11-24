-- V7__audit_log_optimization_indexes.sql
-- Optimization indexes for audit_log queries (Registration JTI and Global Mobile Toggle)

CREATE INDEX IF NOT EXISTS idx_audit_log_registration_jti
    ON audit_log ((details->>'jti'))
    WHERE action_type = 'REGISTRATION_JTI_INVALIDATED';

CREATE INDEX IF NOT EXISTS idx_audit_log_global_mobile_toggle
    ON audit_log (created_at DESC)
    WHERE action_type = 'GLOBAL_MOBILE_TOGGLE';

-- ============================================================================
-- V4__canonical_customer_role.sql
-- Align existing persisted role values to canonical role 'CUSTOMER'
-- ============================================================================

ALTER TABLE user_device DROP CONSTRAINT IF EXISTS fk_device_user_role;
ALTER TABLE user_session DROP CONSTRAINT IF EXISTS fk_session_user_role;

UPDATE app_user SET role = 'CUSTOMER' WHERE role = 'CUSTOMER_ADMIN';
UPDATE user_device SET user_role = 'CUSTOMER' WHERE user_role = 'CUSTOMER_ADMIN';
UPDATE user_session SET user_role = 'CUSTOMER' WHERE user_role = 'CUSTOMER_ADMIN';

ALTER TABLE user_device ADD CONSTRAINT fk_device_user_role FOREIGN KEY (user_id, user_role) REFERENCES app_user(id, role);
ALTER TABLE user_session ADD CONSTRAINT fk_session_user_role FOREIGN KEY (user_id, user_role) REFERENCES app_user(id, role);

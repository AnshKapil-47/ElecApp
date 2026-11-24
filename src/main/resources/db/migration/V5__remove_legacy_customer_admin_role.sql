-- ============================================================================
-- V5__remove_legacy_customer_admin_role.sql
-- 1. Remove legacy CUSTOMER_ADMIN role completely from all active constraints,
--    indexes, and triggers, enforcing the 5 canonical roles:
--    OWNER, SUBOWNER, AGENT, CUSTOMER, SUBUSER.
-- 2. Provide SECURITY DEFINER partition management functions for least-privilege
--    runtime execution (elecsarthi_app).
-- ============================================================================

-- Ensure any existing rows use canonical role 'CUSTOMER'
UPDATE app_user SET role = 'CUSTOMER' WHERE role = 'CUSTOMER_ADMIN';
UPDATE user_device SET user_role = 'CUSTOMER' WHERE user_role = 'CUSTOMER_ADMIN';
UPDATE user_session SET user_role = 'CUSTOMER' WHERE user_role = 'CUSTOMER_ADMIN';

-- ----------------------------------------------------------------------------
-- 1. APP_USER CONSTRAINTS & INDEXES
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT conname 
        FROM pg_constraint 
        WHERE conrelid = 'app_user'::regclass 
          AND contype = 'c' 
          AND pg_get_constraintdef(oid) LIKE '%CUSTOMER_ADMIN%'
    ) LOOP
        EXECUTE 'ALTER TABLE app_user DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
END $$;

ALTER TABLE app_user DROP CONSTRAINT IF EXISTS ck_app_user_role;
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS ck_tenant_by_role;

ALTER TABLE app_user ADD CONSTRAINT ck_app_user_role 
    CHECK (role IN ('OWNER', 'SUBOWNER', 'AGENT', 'CUSTOMER', 'SUBUSER'));

ALTER TABLE app_user ADD CONSTRAINT ck_tenant_by_role 
    CHECK (
        (role IN ('CUSTOMER', 'SUBUSER') AND customer_account_id IS NOT NULL) OR
        (role IN ('OWNER', 'SUBOWNER', 'AGENT') AND customer_account_id IS NULL)
    );

DROP INDEX IF EXISTS ux_one_customer_admin;
DROP INDEX IF EXISTS ux_one_customer_primary_login;
CREATE UNIQUE INDEX ux_one_customer_primary_login ON app_user (customer_account_id)
WHERE role = 'CUSTOMER';

-- ----------------------------------------------------------------------------
-- 2. USER_DEVICE CONSTRAINTS
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT conname 
        FROM pg_constraint 
        WHERE conrelid = 'user_device'::regclass 
          AND contype = 'c' 
          AND pg_get_constraintdef(oid) LIKE '%CUSTOMER_ADMIN%'
    ) LOOP
        EXECUTE 'ALTER TABLE user_device DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
END $$;

ALTER TABLE user_device DROP CONSTRAINT IF EXISTS ck_user_device_role;
ALTER TABLE user_device DROP CONSTRAINT IF EXISTS ck_device_tenant;

ALTER TABLE user_device ADD CONSTRAINT ck_user_device_role 
    CHECK (user_role IN ('AGENT', 'CUSTOMER', 'SUBUSER'));

ALTER TABLE user_device ADD CONSTRAINT ck_device_tenant 
    CHECK (
        (user_role = 'AGENT' AND customer_account_id IS NULL) OR
        (user_role IN ('CUSTOMER', 'SUBUSER') AND customer_account_id IS NOT NULL)
    );

-- ----------------------------------------------------------------------------
-- 3. USER_SESSION CONSTRAINTS
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT conname 
        FROM pg_constraint 
        WHERE conrelid = 'user_session'::regclass 
          AND contype = 'c' 
          AND pg_get_constraintdef(oid) LIKE '%CUSTOMER_ADMIN%'
    ) LOOP
        EXECUTE 'ALTER TABLE user_session DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
END $$;

ALTER TABLE user_session DROP CONSTRAINT IF EXISTS ck_session_client_by_role;

ALTER TABLE user_session ADD CONSTRAINT ck_session_client_by_role 
    CHECK (
        (client_type = 'WEB' AND user_role IN ('OWNER', 'SUBOWNER') AND device_id IS NULL AND refresh_token_hash IS NULL) OR
        (client_type = 'MOBILE' AND user_role IN ('AGENT', 'CUSTOMER', 'SUBUSER') AND device_id IS NOT NULL)
    );

-- ----------------------------------------------------------------------------
-- 4. SYNC_SESSION ROLE TRIGGER
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_sync_user_role() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = NEW.user_id AND role = 'CUSTOMER') THEN
        RAISE EXCEPTION 'bulk sync allowed only for Customer primary login';
    END IF;
    RETURN NEW;
END;
$$;

-- ----------------------------------------------------------------------------
-- 5. LEAST-PRIVILEGE PARTITION LIFECYCLE FUNCTIONS (SECURITY DEFINER)
-- ----------------------------------------------------------------------------
ALTER FUNCTION create_dataset_partitions(bigint) SECURITY DEFINER;
ALTER FUNCTION create_dataset_indexes(bigint) SECURITY DEFINER;

CREATE OR REPLACE FUNCTION detach_and_drop_dataset_partitions(p_dataset_id bigint)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_part text := format('voter_d%s', p_dataset_id);
    v_t_part text := format('voter_turnout_d%s', p_dataset_id);
    is_attached boolean;
BEGIN
    -- Check if voter_turnout partition exists and is attached
    SELECT EXISTS (
        SELECT 1
        FROM pg_inherits i
        JOIN pg_class c ON i.inhrelid = c.oid
        JOIN pg_class p ON i.inhparent = p.oid
        WHERE c.relname = v_t_part AND p.relname = 'voter_turnout'
    ) INTO is_attached;

    IF is_attached THEN
        EXECUTE format('ALTER TABLE voter_turnout DETACH PARTITION %I;', v_t_part);
    END IF;

    -- Drop voter_turnout partition table if exists
    EXECUTE format('DROP TABLE IF EXISTS %I CASCADE;', v_t_part);

    -- Check if voter partition exists and is attached
    SELECT EXISTS (
        SELECT 1
        FROM pg_inherits i
        JOIN pg_class c ON i.inhrelid = c.oid
        JOIN pg_class p ON i.inhparent = p.oid
        WHERE c.relname = v_part AND p.relname = 'voter'
    ) INTO is_attached;

    IF is_attached THEN
        EXECUTE format('ALTER TABLE voter DETACH PARTITION %I;', v_part);
    END IF;

    -- Drop voter partition table if exists
    EXECUTE format('DROP TABLE IF EXISTS %I CASCADE;', v_part);
END;
$$;

-- Overload for integer argument to prevent implicit cast errors
CREATE OR REPLACE FUNCTION detach_and_drop_dataset_partitions(p_dataset_id integer)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
AS $$
    SELECT detach_and_drop_dataset_partitions(p_dataset_id::bigint);
$$;

-- Grant permissions to elecsarthi_app if the role exists
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'elecsarthi_app') THEN
        GRANT EXECUTE ON FUNCTION create_dataset_partitions(bigint) TO elecsarthi_app;
        GRANT EXECUTE ON FUNCTION create_dataset_indexes(bigint) TO elecsarthi_app;
        GRANT EXECUTE ON FUNCTION detach_and_drop_dataset_partitions(bigint) TO elecsarthi_app;
        GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO elecsarthi_app;
    END IF;
END $$;

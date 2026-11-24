-- ============================================================================
-- V6__harden_security_definer_partition_functions.sql
-- 1. Secure SECURITY DEFINER partition management functions:
--    - Fixed and trusted search_path = pg_catalog, public to prevent search-path injection.
--    - Schema-qualified object references (public.voter, public.voter_turnout).
--    - Strict validation of dataset IDs (must be positive integer).
--    - Safe dynamic SQL identifier construction with %I and literals with %L.
--    - Rigorous pg_inherits partition-parent verification scoped to public schema.
--    - Revoke EXECUTE from PUBLIC.
--    - Explicitly grant EXECUTE strictly to elecsarthi_app runtime role.
--    - Robust error propagation.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. REVOKE DEFAULT EXECUTE PRIVILEGES FROM PUBLIC
-- ----------------------------------------------------------------------------
REVOKE ALL ON FUNCTION public.create_dataset_partitions(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.create_dataset_indexes(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.detach_and_drop_dataset_partitions(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.detach_and_drop_dataset_partitions(integer) FROM PUBLIC;

-- ----------------------------------------------------------------------------
-- 2. HARDENED create_dataset_partitions
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.create_dataset_partitions(p_dataset_id bigint)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_part text;
    v_t_part text;
BEGIN
    IF p_dataset_id IS NULL OR p_dataset_id <= 0 THEN
        RAISE EXCEPTION 'Invalid dataset_id: must be a positive integer';
    END IF;

    v_part := format('voter_d%s', p_dataset_id);
    v_t_part := format('voter_turnout_d%s', p_dataset_id);

    EXECUTE format('CREATE TABLE IF NOT EXISTS public.%I PARTITION OF public.voter FOR VALUES IN (%L);', v_part, p_dataset_id);
    EXECUTE format('CREATE TABLE IF NOT EXISTS public.%I PARTITION OF public.voter_turnout FOR VALUES IN (%L);', v_t_part, p_dataset_id);
END;
$$;

-- ----------------------------------------------------------------------------
-- 3. HARDENED create_dataset_indexes
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.create_dataset_indexes(p_dataset_id bigint)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_part text;
    is_attached boolean;
BEGIN
    IF p_dataset_id IS NULL OR p_dataset_id <= 0 THEN
        RAISE EXCEPTION 'Invalid dataset_id: must be a positive integer';
    END IF;

    v_part := format('voter_d%s', p_dataset_id);

    -- Strict verification that target table exists in public schema and is attached to public.voter
    SELECT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_inherits i
        JOIN pg_catalog.pg_class c ON i.inhrelid = c.oid
        JOIN pg_catalog.pg_class p ON i.inhparent = p.oid
        WHERE c.relname = v_part
          AND c.relnamespace = 'public'::regnamespace
          AND p.relname = 'voter'
          AND p.relnamespace = 'public'::regnamespace
    ) INTO is_attached;

    IF NOT is_attached THEN
        RAISE EXCEPTION 'Partition public.%I is not attached to public.voter', v_part;
    END IF;

    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (state, pc_no, ac_no, part_no);', format('idx_%s_ls', v_part), v_part);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (state, ac_no, part_no);', format('idx_%s_vs', v_part), v_part);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (state, district, municipal_name_nagar_nigam_en, nagar_nigam_ward_no, booth_no);', format('idx_%s_nn', v_part), v_part);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (state, district, nagar_palika_ward_no, booth_no);', format('idx_%s_np', v_part), v_part);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (state, district, nagar_parishad_ward_no, booth_no);', format('idx_%s_npr', v_part), v_part);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (state, district, zilla_parishad_name_ward_no, booth_no);', format('idx_%s_zp', v_part), v_part);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (state, district, panchayat_samiti_name_ward_no, booth_no);', format('idx_%s_ps', v_part), v_part);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (state, district, panchayat_no, booth_no);', format('idx_%s_pan', v_part), v_part);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (voter_id);', format('idx_%s_epic', v_part), v_part);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (part_no, serial_no);', format('idx_%s_roll', v_part), v_part);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I USING gin ((coalesce(v_fname_en,'''') || '' '' || coalesce(v_lname_en,'''')) gin_trgm_ops);', format('idx_%s_name_en_trgm', v_part), v_part);
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I USING gin ((coalesce(v_fname_mt,'''') || '' '' || coalesce(v_lname_mt,'''')) gin_trgm_ops);', format('idx_%s_name_mt_trgm', v_part), v_part);
END;
$$;

-- ----------------------------------------------------------------------------
-- 4. HARDENED detach_and_drop_dataset_partitions
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.detach_and_drop_dataset_partitions(p_dataset_id bigint)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_part text;
    v_t_part text;
    is_attached boolean;
BEGIN
    IF p_dataset_id IS NULL OR p_dataset_id <= 0 THEN
        RAISE EXCEPTION 'Invalid dataset_id: must be a positive integer';
    END IF;

    v_part := format('voter_d%s', p_dataset_id);
    v_t_part := format('voter_turnout_d%s', p_dataset_id);

    -- 1. Check if voter_turnout partition exists and is attached to public.voter_turnout
    SELECT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_inherits i
        JOIN pg_catalog.pg_class c ON i.inhrelid = c.oid
        JOIN pg_catalog.pg_class p ON i.inhparent = p.oid
        WHERE c.relname = v_t_part
          AND c.relnamespace = 'public'::regnamespace
          AND p.relname = 'voter_turnout'
          AND p.relnamespace = 'public'::regnamespace
    ) INTO is_attached;

    IF is_attached THEN
        EXECUTE format('ALTER TABLE public.voter_turnout DETACH PARTITION public.%I;', v_t_part);
    END IF;

    -- Drop partition table if exists in public schema
    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_class c
        WHERE c.relname = v_t_part AND c.relnamespace = 'public'::regnamespace
    ) THEN
        EXECUTE format('DROP TABLE public.%I CASCADE;', v_t_part);
    END IF;

    -- 2. Check if voter partition exists and is attached to public.voter
    SELECT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_inherits i
        JOIN pg_catalog.pg_class c ON i.inhrelid = c.oid
        JOIN pg_catalog.pg_class p ON i.inhparent = p.oid
        WHERE c.relname = v_part
          AND c.relnamespace = 'public'::regnamespace
          AND p.relname = 'voter'
          AND p.relnamespace = 'public'::regnamespace
    ) INTO is_attached;

    IF is_attached THEN
        EXECUTE format('ALTER TABLE public.voter DETACH PARTITION public.%I;', v_part);
    END IF;

    -- Drop partition table if exists in public schema
    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_class c
        WHERE c.relname = v_part AND c.relnamespace = 'public'::regnamespace
    ) THEN
        EXECUTE format('DROP TABLE public.%I CASCADE;', v_part);
    END IF;
END;
$$;

-- Overload for integer argument
CREATE OR REPLACE FUNCTION public.detach_and_drop_dataset_partitions(p_dataset_id integer)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM public.detach_and_drop_dataset_partitions(p_dataset_id::bigint);
END;
$$;

-- ----------------------------------------------------------------------------
-- 5. EXPLICIT GRANTS STRICTLY TO elecsarthi_app
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'elecsarthi_app') THEN
        GRANT EXECUTE ON FUNCTION public.create_dataset_partitions(bigint) TO elecsarthi_app;
        GRANT EXECUTE ON FUNCTION public.create_dataset_indexes(bigint) TO elecsarthi_app;
        GRANT EXECUTE ON FUNCTION public.detach_and_drop_dataset_partitions(bigint) TO elecsarthi_app;
        GRANT EXECUTE ON FUNCTION public.detach_and_drop_dataset_partitions(integer) TO elecsarthi_app;
    END IF;
END $$;

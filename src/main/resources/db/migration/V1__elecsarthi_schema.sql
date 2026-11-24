-- ============================================================================
-- V1__elecsarthi_schema.sql
-- ElecSarthi Initial Schema Baseline (18 Tables)
-- Tested on PostgreSQL 16+ / 18
-- ============================================================================

-- Required Extensions
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Global voter ID sequence
CREATE SEQUENCE IF NOT EXISTS voter_id_seq START WITH 1 INCREMENT BY 1;

-- Whitelist function for 56 voter columns
CREATE OR REPLACE FUNCTION is_voter_column(col text) RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
SELECT col IN (
    'state', 'district', 'pc_no', 'pc_name_en', 'pc_name_mt',
    'ac_no', 'ac_name_en', 'ac_name_mt', 'part_no', 'booth_no',
    'booth_name_en', 'booth_name_mt', 'booth_address_en', 'booth_address_mt',
    'serial_no', 'voter_id', 'v_fname_en', 'v_lname_en', 'v_fname_mt', 'v_lname_mt',
    'vr_fname_en', 'vr_lname_en', 'vr_fname_mt', 'vr_lname_mt',
    'house_no', 'house_location_en', 'house_location_mt',
    'section_no', 'section_name_en', 'section_name_mt',
    'age', 'sex', 'rural_urban', 'tehsil', 'block', 'pincode',
    'municipal_name_nagar_nigam_en', 'municipal_name_nagar_nigam_mt', 'nagar_nigam_ward_no',
    'municipal_name_nagar_palika_en', 'municipal_name_nagar_palika_mt', 'nagar_palika_ward_no',
    'municipal_name_nagar_parishad_en', 'municipal_name_nagar_parishad_mt', 'nagar_parishad_ward_no',
    'zilla_parishad_name_en', 'zilla_parishad_name_mt', 'zilla_parishad_name_ward_no',
    'panchayat_samiti_name_en', 'panchayat_samiti_name_mt', 'panchayat_samiti_name_ward_no',
    'panchayat_name_en', 'panchayat_name_mt', 'panchayat_no',
    'mobile_no', 'voter_image'
);
$$;

-- ----------------------------------------------------------------------------
-- 1. CUSTOMER_ACCOUNT
-- ----------------------------------------------------------------------------
CREATE TABLE customer_account (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by_user_id bigint NOT NULL,
    owning_agent_user_id bigint,
    customer_name text NOT NULL,
    party_or_org_name text,
    contact_phone text NOT NULL,
    contact_email citext,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    activation_status text NOT NULL DEFAULT 'PENDING' CHECK (activation_status IN ('PENDING', 'ACTIVATED')),
    activation_code_hash char(64),
    activation_code_key_version smallint,
    activation_code_issued_at timestamptz,
    activation_code_expires_at timestamptz,
    activation_code_issued_by_user_id bigint,
    activation_attempt_count smallint NOT NULL DEFAULT 0 CHECK (activation_attempt_count >= 0 AND activation_attempt_count <= 5),
    activated_at timestamptz,
    suspended_at timestamptz,
    suspension_reason text,
    suspended_by_user_id bigint,
    mobile_access_policy_version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_customer_phone_e164 CHECK (contact_phone ~ '^\+[1-9]\d{6,14}$'),
    CONSTRAINT ux_customer_phone UNIQUE (contact_phone),
    CONSTRAINT ck_customer_activation_hash CHECK (activation_status <> 'ACTIVATED' OR activation_code_hash IS NULL)
);

-- ----------------------------------------------------------------------------
-- 2. APP_USER
-- ----------------------------------------------------------------------------
CREATE TABLE app_user (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_account_id bigint REFERENCES customer_account(id),
    created_by_user_id bigint REFERENCES app_user(id),
    username citext NOT NULL UNIQUE,
    full_name text NOT NULL,
    password_hash text,
    must_change_password boolean NOT NULL DEFAULT false,
    role text NOT NULL CHECK (role IN ('OWNER', 'SUBOWNER', 'AGENT', 'CUSTOMER', 'CUSTOMER_ADMIN', 'SUBUSER')),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_tenant_by_role CHECK (
        (role IN ('CUSTOMER', 'CUSTOMER_ADMIN', 'SUBUSER') AND customer_account_id IS NOT NULL) OR
        (role IN ('OWNER', 'SUBOWNER', 'AGENT') AND customer_account_id IS NULL)
    ),
    CONSTRAINT ck_user_password_required CHECK (status <> 'ACTIVE' OR password_hash IS NOT NULL),
    CONSTRAINT ux_app_user_id_role UNIQUE (id, role),
    CONSTRAINT ux_app_user_id_customer UNIQUE (id, customer_account_id),
    CONSTRAINT ux_app_user_id_customer_role UNIQUE (id, customer_account_id, role)
);

-- Exactly one primary login per Customer
CREATE UNIQUE INDEX ux_one_customer_admin ON app_user (customer_account_id)
WHERE role IN ('CUSTOMER', 'CUSTOMER_ADMIN');

-- FK from customer_account to app_user
ALTER TABLE customer_account
    ADD CONSTRAINT fk_customer_created_by FOREIGN KEY (created_by_user_id) REFERENCES app_user(id);
ALTER TABLE customer_account
    ADD CONSTRAINT fk_customer_act_issued_by FOREIGN KEY (activation_code_issued_by_user_id) REFERENCES app_user(id);
ALTER TABLE customer_account
    ADD CONSTRAINT fk_customer_suspended_by FOREIGN KEY (suspended_by_user_id) REFERENCES app_user(id);

-- Owning agent must have role AGENT
CREATE OR REPLACE FUNCTION check_owning_agent_role() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.owning_agent_user_id IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = NEW.owning_agent_user_id AND role = 'AGENT') THEN
            RAISE EXCEPTION 'owning agent must have role AGENT';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_check_owning_agent_role
BEFORE INSERT OR UPDATE ON customer_account
FOR EACH ROW EXECUTE FUNCTION check_owning_agent_role();

-- ----------------------------------------------------------------------------
-- 3. USER_DEVICE
-- ----------------------------------------------------------------------------
CREATE TABLE user_device (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_account_id bigint REFERENCES customer_account(id),
    user_id bigint NOT NULL,
    user_role text NOT NULL CHECK (user_role IN ('AGENT', 'CUSTOMER', 'CUSTOMER_ADMIN', 'SUBUSER')),
    installation_id text NOT NULL,
    public_key text,
    key_algorithm text DEFAULT 'ECDSA_P256',
    platform text NOT NULL CHECK (platform IN ('ANDROID', 'IOS')),
    device_name text,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED', 'REPLACED')),
    challenge_hash char(64),
    challenge_purpose text,
    challenge_expires_at timestamptz,
    last_request_counter bigint NOT NULL DEFAULT 0,
    replaced_by_device_id uuid REFERENCES user_device(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    activated_at timestamptz,
    last_used_at timestamptz,
    revoked_at timestamptz,
    revoked_by_user_id bigint REFERENCES app_user(id),
    CONSTRAINT fk_device_user_role FOREIGN KEY (user_id, user_role) REFERENCES app_user(id, role),
    CONSTRAINT fk_device_user_customer FOREIGN KEY (user_id, customer_account_id) REFERENCES app_user(id, customer_account_id),
    CONSTRAINT ck_device_tenant CHECK (
        (user_role = 'AGENT' AND customer_account_id IS NULL) OR
        (user_role IN ('CUSTOMER', 'CUSTOMER_ADMIN', 'SUBUSER') AND customer_account_id IS NOT NULL)
    ),
    CONSTRAINT ux_user_device_id_user UNIQUE (id, user_id)
);

CREATE UNIQUE INDEX ux_device_active_per_user ON user_device (user_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX ux_device_pending_per_user ON user_device (user_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX ux_device_installation_customer ON user_device (customer_account_id, installation_id) WHERE customer_account_id IS NOT NULL;
CREATE UNIQUE INDEX ux_device_installation_agent ON user_device (installation_id) WHERE customer_account_id IS NULL;

-- ----------------------------------------------------------------------------
-- 4. USER_SESSION
-- ----------------------------------------------------------------------------
CREATE TABLE user_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id bigint NOT NULL,
    user_role text NOT NULL,
    device_id uuid,
    client_type text NOT NULL CHECK (client_type IN ('WEB', 'MOBILE')),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'TERMINATED', 'EXPIRED')),
    issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_seen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    idle_expires_at timestamptz,
    absolute_expires_at timestamptz NOT NULL,
    refresh_token_hash char(64),
    refresh_token_generation int NOT NULL DEFAULT 1,
    refresh_token_expires_at timestamptz,
    rotated_at timestamptz,
    terminated_at timestamptz,
    termination_reason text,
    terminated_by_user_id bigint REFERENCES app_user(id),
    ip_address inet,
    user_agent text,
    CONSTRAINT fk_session_user_role FOREIGN KEY (user_id, user_role) REFERENCES app_user(id, role),
    CONSTRAINT fk_session_device FOREIGN KEY (device_id, user_id) REFERENCES user_device(id, user_id),
    CONSTRAINT ck_session_client_by_role CHECK (
        (client_type = 'WEB' AND user_role IN ('OWNER', 'SUBOWNER') AND device_id IS NULL AND refresh_token_hash IS NULL) OR
        (client_type = 'MOBILE' AND user_role IN ('AGENT', 'CUSTOMER', 'CUSTOMER_ADMIN', 'SUBUSER') AND device_id IS NOT NULL)
    ),
    CONSTRAINT ck_session_terminated CHECK (
        status <> 'TERMINATED' OR (terminated_at IS NOT NULL AND termination_reason IS NOT NULL)
    ),
    CONSTRAINT ux_session_refresh_hash UNIQUE (refresh_token_hash)
);

-- ----------------------------------------------------------------------------
-- 5. AUDIT_LOG
-- ----------------------------------------------------------------------------
CREATE TABLE audit_log (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id bigint REFERENCES app_user(id),
    customer_account_id bigint REFERENCES customer_account(id),
    dataset_id bigint,
    device_id uuid,
    action_type text NOT NULL,
    outcome text NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED')),
    target_entity text,
    target_id text,
    details jsonb,
    ip_address inet,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE OR REPLACE FUNCTION audit_log_prevent_modification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$;

CREATE TRIGGER trg_audit_log_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_log
FOR EACH STATEMENT EXECUTE FUNCTION audit_log_prevent_modification();

-- ----------------------------------------------------------------------------
-- 6. ELECTION_TYPE
-- ----------------------------------------------------------------------------
CREATE TABLE election_type (
    id smallint PRIMARY KEY,
    code text NOT NULL UNIQUE CHECK (code IN (
        'LOK_SABHA', 'VIDHAN_SABHA', 'NAGAR_NIGAM', 'NAGAR_PALIKA',
        'NAGAR_PARISHAD', 'ZILLA_PANCHAYAT', 'PANCHAYAT_SAMITI', 'PANCHAYAT'
    )),
    display_name text NOT NULL,
    booth_column text NOT NULL,
    guard_column text,
    is_active boolean NOT NULL DEFAULT true
);

INSERT INTO election_type (id, code, display_name, booth_column, guard_column) VALUES
 (1, 'LOK_SABHA', 'Lok Sabha', 'part_no', NULL),
 (2, 'VIDHAN_SABHA', 'Vidhan Sabha', 'part_no', NULL),
 (3, 'NAGAR_NIGAM', 'Nagar Nigam', 'booth_no', NULL),
 (4, 'NAGAR_PALIKA', 'Nagar Palika', 'booth_no', 'municipal_name_nagar_palika_en'),
 (5, 'NAGAR_PARISHAD', 'Nagar Parishad', 'booth_no', 'municipal_name_nagar_parishad_en'),
 (6, 'ZILLA_PANCHAYAT', 'Zilla Panchayat', 'booth_no', NULL),
 (7, 'PANCHAYAT_SAMITI', 'Panchayat Samiti', 'booth_no', 'panchayat_samiti_name_en'),
 (8, 'PANCHAYAT', 'Panchayat', 'booth_no', 'panchayat_samiti_name_en');

-- ----------------------------------------------------------------------------
-- 7. ELECTION_TYPE_FIELD
-- ----------------------------------------------------------------------------
CREATE TABLE election_type_field (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    election_type_id smallint NOT NULL REFERENCES election_type(id),
    source_column text NOT NULL,
    value_type text NOT NULL CHECK (value_type IN ('TEXT', 'INT')),
    label_en text NOT NULL,
    label_mt text,
    usage text NOT NULL CHECK (usage IN ('FILTER', 'DISPLAY')),
    filter_order smallint,
    display_order smallint NOT NULL,
    on_slip boolean NOT NULL DEFAULT false,
    CONSTRAINT ck_field_source_column CHECK (is_voter_column(source_column)),
    CONSTRAINT ux_election_type_source_col UNIQUE (election_type_id, source_column)
);

-- ----------------------------------------------------------------------------
-- 8. DATASET
-- ----------------------------------------------------------------------------
CREATE TABLE dataset (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name text NOT NULL,
    original_file_name text NOT NULL,
    file_sha256 char(64) NOT NULL,
    storage_tier text NOT NULL CHECK (storage_tier IN ('MASTER', 'LONG_TERM')),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IMPORTED', 'FAILED', 'SUPERSEDED', 'DELETING')),
    states text[],
    supersedes_dataset_id bigint REFERENCES dataset(id) ON DELETE SET NULL,
    superseded_at timestamptz,
    delete_after timestamptz NOT NULL,
    total_rows int DEFAULT 0,
    imported_rows int DEFAULT 0,
    failed_rows int DEFAULT 0,
    imported_by_user_id bigint NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    CONSTRAINT ux_dataset_id_tier UNIQUE (id, storage_tier)
);

CREATE UNIQUE INDEX ux_dataset_file ON dataset (file_sha256) WHERE status <> 'FAILED';

-- ----------------------------------------------------------------------------
-- 9. IMPORT_ISSUE
-- ----------------------------------------------------------------------------
CREATE TABLE import_issue (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dataset_id bigint NOT NULL REFERENCES dataset(id),
    sheet text,
    row_no int,
    column_name text,
    issue_code text NOT NULL,
    issue_detail text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- ----------------------------------------------------------------------------
-- 10. VOTER (Partitioned by dataset_id)
-- ----------------------------------------------------------------------------
CREATE TABLE voter (
    dataset_id bigint NOT NULL,
    id bigint NOT NULL DEFAULT nextval('voter_id_seq'),
    source_record_key_hash char(64) NOT NULL,
    state text,
    district text,
    pc_no int,
    pc_name_en text,
    pc_name_mt text,
    ac_no int,
    ac_name_en text,
    ac_name_mt text,
    part_no int,
    booth_no int,
    booth_name_en text,
    booth_name_mt text,
    booth_address_en text,
    booth_address_mt text,
    serial_no int,
    voter_id text,
    v_fname_en text,
    v_lname_en text,
    v_fname_mt text,
    v_lname_mt text,
    vr_fname_en text,
    vr_lname_en text,
    vr_fname_mt text,
    vr_lname_mt text,
    house_no text,
    house_location_en text,
    house_location_mt text,
    section_no int,
    section_name_en text,
    section_name_mt text,
    age int,
    sex text,
    rural_urban text,
    tehsil text,
    block text,
    pincode text,
    municipal_name_nagar_nigam_en text,
    municipal_name_nagar_nigam_mt text,
    nagar_nigam_ward_no int,
    municipal_name_nagar_palika_en text,
    municipal_name_nagar_palika_mt text,
    nagar_palika_ward_no int,
    municipal_name_nagar_parishad_en text,
    municipal_name_nagar_parishad_mt text,
    nagar_parishad_ward_no int,
    zilla_parishad_name_en text,
    zilla_parishad_name_mt text,
    zilla_parishad_name_ward_no int,
    panchayat_samiti_name_en text,
    panchayat_samiti_name_mt text,
    panchayat_samiti_name_ward_no int,
    panchayat_name_en text,
    panchayat_name_mt text,
    panchayat_no int,
    mobile_no_enc bytea,
    mobile_key_version smallint,
    voter_image_ref text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (dataset_id, id),
    CONSTRAINT ux_voter_dataset_source_key UNIQUE (dataset_id, source_record_key_hash)
) PARTITION BY LIST (dataset_id);

-- ----------------------------------------------------------------------------
-- 11. VOTER_TURNOUT (Partitioned by dataset_id)
-- ----------------------------------------------------------------------------
CREATE TABLE voter_turnout (
    dataset_id bigint NOT NULL,
    customer_account_id bigint NOT NULL REFERENCES customer_account(id),
    voter_row_id bigint NOT NULL,
    marked_by_user_id bigint REFERENCES app_user(id),
    marked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    source text NOT NULL CHECK (source IN ('ONLINE', 'OFFLINE_LEASE', 'OFFLINE_SYNC')),
    PRIMARY KEY (dataset_id, customer_account_id, voter_row_id),
    CONSTRAINT fk_turnout_voter FOREIGN KEY (dataset_id, voter_row_id) REFERENCES voter(dataset_id, id)
) PARTITION BY LIST (dataset_id);

-- Functions to manage partitions & indexes
CREATE OR REPLACE FUNCTION create_dataset_partitions(p_dataset_id bigint) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('CREATE TABLE IF NOT EXISTS voter_d%s PARTITION OF voter FOR VALUES IN (%s);', p_dataset_id, p_dataset_id);
    EXECUTE format('CREATE TABLE IF NOT EXISTS voter_turnout_d%s PARTITION OF voter_turnout FOR VALUES IN (%s);', p_dataset_id, p_dataset_id);
END;
$$;

CREATE OR REPLACE FUNCTION create_dataset_indexes(p_dataset_id bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    tbl text := format('voter_d%s', p_dataset_id);
BEGIN
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_ls ON %I (state, pc_no, ac_no, part_no);', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_vs ON %I (state, ac_no, part_no);', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_nn ON %I (state, district, municipal_name_nagar_nigam_en, nagar_nigam_ward_no, booth_no);', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_np ON %I (state, district, nagar_palika_ward_no, booth_no);', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_npr ON %I (state, district, nagar_parishad_ward_no, booth_no);', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_zp ON %I (state, district, zilla_parishad_name_ward_no, booth_no);', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_ps ON %I (state, district, panchayat_samiti_name_ward_no, booth_no);', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_pan ON %I (state, district, panchayat_no, booth_no);', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_epic ON %I (voter_id);', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_roll ON %I (part_no, serial_no);', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_name_en_trgm ON %I USING gin ((coalesce(v_fname_en,'''') || '' '' || coalesce(v_lname_en,'''')) gin_trgm_ops);', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_name_mt_trgm ON %I USING gin ((coalesce(v_fname_mt,'''') || '' '' || coalesce(v_lname_mt,'''')) gin_trgm_ops);', tbl, tbl);
END;
$$;

-- ----------------------------------------------------------------------------
-- 12. AGENT_SALE
-- ----------------------------------------------------------------------------
CREATE TABLE agent_sale (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sold_by_user_id bigint NOT NULL,
    customer_account_id bigint NOT NULL REFERENCES customer_account(id),
    election_type_id smallint NOT NULL REFERENCES election_type(id),
    storage_tier text NOT NULL CHECK (storage_tier IN ('MASTER', 'LONG_TERM')),
    idempotency_key text NOT NULL,
    request_sha256 char(64) NOT NULL,
    sold_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    status text NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED', 'VOIDED')),
    voided_at timestamptz,
    void_reason text,
    voided_by_user_id bigint REFERENCES app_user(id),
    CONSTRAINT fk_sale_sold_by FOREIGN KEY (sold_by_user_id) REFERENCES app_user(id),
    CONSTRAINT ux_sale_agent_idempotency UNIQUE (sold_by_user_id, idempotency_key),
    CONSTRAINT ck_sale_voided CHECK (
        status <> 'VOIDED' OR (voided_at IS NOT NULL AND void_reason IS NOT NULL AND voided_by_user_id IS NOT NULL)
    )
);

CREATE OR REPLACE FUNCTION check_agent_sale_seller_role() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = NEW.sold_by_user_id AND role = 'AGENT') THEN
        RAISE EXCEPTION 'a Subowner recorded as the seller';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_check_agent_sale_seller_role
BEFORE INSERT OR UPDATE ON agent_sale
FOR EACH ROW EXECUTE FUNCTION check_agent_sale_seller_role();

-- ----------------------------------------------------------------------------
-- 13. AGENT_SALE_LINE (Immutable History)
-- ----------------------------------------------------------------------------
CREATE TABLE agent_sale_line (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sale_id bigint NOT NULL REFERENCES agent_sale(id),
    line_no smallint NOT NULL,
    dataset_id bigint NOT NULL,
    dataset_name text NOT NULL,
    storage_tier text NOT NULL CHECK (storage_tier IN ('MASTER', 'LONG_TERM')),
    criteria jsonb NOT NULL,
    criteria_hash char(64) NOT NULL,
    scope_label text NOT NULL,
    voter_count_snapshot int NOT NULL,
    duration_value int NOT NULL CHECK (duration_value >= 1),
    duration_unit text NOT NULL CHECK (duration_unit IN ('DAY', 'WEEK', 'MONTH')),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    CONSTRAINT ux_sale_line_no UNIQUE (sale_id, line_no),
    CONSTRAINT ux_sale_line_criteria UNIQUE (sale_id, criteria_hash),
    CONSTRAINT ck_sale_line_validity CHECK (valid_from < valid_until)
);

-- ----------------------------------------------------------------------------
-- 14. ENTITLEMENT
-- ----------------------------------------------------------------------------
CREATE TABLE entitlement (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_account_id bigint NOT NULL REFERENCES customer_account(id),
    election_type_id smallint NOT NULL REFERENCES election_type(id),
    dataset_id bigint NOT NULL,
    storage_tier text NOT NULL CHECK (storage_tier IN ('MASTER', 'LONG_TERM')),
    criteria jsonb NOT NULL,
    criteria_hash char(64) NOT NULL,
    scope_label text NOT NULL,
    source text NOT NULL CHECK (source IN ('SALE', 'DIRECT')),
    sale_line_id bigint UNIQUE,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    mobile_access boolean NOT NULL DEFAULT false,
    mobile_access_changed_at timestamptz,
    mobile_access_changed_by bigint REFERENCES app_user(id),
    created_by_user_id bigint NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_at timestamptz,
    revoke_reason text,
    revoked_by_user_id bigint REFERENCES app_user(id),
    CONSTRAINT fk_ent_dataset_tier FOREIGN KEY (dataset_id, storage_tier) REFERENCES dataset(id, storage_tier),
    CONSTRAINT ux_entitlement_id_customer UNIQUE (id, customer_account_id),
    CONSTRAINT ck_ent_source CHECK (
        (source = 'SALE' AND sale_line_id IS NOT NULL) OR
        (source = 'DIRECT' AND sale_line_id IS NULL)
    ),
    CONSTRAINT ck_ent_revoked CHECK (
        status <> 'REVOKED' OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL AND revoked_by_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_ent_mobile_access CHECK (
        mobile_access = false OR (mobile_access_changed_at IS NOT NULL AND mobile_access_changed_by IS NOT NULL)
    ),
    CONSTRAINT ex_ent_no_overlap EXCLUDE USING gist (
        customer_account_id WITH =,
        election_type_id WITH =,
        storage_tier WITH =,
        criteria_hash WITH =,
        tstzrange(valid_from, valid_until, '[)') WITH &&
    ) WHERE (status = 'ACTIVE')
);

-- ----------------------------------------------------------------------------
-- 15. BOOTH_ASSIGNMENT
-- ----------------------------------------------------------------------------
CREATE TABLE booth_assignment (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_account_id bigint NOT NULL,
    subuser_id bigint NOT NULL,
    entitlement_id bigint NOT NULL,
    booth_value int NOT NULL,
    slot smallint NOT NULL CHECK (slot IN (1, 2)),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    assigned_by_user_id bigint NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_at timestamptz,
    revoke_reason text,
    revoked_by_user_id bigint REFERENCES app_user(id),
    CONSTRAINT fk_ba_customer FOREIGN KEY (customer_account_id) REFERENCES customer_account(id),
    CONSTRAINT fk_ba_subuser FOREIGN KEY (subuser_id, customer_account_id) REFERENCES app_user(id, customer_account_id),
    CONSTRAINT fk_ba_entitlement FOREIGN KEY (entitlement_id, customer_account_id) REFERENCES entitlement(id, customer_account_id),
    CONSTRAINT ck_ba_validity CHECK (valid_from < valid_until),
    CONSTRAINT ck_ba_seven_days CHECK (valid_until <= valid_from + interval '7 days'),
    CONSTRAINT ux_ba_id_subuser UNIQUE (id, subuser_id),
    CONSTRAINT ex_ba_slot EXCLUDE USING gist (
        subuser_id WITH =,
        slot WITH =,
        tstzrange(valid_from, valid_until, '[)') WITH &&
    ) WHERE (status = 'ACTIVE'),
    CONSTRAINT ex_ba_same_booth EXCLUDE USING gist (
        subuser_id WITH =,
        entitlement_id WITH =,
        booth_value WITH =,
        tstzrange(valid_from, valid_until, '[)') WITH &&
    ) WHERE (status = 'ACTIVE')
);

CREATE OR REPLACE FUNCTION check_subuser_assignment_role() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = NEW.subuser_id AND role = 'SUBUSER') THEN
        RAISE EXCEPTION 'assigned user must have role SUBUSER';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_check_subuser_assignment_role
BEFORE INSERT OR UPDATE ON booth_assignment
FOR EACH ROW EXECUTE FUNCTION check_subuser_assignment_role();

-- ----------------------------------------------------------------------------
-- 16. OFFLINE_LEASE
-- ----------------------------------------------------------------------------
CREATE TABLE offline_lease (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booth_assignment_id bigint NOT NULL,
    subuser_id bigint NOT NULL,
    device_id uuid NOT NULL,
    lease_seq bigint NOT NULL,
    payload_sha256 char(64) NOT NULL,
    signing_key_version smallint NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revoked_by_user_id bigint REFERENCES app_user(id),
    CONSTRAINT fk_lease_assignment FOREIGN KEY (booth_assignment_id, subuser_id) REFERENCES booth_assignment(id, subuser_id),
    CONSTRAINT fk_lease_device FOREIGN KEY (device_id, subuser_id) REFERENCES user_device(id, user_id),
    CONSTRAINT ux_lease_device_seq UNIQUE (device_id, lease_seq),
    CONSTRAINT ck_lease_expiry CHECK (issued_at < expires_at AND expires_at <= issued_at + interval '7 days')
);

-- ----------------------------------------------------------------------------
-- 17. SYNC_SESSION
-- ----------------------------------------------------------------------------
CREATE TABLE sync_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_account_id bigint NOT NULL,
    user_id bigint NOT NULL,
    entitlement_id bigint NOT NULL,
    device_id uuid NOT NULL,
    protocol_version smallint NOT NULL DEFAULT 1,
    manifest_sha256 char(64) NOT NULL,
    scope_snapshot jsonb NOT NULL,
    policy_version int NOT NULL,
    includes_mobile boolean NOT NULL DEFAULT false,
    total_records int NOT NULL,
    total_chunks int NOT NULL,
    chunk_size int NOT NULL,
    chunks_done int NOT NULL DEFAULT 0,
    signing_key_version smallint NOT NULL,
    offline_valid_until timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    cancel_reason text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT fk_sync_user FOREIGN KEY (user_id, customer_account_id) REFERENCES app_user(id, customer_account_id),
    CONSTRAINT fk_sync_entitlement FOREIGN KEY (entitlement_id, customer_account_id) REFERENCES entitlement(id, customer_account_id),
    CONSTRAINT fk_sync_device FOREIGN KEY (device_id, user_id) REFERENCES user_device(id, user_id)
);

CREATE OR REPLACE FUNCTION check_sync_user_role() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = NEW.user_id AND role IN ('CUSTOMER', 'CUSTOMER_ADMIN')) THEN
        RAISE EXCEPTION 'bulk sync allowed only for Customer primary login';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_check_sync_user_role
BEFORE INSERT OR UPDATE ON sync_session
FOR EACH ROW EXECUTE FUNCTION check_sync_user_role();

-- ----------------------------------------------------------------------------
-- 18. DEVICE_NONCE
-- ----------------------------------------------------------------------------
CREATE TABLE device_nonce (
    device_id uuid NOT NULL REFERENCES user_device(id),
    nonce text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (device_id, nonce)
);

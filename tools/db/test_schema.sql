-- Business-rule tests for the ElecSarthi schema. Run by tools/db/run_tests.sh on a fresh DB after all migrations.
-- Every line prints PASS or raises. Expected rejections must be rejected; valid actions must succeed.
\set ON_ERROR_STOP 1
SET client_min_messages = notice;

CREATE FUNCTION pg_temp.reject(q text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE q;
    EXCEPTION WHEN others THEN
        RAISE NOTICE 'PASS  rejected  %  [%]', label, SQLSTATE;
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  accepted but should be rejected: %', label;
END $$;
CREATE FUNCTION pg_temp.accept(q text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE q;
    RAISE NOTICE 'PASS  accepted  %', label;
END $$;

-- explicit ids are used for rows the tests refer to; defaults start at 1000 so they never collide
SELECT setval(pg_get_serial_sequence(t, 'id'), 1000) FROM unnest(ARRAY['agent_sale','agent_sale_line','entitlement','booth_assignment']) t;

-- ---------- field configuration (loaded by V3 from the sheet) ----------
DO $$ BEGIN
  IF (SELECT count(*) FROM election_type_field) <> 271 THEN RAISE EXCEPTION 'FAIL seed rows %', (SELECT count(*) FROM election_type_field); END IF;
  IF (SELECT count(*) FROM election_type_field WHERE usage = 'FILTER') <> 24 THEN RAISE EXCEPTION 'FAIL filter rows'; END IF;
  RAISE NOTICE 'PASS  checked   V3 seeded 271 field rows, 24 of them FILTER (skin cells)';
END $$;

-- ---------- people ----------
INSERT INTO app_user (username, full_name, password_hash, role) VALUES
 ('owner','Owner','x','OWNER'), ('sub1','Subowner','x','SUBOWNER'),
 ('agentA','Agent A','x','AGENT'), ('agentB','Agent B','x','AGENT');
INSERT INTO customer_account (created_by_user_id, owning_agent_user_id, customer_name, contact_phone)
 VALUES (3, 3, 'Customer One', '+919800000001'), (4, 4, 'Customer Two', '+919800000002');
INSERT INTO app_user (customer_account_id, created_by_user_id, username, full_name, role, status) VALUES
 (1, 3, 'ca1', 'C1 admin', 'CUSTOMER', 'PENDING'),
 (2, 4, 'ca2', 'C2 admin', 'CUSTOMER', 'PENDING');
INSERT INTO app_user (customer_account_id, created_by_user_id, username, full_name, password_hash, role) VALUES
 (1, 5, 'su11', 'Sub 1 of C1', 'x', 'SUBUSER'), (2, 6, 'su21', 'Sub 1 of C2', 'x', 'SUBUSER');
-- ids: 1 owner, 2 subowner, 3 agentA, 4 agentB, 5 ca1, 6 ca2, 7 su11 (C1), 8 su21 (C2)

SELECT pg_temp.reject($$UPDATE customer_account SET owning_agent_user_id = 2 WHERE id = 1$$,
  'owning agent must have role AGENT (Subowner given)');
SELECT pg_temp.reject($$INSERT INTO app_user (customer_account_id, username, full_name, password_hash, role)
  VALUES (1,'ca1b','second admin','x','CUSTOMER')$$, 'second primary login for one Customer');
SELECT pg_temp.reject($$INSERT INTO app_user (customer_account_id, username, full_name, password_hash, role)
  VALUES (1,'legacy_ca','legacy admin','x','CUSTOMER_ADMIN')$$, 'reject non-canonical CUSTOMER_ADMIN role in app_user');
SELECT pg_temp.reject($$INSERT INTO app_user (username, full_name, password_hash, role)
  VALUES ('lonely','x','x','SUBUSER')$$, 'Subuser without a Customer');
SELECT pg_temp.reject($$INSERT INTO app_user (customer_account_id, username, full_name, password_hash, role)
  VALUES (1,'ag3','x','x','AGENT')$$, 'platform role attached to a Customer');
SELECT pg_temp.reject($$INSERT INTO app_user (username, full_name, role, status)
  VALUES ('nopass','x','AGENT','ACTIVE')$$, 'active user without password');
SELECT pg_temp.reject($$INSERT INTO customer_account (created_by_user_id, customer_name, contact_phone)
  VALUES (1,'dup','+919800000001')$$, 'duplicate Customer contact phone');
SELECT pg_temp.reject($$INSERT INTO customer_account (created_by_user_id, customer_name, contact_phone)
  VALUES (1,'bad','98000 00001')$$, 'contact phone not in E.164');
SELECT pg_temp.reject($$INSERT INTO election_type_field (election_type_id, source_column, value_type, label_en, usage, display_order)
  VALUES (1,'password_hash','TEXT','x','DISPLAY',99)$$, 'field outside the voter-column whitelist');

-- ---------- datasets and voters ----------
INSERT INTO dataset (name, original_file_name, file_sha256, storage_tier, status, delete_after, imported_by_user_id, states)
 VALUES ('HR master', 'hr.xlsx', repeat('a',64), 'MASTER', 'IMPORTED', now() + interval '60 days', 1, '{HARYANA}'),
        ('HR AC10 long', 'hr10.xlsx', repeat('b',64), 'LONG_TERM', 'IMPORTED', now() + interval '400 days', 1, '{HARYANA}');
SELECT create_dataset_partitions(1); SELECT create_dataset_partitions(2);
INSERT INTO voter (dataset_id, source_record_key_hash, state, ac_no, part_no, serial_no, voter_id, v_fname_en, house_no)
SELECT 1, md5(g::text) || md5(g::text), 'HARYANA', 10, 100 + (g % 3), g, 'EPIC' || g, 'Name' || g, (g % 50)::text
FROM generate_series(1, 300) g;
INSERT INTO voter (dataset_id, source_record_key_hash, state, ac_no, part_no, serial_no, voter_id)
SELECT 2, md5(g::text) || md5(g::text), 'HARYANA', 10, 100 + (g % 3), g, 'EPIC' || g FROM generate_series(1, 300) g;
SELECT create_dataset_indexes(1);
SELECT pg_temp.accept($$SELECT 1$$, 'partitions + 12 indexes created for dataset 1');

SELECT pg_temp.reject($$INSERT INTO dataset (name, original_file_name, file_sha256, storage_tier, delete_after, imported_by_user_id)
  VALUES ('again','hr.xlsx', repeat('a',64), 'MASTER', now(), 1)$$, 're-import of an already imported file');
UPDATE dataset SET status = 'FAILED' WHERE id = 2;
SELECT pg_temp.accept($$INSERT INTO dataset (name, original_file_name, file_sha256, storage_tier, delete_after, imported_by_user_id)
  VALUES ('retry','hr10.xlsx', repeat('b',64), 'LONG_TERM', now() + interval '30 days', 1)$$, 'retry of a FAILED file');
DELETE FROM dataset WHERE name = 'retry';  UPDATE dataset SET status = 'IMPORTED' WHERE id = 2;
SELECT pg_temp.reject($$INSERT INTO voter (dataset_id, source_record_key_hash, state) VALUES (1, md5('1')||md5('1'), 'HARYANA')$$,
  'same source record twice in one dataset');
SELECT pg_temp.accept($$SELECT count(*) FROM (SELECT 1 FROM voter_d2 n JOIN voter_d1 o USING (source_record_key_hash) LIMIT 1) x$$,
  'overlap probe between datasets runs on the indexed key');

-- ---------- sales and entitlements ----------
SELECT pg_temp.reject($$INSERT INTO agent_sale (sold_by_user_id, customer_account_id, election_type_id, storage_tier, idempotency_key, request_sha256)
  VALUES (2, 1, 2, 'MASTER', 'k0', repeat('0',64))$$, 'a Subowner recorded as the seller');
INSERT INTO agent_sale (id, sold_by_user_id, customer_account_id, election_type_id, storage_tier, idempotency_key, request_sha256)
 OVERRIDING SYSTEM VALUE VALUES (1, 3, 1, 2, 'MASTER', 'k1', repeat('1',64));
SELECT pg_temp.reject($$INSERT INTO agent_sale (sold_by_user_id, customer_account_id, election_type_id, storage_tier, idempotency_key, request_sha256)
  VALUES (3, 1, 2, 'MASTER', 'k1', repeat('2',64))$$, 'same idempotency key reused by the same Agent');
INSERT INTO agent_sale_line (id, sale_id, line_no, dataset_id, dataset_name, storage_tier, criteria, criteria_hash, scope_label,
  voter_count_snapshot, duration_value, duration_unit, valid_from, valid_until)
 OVERRIDING SYSTEM VALUE VALUES (1, 1, 1, 1, 'HR master', 'MASTER', '{"ac_no":10,"state":"HARYANA"}', repeat('c',64), 'Vidhan Sabha · Haryana · AC 10',
  300, 10, 'DAY', '2026-12-01', '2026-12-11');
SELECT pg_temp.reject($$INSERT INTO agent_sale_line (sale_id, line_no, dataset_id, dataset_name, storage_tier, criteria, criteria_hash, scope_label,
  voter_count_snapshot, duration_value, duration_unit, valid_from, valid_until)
  VALUES (1, 2, 1, 'x', 'MASTER', '{}', repeat('c',64), 'x', 1, 1, 'DAY', '2026-12-01', '2026-12-02')$$, 'same area twice in one sale');

INSERT INTO entitlement (id, customer_account_id, election_type_id, dataset_id, storage_tier, criteria, criteria_hash, scope_label,
  source, sale_line_id, valid_from, valid_until, created_by_user_id)
 OVERRIDING SYSTEM VALUE VALUES (1, 1, 2, 1, 'MASTER', '{"ac_no":10,"state":"HARYANA"}', repeat('c',64), 'VS · HR · AC 10', 'SALE', 1, '2026-12-01', '2026-12-11', 3);
SELECT pg_temp.reject($$INSERT INTO entitlement (customer_account_id, election_type_id, dataset_id, storage_tier, criteria, criteria_hash, scope_label,
  source, valid_from, valid_until, created_by_user_id)
  VALUES (1, 2, 1, 'LONG_TERM', '{}', repeat('d',64), 'x', 'DIRECT', '2026-12-01', '2026-12-05', 1)$$,
  'entitlement tier different from its dataset tier');
SELECT pg_temp.reject($$INSERT INTO entitlement (customer_account_id, election_type_id, dataset_id, storage_tier, criteria, criteria_hash, scope_label,
  source, valid_from, valid_until, created_by_user_id)
  VALUES (1, 2, 1, 'MASTER', '{}', repeat('c',64), 'x', 'DIRECT', '2026-12-05', '2026-12-20', 1)$$,
  'overlapping validity for the same Customer, type, tier and area');
SELECT pg_temp.accept($$INSERT INTO entitlement (id, customer_account_id, election_type_id, dataset_id, storage_tier, criteria, criteria_hash, scope_label,
  source, valid_from, valid_until, created_by_user_id)
  OVERRIDING SYSTEM VALUE VALUES (2, 1, 2, 1, 'MASTER', '{}', repeat('c',64), 'x', 'DIRECT', '2026-12-11', '2026-12-21', 1)$$,
  'back-to-back renewal starting exactly at the old end');
SELECT pg_temp.accept($$INSERT INTO entitlement (id, customer_account_id, election_type_id, dataset_id, storage_tier, criteria, criteria_hash, scope_label,
  source, valid_from, valid_until, created_by_user_id)
  OVERRIDING SYSTEM VALUE VALUES (3, 1, 2, 1, 'MASTER', '{}', repeat('c',64), 'x', 'DIRECT', '2027-01-10', '2027-01-20', 1)$$,
  're-buying the same area after expiry');
SELECT pg_temp.accept($$INSERT INTO entitlement (id, customer_account_id, election_type_id, dataset_id, storage_tier, criteria, criteria_hash, scope_label,
  source, valid_from, valid_until, created_by_user_id)
  OVERRIDING SYSTEM VALUE VALUES (4, 1, 2, 2, 'LONG_TERM', '{}', repeat('c',64), 'x', 'DIRECT', '2026-12-01', '2027-12-01', 1)$$,
  'same area in LONG_TERM while MASTER is active (different tier)');
SELECT pg_temp.accept($$INSERT INTO entitlement (id, customer_account_id, election_type_id, dataset_id, storage_tier, criteria, criteria_hash, scope_label,
  source, valid_from, valid_until, created_by_user_id)
  OVERRIDING SYSTEM VALUE VALUES (5, 2, 2, 1, 'MASTER', '{}', repeat('c',64), 'x', 'DIRECT', '2026-12-01', '2026-12-11', 1)$$,
  'same area sold to a different Customer');
SELECT pg_temp.reject($$INSERT INTO entitlement (customer_account_id, election_type_id, dataset_id, storage_tier, criteria, criteria_hash, scope_label,
  source, sale_line_id, valid_from, valid_until, created_by_user_id)
  VALUES (2, 1, 1, 'MASTER', '{}', repeat('e',64), 'x', 'SALE', 1, '2026-12-01', '2026-12-11', 3)$$,
  'one sale line creating two entitlements');
SELECT pg_temp.reject($$INSERT INTO entitlement (customer_account_id, election_type_id, dataset_id, storage_tier, criteria, criteria_hash, scope_label,
  source, valid_from, valid_until, created_by_user_id)
  VALUES (1, 1, 1, 'MASTER', '{}', repeat('f',64), 'x', 'SALE', '2026-12-01', '2026-12-11', 3)$$,
  'SALE entitlement without a sale line');
SELECT pg_temp.reject($$UPDATE entitlement SET status = 'REVOKED' WHERE id = 1$$, 'revocation without who and when');
SELECT pg_temp.accept($$UPDATE agent_sale SET status='VOIDED', voided_at=now(), voided_by_user_id=1, void_reason='typo' WHERE id = 1;
  UPDATE entitlement SET status='REVOKED', revoked_at=now(), revoked_by_user_id=1, revoke_reason='sale voided' WHERE id = 1$$,
  'Owner voids the sale and its entitlement is revoked');
SELECT pg_temp.accept($$INSERT INTO entitlement (id, customer_account_id, election_type_id, dataset_id, storage_tier, criteria, criteria_hash, scope_label,
  source, valid_from, valid_until, created_by_user_id)
  OVERRIDING SYSTEM VALUE VALUES (6, 1, 2, 1, 'MASTER', '{}', repeat('c',64), 'x', 'DIRECT', '2026-12-01', '2026-12-11', 1)$$,
  'a revoked entitlement no longer blocks the same window');
SELECT pg_temp.reject($$UPDATE entitlement SET mobile_access = true WHERE id = 4$$, 'mobile access switched on without who and when');
SELECT pg_temp.accept($$UPDATE entitlement SET mobile_access = true, mobile_access_changed_at = now(), mobile_access_changed_by = 1 WHERE id = 4$$,
  'Owner switches on mobile numbers for one entitlement');
-- entitlement ids: 1 revoked, 2 renewal C1, 3 rebuy C1, 4 LONG_TERM C1, 5 C2 MASTER, 6 C1 MASTER Dec 1-11

-- ---------- booth assignments ----------
SELECT pg_temp.accept($$INSERT INTO booth_assignment (id, customer_account_id, subuser_id, entitlement_id, booth_value, slot, valid_from, valid_until, assigned_by_user_id)
  OVERRIDING SYSTEM VALUE VALUES (1, 1, 7, 6, 101, 1, '2026-12-01', '2026-12-08', 5), (2, 1, 7, 6, 102, 2, '2026-12-01', '2026-12-08', 5)$$,
  'Subuser gets 2 booths (slots 1 and 2)');
SELECT pg_temp.reject($$INSERT INTO booth_assignment (customer_account_id, subuser_id, entitlement_id, booth_value, slot, valid_from, valid_until, assigned_by_user_id)
  VALUES (1, 7, 6, 100, 1, '2026-12-03', '2026-12-05', 5)$$, 'third booth while slots 1 and 2 are taken');
SELECT pg_temp.accept($$INSERT INTO booth_assignment (customer_account_id, subuser_id, entitlement_id, booth_value, slot, valid_from, valid_until, assigned_by_user_id)
  VALUES (1, 7, 6, 100, 1, '2026-12-08', '2026-12-11', 5)$$, 'slot 1 reused after the first assignment ended');
SELECT pg_temp.reject($$INSERT INTO booth_assignment (customer_account_id, subuser_id, entitlement_id, booth_value, slot, valid_from, valid_until, assigned_by_user_id)
  VALUES (1, 7, 2, 103, 1, '2026-12-11', '2026-12-19', 5)$$, 'assignment longer than 7 days');
SELECT pg_temp.reject($$INSERT INTO booth_assignment (customer_account_id, subuser_id, entitlement_id, booth_value, slot, valid_from, valid_until, assigned_by_user_id)
  VALUES (1, 8, 6, 101, 1, '2026-12-01', '2026-12-05', 5)$$, 'Customer 2''s Subuser on Customer 1''s entitlement');
SELECT pg_temp.reject($$INSERT INTO booth_assignment (customer_account_id, subuser_id, entitlement_id, booth_value, slot, valid_from, valid_until, assigned_by_user_id)
  VALUES (2, 8, 6, 101, 1, '2026-12-01', '2026-12-05', 6)$$, 'Customer 2 assigning onto Customer 1''s entitlement');
SELECT pg_temp.reject($$INSERT INTO booth_assignment (customer_account_id, subuser_id, entitlement_id, booth_value, slot, valid_from, valid_until, assigned_by_user_id)
  VALUES (1, 5, 6, 101, 1, '2026-12-20', '2026-12-21', 5)$$, 'Customer primary login used as a Subuser');
SELECT pg_temp.accept($$INSERT INTO booth_assignment (customer_account_id, subuser_id, entitlement_id, booth_value, slot, valid_from, valid_until, assigned_by_user_id)
  VALUES (2, 8, 5, 101, 1, '2026-12-01', '2026-12-08', 6)$$, 'another Customer''s Subuser on the same booth (no per-booth limit)');

-- ---------- devices, leases, sync ----------
INSERT INTO user_device (customer_account_id, user_id, user_role, installation_id, public_key, platform, status, activated_at)
 VALUES (1, 7, 'SUBUSER',        'inst-su11', 'pk', 'ANDROID', 'ACTIVE', now()),
        (1, 5, 'CUSTOMER',       'inst-ca1',  'pk', 'ANDROID', 'ACTIVE', now()),
        (2, 8, 'SUBUSER',        'inst-su21', 'pk', 'ANDROID', 'ACTIVE', now());
SELECT pg_temp.accept($$INSERT INTO user_device (customer_account_id, user_id, user_role, installation_id, public_key, platform, status, activated_at)
  VALUES (NULL, 3, 'AGENT', 'inst-agentA', 'pk', 'ANDROID', 'ACTIVE', now())$$, 'Agent registers his phone (Agents use the app)');
SELECT pg_temp.reject($$INSERT INTO user_device (customer_account_id, user_id, user_role, installation_id, platform)
  VALUES (NULL, 1, 'OWNER', 'inst-owner', 'ANDROID')$$, 'Owner registers a phone (Owner uses the web only)');
SELECT pg_temp.reject($$INSERT INTO user_device (customer_account_id, user_id, user_role, installation_id, platform)
  VALUES (1, 7, 'CUSTOMER', 'inst-fake', 'ANDROID')$$, 'Subuser''s device row claiming the CUSTOMER role');
SELECT pg_temp.reject($$INSERT INTO user_device (customer_account_id, user_id, user_role, installation_id, platform)
  VALUES (1, 5, 'CUSTOMER_ADMIN', 'inst-legacy-dev', 'ANDROID')$$, 'reject non-canonical CUSTOMER_ADMIN role in user_device');
SELECT pg_temp.reject($$INSERT INTO user_device (customer_account_id, user_id, user_role, installation_id, platform)
  VALUES (NULL, 7, 'SUBUSER', 'inst-noten', 'ANDROID')$$, 'Subuser device without its Customer');
SELECT pg_temp.reject($$INSERT INTO user_device (customer_account_id, user_id, user_role, installation_id, public_key, platform, status)
  VALUES (1, 7, 'SUBUSER', 'inst-su11-b', 'pk', 'ANDROID', 'ACTIVE')$$, 'second ACTIVE device for one user');
SELECT pg_temp.reject($$INSERT INTO user_device (customer_account_id, user_id, user_role, installation_id, public_key, platform, status)
  VALUES (1, 8, 'SUBUSER', 'inst-x', 'pk', 'ANDROID', 'PENDING')$$, 'device row linking a user to the wrong Customer');
SELECT pg_temp.accept($$UPDATE user_device SET status='REVOKED', revoked_at=now(), revoked_by_user_id=1 WHERE installation_id='inst-su21';
  INSERT INTO user_device (customer_account_id, user_id, user_role, installation_id, platform) VALUES (2, 8, 'SUBUSER', 'inst-su21-new', 'IOS')$$,
  'revoke a device and start registering a replacement (PENDING)');
SELECT pg_temp.reject($$INSERT INTO user_device (customer_account_id, user_id, user_role, installation_id, platform) VALUES (2, 8, 'SUBUSER', 'inst-su21', 'IOS')$$,
  'reuse of a revoked installation id');

SELECT pg_temp.accept($$INSERT INTO offline_lease (booth_assignment_id, subuser_id, device_id, lease_seq, payload_sha256, signing_key_version, expires_at)
  SELECT 1, 7, d.id, 1, repeat('9',64), 1, now() + interval '24 hours' FROM user_device d WHERE installation_id='inst-su11'$$,
  'lease for the Subuser''s own device');
SELECT pg_temp.reject($$INSERT INTO offline_lease (booth_assignment_id, subuser_id, device_id, lease_seq, payload_sha256, signing_key_version, expires_at)
  SELECT 1, 7, d.id, 2, repeat('9',64), 1, now() + interval '24 hours' FROM user_device d WHERE installation_id='inst-ca1'$$,
  'lease on a device that belongs to someone else');
SELECT pg_temp.reject($$INSERT INTO offline_lease (booth_assignment_id, subuser_id, device_id, lease_seq, payload_sha256, signing_key_version, expires_at)
  SELECT 1, 7, d.id, 1, repeat('9',64), 1, now() + interval '24 hours' FROM user_device d WHERE installation_id='inst-su11'$$,
  'lease sequence number reused on a device');

SELECT pg_temp.accept($$INSERT INTO sync_session (customer_account_id, user_id, entitlement_id, device_id, protocol_version, manifest_sha256,
  scope_snapshot, policy_version, total_records, total_chunks, chunk_size, signing_key_version, offline_valid_until, expires_at)
  SELECT 1, 5, 6, d.id, 1, repeat('8',64), '{}', 1, 300, 1, 1000, 1, now() + interval '24 hours', now() + interval '2 hours'
  FROM user_device d WHERE installation_id='inst-ca1'$$, 'bulk sync by the Customer primary user');
SELECT pg_temp.reject($$INSERT INTO sync_session (customer_account_id, user_id, entitlement_id, device_id, protocol_version, manifest_sha256,
  scope_snapshot, policy_version, total_records, total_chunks, chunk_size, signing_key_version, offline_valid_until, expires_at)
  SELECT 1, 7, 6, d.id, 1, repeat('8',64), '{}', 1, 300, 1, 1000, 1, now(), now() FROM user_device d WHERE installation_id='inst-su11'$$,
  'bulk sync by a Subuser');
SELECT pg_temp.reject($$INSERT INTO sync_session (customer_account_id, user_id, entitlement_id, device_id, protocol_version, manifest_sha256,
  scope_snapshot, policy_version, total_records, total_chunks, chunk_size, signing_key_version, offline_valid_until, expires_at)
  SELECT 1, 5, 5, d.id, 1, repeat('8',64), '{}', 1, 300, 1, 1000, 1, now(), now() FROM user_device d WHERE installation_id='inst-ca1'$$,
  'Customer 1 syncing Customer 2''s entitlement');

INSERT INTO device_nonce (device_id, nonce, expires_at)
 SELECT id, 'nonce-0123456789ab', now() + interval '10 minutes' FROM user_device WHERE installation_id='inst-ca1';
SELECT pg_temp.reject($$INSERT INTO device_nonce (device_id, nonce, expires_at)
  SELECT id, 'nonce-0123456789ab', now() FROM user_device WHERE installation_id='inst-ca1'$$, 'replayed nonce');

-- ---------- sessions (Owner/Subowner = web, Agent/Customer/Subuser = app) ----------
SELECT pg_temp.accept($$INSERT INTO user_session (user_id, user_role, client_type, absolute_expires_at)
  VALUES (1, 'OWNER', 'WEB', now() + interval '12 hours')$$, 'Owner logs in on the web');
SELECT pg_temp.accept($$INSERT INTO user_session (user_id, user_role, device_id, client_type, absolute_expires_at, refresh_token_hash)
  SELECT 3, 'AGENT', id, 'MOBILE', now() + interval '90 days', repeat('6',64) FROM user_device WHERE installation_id='inst-agentA'$$,
  'Agent logs in on the app with his own phone');
SELECT pg_temp.reject($$INSERT INTO user_session (user_id, user_role, client_type, absolute_expires_at)
  VALUES (3, 'AGENT', 'WEB', now() + interval '12 hours')$$, 'Agent session on the web');
SELECT pg_temp.reject($$INSERT INTO user_session (user_id, user_role, client_type, absolute_expires_at)
  VALUES (2, 'SUBOWNER', 'MOBILE', now() + interval '12 hours')$$, 'Subowner session on the app');
SELECT pg_temp.reject($$INSERT INTO user_session (user_id, user_role, client_type, absolute_expires_at)
  VALUES (5, 'OWNER', 'WEB', now() + interval '12 hours')$$, 'session claiming a role the user does not have');
SELECT pg_temp.reject($$INSERT INTO user_session (user_id, user_role, client_type, absolute_expires_at, refresh_token_hash)
  VALUES (1, 'OWNER', 'WEB', now() + interval '12 hours', repeat('7',64))$$, 'refresh token on a WEB session');
SELECT pg_temp.reject($$INSERT INTO user_session (user_id, user_role, device_id, client_type, absolute_expires_at)
  SELECT 5, 'CUSTOMER', id, 'MOBILE', now() + interval '90 days' FROM user_device WHERE installation_id='inst-su11'$$,
  'session bound to another user''s device');
SELECT pg_temp.reject($$INSERT INTO user_session (user_id, user_role, device_id, client_type, absolute_expires_at)
  SELECT 5, 'CUSTOMER_ADMIN', id, 'MOBILE', now() + interval '90 days' FROM user_device WHERE installation_id='inst-ca1'$$,
  'reject non-canonical CUSTOMER_ADMIN role in user_session');
SELECT pg_temp.reject($$INSERT INTO user_session (user_id, user_role, client_type, absolute_expires_at, status)
  VALUES (1, 'OWNER', 'WEB', now(), 'TERMINATED')$$, 'terminated session without time and reason');

-- ---------- turnout ----------
SELECT pg_temp.accept($$INSERT INTO voter_turnout (dataset_id, customer_account_id, voter_row_id, marked_by_user_id, source)
  VALUES (1, 1, 1, 7, 'ONLINE') ON CONFLICT DO NOTHING;
  INSERT INTO voter_turnout (dataset_id, customer_account_id, voter_row_id, marked_by_user_id, source)
  VALUES (1, 1, 1, 5, 'OFFLINE_SYNC') ON CONFLICT DO NOTHING$$, 'Subuser and Customer mark the same voter');
DO $$ BEGIN
  IF (SELECT count(*) FROM voter_turnout WHERE customer_account_id = 1 AND voter_row_id = 1) <> 1
  THEN RAISE EXCEPTION 'FAIL turnout counted twice'; END IF;
  RAISE NOTICE 'PASS  checked   turnout stays at one row per Customer and voter';
END $$;
SELECT pg_temp.reject($$INSERT INTO voter_turnout (dataset_id, customer_account_id, voter_row_id, marked_by_user_id, source)
  VALUES (2, 1, 1, 7, 'ONLINE')$$, 'turnout for a voter row that is not in that dataset');

-- ---------- audit log and runtime privileges ----------
INSERT INTO audit_log (actor_user_id, action_type, outcome) VALUES (1, 'SALE_VOID', 'SUCCESS');
SELECT pg_temp.reject($$UPDATE audit_log SET outcome = 'DENIED'$$, 'editing the audit log (even as owner)');
SELECT pg_temp.reject($$DELETE FROM audit_log$$, 'deleting from the audit log (even as owner)');
SELECT pg_temp.reject($$TRUNCATE audit_log$$, 'truncating the audit log');

SET ROLE elecsarthi_app;
SELECT pg_temp.accept($$INSERT INTO audit_log (actor_user_id, action_type, outcome) VALUES (1, 'LOGIN', 'SUCCESS')$$,
  'runtime role appends to the audit log');
SELECT pg_temp.reject($$UPDATE agent_sale_line SET scope_label = 'changed'$$, 'runtime role edits sale history');
SELECT pg_temp.reject($$DELETE FROM agent_sale$$, 'runtime role deletes a sale');
SELECT pg_temp.reject($$UPDATE agent_sale SET customer_account_id = 2$$, 'runtime role changes who bought');
SELECT pg_temp.accept($$UPDATE agent_sale SET void_reason = 'typo (confirmed)' WHERE id = 1$$, 'runtime role can record void details');
SELECT pg_temp.reject($$INSERT INTO election_type_field (election_type_id, source_column, value_type, label_en, usage, display_order)
  VALUES (1,'mobile_no','TEXT','x','DISPLAY',99)$$, 'runtime role changes the field configuration');
SELECT pg_temp.reject($$DROP TABLE voter_d1$$, 'runtime role drops voter data');
RESET ROLE;

-- ---------- dataset deletion by partition ----------
SELECT pg_temp.accept($$
  DELETE FROM offline_lease; DELETE FROM sync_session; DELETE FROM booth_assignment WHERE entitlement_id IN (SELECT id FROM entitlement WHERE dataset_id = 2);
  DELETE FROM entitlement WHERE dataset_id = 2; DELETE FROM import_issue WHERE dataset_id = 2;
  ALTER TABLE voter_turnout DETACH PARTITION voter_turnout_d2; DROP TABLE voter_turnout_d2;
  ALTER TABLE voter DETACH PARTITION voter_d2; DROP TABLE voter_d2;
  DELETE FROM dataset WHERE id = 2$$, 'LONG_TERM dataset deleted by dropping its partitions');
DO $$ BEGIN
  IF (SELECT count(*) FROM agent_sale_line) <> 1 THEN RAISE EXCEPTION 'FAIL sale history lost'; END IF;
  RAISE NOTICE 'PASS  checked   sale history survives dataset deletion';
END $$;

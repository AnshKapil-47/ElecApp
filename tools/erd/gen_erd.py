"""Generate the ElecSarthi v2 ER diagram (DOT -> SVG/PNG).

Single source of truth for the diagram. Column order and names must match
ElecSarthi_ER_Diagram.md.

Row kinds:  pk, pkfk, fk, uk, n (normal), snap (snapshot, no FK), sens (sensitive)
Edge: (parent, child, parent_end, child_end)
  ends: one | zero_one | many | zero_many
"""
import html
import subprocess
import sys

GROUP_COLOR = {
    "identity": "#2f5a86",
    "data": "#4c6b55",
    "sales": "#7d5a36",
    "field": "#62507f",
}
ROW_FILL = {
    "pk": "#fdecc8", "pkfk": "#fdecc8", "fk": "#e3eefb", "uk": "#fff6cf",
    "n": "#ffffff", "snap": "#efeafa", "sens": "#fde4e4", "new": "#e6f4ea",
}
ROW_TAG = {"pk": "PK", "pkfk": "PK,FK", "fk": "FK", "uk": "UK",
           "n": "", "snap": "", "sens": "", "new": ""}

# (kind, name, type, note, is_new_in_v2)
T = {}

def table(name, group, rows):
    T[name] = (group, rows)

# ---------------- Identity and history ----------------
table("CUSTOMER_ACCOUNT", "identity", [
    ("pk", "id", "bigint", "", False),
    ("fk", "created_by_user_id", "bigint", "provenance (never changes)", False),
    ("fk", "owning_agent_user_id", "bigint", "role-checked FK: must be AGENT", False),
    ("n", "customer_name / party_or_org_name", "text", "", False),
    ("uk", "contact_phone", "text", "E.164 CHECK; login identifier", False),
    ("n", "contact_email", "citext", "", False),
    ("n", "status", "enum", "ACTIVE SUSPENDED", False),
    ("n", "activation_status", "enum", "PENDING ACTIVATED", False),
    ("n", "activation_code_hash", "char(64)", "HMAC + pepper; not unique; null after use", False),
    ("n", "activation_code_key_version / issued_at / expires_at", "", "", False),
    ("fk", "activation_code_issued_by_user_id", "bigint", "", False),
    ("n", "activation_attempt_count", "smallint", "locks at 5", False),
    ("n", "activated_at", "timestamptz", "", False),
    ("n", "suspended_at / suspension_reason", "", "", False),
    ("fk", "suspended_by_user_id", "bigint", "", False),
    ("n", "mobile_access_policy_version", "int", "bump = app purges cache", False),
    ("n", "created_at / updated_at", "timestamptz", "", False),
])
table("APP_USER", "identity", [
    ("pk", "id", "bigint", "", False),
    ("fk", "customer_account_id", "bigint", "required iff CUSTOMER_ADMIN / SUBUSER", False),
    ("fk", "created_by_user_id", "bigint", "", False),
    ("uk", "username", "citext", "", False),
    ("n", "full_name", "text", "", False),
    ("n", "password_hash", "text", "BCrypt; null only while PENDING", False),
    ("n", "must_change_password", "boolean", "", False),
    ("n", "role", "enum", "OWNER SUBOWNER AGENT CUSTOMER_ADMIN SUBUSER", False),
    ("n", "status", "enum", "PENDING ACTIVE SUSPENDED DEACTIVATED", False),
    ("n", "created_at / updated_at", "timestamptz", "", False),
])
table("USER_DEVICE", "identity", [
    ("pk", "id", "uuid", "", False),
    ("fk", "customer_account_id", "bigint", "required for Customer users, null for AGENT", False),
    ("fk", "user_id", "bigint", "one ACTIVE + one PENDING per user", False),
    ("n", "user_role", "enum", "AGENT CUSTOMER_ADMIN SUBUSER (FK-checked)", False),
    ("uk", "installation_id", "text", "unique forever; never reused", False),
    ("n", "public_key / key_algorithm", "text", "ECDSA P-256", False),
    ("n", "platform / device_name", "enum,text", "ANDROID IOS", False),
    ("n", "status", "enum", "PENDING ACTIVE REVOKED REPLACED", False),
    ("n", "challenge_hash / purpose / expires_at", "", "single-use challenge", False),
    ("n", "last_request_counter", "bigint", "monotonic marker", False),
    ("fk", "replaced_by_device_id", "uuid", "self", False),
    ("n", "created_at / activated_at / last_used_at", "timestamptz", "", False),
    ("n", "revoked_at", "timestamptz", "", False),
    ("fk", "revoked_by_user_id", "bigint", "", False),
])
table("USER_SESSION", "identity", [
    ("pk", "id", "uuid", "= sid claim in access token", False),
    ("fk", "user_id", "bigint", "", False),
    ("n", "user_role", "enum", "FK-checked against app_user", False),
    ("fk", "device_id", "uuid", "FK (device, user): own device only", False),
    ("n", "client_type", "enum", "WEB = Owner/Subowner, MOBILE = others", False),
    ("n", "status", "enum", "ACTIVE TERMINATED EXPIRED", False),
    ("n", "issued_at / last_seen_at / absolute_expires_at", "timestamptz", "", False),
    ("uk", "refresh_token_hash", "char(64)", "MOBILE only", False),
    ("n", "refresh_token_generation", "int", "lower = reuse = revoke", False),
    ("n", "refresh_token_expires_at / rotated_at", "timestamptz", "", False),
    ("n", "terminated_at / termination_reason", "", "", False),
    ("fk", "terminated_by_user_id", "bigint", "", False),
    ("n", "ip_address", "inet", "", False),
])
table("AUDIT_LOG", "identity", [
    ("pk", "id", "bigint", "append-only (trigger + grants)", False),
    ("fk", "actor_user_id", "bigint", "null = system job", False),
    ("fk", "customer_account_id", "bigint", "", False),
    ("snap", "dataset_id", "bigint", "no FK", False),
    ("n", "action_type / outcome", "text", "SUCCESS DENIED", False),
    ("n", "target_entity / target_id", "text", "", False),
    ("n", "details", "jsonb", "never mobile numbers or secrets", False),
    ("n", "ip_address", "inet", "", False),
    ("snap", "device_id", "uuid", "no FK", False),
    ("n", "created_at", "timestamptz", "", False),
])

# ---------------- Election configuration and voter data ----------------
table("ELECTION_TYPE", "data", [
    ("pk", "id", "smallint", "", False),
    ("uk", "code", "text", "8 types", False),
    ("n", "display_name", "text", "", False),
    ("n", "booth_column", "text", "part_no or booth_no", False),
    ("n", "guard_column", "text", "body name checked at sale", False),
    ("n", "is_active", "boolean", "", False),
])
table("ELECTION_TYPE_FIELD", "data", [
    ("pk", "id", "bigint", "", False),
    ("fk", "election_type_id", "smallint", "UK with source_column", False),
    ("n", "source_column", "text", "CHECK: one of 56 voter columns", False),
    ("n", "value_type", "enum", "TEXT INT", False),
    ("n", "label_en / label_mt", "text", "mt = mother tongue", False),
    ("n", "usage", "enum", "FILTER (skin) DISPLAY (green)", False),
    ("n", "filter_order", "smallint", "FILTER only", False),
    ("n", "display_order", "smallint", "", False),
    ("n", "on_slip", "boolean", "", False),
])
table("DATASET", "data", [
    ("pk", "id", "bigint", "UK (id, storage_tier)", False),
    ("n", "name / original_file_name", "text", "Owner and Subowner only", False),
    ("uk", "file_sha256", "char(64)", "unique unless FAILED", False),
    ("n", "storage_tier", "enum", "MASTER LONG_TERM", False),
    ("n", "status", "enum", "PENDING IMPORTED FAILED SUPERSEDED DELETING", False),
    ("n", "states", "text[]", "for fast resolution", False),
    ("fk", "supersedes_dataset_id", "bigint", "self", False),
    ("n", "superseded_at", "timestamptz", "", False),
    ("n", "delete_after", "timestamptz", "MASTER import+60d", False),
    ("n", "total / imported / failed_rows", "int", "", False),
    ("fk", "imported_by_user_id", "bigint", "", False),
    ("n", "created_at / completed_at", "timestamptz", "", False),
])
table("IMPORT_ISSUE", "data", [
    ("pk", "id", "bigint", "", False),
    ("fk", "dataset_id", "bigint", "max 200 kept", False),
    ("n", "sheet / row_no / column_name", "", "positions only", False),
    ("n", "issue_code", "enum", "OVERLAP AMBIGUOUS_AREA BAD_NUMBER ...", False),
    ("n", "issue_detail", "text", "no personal values", False),
])
table("VOTER", "data", [
    ("pkfk", "dataset_id", "bigint", "PARTITION BY LIST", False),
    ("pk", "id", "bigint", "global sequence", False),
    ("uk", "source_record_key_hash", "char(64)", "UK with dataset; overlap probe", False),
    ("n", "pc_no, ac_no (+ names en/mt)", "int,text", "", False),
    ("n", "part_no, booth_no, booth_name / booth_address en/mt, serial_no", "", "", False),
    ("n", "v_* / vr_* names en/mt, voter_id, age, sex", "", "voter_id = EPIC", False),
    ("n", "house_no, house_location en/mt, section_*", "", "", False),
    ("n", "municipal_name_nagar_* en/mt, *_ward_no", "", "nigam, palika, parishad", False),
    ("n", "zilla_parishad_* / panchayat_samiti_* / panchayat_*", "", "", False),
    ("n", "district, rural_urban, tehsil, block, pincode, state", "text", "", False),
    ("sens", "mobile_no_enc / mobile_key_version", "bytea", "AES-GCM; only with mobile_access", False),
    ("sens", "voter_image_ref", "text", "private object key", False),
])
table("VOTER_TURNOUT", "data", [
    ("pkfk", "dataset_id", "bigint", "PARTITION BY LIST", False),
    ("pkfk", "customer_account_id", "bigint", "", False),
    ("pkfk", "voter_row_id", "bigint", "FK (dataset_id, voter_row_id)", False),
    ("fk", "marked_by_user_id", "bigint", "Customer or Subuser", False),
    ("n", "marked_at", "timestamptz", "server time", False),
    ("n", "source", "enum", "ONLINE OFFLINE_LEASE OFFLINE_SYNC", False),
])

# ---------------- Sales and entitlement ----------------
table("AGENT_SALE", "sales", [
    ("pk", "id", "bigint", "", False),
    ("fk", "sold_by_user_id", "bigint", "role-checked FK: must be AGENT", False),
    ("fk", "customer_account_id", "bigint", "", False),
    ("fk", "election_type_id", "smallint", "one type per sale", False),
    ("n", "storage_tier", "enum", "one tier per sale", False),
    ("uk", "idempotency_key", "text", "UK with sold_by_user_id", False),
    ("n", "request_sha256", "char(64)", "same key, other content = 409", False),
    ("n", "sold_at", "timestamptz", "", False),
    ("n", "status", "enum", "COMPLETED VOIDED", False),
    ("n", "voided_at / void_reason", "", "", False),
    ("fk", "voided_by_user_id", "bigint", "Owner", False),
])
table("AGENT_SALE_LINE", "sales", [
    ("pk", "id", "bigint", "", False),
    ("fk", "sale_id", "bigint", "UK (sale, line_no), UK (sale, criteria_hash)", False),
    ("n", "line_no", "smallint", "", False),
    ("snap", "dataset_id / dataset_name", "", "snapshot, no FK", False),
    ("snap", "storage_tier", "enum", "snapshot", False),
    ("snap", "criteria / criteria_hash", "jsonb", "canonical area", False),
    ("snap", "scope_label", "text", "e.g. Vidhan Sabha · Haryana · AC 10", False),
    ("snap", "voter_count_snapshot", "int", "", False),
    ("snap", "duration_value / duration_unit", "", "DAY WEEK MONTH", False),
    ("snap", "valid_from / valid_until", "timestamptz", "", False),
])
table("ENTITLEMENT", "sales", [
    ("pk", "id", "bigint", "UK (id, customer_account_id)", False),
    ("fk", "customer_account_id", "bigint", "", False),
    ("fk", "election_type_id", "smallint", "the lens", False),
    ("fk", "dataset_id", "bigint", "FK (dataset, tier): tier must match", False),
    ("n", "storage_tier", "enum", "MASTER LONG_TERM", False),
    ("n", "criteria / criteria_hash", "jsonb", "canonical FILTER values", False),
    ("n", "scope_label", "text", "", False),
    ("n", "source", "enum", "SALE DIRECT", False),
    ("fk", "sale_line_id", "bigint", "UK; required iff SALE", False),
    ("n", "status", "enum", "ACTIVE REVOKED", False),
    ("n", "valid_from / valid_until", "timestamptz", "EXCLUDE overlap per customer+type+tier+area", False),
    ("n", "mobile_access", "boolean", "Owner-only switch", False),
    ("n", "mobile_access_changed_at", "timestamptz", "", False),
    ("fk", "mobile_access_changed_by", "bigint", "", False),
    ("fk", "created_by_user_id", "bigint", "", False),
    ("n", "created_at", "timestamptz", "", False),
    ("n", "revoked_at / revoke_reason", "", "", False),
    ("fk", "revoked_by_user_id", "bigint", "", False),
])

# ---------------- Field operations and offline ----------------
table("BOOTH_ASSIGNMENT", "field", [
    ("pk", "id", "bigint", "", False),
    ("fk", "customer_account_id", "bigint", "same tenant on both FKs", False),
    ("fk", "subuser_id", "bigint", "role-checked FK: must be SUBUSER", False),
    ("fk", "entitlement_id", "bigint", "FK (entitlement, customer)", False),
    ("n", "booth_value", "int", "value of election_type.booth_column", False),
    ("n", "slot", "smallint", "1 or 2", False),
    ("n", "status", "enum", "ACTIVE REVOKED", False),
    ("n", "valid_from / valid_until", "timestamptz", "<= 7 days; EXCLUDE per slot", False),
    ("fk", "assigned_by_user_id", "bigint", "", False),
    ("n", "revoked_at / revoke_reason", "", "", False),
    ("fk", "revoked_by_user_id", "bigint", "", False),
])
table("OFFLINE_LEASE", "field", [
    ("pk", "id", "uuid", "", False),
    ("fk", "booth_assignment_id", "bigint", "FK (assignment, subuser)", False),
    ("fk", "subuser_id", "bigint", "ties assignment and device", False),
    ("fk", "device_id", "uuid", "FK (device, subuser): own device", False),
    ("uk", "lease_seq", "bigint", "UK with device", False),
    ("n", "payload_sha256", "char(64)", "", False),
    ("n", "signing_key_version", "smallint", "Ed25519 server key", False),
    ("n", "issued_at / expires_at", "timestamptz", "<= assignment, entitlement, 24h", False),
    ("n", "revoked_at", "timestamptz", "", False),
    ("fk", "revoked_by_user_id", "bigint", "", False),
])
table("SYNC_SESSION", "field", [
    ("pk", "id", "uuid", "", False),
    ("fk", "customer_account_id", "bigint", "same tenant on all FKs", False),
    ("fk", "user_id", "bigint", "role-checked FK: CUSTOMER_ADMIN", False),
    ("fk", "entitlement_id", "bigint", "one area per sync", False),
    ("fk", "device_id", "uuid", "FK (device, user)", False),
    ("n", "protocol_version / manifest_sha256", "", "", False),
    ("n", "scope_snapshot", "jsonb", "descriptive; no dataset ids", False),
    ("n", "policy_version / includes_mobile", "", "", False),
    ("n", "total_records / total_chunks / chunk_size / chunks_done", "int", "", False),
    ("n", "signing_key_version", "smallint", "", False),
    ("n", "offline_valid_until", "timestamptz", "<= entitlement end, +24h", False),
    ("n", "status / cancel_reason", "enum", "ACTIVE COMPLETED CANCELLED EXPIRED", False),
    ("n", "created_at / expires_at / completed_at", "timestamptz", "", False),
])
table("DEVICE_NONCE", "field", [
    ("pkfk", "device_id", "uuid", "", False),
    ("pk", "nonce", "text", "single use", False),
    ("n", "created_at / expires_at", "timestamptz", "purged after window", False),
])

EDGES = [
    ("CUSTOMER_ACCOUNT", "APP_USER", "zero_one", "zero_many"),
    ("APP_USER", "CUSTOMER_ACCOUNT", "zero_one", "zero_many"),          # owning agent
    ("APP_USER", "USER_DEVICE", "one", "zero_many"),
    ("APP_USER", "USER_SESSION", "one", "zero_many"),
    ("USER_DEVICE", "USER_SESSION", "zero_one", "zero_many"),
    ("ELECTION_TYPE", "ELECTION_TYPE_FIELD", "one", "many"),
    ("DATASET", "IMPORT_ISSUE", "one", "zero_many"),
    ("DATASET", "VOTER", "one", "zero_many"),
    ("DATASET", "DATASET", "zero_one", "zero_one"),                     # supersedes
    ("VOTER", "VOTER_TURNOUT", "one", "zero_many"),
    ("CUSTOMER_ACCOUNT", "VOTER_TURNOUT", "one", "zero_many"),
    ("APP_USER", "AGENT_SALE", "one", "zero_many"),
    ("CUSTOMER_ACCOUNT", "AGENT_SALE", "one", "zero_many"),
    ("ELECTION_TYPE", "AGENT_SALE", "one", "zero_many"),
    ("AGENT_SALE", "AGENT_SALE_LINE", "one", "many"),
    ("AGENT_SALE_LINE", "ENTITLEMENT", "zero_one", "zero_one"),
    ("CUSTOMER_ACCOUNT", "ENTITLEMENT", "one", "zero_many"),
    ("ELECTION_TYPE", "ENTITLEMENT", "one", "zero_many"),
    ("DATASET", "ENTITLEMENT", "one", "zero_many"),
    ("ENTITLEMENT", "BOOTH_ASSIGNMENT", "one", "zero_many"),
    ("APP_USER", "BOOTH_ASSIGNMENT", "one", "zero_many"),               # subuser
    ("BOOTH_ASSIGNMENT", "OFFLINE_LEASE", "one", "zero_many"),
    ("USER_DEVICE", "OFFLINE_LEASE", "one", "zero_many"),
    ("ENTITLEMENT", "SYNC_SESSION", "one", "zero_many"),
    ("USER_DEVICE", "SYNC_SESSION", "one", "zero_many"),
    ("USER_DEVICE", "DEVICE_NONCE", "one", "zero_many"),
]

# Graphviz arrow names: first shape listed sits next to the node.
END = {"one": "teetee", "zero_one": "teeodot", "many": "crowtee", "zero_many": "crowodot"}

GROUP_LABEL = {
    "identity": "Identity, devices and history",
    "data": "Election configuration and voter data",
    "sales": "Sales and entitlement",
    "field": "Field operations and offline",
}


def esc(s):
    return html.escape(s, quote=True)


def node_label(name):
    group, rows = T[name]
    head = GROUP_COLOR[group]
    out = ['<<TABLE BORDER="1" COLOR="#8a8f98" CELLBORDER="0" CELLSPACING="0" CELLPADDING="6">',
           f'<TR><TD COLSPAN="4" BGCOLOR="{head}" ALIGN="LEFT"><FONT COLOR="white" POINT-SIZE="22"><B>{esc(name)}</B></FONT></TD></TR>']
    for kind, col, typ, note, new in rows:
        fill = ROW_FILL[kind]
        if new and kind in ("n", "uk", "fk"):
            fill = ROW_FILL["new"] if kind == "n" else fill
        tag = ROW_TAG[kind]
        mark = " ★" if new else ""
        out.append(
            f'<TR>'
            f'<TD BGCOLOR="{fill}" ALIGN="LEFT"><FONT POINT-SIZE="12" COLOR="#555555">{esc(tag) if tag else " "}</FONT></TD>'
            f'<TD BGCOLOR="{fill}" ALIGN="LEFT"><FONT POINT-SIZE="16"><B>{esc(col)}{mark}</B></FONT></TD>'
            f'<TD BGCOLOR="{fill}" ALIGN="LEFT"><FONT POINT-SIZE="12" COLOR="#666666">{esc(typ) if typ else " "}</FONT></TD>'
            f'<TD BGCOLOR="{fill}" ALIGN="LEFT"><FONT POINT-SIZE="12" COLOR="#7a5a00">{esc(note) if note else " "}</FONT></TD>'
            f'</TR>')
    out.append('</TABLE>>')
    return "".join(out)


def build_dot(swap=frozenset()):
    lines = [
        'digraph G {',
        '  graph [rankdir=TB, splines=ortho, nodesep=0.9, ranksep=1.6, bgcolor="#f6f7fa", '
        'fontname="Inter", pad=0.6, newrank=true, labelloc=t, labeljust=l, '
        'label=<<FONT POINT-SIZE="40"><B>ElecSarthi – ER diagram</B></FONT><BR ALIGN="LEFT"/>'
        '<FONT POINT-SIZE="20" COLOR="#555555">18 tables · tested on PostgreSQL 16 · area = election-type FILTER values (criteria) · '
        'MASTER / LONG_TERM tier on the dataset · voter and voter_turnout partitioned by dataset_id</FONT><BR ALIGN="LEFT"/>'
        '<FONT POINT-SIZE="18" COLOR="#555555">Ends: bar = exactly one, bar+circle = zero or one, crow+bar = one or many, '
        'crow+circle = zero or many. Violet = snapshot (no FK). Red = sensitive. '
        'Actor FKs (created_by, granted_by, revoked_by, voided_by, marked_by...) are listed but not drawn.</FONT><BR ALIGN="LEFT"/>>];',
        '  node [shape=plaintext, fontname="Inter"];',
        '  edge [color="#4a5568", penwidth=2, dir=both, arrowsize=1.3];',
    ]
    groups = {}
    for name, (g, _) in T.items():
        groups.setdefault(g, []).append(name)
    for g, names in groups.items():
        lines.append(f'  subgraph cluster_{g} {{ label=<<FONT POINT-SIZE="24" COLOR="{GROUP_COLOR[g]}"><B>{GROUP_LABEL[g]}</B></FONT>>; '
                     f'style="rounded,dashed"; color="{GROUP_COLOR[g]}"; margin=24;')
        for n in names:
            lines.append(f'    {n} [label={node_label(n)}];')
        lines.append('  }')
    for p, c, pe, ce in EDGES:
        # Graphviz 2.43 with splines=ortho draws head/tail swapped on some
        # edges; those are detected from the SVG and swapped back here.
        tail, head = (END[ce], END[pe]) if (p, c) in swap else (END[pe], END[ce])
        lines.append(f'  {p} -> {c} [arrowtail={tail}, arrowhead={head}];')
    lines.append('}')
    return "\n".join(lines)


if __name__ == "__main__":
    assert len(T) == 18, len(T)
    for p, c, _, _ in EDGES:
        assert p in T and c in T, (p, c)
    from verify_ends import wrong_edges
    out = sys.argv[1] if len(sys.argv) > 1 else "ElecSarthi_ER_Diagram"
    swap = set()
    for attempt in range(4):
        with open(out + ".dot", "w") as f:
            f.write(build_dot(frozenset(swap)))
        subprocess.run(["dot", "-Tsvg", out + ".dot", "-o", out + ".svg"], check=True)
        bad = wrong_edges(out + ".svg")
        print(f"attempt {attempt}: {len(bad)} reversed")
        if not bad:
            break
        swap ^= set(bad)
    else:
        sys.exit("could not fix edge ends")
    subprocess.run(["dot", "-Tpng", out + ".dot", "-o", out + ".png"], check=True)
    print("ok", len(T), "tables", len(EDGES), "edges")

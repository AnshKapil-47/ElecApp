"""Generate election_type_field_seed.csv (fresh schema: booth column lives on election_type)

Original purpose: from NEW_APP_COLUMN_DETAILS.xlsx cell colours.

Colour rule (read from the workbook theme):
  theme accent2 (orange, tint +0.40)       -> FILTER  ("skin")
  theme accent3 (green, any tint), 00B050  -> DISPLAY ("green")
  white / no fill                          -> field not used by that election type

Usage: python3 -I gen_seed.py NEW_APP_COLUMN_DETAILS.xlsx out.csv
"""
import csv
import sys

import openpyxl

TYPES = ["LOK_SABHA", "VIDHAN_SABHA", "NAGAR_NIGAM", "NAGAR_PALIKA",
         "NAGAR_PARISHAD", "ZILLA_PANCHAYAT", "PANCHAYAT_SAMITI", "PANCHAYAT"]

# Sheet header -> canonical voter column (typos fixed once, at import)
RENAME = {
    "state": "state",
    "Panchayat no": "panchayat_no",
    "NAGAR_PRISHAD_WARD_NO": "nagar_parishad_ward_no",
    "MUNICIPAL_NAME_NAGAR_PRISHAD_MT": "municipal_name_nagar_parishad_mt",
}
UNNAMED = {12: "booth_address_en", 13: "booth_address_mt"}   # confirmed by Owner, 24 Nov 2026

# Filter order per type, exactly as the sheet's skin cells (decided: keep sheet as is)
FILTER_ORDER = {
    "LOK_SABHA": ["state", "pc_no", "ac_no"],
    "VIDHAN_SABHA": ["state", "ac_no"],
    "NAGAR_NIGAM": ["state", "district", "municipal_name_nagar_nigam_en", "nagar_nigam_ward_no"],
    "NAGAR_PALIKA": ["state", "district", "nagar_palika_ward_no"],
    "NAGAR_PARISHAD": ["state", "district", "nagar_parishad_ward_no"],
    "ZILLA_PANCHAYAT": ["state", "district", "zilla_parishad_name_ward_no"],
    "PANCHAYAT_SAMITI": ["state", "district", "panchayat_samiti_name_ward_no"],
    "PANCHAYAT": ["state", "district", "panchayat_no"],
}
BOOTH_KEY = {t: ("part_no" if t in ("LOK_SABHA", "VIDHAN_SABHA") else "booth_no") for t in TYPES}

# INT columns: sheet "NUMBER" type, except PC_NAME_MT (a name, mistyped as NUMBER),
# plus ward / panchayat numbers that the sheet left untyped.
INT_EXTRA = {"nagar_palika_ward_no", "nagar_parishad_ward_no", "panchayat_no"}
NOT_INT = {"pc_name_mt"}

# Proposed default slip fields (Owner may change in the seed before loading)
SLIP = {"part_no", "booth_no", "booth_name_en", "booth_name_mt", "booth_address_en", "booth_address_mt",
        "serial_no", "v_fname_en", "v_lname_en", "v_fname_mt", "v_lname_mt", "voter_id",
        "vr_fname_en", "vr_lname_en", "vr_fname_mt", "vr_lname_mt", "house_no", "age", "sex"}


def usage_of(cell):
    f = cell.fill
    if f is None or f.fill_type is None:
        return None
    fg = f.fgColor
    if fg.type == "rgb" and fg.rgb == "FF00B050":
        return "DISPLAY"
    if fg.type == "theme" and fg.theme == 5:
        return "FILTER"
    if fg.type == "theme" and fg.theme == 6:
        return "DISPLAY"
    return None  # theme 0 = white = not used


def label(col):
    base = col.replace("_en", "").replace("_mt", "")
    words = base.replace("_", " ").split()
    fix = {"no": "No", "pc": "PC", "ac": "AC", "v": "Voter", "vr": "Relative",
           "fname": "first name", "lname": "last name"}
    return " ".join(fix.get(w, w.capitalize() if i == 0 else w) for i, w in enumerate(words))


def main(src, out):
    ws = openpyxl.load_workbook(src).active
    columns = []  # (row, canonical, sheet_type)
    for r in range(2, ws.max_row + 1):
        raw = ws.cell(r, 1).value
        if raw is None:
            canon = UNNAMED[r]
        else:
            raw = raw.strip()
            canon = RENAME.get(raw, raw.lower())
        columns.append((r, canon, (ws.cell(r, 3).value or "").strip()))
    assert len(columns) == 56, len(columns)

    rows = []
    for ti, t in enumerate(TYPES):
        filt, disp = [], []
        for r, canon, typ in columns:
            u = usage_of(ws.cell(r, 4 + ti))
            if u == "FILTER":
                filt.append(canon)
            elif u == "DISPLAY":
                disp.append(canon)
        assert sorted(filt) == sorted(FILTER_ORDER[t]), (t, filt)
        assert BOOTH_KEY[t] in disp, (t, "booth key not displayed")
        order = FILTER_ORDER[t] + disp
        for d_ord, canon in enumerate(order, start=1):
            typ = next(x[2] for x in columns if x[1] == canon)
            is_int = (typ == "NUMBER" or canon in INT_EXTRA) and canon not in NOT_INT
            is_filter = canon in FILTER_ORDER[t]
            rows.append({
                "election_type_code": t,
                "source_column": canon,
                "value_type": "INT" if is_int else "TEXT",
                "label_en": label(canon),
                "label_mt": "",
                "usage": "FILTER" if is_filter else "DISPLAY",
                "filter_order": FILTER_ORDER[t].index(canon) + 1 if is_filter else "",
                "display_order": d_ord,
                "on_slip": "true" if canon in SLIP else "false",
            })
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    by_type = {t: sum(1 for x in rows if x["election_type_code"] == t) for t in TYPES}
    print("rows:", len(rows), by_type)
    print("voter columns:", [c for _, c, _ in columns])


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])

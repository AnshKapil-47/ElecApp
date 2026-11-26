package com.elecsarthi.service.impl;

import java.util.Arrays;
import java.util.Locale;
import com.elecsarthi.config.AppProperties;
import com.elecsarthi.entity.ImportIssue;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.CriteriaNormalizer;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Receives the rows of one XLSX workbook from the POI SAX parser, normalizes them and streams them, one row at a
 * time, into the COPY of the dataset's partition table. Only the current row (and the writer's small buffer) is
 * held in memory. The first row of every sheet is the header; unknown header columns are ignored.
 *
 * <p><b>Every data row is imported.</b> The roll is a refined, official dataset, so a missing voter id, serial number
 * or state, an unreadable number or a repeated record never drops a row: the value is stored as NULL (or, for a
 * repeated record, the row gets its own record key) and an informational issue with the position is recorded.
 * Only rows with no data at all (blank lines) are skipped, and they are not counted.
 */
class VoterSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {

    private static final int MAX_ISSUES = 200;
    private static final int MAX_HEADER_COLUMNS = 1024;

    /** Insert order of the voter columns that come straight from the sheet. */
    private static final List<String> VOTER_COLUMNS = List.of(
            "state", "district", "pc_no", "pc_name_en", "pc_name_mt",
            "ac_no", "ac_name_en", "ac_name_mt", "part_no", "booth_no",
            "booth_name_en", "booth_name_mt", "booth_address_en", "booth_address_mt",
            "serial_no", "voter_id", "v_fname_en", "v_lname_en", "v_fname_mt", "v_lname_mt",
            "vr_fname_en", "vr_lname_en", "vr_fname_mt", "vr_lname_mt",
            "house_no", "house_location_en", "house_location_mt",
            "section_no", "section_name_en", "section_name_mt",
            "age", "sex", "rural_urban", "tehsil", "block", "pincode",
            "municipal_name_nagar_nigam_en", "municipal_name_nagar_nigam_mt", "nagar_nigam_ward_no",
            "municipal_name_nagar_palika_en", "municipal_name_nagar_palika_mt", "nagar_palika_ward_no",
            "municipal_name_nagar_parishad_en", "municipal_name_nagar_parishad_mt", "nagar_parishad_ward_no",
            "zilla_parishad_name_en", "zilla_parishad_name_mt", "zilla_parishad_name_ward_no",
            "panchayat_samiti_name_en", "panchayat_samiti_name_mt", "panchayat_samiti_name_ward_no",
            "panchayat_name_en", "panchayat_name_mt", "panchayat_no");

    /** Columns stored as integers; everything else is stored as text. */
    static final Set<String> INT_COLUMNS = Set.of(
            "pc_no", "ac_no", "part_no", "booth_no", "serial_no",
            "section_no", "age", "nagar_nigam_ward_no", "nagar_palika_ward_no",
            "nagar_parishad_ward_no", "zilla_parishad_name_ward_no",
            "panchayat_samiti_name_ward_no", "panchayat_no");

    /**
     * Integer columns that identify a voter or the area it belongs to. A present value that is not a non-negative
     * whole number is stored as NULL, and an INVALID_NUMBER issue records where it was. Descriptive numbers
     * (age, section_no) are lenient and not reported.
     */
    private static final Set<String> STRICT_INT_COLUMNS = Set.of(
            "pc_no", "ac_no", "part_no", "booth_no", "serial_no",
            "nagar_nigam_ward_no", "nagar_palika_ward_no", "nagar_parishad_ward_no",
            "zilla_parishad_name_ward_no", "panchayat_samiti_name_ward_no", "panchayat_no");

    /** The COPY column list; {@link #buildRow} produces the values in exactly this order. */
    static final List<String> COPY_COLUMNS;

    private static final Map<String, Integer> SHEET_COLUMN_INDEX = new HashMap<>();
    private static final int N = VOTER_COLUMNS.size();
    private static final int MOBILE = N;

    private static final int STATE = VOTER_COLUMNS.indexOf("state");
    private static final int DISTRICT = VOTER_COLUMNS.indexOf("district");
    private static final int AC_NO = VOTER_COLUMNS.indexOf("ac_no");
    private static final int PART_NO = VOTER_COLUMNS.indexOf("part_no");
    private static final int BOOTH_NO = VOTER_COLUMNS.indexOf("booth_no");
    private static final int SERIAL_NO = VOTER_COLUMNS.indexOf("serial_no");
    private static final int VOTER_ID = VOTER_COLUMNS.indexOf("voter_id");

    private static final boolean[] IS_INT = new boolean[N];
    private static final boolean[] IS_STRICT_INT = new boolean[N];

    static {
        for (int i = 0; i < N; i++) {
            String column = VOTER_COLUMNS.get(i);
            SHEET_COLUMN_INDEX.put(column, i);
            IS_INT[i] = INT_COLUMNS.contains(column);
            IS_STRICT_INT[i] = STRICT_INT_COLUMNS.contains(column);
        }
        SHEET_COLUMN_INDEX.put("mobile_no", MOBILE);
        // Known header misspelling in source sheets: PRISHAD for PARISHAD.
        SHEET_COLUMN_INDEX.put("municipal_name_nagar_prishad_en", SHEET_COLUMN_INDEX.get("municipal_name_nagar_parishad_en"));
        SHEET_COLUMN_INDEX.put("municipal_name_nagar_prishad_mt", SHEET_COLUMN_INDEX.get("municipal_name_nagar_parishad_mt"));
        SHEET_COLUMN_INDEX.put("nagar_prishad_ward_no", SHEET_COLUMN_INDEX.get("nagar_parishad_ward_no"));

        List<String> copyColumns = new ArrayList<>(List.of("dataset_id", "source_record_key_hash"));
        copyColumns.addAll(VOTER_COLUMNS);
        copyColumns.addAll(List.of("mobile_no_enc", "mobile_key_version", "voter_image_ref"));
        COPY_COLUMNS = List.copyOf(copyColumns);
    }

    private final Long datasetId;
    private final boolean[] normalizeText = new boolean[N];
    private final VoterCopyWriter writer;
    private final AppProperties.SecurityProperties security;
    private final byte[] mobileAesKey;
    private final Instant importedAt;

    /** Sheet column index -> index into {@link #cells}; -1 = column not used. */
    private final int[] headerMap = new int[MAX_HEADER_COLUMNS];
    private final String[] cells = new String[N + 1];
    private boolean rowHasData;
    private final Set<String> states = new HashSet<>();
    private final List<ImportIssue> issues = new ArrayList<>();
    private final LongSet seenKeys = new LongSet();
    private final Map<Long, Integer> repeatCounts = new HashMap<>();
    private int currentRowNum = -1;
    private String currentSheetName = "Sheet1";
    private int totalRows;
    private int importedRows;

    VoterSheetHandler(Long datasetId, Set<String> normalizedTextColumns, VoterCopyWriter writer,
                      AppProperties appProperties, Instant importedAt) {
        this.datasetId = datasetId;
        this.writer = writer;
        this.security = appProperties.getSecurity();
        this.mobileAesKey = HexFormat.of().parseHex(security.getVoterMobileAesKeyHex());
        this.importedAt = importedAt;
        for (int i = 0; i < N; i++) {
            normalizeText[i] = !IS_INT[i] && normalizedTextColumns.contains(VOTER_COLUMNS.get(i));
        }
        Arrays.fill(headerMap, -1);
    }

    int totalRows() {
        return totalRows;
    }

    int importedRows() {
        return importedRows;
    }

    /** Always 0: no row is rejected. Kept because the dataset record and API expose the counter. */
    int failedRows() {
        return 0;
    }

    List<String> states() {
        return new ArrayList<>(states);
    }

    List<ImportIssue> issues() {
        return issues;
    }

    void startNewSheet(String sheetName) {
        currentSheetName = sheetName;
        Arrays.fill(headerMap, -1);
        Arrays.fill(cells, null);
        rowHasData = false;
        currentRowNum = -1;
    }

    @Override
    public void startRow(int rowNum) {
        currentRowNum = rowNum;
        Arrays.fill(cells, null);
        rowHasData = false;
    }

    @Override
    public void cell(String cellReference, String formattedValue, XSSFComment comment) {
        if (formattedValue == null) {
            return;
        }
        String value = formattedValue.strip();
        if (value.isEmpty()) {
            return;
        }
        int columnIndex = columnIndex(cellReference);
        if (columnIndex < 0 || columnIndex >= MAX_HEADER_COLUMNS) {
            return;
        }
        if (currentRowNum == 0) {
            String header = value.replace("﻿", "").toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            Integer target = SHEET_COLUMN_INDEX.get(header);
            if (target != null) {
                headerMap[columnIndex] = target;
            }
        } else {
            int target = headerMap[columnIndex];
            if (target >= 0) {
                cells[target] = value;
                rowHasData = true;
            }
        }
    }

    @Override
    public void endRow(int rowNum) {
        if (rowNum == 0 || !rowHasData) {
            return;
        }
        totalRows++;

        for (int i = 0; i < N; i++) {
            if (normalizeText[i] && cells[i] != null) {
                cells[i] = CriteriaNormalizer.normalizeText(cells[i]);
                if (cells[i] != null && cells[i].isEmpty()) {
                    cells[i] = null;
                }
            }
        }

        if (cells[STATE] == null) {
            addIssue(rowNum, "state", "MISSING_FIELD", "State is empty at row " + rowNum + "; imported with no value");
        } else {
            states.add(cells[STATE]);
        }
        if (cells[VOTER_ID] == null) {
            addIssue(rowNum, "voter_id", "MISSING_FIELD", "Voter id is empty at row " + rowNum + "; imported with no value");
        }
        if (cells[SERIAL_NO] == null) {
            addIssue(rowNum, "serial_no", "MISSING_FIELD", "Serial number is empty at row " + rowNum + "; imported with no value");
        }

        writer.writeRow(buildRow(rowNum));
        importedRows++;
    }

    private List<Object> buildRow(int rowNum) {
        List<Object> values = new ArrayList<>(N + 5);
        values.add(datasetId);
        values.add(recordKey(rowNum));
        for (int i = 0; i < N; i++) {
            String value = cells[i];
            if (!IS_INT[i]) {
                values.add(value);
                continue;
            }
            Integer parsed = parseInteger(value);
            if (IS_STRICT_INT[i] && value != null && (parsed == null || parsed < 0)) {
                addIssue(rowNum, VOTER_COLUMNS.get(i), "INVALID_NUMBER",
                        "Value is not a valid non-negative whole number at row " + rowNum + "; imported with no value");
                parsed = null;
            }
            values.add(parsed);
        }

        byte[] mobileEnc = null;
        Short mobileKeyVersion = null;
        String mobileNo = cells[MOBILE];
        if (mobileNo != null) {
            try {
                mobileEnc = CryptoUtils.aesGcmEncrypt(mobileNo.getBytes(StandardCharsets.UTF_8), mobileAesKey);
                mobileKeyVersion = (short) security.getVoterMobileKeyVersion();
            } catch (Exception e) {
                addIssue(rowNum, "mobile_no", "ENCRYPTION_FAILED", "Failed to encrypt mobile at row " + rowNum);
            }
        }
        values.add(mobileEnc);
        values.add(mobileKeyVersion);
        values.add(null); // voter_image_ref is never imported from the sheet
        return values;
    }

    /**
     * The record key identifies a voter across datasets; the overlap probe compares these hashes. A row whose key
     * was already used earlier in the file (a repeated record, or rows that lack every identifying field) gets the
     * number of its repetition appended, so it is kept and the key stays the same on a re-import of the same file.
     */
    private String recordKey(int rowNum) {
        String base = String.join("|",
                orEmpty(cells[STATE]), orEmpty(cells[DISTRICT]), orEmpty(cells[AC_NO]), orEmpty(cells[PART_NO]),
                orEmpty(cells[BOOTH_NO]), orEmpty(cells[SERIAL_NO]), orEmpty(cells[VOTER_ID]));
        String hash = CryptoUtils.sha256Hex(base);
        long fingerprint = Long.parseUnsignedLong(hash.substring(0, 16), 16);
        if (seenKeys.add(fingerprint)) {
            return hash;
        }
        int repeat = repeatCounts.merge(fingerprint, 1, Integer::sum);
        addIssue(rowNum, "voter_id", "DUPLICATE_RECORD", "Record repeats an earlier row at row " + rowNum + "; imported as a separate record");
        return CryptoUtils.sha256Hex(base + "|#" + repeat);
    }

    private void addIssue(int rowNum, String column, String code, String detail) {
        if (issues.size() < MAX_ISSUES) {
            issues.add(ImportIssue.builder()
                    .datasetId(datasetId)
                    .sheet(currentSheetName)
                    .rowNo(rowNum)
                    .columnName(column)
                    .issueCode(code)
                    .issueDetail(detail)
                    .createdAt(importedAt)
                    .build());
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Excel serialises integers as "12.0"; a value that is not a number is stored as null. */
    private static Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        int dot = value.indexOf('.');
        try {
            return Integer.parseInt(dot >= 0 ? value.substring(0, dot) : value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Zero-based column index of an A1-style cell reference such as "AB12". */
    private static int columnIndex(String cellReference) {
        int index = 0;
        for (int i = 0; i < cellReference.length() && Character.isLetter(cellReference.charAt(i)); i++) {
            index = index * 26 + (Character.toUpperCase(cellReference.charAt(i)) - 'A' + 1);
        }
        return index - 1;
    }

    /** Open-addressing set of primitive longs: tracks record keys without boxing millions of values. */
    private static final class LongSet {
        private long[] table = new long[1 << 16];
        private boolean[] used = new boolean[1 << 16];
        private int size;

        boolean add(long value) {
            if (size * 2 >= table.length) {
                grow();
            }
            return insert(table, used, value) && ++size > 0;
        }

        private static boolean insert(long[] t, boolean[] u, long value) {
            int mask = t.length - 1;
            int slot = (int) ((value * 0x9E3779B97F4A7C15L) >>> 40) & mask;
            while (u[slot]) {
                if (t[slot] == value) {
                    return false;
                }
                slot = (slot + 1) & mask;
            }
            t[slot] = value;
            u[slot] = true;
            return true;
        }

        private void grow() {
            long[] newTable = new long[table.length * 2];
            boolean[] newUsed = new boolean[table.length * 2];
            for (int i = 0; i < table.length; i++) {
                if (used[i]) {
                    insert(newTable, newUsed, table[i]);
                }
            }
            table = newTable;
            used = newUsed;
        }
    }
}

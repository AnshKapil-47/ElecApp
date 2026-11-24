package com.elecsarthi.service.impl;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.entity.ImportIssue;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.CriteriaNormalizer;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Receives the rows of one XLSX workbook from the POI SAX parser, normalizes them and inserts them in batches
 * into the dataset's partition table. Nothing but the current row and the current batch is held in memory.
 * The first row of every sheet is the header; unknown header columns are ignored.
 */
class VoterSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {

    private static final int MAX_ISSUES = 200;
    private static final int BATCH_SIZE = 1000;

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

    private static final Set<String> SHEET_COLUMNS;

    static {
        Set<String> columns = new HashSet<>(VOTER_COLUMNS);
        columns.add("mobile_no");
        SHEET_COLUMNS = Set.copyOf(columns);
    }

    private final Long datasetId;
    private final Set<String> normalizedTextColumns;
    private final JdbcTemplate jdbcTemplate;
    private final AppProperties.SecurityProperties security;
    private final byte[] mobileAesKey;
    private final Instant importedAt;
    private final String insertSql;

    private final Map<Integer, String> headerMap = new HashMap<>();
    private final Map<String, String> currentRow = new HashMap<>();
    private final List<Object[]> batch = new ArrayList<>();
    private final Set<String> states = new HashSet<>();
    private final List<ImportIssue> issues = new ArrayList<>();
    private int currentRowNum = -1;
    private String currentSheetName = "Sheet1";
    private int totalRows;
    private int importedRows;
    private int failedRows;

    VoterSheetHandler(Long datasetId, Set<String> normalizedTextColumns, JdbcTemplate jdbcTemplate,
                      AppProperties appProperties, Instant importedAt) {
        this.datasetId = datasetId;
        this.normalizedTextColumns = normalizedTextColumns;
        this.jdbcTemplate = jdbcTemplate;
        this.security = appProperties.getSecurity();
        this.mobileAesKey = HexFormat.of().parseHex(security.getVoterMobileAesKeyHex());
        this.importedAt = importedAt;
        this.insertSql = "INSERT INTO voter_d" + datasetId + " (dataset_id, source_record_key_hash, "
                + String.join(", ", VOTER_COLUMNS)
                + ", mobile_no_enc, mobile_key_version, voter_image_ref) VALUES ("
                + String.join(",", Collections.nCopies(VOTER_COLUMNS.size() + 5, "?")) + ")";
    }

    int totalRows() {
        return totalRows;
    }

    int importedRows() {
        return importedRows;
    }

    int failedRows() {
        return failedRows;
    }

    List<String> states() {
        return new ArrayList<>(states);
    }

    List<ImportIssue> issues() {
        return issues;
    }

    void startNewSheet(String sheetName) {
        currentSheetName = sheetName;
        headerMap.clear();
        currentRow.clear();
        currentRowNum = -1;
    }

    /** Writes the last partial batch; call once after all sheets are parsed. */
    void finish() {
        flushBatch();
    }

    @Override
    public void startRow(int rowNum) {
        currentRowNum = rowNum;
        currentRow.clear();
    }

    @Override
    public void cell(String cellReference, String formattedValue, XSSFComment comment) {
        if (formattedValue == null || formattedValue.isBlank()) {
            return;
        }
        String value = formattedValue.trim();
        int columnIndex = columnIndex(cellReference);
        if (currentRowNum == 0) {
            String column = value.toLowerCase().replace(" ", "_");
            if (SHEET_COLUMNS.contains(column)) {
                headerMap.put(columnIndex, column);
            }
        } else {
            String column = headerMap.get(columnIndex);
            if (column != null) {
                currentRow.put(column, value);
            }
        }
    }

    @Override
    public void endRow(int rowNum) {
        if (rowNum == 0) {
            return;
        }
        totalRows++;

        normalizedTextColumns.forEach(column ->
                currentRow.computeIfPresent(column, (name, value) -> CriteriaNormalizer.normalizeText(value)));

        String state = currentRow.get("state");
        String voterId = currentRow.get("voter_id");
        String serialNo = currentRow.get("serial_no");
        if (isEmpty(state) || isEmpty(voterId) || serialNo == null) {
            failedRows++;
            addIssue(rowNum, "voter_id", "MISSING_REQUIRED_FIELD", "Missing required identification field at row " + rowNum);
            return;
        }
        states.add(state);

        batch.add(buildRow(rowNum, state, voterId, serialNo));
        importedRows++;
        if (batch.size() >= BATCH_SIZE) {
            flushBatch();
        }
    }

    private Object[] buildRow(int rowNum, String state, String voterId, String serialNo) {
        // The record key identifies a voter across datasets; the overlap probe compares these hashes.
        String sourceKeyHash = CryptoUtils.sha256Hex(String.join("|",
                state,
                currentRow.getOrDefault("district", ""),
                currentRow.getOrDefault("ac_no", ""),
                currentRow.getOrDefault("part_no", ""),
                currentRow.getOrDefault("booth_no", ""),
                serialNo,
                voterId));

        List<Object> values = new ArrayList<>(VOTER_COLUMNS.size() + 5);
        values.add(datasetId);
        values.add(sourceKeyHash);
        for (String column : VOTER_COLUMNS) {
            String value = currentRow.get(column);
            values.add(INT_COLUMNS.contains(column) ? parseInteger(value) : value);
        }

        byte[] mobileEnc = null;
        Short mobileKeyVersion = null;
        String mobileNo = currentRow.get("mobile_no");
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
        return values.toArray();
    }

    private void flushBatch() {
        if (batch.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(insertSql, batch);
        batch.clear();
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

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
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
}

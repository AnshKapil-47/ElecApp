package com.elecsarthi.dataset;

import com.elecsarthi.dto.request.DatasetUploadRequest;
import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.ElectionTypeCode;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.service.DatasetImportService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The XLSX import loads voter rows with one PostgreSQL COPY per dataset. */
@SpringBootTest
@ActiveProfiles("test")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class DatasetCopyImportIntegrationTest {

    private static final String[] HEADER = {
            "state", "district", "ac_no", "part_no", "booth_no", "serial_no", "voter_id",
            "v_fname_en", "v_lname_en", "house_no", "age", "mobile_no"};

    @Autowired
    private DatasetImportService datasetImportService;
    @Autowired
    private DatasetRepository datasetRepository;
    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AppUser owner;

    @BeforeEach
    void cleanDatabaseAndCreateOwner() {
        jdbcTemplate.execute(
                "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; "
                        + "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, "
                        + "agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter CASCADE; "
                        + "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;");
        owner = userRepository.save(AppUser.builder()
                .username("copy_owner")
                .fullName("Copy Owner")
                .passwordHash("hashed")
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private DatasetDetailResponse importWorkbook(String fileName, byte[] bytes) {
        return datasetImportService.importDataset(
                new MockMultipartFile("file", fileName, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes),
                DatasetUploadRequest.builder()
                        .name(fileName)
                        .electionTypeCode(ElectionTypeCode.VIDHAN_SABHA)
                        .storageTier(DatasetTier.MASTER)
                        .build(),
                owner.getId());
    }

    private static byte[] workbook(Consumer<Workbook> content) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            content.accept(workbook);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void sheet(Workbook workbook, String name, String[] header, List<Object[]> rows) {
        Sheet sheet = workbook.createSheet(name);
        Row headerRow = sheet.createRow(0);
        for (int c = 0; c < header.length; c++) {
            headerRow.createCell(c).setCellValue(header[c]);
        }
        int r = 1;
        for (Object[] values : rows) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < values.length; c++) {
                if (values[c] instanceof Number number) {
                    row.createCell(c).setCellValue(number.doubleValue());
                } else if (values[c] != null) {
                    row.createCell(c).setCellValue(values[c].toString());
                }
            }
        }
    }

    private static Object[] voter(int serial, String firstName) {
        return new Object[]{"HARYANA", "GURGAON", 10, 101, 1, serial, "EPIC" + serial, firstName, "Kumar", "H-" + serial, 20 + serial % 60, "+9198000" + (10000 + serial % 90000)};
    }

    private boolean partitionExists(Long datasetId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT to_regclass('public.voter_d" + datasetId + "') IS NOT NULL", Boolean.class));
    }

    @Test
    @DisplayName("COPY import: a large workbook is streamed into its partition and every row is loaded")
    void largeWorkbookIsLoadedCompletely() throws IOException {
        int rowCount = 50_000;
        byte[] bytes = workbook(wb -> sheet(wb, "Voters", HEADER,
                java.util.stream.IntStream.rangeClosed(1, rowCount).mapToObj(i -> voter(i, "Voter" + i)).toList()));

        long startedAt = System.nanoTime();
        DatasetDetailResponse response = importWorkbook("large.xlsx", bytes);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        System.out.printf("BENCHMARK import of %d rows (%d KB xlsx) took %d ms%n", rowCount, bytes.length / 1024, elapsedMillis);

        assertEquals(DatasetStatus.IMPORTED, response.getStatus());
        assertEquals(rowCount, response.getTotalRows());
        assertEquals(rowCount, response.getImportedRows());
        assertEquals(0, response.getFailedRows());
        assertEquals(rowCount, jdbcTemplate.queryForObject("SELECT count(*) FROM voter_d" + response.getId(), Integer.class));
        assertEquals("Voter31337", jdbcTemplate.queryForObject(
                "SELECT v_fname_en FROM voter_d" + response.getId() + " WHERE serial_no = 31337", String.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'voter_d" + response.getId() + "' AND indexdef LIKE '%source_record_key_hash%'", Integer.class),
                "indexes are created after the load");
    }

    @Test
    @DisplayName("COPY import: tabs, newlines, backslashes, \\N, quotes and Unicode in cells are stored exactly as text")
    void specialCharactersAreStoredVerbatim() throws IOException {
        String[] names = {
                "Tab\tInside", "New\nLine", "Carriage\rReturn", "Back\\slash", "\\N", "\\.", "End\\",
                "Quote\"s and 'single'", "नमस्ते 😀 عربي", "x".repeat(5_000), "a,b;c|d"
        };
        byte[] bytes = workbook(wb -> {
            java.util.List<Object[]> rows = new java.util.ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                rows.add(voter(i + 1, names[i]));
            }
            sheet(wb, "Voters", HEADER, rows);
        });

        DatasetDetailResponse response = importWorkbook("special.xlsx", bytes);

        assertEquals(names.length, response.getImportedRows());
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], jdbcTemplate.queryForObject(
                    "SELECT v_fname_en FROM voter_d" + response.getId() + " WHERE serial_no = ?", String.class, i + 1),
                    "value " + i + " must round-trip unchanged");
        }
    }

    @Test
    @DisplayName("COPY import: absent and non-numeric cells become NULL, not empty text or zero")
    void missingAndInvalidValuesBecomeNull() throws IOException {
        String[] header = {"state", "serial_no", "voter_id", "age", "ac_no", "v_fname_en"};
        byte[] bytes = workbook(wb -> sheet(wb, "Voters", header, List.<Object[]>of(
                new Object[]{"HARYANA", 1, "EPIC1", "not-a-number", null, null},
                new Object[]{"HARYANA", 2, "EPIC2", 34.0, 12, "Asha"})));

        DatasetDetailResponse response = importWorkbook("nulls.xlsx", bytes);
        String table = "voter_d" + response.getId();

        assertEquals(2, response.getImportedRows());
        Map<String, Object> first = jdbcTemplate.queryForMap("SELECT age, ac_no, v_fname_en, district, mobile_no_enc FROM " + table + " WHERE serial_no = 1");
        assertNull(first.get("age"));
        assertNull(first.get("ac_no"));
        assertNull(first.get("v_fname_en"));
        assertNull(first.get("district"));
        assertNull(first.get("mobile_no_enc"));
        Map<String, Object> second = jdbcTemplate.queryForMap("SELECT age, ac_no, v_fname_en FROM " + table + " WHERE serial_no = 2");
        assertEquals(34, second.get("age"));
        assertEquals(12, second.get("ac_no"));
        assertEquals("Asha", second.get("v_fname_en"));
    }

    @Test
    @DisplayName("COPY import: every sheet is loaded, whatever its column order, and rejected rows are reported with their sheet")
    void multipleSheetsAreLoadedAndIssuesNameTheSheet() throws IOException {
        String[] shuffled = {"voter_id", "serial_no", "state", "v_fname_en", "district"};
        byte[] bytes = workbook(wb -> {
            sheet(wb, "First", HEADER, List.<Object[]>of(voter(1, "One"), voter(2, "Two")));
            sheet(wb, "Second", shuffled, List.<Object[]>of(
                    new Object[]{"EPIC3", 3, "HARYANA", "Three", "HISAR"},
                    new Object[]{null, 4, "HARYANA", "NoVoterId", "HISAR"}));
            sheet(wb, "Third", HEADER, List.<Object[]>of(voter(5, "Five")));
        });

        DatasetDetailResponse response = importWorkbook("sheets.xlsx", bytes);

        assertEquals(4, response.getImportedRows());
        assertEquals(1, response.getFailedRows());
        assertEquals(5, response.getTotalRows());
        assertEquals(1, response.getIssues().size());
        assertEquals("Sheet2", response.getIssues().get(0).getSheet());
        assertEquals("HISAR", jdbcTemplate.queryForObject(
                "SELECT district FROM voter_d" + response.getId() + " WHERE serial_no = 3", String.class));
        assertEquals(4, jdbcTemplate.queryForObject("SELECT count(*) FROM voter_d" + response.getId(), Integer.class));
    }

    @Test
    @DisplayName("COPY import: a failure part-way through loads nothing, marks the dataset FAILED and drops its partition")
    void failedCopyRollsBackCompletely() throws IOException {
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 3_000; i++) {
            rows.add(voter(i, "Voter" + i));
        }
        rows.add(voter(1_500, "DuplicateOf1500")); // same record key twice: the unique key rejects it during COPY
        byte[] bytes = workbook(wb -> sheet(wb, "Voters", HEADER, rows));

        ApiException failure = assertThrows(ApiException.class, () -> importWorkbook("duplicate.xlsx", bytes));

        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, failure.getErrorCode());
        assertEquals("Dataset import failed.", failure.getMessage(), "no database detail is returned to the client");
        List<Dataset> datasets = datasetRepository.findAll();
        assertEquals(1, datasets.size());
        assertEquals(DatasetStatus.FAILED, datasets.get(0).getStatus());
        assertFalse(partitionExists(datasets.get(0).getId()), "the partition must be dropped");
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM voter", Integer.class), "no row of the failed load remains");
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM import_issue", Integer.class));

        // The failed file does not lock itself out: a corrected file imports normally.
        rows.remove(rows.size() - 1);
        DatasetDetailResponse retried = importWorkbook("corrected.xlsx", workbook(wb -> sheet(wb, "Voters", HEADER, rows)));
        assertEquals(DatasetStatus.IMPORTED, retried.getStatus());
        assertEquals(3_000, retried.getImportedRows());
        assertTrue(partitionExists(retried.getId()));
        assertNotNull(retried.getId());
    }
}

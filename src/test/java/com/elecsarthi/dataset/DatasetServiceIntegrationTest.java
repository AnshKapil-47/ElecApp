package com.elecsarthi.dataset;

import com.elecsarthi.dto.request.DatasetUploadRequest;
import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.dto.response.ElectionTypeFieldResponse;
import com.elecsarthi.dto.response.ElectionTypeResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.ElectionTypeField;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.ElectionTypeCode;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.FieldUsage;
import com.elecsarthi.enums.FieldValueType;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeFieldRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.service.AreaResolver;
import com.elecsarthi.service.CriteriaNormalizer;
import com.elecsarthi.service.DatasetImportService;
import com.elecsarthi.service.DatasetService;
import com.elecsarthi.service.ElectionTypeService;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DatasetServiceIntegrationTest {

    @Autowired
    private ElectionTypeService electionTypeService;

    @Autowired
    private ElectionTypeRepository electionTypeRepository;

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private DatasetImportService datasetImportService;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private EntitlementRepository entitlementRepository;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private CriteriaNormalizer criteriaNormalizer;

    @Autowired
    private AreaResolver areaResolver;

    @Autowired
    private ElectionTypeFieldRepository electionTypeFieldRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AppUser owner;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
            "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; " +
            "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter RESTART IDENTITY CASCADE; ALTER SEQUENCE voter_id_seq RESTART WITH 1; " +
            "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;"
        );

        owner = AppUser.builder()
                .username("test_owner")
                .fullName("System Owner")
                .passwordHash("hashed")
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build();
        owner = userRepository.save(owner);
    }

    @Test
    @DisplayName("Election types: Verify all 8 seeded types and field definitions")
    void testElectionTypesAndFields() {
        List<ElectionTypeResponse> types = electionTypeService.getAllElectionTypes();
        assertEquals(8, types.size(), "All 8 election types must be present");

        List<ElectionTypeFieldResponse> vsFields = electionTypeService.getFields("VIDHAN_SABHA");
        assertFalse(vsFields.isEmpty());

        boolean hasStateFilter = vsFields.stream()
                .anyMatch(f -> "state".equals(f.getSourceColumn()) && f.getUsage() == com.elecsarthi.enums.FieldUsage.FILTER);
        boolean hasAcNoFilter = vsFields.stream()
                .anyMatch(f -> "ac_no".equals(f.getSourceColumn()) && f.getUsage() == com.elecsarthi.enums.FieldUsage.FILTER);

        assertTrue(hasStateFilter, "Vidhan Sabha must have state filter");
        assertTrue(hasAcNoFilter, "Vidhan Sabha must have ac_no filter");
    }

    @Test
    @DisplayName("CriteriaNormalizer: RFC 8785 canonical JSON formatting and hash calculation")
    void testCriteriaNormalization() {
        ElectionType vs = electionTypeRepository.findByCode(ElectionTypeCode.VIDHAN_SABHA).orElseThrow();

        Map<String, Object> input = new HashMap<>();
        input.put("state", "  haryana  ");
        input.put("ac_no", "10");

        CriteriaNormalizer.NormalizedCriteria norm = criteriaNormalizer.normalize(vs, input);

        assertEquals("{\"ac_no\":10,\"state\":\"HARYANA\"}", norm.getCanonicalJson(),
                "Keys must be sorted lexicographically and values canonicalized");
        assertNotNull(norm.getCriteriaHash());
        assertEquals(64, norm.getCriteriaHash().length());
        assertTrue(norm.getScopeLabel().contains("HARYANA"));
        assertTrue(norm.getScopeLabel().contains("AC 10"));
    }

    @Test
    @DisplayName("CriteriaNormalizer: Rejects missing required filter fields")
    void testCriteriaMissingRequiredField() {
        ElectionType vs = electionTypeRepository.findByCode(ElectionTypeCode.VIDHAN_SABHA).orElseThrow();

        Map<String, Object> input = new HashMap<>();
        input.put("state", "HARYANA");
        // Missing ac_no

        ApiException ex = assertThrows(ApiException.class, () -> criteriaNormalizer.normalize(vs, input));
        assertEquals(400, ex.getStatus().value());
    }

    @Test
    @DisplayName("Dataset streaming import: Ingests valid .xlsx, builds partitions and indexes, rejects duplicates")
    void testDatasetImportAndDuplicateRejection() throws IOException {
        byte[] xlsxBytes = createSampleVoterXlsx(5);

        MockMultipartFile file1 = new MockMultipartFile("file", "voters.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxBytes);

        DatasetUploadRequest req = DatasetUploadRequest.builder()
                .name("Haryana Master AC10")
                .storageTier(DatasetTier.MASTER)
                .build();

        DatasetDetailResponse resp = datasetImportService.importDataset(file1, req, owner.getId());

        assertNotNull(resp);
        assertNotNull(resp.getId());
        assertEquals(DatasetStatus.IMPORTED, resp.getStatus());
        assertEquals(5, resp.getImportedRows());

        // Verify rows in partitioned table
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM voter_d" + resp.getId(), Integer.class);
        assertEquals(5, count);

        // Attempting to upload the exact same file must fail with 409 FILE_ALREADY_EXISTS
        MockMultipartFile file2 = new MockMultipartFile("file", "voters_copy.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxBytes);

        ConflictException confEx = assertThrows(ConflictException.class, () ->
                datasetImportService.importDataset(file2, req, owner.getId()));
        assertEquals(409, confEx.getStatus().value());
    }

    @Test
    @DisplayName("Dataset deletion: Drops partitions and cleans up")
    void testDatasetDeletion() throws IOException {
        byte[] xlsxBytes = createSampleVoterXlsx(3);
        MockMultipartFile file = new MockMultipartFile("file", "voters_del.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxBytes);

        DatasetUploadRequest req = DatasetUploadRequest.builder()
                .name("To Delete")
                .storageTier(DatasetTier.MASTER)
                .build();

        DatasetDetailResponse resp = datasetImportService.importDataset(file, req, owner.getId());
        Long datasetId = resp.getId();

        // Delete dataset
        datasetService.deleteDataset(datasetId, owner.getId());

        // Verify dataset is deleted from DB
        assertFalse(datasetRepository.findById(datasetId).isPresent());

        // Verify partition table is dropped
        Boolean tableExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'voter_d" + datasetId + "')",
                Boolean.class);
        assertFalse(tableExists != null && tableExists, "Partition table voter_d" + datasetId + " must be dropped");
    }

    @Test
    @DisplayName("Dataset deletion: Rejects deletion if active entitlement exists")
    void testDatasetDeletionRejectedWithActiveEntitlement() throws IOException {
        byte[] xlsxBytes = createSampleVoterXlsx(2);
        MockMultipartFile file = new MockMultipartFile("file", "voters_ent.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxBytes);

        DatasetUploadRequest req = DatasetUploadRequest.builder()
                .name("With Entitlement")
                .storageTier(DatasetTier.MASTER)
                .build();

        DatasetDetailResponse resp = datasetImportService.importDataset(file, req, owner.getId());
        Long datasetId = resp.getId();

        CustomerAccount customer = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Acme Corp")
                .contactPhone("+919811122233")
                .createdByUserId(owner.getId())
                .status(UserStatus.ACTIVE)
                .build());

        Instant now = Instant.now();
        entitlementRepository.save(Entitlement.builder()
                .customerAccountId(customer.getId())
                .electionTypeId((short) 2)
                .datasetId(datasetId)
                .storageTier(DatasetTier.MASTER)
                .criteria("{\"ac_no\":10,\"state\":\"HARYANA\"}")
                .criteriaHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .scopeLabel("HARYANA AC 10")
                .source(EntitlementSource.DIRECT)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now.minusSeconds(3600))
                .validUntil(now.plusSeconds(86400 * 30))
                .createdByUserId(owner.getId())
                .createdAt(now)
                .build());

        ConflictException ex = assertThrows(ConflictException.class, () ->
                datasetService.deleteDataset(datasetId, owner.getId()));
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    @DisplayName("Dataset deletion: Rejects deletion if dataset is already DELETING")
    void testDatasetDeletionWhenAlreadyDeleting() {
        Dataset dataset = datasetRepository.save(Dataset.builder()
                .name("Already Deleting")
                .originalFileName("deleting.xlsx")
                .fileSha256("abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd")
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.DELETING)
                .deleteAfter(Instant.now().plus(60, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .createdAt(Instant.now())
                .build());

        ConflictException ex = assertThrows(ConflictException.class, () ->
                datasetService.deleteDataset(dataset.getId(), owner.getId()));
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    @DisplayName("Dataset deletion: Handles safely when partition table is already missing")
    void testDatasetDeletionWithMissingPartitions() {
        Dataset dataset = datasetRepository.save(Dataset.builder()
                .name("Missing Partitions")
                .originalFileName("missing.xlsx")
                .fileSha256("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef")
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .deleteAfter(Instant.now().plus(60, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .createdAt(Instant.now())
                .build());

        assertDoesNotThrow(() -> datasetService.deleteDataset(dataset.getId(), owner.getId()));
        assertFalse(datasetRepository.findById(dataset.getId()).isPresent());
    }

    private byte[] createSampleVoterXlsx(int numRows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            XSSFSheet sheet = workbook.createSheet("Voters");
            XSSFRow header = sheet.createRow(0);

            String[] columns = new String[]{
                    "state", "district", "ac_no", "part_no", "booth_no",
                    "serial_no", "voter_id", "v_fname_en", "v_lname_en", "house_no", "mobile_no"
            };

            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }

            for (int r = 1; r <= numRows; r++) {
                XSSFRow row = sheet.createRow(r);
                row.createCell(0).setCellValue("HARYANA");
                row.createCell(1).setCellValue("GURGAON");
                row.createCell(2).setCellValue(10);
                row.createCell(3).setCellValue(101);
                row.createCell(4).setCellValue(1);
                row.createCell(5).setCellValue(r);
                row.createCell(6).setCellValue("EPIC" + r);
                row.createCell(7).setCellValue("Voter" + r);
                row.createCell(8).setCellValue("Kumar");
                row.createCell(9).setCellValue("H-" + r);
                row.createCell(10).setCellValue("+9198000000" + r);
            }

            workbook.write(bos);
            return bos.toByteArray();
        }
    }

    @Test
    @DisplayName("Dataset import is election-type agnostic: one upload (no election type) resolves under every one of the 8 election types")
    void testUploadWithoutElectionTypeResolvesUnderEveryElectionType() throws IOException {
        List<ElectionType> types = electionTypeRepository.findAll();
        assertEquals(8, types.size(), "All 8 election types must be seeded");
        assertEquals(java.util.EnumSet.allOf(ElectionTypeCode.class),
                types.stream().map(ElectionType::getCode).collect(java.util.stream.Collectors.toCollection(
                        () -> java.util.EnumSet.noneOf(ElectionTypeCode.class))),
                "Every ElectionTypeCode must be seeded");

        // Everything below is derived from election_type_field / election_type: nothing is hard-coded per type.
        Map<Short, List<ElectionTypeField>> filtersByType = new HashMap<>();
        Map<String, FieldValueType> columnTypes = new LinkedHashMap<>();
        Set<String> guardColumns = new LinkedHashSet<>();
        for (ElectionType type : types) {
            List<ElectionTypeField> filters = electionTypeFieldRepository
                    .findByElectionTypeIdAndUsageOrderByFilterOrderAsc(type.getId(), FieldUsage.FILTER);
            assertFalse(filters.isEmpty(), type.getCode() + " must define FILTER fields");
            filtersByType.put(type.getId(), filters);
            filters.forEach(f -> columnTypes.put(f.getSourceColumn(), f.getValueType()));
            if (type.getGuardColumn() != null) {
                guardColumns.add(type.getGuardColumn());
                columnTypes.putIfAbsent(type.getGuardColumn(), FieldValueType.TEXT);
            }
        }

        // One workbook carrying every column any type filters or guards on. Three voters sit inside the area
        // that every type's criteria describe; a fourth differs in every filter column (guards stay constant).
        List<String> header = new ArrayList<>(columnTypes.keySet());
        header.add("serial_no");
        header.add("voter_id");
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("Voters");
            XSSFRow headerRow = sheet.createRow(0);
            for (int i = 0; i < header.size(); i++) {
                headerRow.createCell(i).setCellValue(header.get(i));
            }
            for (int r = 1; r <= 4; r++) {
                boolean inside = r <= 3;
                XSSFRow row = sheet.createRow(r);
                for (int c = 0; c < header.size(); c++) {
                    String column = header.get(c);
                    if ("serial_no".equals(column)) {
                        row.createCell(c).setCellValue(r);
                    } else if ("voter_id".equals(column)) {
                        row.createCell(c).setCellValue("EPICALL" + r);
                    } else if (columnTypes.get(column) == FieldValueType.INT) {
                        row.createCell(c).setCellValue(inside ? 7 : 8);
                    } else if (guardColumns.contains(column)) {
                        row.createCell(c).setCellValue("GUARD ONE");
                    } else {
                        row.createCell(c).setCellValue(inside ? "AREA " + column : "OTHER " + column);
                    }
                }
            }
            workbook.write(bos);
            bytes = bos.toByteArray();
        }

        // the upload metadata carries no election type at all
        DatasetDetailResponse imported = datasetImportService.importDataset(
                xlsx("agnostic.xlsx", bytes), masterRequest("Agnostic", null), owner.getId());
        assertEquals(DatasetStatus.IMPORTED, imported.getStatus());
        assertEquals(4, imported.getImportedRows());

        // the sale picks the lens: each type resolves to the same dataset and the same three voters
        for (ElectionType type : types) {
            Map<String, Object> criteria = new HashMap<>();
            // the sale form's cascade: each FILTER field in filterOrder, narrowed by the values already chosen
            Map<String, String> chosen = new LinkedHashMap<>();
            for (ElectionTypeField filter : filtersByType.get(type.getId())) {
                String column = filter.getSourceColumn();
                String shown; // what the dropdown offers (the importer upper-cases text columns)
                if (filter.getValueType() == FieldValueType.INT) {
                    criteria.put(column, 7);
                    shown = "7";
                } else if (guardColumns.contains(column)) {
                    criteria.put(column, "GUARD ONE");
                    shown = "GUARD ONE";
                } else {
                    criteria.put(column, "AREA " + column);
                    shown = ("AREA " + column).toUpperCase(Locale.ROOT);
                }
                List<String> offered = electionTypeService
                        .getCascadingFilterOptions(type.getCode().name(), DatasetTier.MASTER, column, new HashMap<>(chosen))
                        .getValues().stream().map(String::valueOf).toList();
                assertTrue(offered.contains(shown), type.getCode() + " dropdown for " + column + " must offer the imported value");
                if (!chosen.isEmpty()) {
                    assertEquals(List.of(shown), offered, type.getCode() + " dropdown for " + column + " must narrow to the chosen area");
                }
                chosen.put(column, shown);
            }
            CriteriaNormalizer.NormalizedCriteria area = criteriaNormalizer.normalize(type, criteria);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Dataset resolved = areaResolver.resolveDataset(DatasetTier.MASTER, type, area);
                assertEquals(imported.getId(), resolved.getId(), type.getCode() + " must resolve to the uploaded dataset");
                assertEquals(3, areaResolver.countVoters(resolved.getId(), area), type.getCode() + " must see only its area");
            });
        }

        // Guard types (election_type.guard_column set): one more voter inside the same area carries a second
        // guard value, so every guard type must now refuse the area as ambiguous.
        Map<String, Object> extra = new LinkedHashMap<>();
        for (String column : columnTypes.keySet()) {
            extra.put(column, guardColumns.contains(column) ? "GUARD TWO"
                    : columnTypes.get(column) == FieldValueType.INT ? (Object) 7 : "AREA " + column.toUpperCase(Locale.ROOT));
        }
        extra.put("serial_no", 99);
        extra.put("voter_id", "EPICALL99");
        extra.put("source_record_key_hash", "9".repeat(64));
        jdbcTemplate.update(
                "INSERT INTO voter (dataset_id, mobile_no_enc, " + String.join(", ", extra.keySet()) + ") VALUES (?, 'enc', "
                        + "?, ".repeat(extra.size() - 1) + "?)",
                java.util.stream.Stream.concat(java.util.stream.Stream.of((Object) imported.getId()), extra.values().stream()).toArray());

        List<ElectionType> guardTypes = types.stream().filter(t -> t.getGuardColumn() != null).toList();
        assertFalse(guardTypes.isEmpty(), "Some election types must define a guard column");
        for (ElectionType type : guardTypes) {
            Map<String, Object> criteria = new HashMap<>();
            for (ElectionTypeField filter : filtersByType.get(type.getId())) {
                String column = filter.getSourceColumn();
                criteria.put(column, filter.getValueType() == FieldValueType.INT ? (Object) 7 : "AREA " + column);
            }
            CriteriaNormalizer.NormalizedCriteria area = criteriaNormalizer.normalize(type, criteria);
            ApiException refused = assertThrows(ApiException.class, () ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                            areaResolver.resolveDataset(DatasetTier.MASTER, type, area)),
                    type.getCode() + " must refuse an area whose voters carry two guard values");
            assertEquals(com.elecsarthi.exception.errorcode.ErrorCode.AMBIGUOUS_AREA, refused.getErrorCode());
        }
    }

    @Test
    @DisplayName("Dataset list shows the states each dataset covers")
    void testDatasetListShowsStates() throws IOException {
        DatasetDetailResponse imported = datasetImportService.importDataset(
                xlsx("states.xlsx", createSampleVoterXlsx(2)), masterRequest("Haryana List", null), owner.getId());

        var page = datasetService.listDatasets(org.springframework.data.domain.PageRequest.of(0, 10));
        var summary = page.getContent().stream().filter(s -> s.getId().equals(imported.getId())).findFirst().orElseThrow();
        assertEquals(List.of("HARYANA"), summary.getStates());
    }

    private DatasetUploadRequest masterRequest(String name, Long supersedes) {
        return DatasetUploadRequest.builder()
                .name(name)
                .storageTier(DatasetTier.MASTER)
                .supersedesDatasetId(supersedes)
                .build();
    }

    private MockMultipartFile xlsx(String fileName, byte[] bytes) {
        return new MockMultipartFile("file", fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private boolean partitionExists(Long datasetId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'voter_d" + datasetId + "')",
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Test
    @DisplayName("Dataset import: overlapping records in the same tier are rejected and the failed dataset is cleaned up")
    void testOverlappingImportRejectedAndCleanedUp() throws IOException {
        datasetImportService.importDataset(xlsx("first.xlsx", createSampleVoterXlsx(3)), masterRequest("First", null), owner.getId());

        ConflictException ex = assertThrows(ConflictException.class, () ->
                datasetImportService.importDataset(xlsx("second.xlsx", createSampleVoterXlsx(4)), masterRequest("Second", null), owner.getId()));
        assertEquals(com.elecsarthi.exception.errorcode.ErrorCode.DATASET_OVERLAP, ex.getErrorCode());

        Dataset failed = datasetRepository.findAll().stream()
                .filter(d -> d.getStatus() == DatasetStatus.FAILED).findFirst().orElseThrow();
        assertFalse(partitionExists(failed.getId()), "Partitions of a failed import must be dropped");
    }

    @Test
    @DisplayName("Dataset import: a correction supersedes the old dataset even though their records overlap")
    void testSupersedingImportMarksOldDatasetSuperseded() throws IOException {
        DatasetDetailResponse old = datasetImportService.importDataset(
                xlsx("old.xlsx", createSampleVoterXlsx(3)), masterRequest("Old", null), owner.getId());

        DatasetDetailResponse corrected = datasetImportService.importDataset(
                xlsx("corrected.xlsx", createSampleVoterXlsx(4)), masterRequest("Corrected", old.getId()), owner.getId());

        assertEquals(DatasetStatus.IMPORTED, corrected.getStatus());
        assertEquals(old.getId(), corrected.getSupersedesDatasetId());
        Dataset reloaded = datasetRepository.findById(old.getId()).orElseThrow();
        assertEquals(DatasetStatus.SUPERSEDED, reloaded.getStatus());
        assertNotNull(reloaded.getSupersededAt());
    }

    @Test
    @DisplayName("Dataset import: only .xlsx files are accepted and nothing is persisted otherwise")
    void testNonXlsxRejected() {
        MockMultipartFile csv = new MockMultipartFile("file", "voters.csv", "text/csv", "a,b".getBytes());

        assertThrows(com.elecsarthi.exception.common.BadRequestException.class, () ->
                datasetImportService.importDataset(csv, masterRequest("Csv", null), owner.getId()));
        assertEquals(0, datasetRepository.count());
    }

    @Test
    @DisplayName("Dataset import: rows without voter_id are still imported and recorded as issues")
    void testRowMissingRequiredFieldRecordedAsIssue() throws IOException {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("Voters");
            String[] header = {"state", "ac_no", "serial_no", "voter_id"};
            XSSFRow headerRow = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) {
                headerRow.createCell(i).setCellValue(header[i]);
            }
            XSSFRow valid = sheet.createRow(1);
            valid.createCell(0).setCellValue("HARYANA");
            valid.createCell(1).setCellValue(10);
            valid.createCell(2).setCellValue(1);
            valid.createCell(3).setCellValue("EPIC1");
            XSSFRow missingVoterId = sheet.createRow(2);
            missingVoterId.createCell(0).setCellValue("HARYANA");
            missingVoterId.createCell(1).setCellValue(10);
            missingVoterId.createCell(2).setCellValue(2);
            workbook.write(bos);
            bytes = bos.toByteArray();
        }

        DatasetDetailResponse resp = datasetImportService.importDataset(
                xlsx("partial.xlsx", bytes), masterRequest("Partial", null), owner.getId());

        assertEquals(2, resp.getImportedRows());
        assertEquals(0, resp.getFailedRows());
        assertEquals(1, resp.getIssues().size());
        assertEquals("MISSING_FIELD", resp.getIssues().get(0).getIssueCode());
        assertEquals("voter_id", resp.getIssues().get(0).getColumnName());
    }
}

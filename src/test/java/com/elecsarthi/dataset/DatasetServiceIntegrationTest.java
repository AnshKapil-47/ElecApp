package com.elecsarthi.dataset;

import com.elecsarthi.dto.request.DatasetUploadRequest;
import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.dto.response.ElectionTypeFieldResponse;
import com.elecsarthi.dto.response.ElectionTypeResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.ElectionTypeCode;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private JdbcTemplate jdbcTemplate;

    private AppUser owner;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
            "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; " +
            "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter CASCADE; " +
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
                .electionTypeCode(ElectionTypeCode.VIDHAN_SABHA)
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
                .electionTypeCode(ElectionTypeCode.VIDHAN_SABHA)
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
                .electionTypeCode(ElectionTypeCode.VIDHAN_SABHA)
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
}

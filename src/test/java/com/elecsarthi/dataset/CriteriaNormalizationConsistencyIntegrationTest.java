package com.elecsarthi.dataset;

import com.elecsarthi.dto.request.DatasetUploadRequest;
import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.ElectionTypeCode;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.service.AreaResolver;
import com.elecsarthi.service.CriteriaNormalizer;
import com.elecsarthi.service.CriteriaNormalizer.NormalizedCriteria;
import com.elecsarthi.service.DatasetImportService;
import com.elecsarthi.exception.errorcode.ErrorCode;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A value stored by the import and the same value requested in a sale must normalize to the same text, or the area
 * silently comes up empty. Integer criteria must be exact, so a request can never be rounded into another area.
 */
@SpringBootTest
@ActiveProfiles("test")
class CriteriaNormalizationConsistencyIntegrationTest {

    @Autowired
    private CriteriaNormalizer normalizer;
    @Autowired
    private ElectionTypeRepository electionTypeRepository;
    @Autowired
    private DatasetImportService datasetImportService;
    @Autowired
    private AreaResolver areaResolver;
    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ElectionType vidhanSabha;
    private Locale originalLocale;

    @BeforeEach
    void setUp() {
        originalLocale = Locale.getDefault();
        jdbcTemplate.execute(
                "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; "
                        + "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, "
                        + "agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter CASCADE; "
                        + "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;");
        vidhanSabha = electionTypeRepository.findByCode(ElectionTypeCode.VIDHAN_SABHA).orElseThrow();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    private Map<String, Object> criteria(Object state, Object acNo) {
        Map<String, Object> input = new HashMap<>();
        input.put("state", state);
        input.put("ac_no", acNo);
        return input;
    }

    @Test
    @DisplayName("Text normalization: Unicode whitespace collapses, accents compose, case folds, joiners survive")
    void textNormalizationHandlesUnicode() {
        assertEquals("NEW DELHI", CriteriaNormalizer.normalizeText("  new  delhi \t\n"));
        assertEquals("NEW DELHI", CriteriaNormalizer.normalizeText("new delhi "));
        assertEquals("ÉCOLE", CriteriaNormalizer.normalizeText("école"), "NFD and NFC spellings are the same value");
        assertEquals("", CriteriaNormalizer.normalizeText("   \t "));
        assertEquals("", CriteriaNormalizer.normalizeText(null));
        String withJoiner = "क्‍ष";
        assertEquals(withJoiner, CriteriaNormalizer.normalizeText(withJoiner), "a zero-width joiner is part of the Indic text, not whitespace");
    }

    @Test
    @DisplayName("Case mapping does not depend on the server's default locale")
    void normalizationIgnoresDefaultLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        assertEquals("ISTANBUL DISTRICT", CriteriaNormalizer.normalizeText("istanbul district"));
        NormalizedCriteria area = normalizer.normalize(vidhanSabha, Map.of("STATE", "kerala", "AC_NO", 7));
        assertEquals("{\"ac_no\":7,\"state\":\"KERALA\"}", area.getCanonicalJson());
    }

    @Test
    @DisplayName("Integer criteria must be exact, positive and in range; nothing is rounded or truncated")
    void integerCriteriaAreExact() {
        assertEquals(12L, normalizer.normalize(vidhanSabha, criteria("kerala", 12)).getCriteriaMap().get("ac_no"));
        assertEquals(12L, normalizer.normalize(vidhanSabha, criteria("kerala", " 12 ")).getCriteriaMap().get("ac_no"));
        assertEquals(12L, normalizer.normalize(vidhanSabha, criteria("kerala", 12.0d)).getCriteriaMap().get("ac_no"));

        for (Object bad : new Object[]{12.9d, 12.5f, "12.0", "12.9", "abc", "", 0, -3, 2_147_483_648L, 1e30d}) {
            assertThrows(BadRequestException.class, () -> normalizer.normalize(vidhanSabha, criteria("kerala", bad)),
                    "must be rejected: " + bad);
        }
    }

    @Test
    @DisplayName("Criteria fail closed: unknown, missing, blank and null fields are rejected")
    void criteriaFailClosed() {
        assertThrows(BadRequestException.class, () -> normalizer.normalize(vidhanSabha, Map.of()));
        assertThrows(BadRequestException.class, () -> normalizer.normalize(vidhanSabha, Map.of("state", "kerala")));
        assertThrows(BadRequestException.class, () -> normalizer.normalize(vidhanSabha, Map.of("ac_no", 7)));
        assertThrows(BadRequestException.class, () -> normalizer.normalize(vidhanSabha, criteria("     ", 7)));
        assertThrows(BadRequestException.class, () -> normalizer.normalize(vidhanSabha, criteria(null, 7)));
        Map<String, Object> withUnknownColumn = criteria("kerala", 7);
        withUnknownColumn.put("voter_id; DROP TABLE voter", "x");
        assertThrows(BadRequestException.class, () -> normalizer.normalize(vidhanSabha, withUnknownColumn));
        Map<String, Object> notAFilterOfThisType = criteria("kerala", 7);
        notAFilterOfThisType.put("mobile_no", "9999999999");
        assertThrows(BadRequestException.class, () -> normalizer.normalize(vidhanSabha, notAFilterOfThisType));
    }

    @Test
    @DisplayName("An imported sheet and a differently spelled request resolve to the same dataset")
    void importedAndRequestedSpellingsMatch() throws IOException {
        AppUser owner = userRepository.save(AppUser.builder()
                .username("norm_owner").fullName("Norm Owner").passwordHash("hashed").role(Role.OWNER).status(UserStatus.ACTIVE).build());
        byte[] workbook;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Voters");
            String[] header = {"state", "ac_no", "serial_no", "voter_id"};
            XSSFRow headerRow = sheet.createRow(0);
            for (int c = 0; c < header.length; c++) {
                headerRow.createCell(c).setCellValue(header[c]);
            }
            XSSFRow row = sheet.createRow(1);
            row.createCell(0).setCellValue("  Québec  north \t");
            row.createCell(1).setCellValue(7);
            row.createCell(2).setCellValue(1);
            row.createCell(3).setCellValue("epic 1");
            wb.write(out);
            workbook = out.toByteArray();
        }
        DatasetDetailResponse imported = datasetImportService.importDataset(
                new MockMultipartFile("file", "norm.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook),
                DatasetUploadRequest.builder().name("Norm").electionTypeCode(ElectionTypeCode.VIDHAN_SABHA).storageTier(DatasetTier.MASTER).build(),
                owner.getId());

        assertEquals("QUÉBEC NORTH", jdbcTemplate.queryForObject(
                "SELECT state FROM voter_d" + imported.getId() + " WHERE serial_no = 1", String.class));

        for (Object requestedState : new Object[]{"québec north", "QUÉBEC NORTH", "  Québec   North  "}) {
            NormalizedCriteria area = normalizer.normalize(vidhanSabha, criteria(requestedState, "7"));
            Dataset resolved = transactionTemplate.execute(status -> areaResolver.resolveDataset(DatasetTier.MASTER, vidhanSabha, area));
            assertEquals(imported.getId(), resolved.getId(), "the request must reach the imported voters: " + requestedState);
        }

        NormalizedCriteria otherArea = normalizer.normalize(vidhanSabha, criteria("québec north", 8));
        ApiException missing = assertThrows(ApiException.class,
                () -> transactionTemplate.execute(status -> areaResolver.resolveDataset(DatasetTier.MASTER, vidhanSabha, otherArea)));
        assertEquals(ErrorCode.AREA_UNAVAILABLE, missing.getErrorCode());
    }
}

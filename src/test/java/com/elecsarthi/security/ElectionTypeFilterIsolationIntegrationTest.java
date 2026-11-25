package com.elecsarthi.security;

import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent audit batch 3: the sale-form filter endpoint must offer only areas that can really be sold through the chosen
 * election type and storage tier. The data mirrors the audit datasets 113 (Vidhan Sabha, MADHYA PRADESH, AC 201) and
 * 132 (Panchayat, CHHATTISGARH, panchayat 501), imported through the real XLSX pipeline.
 */
class ElectionTypeFilterIsolationIntegrationTest extends SecurityIntegrationTestSupport {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String[] VIDHAN_HEADER = {"state", "ac_no", "part_no", "serial_no", "voter_id"};
    private static final String[] PANCHAYAT_HEADER = {"state", "district", "panchayat_no", "booth_no", "serial_no", "voter_id"};
    private static final String[] HYBRID_HEADER = {"state", "district", "ac_no", "panchayat_no", "part_no", "booth_no", "serial_no", "voter_id"};

    @Autowired private AppUserRepository userRepository;
    @Autowired private CustomerAccountRepository customerAccountRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private String ownerToken;
    private String subownerToken;
    private String agentToken;
    private String customerToken;
    private String subuserToken;

    @BeforeEach
    void setUpFixture() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        AppUser owner = user("fi_owner", Role.OWNER, null);
        AppUser subowner = user("fi_subowner", Role.SUBOWNER, null);
        AppUser agent = user("fi_agent", Role.AGENT, null);
        CustomerAccount account = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Filter Isolation Customer").contactPhone("+919123456740")
                .status(UserStatus.ACTIVE).createdByUserId(owner.getId()).build());
        AppUser customer = user("fi_customer", Role.CUSTOMER, account.getId());
        AppUser subuser = user("fi_subuser", Role.SUBUSER, account.getId());
        ownerToken = createActiveSessionAndToken(owner, ClientType.WEB, null);
        subownerToken = createActiveSessionAndToken(subowner, ClientType.WEB, null);
        agentToken = createActiveSessionAndToken(agent, ClientType.MOBILE, createActiveDevice(agent, null, "fi-agent").getId());
        customerToken = createActiveSessionAndToken(customer, ClientType.MOBILE, createActiveDevice(customer, account.getId(), "fi-cust").getId());
        subuserToken = createActiveSessionAndToken(subuser, ClientType.MOBILE, createActiveDevice(subuser, account.getId(), "fi-sub").getId());

        // MASTER Vidhan Sabha (like dataset 113): no panchayat_no
        importAs(ownerToken, "vs.xlsx", workbook(VIDHAN_HEADER, List.<Object[]>of(
                new Object[] {"MADHYA PRADESH", 201, 1, 1, "VSA0001"}, new Object[] {"MADHYA PRADESH", 201, 2, 2, "VSA0002"})),
                "VS_MASTER", "VIDHAN_SABHA", "MASTER");
        // MASTER Panchayat (like dataset 132): no ac_no
        importAs(subownerToken, "pan.xlsx", workbook(PANCHAYAT_HEADER, List.<Object[]>of(
                new Object[] {"CHHATTISGARH", "AUDIT SUB DISTRICT", 501, 1, 1, "PAN0001"}, new Object[] {"CHHATTISGARH", "AUDIT SUB DISTRICT", 501, 2, 2, "PAN0002"})),
                "PAN_MASTER", "PANCHAYAT", "MASTER");
        // MASTER dataset usable through both lenses (carries ac_no and panchayat_no)
        importAs(ownerToken, "hybrid.xlsx", workbook(HYBRID_HEADER, List.<Object[]>of(
                new Object[] {"HYBRID STATE", "HYBRID DISTRICT", 77, 88, 1, 1, 1, "HYB0001"})),
                "HYBRID_MASTER", "VIDHAN_SABHA", "MASTER");
        // LONG_TERM Vidhan Sabha: a different tier, different state
        importAs(ownerToken, "vs-lt.xlsx", workbook(VIDHAN_HEADER, List.<Object[]>of(
                new Object[] {"LONGTERM STATE", 9, 1, 1, "VLT0001"})),
                "VS_LONG_TERM", "VIDHAN_SABHA", "LONG_TERM");
    }

    private AppUser user(String username, Role role, Long customerAccountId) {
        return userRepository.save(AppUser.builder().username(username).fullName(username)
                .passwordHash(passwordEncoder.encode("Passw0rd!xyz")).role(role).status(UserStatus.ACTIVE)
                .customerAccountId(customerAccountId).build());
    }

    private static byte[] workbook(String[] header, List<Object[]> rows) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Voters");
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < header.length; c++) {
                headerRow.createCell(c).setCellValue(header[c]);
            }
            int r = 1;
            for (Object[] values : rows) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    if (values[c] instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else if (values[c] != null) {
                        row.createCell(c).setCellValue(values[c].toString());
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void importAs(String token, String file, byte[] bytes, String name, String type, String tier) throws Exception {
        mockMvc.perform(multipart("/api/v1/datasets/upload")
                        .file(new MockMultipartFile("file", file, XLSX, bytes))
                        .file(new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                                ("{\"name\":\"" + name + "\",\"electionTypeCode\":\"" + type + "\",\"storageTier\":\"" + tier + "\"}").getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    private ResultActions filter(String token, String type, String tier, String target, String... narrowing) throws Exception {
        var request = get("/api/v1/election-types/" + type + "/filters").header("Authorization", "Bearer " + token).param("target", target);
        if (tier != null) {
            request.param("tier", tier);
        }
        for (int i = 0; i < narrowing.length; i += 2) {
            request.param(narrowing[i], narrowing[i + 1]);
        }
        return mockMvc.perform(request);
    }

    private String values(String token, String type, String tier, String target, String... narrowing) throws Exception {
        String body = filter(token, type, tier, target, narrowing).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("{\"targetField\":\"" + target + "\"", body.substring(0, body.indexOf(",\"values\"")), "response shape: only targetField and values");
        return body.substring(body.indexOf("\"values\":") + 9, body.length() - 1);
    }

    @Test
    @DisplayName("VIDHAN_SABHA offers Vidhan Sabha areas only; Panchayat-only values never leak in")
    void vidhanSabhaOffersOnlyVidhanSabhaAreas() throws Exception {
        assertEquals("[\"HYBRID STATE\",\"MADHYA PRADESH\"]", values(agentToken, "VIDHAN_SABHA", "MASTER", "state"));
        assertEquals("[201]", values(agentToken, "VIDHAN_SABHA", "MASTER", "ac_no", "state", "MADHYA PRADESH"));
        assertEquals("[]", values(agentToken, "VIDHAN_SABHA", "MASTER", "ac_no", "state", "CHHATTISGARH"));
    }

    @Test
    @DisplayName("PANCHAYAT offers Panchayat areas only; Vidhan Sabha-only values never leak in")
    void panchayatOffersOnlyPanchayatAreas() throws Exception {
        assertEquals("[\"CHHATTISGARH\",\"HYBRID STATE\"]", values(agentToken, "PANCHAYAT", "MASTER", "state"));
        assertEquals("[\"AUDIT SUB DISTRICT\"]", values(agentToken, "PANCHAYAT", "MASTER", "district", "state", "CHHATTISGARH"));
        assertEquals("[501]", values(agentToken, "PANCHAYAT", "MASTER", "panchayat_no", "state", "CHHATTISGARH", "district", "AUDIT SUB DISTRICT"));
        assertEquals("[]", values(agentToken, "PANCHAYAT", "MASTER", "district", "state", "MADHYA PRADESH"));
    }

    @Test
    @DisplayName("The guard column of an election type is offered only from rows that election type can sell")
    void guardColumnFollowsTheSameEligibility() throws Exception {
        // PANCHAYAT's guard column is panchayat_samiti_name_en; none of the fixtures has one, so nothing is offered
        assertEquals("[]", values(agentToken, "PANCHAYAT", "MASTER", "panchayat_samiti_name_en"));
    }

    @Test
    @DisplayName("A dataset carrying the filter columns of two election types is offered through both; a type it cannot serve gets nothing")
    void dualUseDatasetFollowsTheLens() throws Exception {
        assertEquals("[77]", values(agentToken, "VIDHAN_SABHA", "MASTER", "ac_no", "state", "HYBRID STATE"));
        assertEquals("[88]", values(agentToken, "PANCHAYAT", "MASTER", "panchayat_no", "state", "HYBRID STATE", "district", "HYBRID DISTRICT"));
        // LOK_SABHA needs pc_no as well; none of these datasets has it
        assertEquals("[]", values(agentToken, "LOK_SABHA", "MASTER", "state"));
    }

    @Test
    @DisplayName("The storage tier is enforced: LONG_TERM data never appears under MASTER and vice versa")
    void tierIsolation() throws Exception {
        assertEquals("[\"LONGTERM STATE\"]", values(agentToken, "VIDHAN_SABHA", "LONG_TERM", "state"));
        assertEquals("[\"HYBRID STATE\",\"MADHYA PRADESH\"]", values(agentToken, "VIDHAN_SABHA", "MASTER", "state"));
        assertEquals("[]", values(agentToken, "PANCHAYAT", "LONG_TERM", "state"));
        assertEquals("[9]", values(agentToken, "VIDHAN_SABHA", "LONG_TERM", "ac_no", "state", "LONGTERM STATE"));
        assertEquals("[]", values(agentToken, "VIDHAN_SABHA", "MASTER", "ac_no", "state", "LONGTERM STATE"));
    }

    @Test
    @DisplayName("tier is required and must be a real tier")
    void tierIsRequired() throws Exception {
        filter(agentToken, "VIDHAN_SABHA", null, "state").andExpect(status().isBadRequest());
        filter(agentToken, "VIDHAN_SABHA", "FOREVER", "state").andExpect(status().isBadRequest());
        filter(agentToken, "VIDHAN_SABHA", "", "state").andExpect(status().isBadRequest());
        filter(agentToken, "VIDHAN_SABHA", "master", "state").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Existing column validation is intact: DISPLAY and hostile names are 400, Customer and Subuser are 403")
    void validationAndRolesAreIntact() throws Exception {
        for (String bad : new String[] {"voter_id", "age", "mobile_no_enc", "state; DROP TABLE voter", "state OR 1=1", "1", "(select 1)"}) {
            filter(agentToken, "VIDHAN_SABHA", "MASTER", bad).andExpect(status().isBadRequest());
            filter(agentToken, "VIDHAN_SABHA", "MASTER", "state", bad, "x").andExpect(status().isBadRequest());
        }
        for (String token : new String[] {customerToken, subuserToken}) {
            filter(token, "VIDHAN_SABHA", "MASTER", "state").andExpect(status().isForbidden());
            filter(token, "PANCHAYAT", "LONG_TERM", "state").andExpect(status().isForbidden());
        }
        for (String token : new String[] {ownerToken, subownerToken, agentToken}) {
            assertEquals("[\"HYBRID STATE\",\"MADHYA PRADESH\"]", values(token, "VIDHAN_SABHA", "MASTER", "state"));
        }
    }

    @Test
    @DisplayName("Nothing about datasets reaches the Agent: no ids, names or file names in any filter response")
    void noDatasetInternalsInResponses() throws Exception {
        for (String type : new String[] {"VIDHAN_SABHA", "PANCHAYAT", "LOK_SABHA"}) {
            for (String tier : new String[] {"MASTER", "LONG_TERM"}) {
                String body = filter(agentToken, type, tier, "state").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                for (String secret : new String[] {"VS_MASTER", "PAN_MASTER", "HYBRID_MASTER", "VS_LONG_TERM", ".xlsx", "datasetId", "\"id\"", "sha256"}) {
                    assertFalse(body.contains(secret), type + "/" + tier + " leaked " + secret);
                }
            }
        }
    }
}

package com.elecsarthi.security;

import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent audit batch 3: an Agent creates Customers and sells areas; the backend resolves the dataset from election type,
 * tier and geography; Agents are isolated from each other; Owner sees everything, Subowner no sales. The datasets mirror
 * audit datasets 113 (Vidhan Sabha, MADHYA PRADESH, AC 201) and 132 (Panchayat, CHHATTISGARH, panchayat 501).
 */
class AgentWorkflowIntegrationTest extends SecurityIntegrationTestSupport {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final short VIDHAN = 2;
    private static final short PANCHAYAT = 8;
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired private AppUserRepository userRepository;
    @Autowired private CustomerAccountRepository customerAccountRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private AppUser agentA;
    private AppUser agentB;
    private String ownerToken;
    private String subownerToken;
    private String agentAToken;
    private String agentBToken;
    private String customerToken;
    private String subuserToken;
    private long vidhanDatasetId;
    private long panchayatDatasetId;

    @BeforeEach
    void setUpFixture() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        AppUser owner = user("aw_owner", Role.OWNER, null);
        AppUser subowner = user("aw_subowner", Role.SUBOWNER, null);
        agentA = user("aw_agent_a", Role.AGENT, null);
        agentB = user("aw_agent_b", Role.AGENT, null);
        CustomerAccount other = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Unrelated Customer").contactPhone("+919123456750")
                .status(UserStatus.ACTIVE).createdByUserId(owner.getId()).build());
        AppUser customer = user("aw_customer", Role.CUSTOMER, other.getId());
        AppUser subuser = user("aw_subuser", Role.SUBUSER, other.getId());
        ownerToken = createActiveSessionAndToken(owner, ClientType.WEB, null);
        subownerToken = createActiveSessionAndToken(subowner, ClientType.WEB, null);
        agentAToken = createActiveSessionAndToken(agentA, ClientType.MOBILE, createActiveDevice(agentA, null, "aw-a").getId());
        agentBToken = createActiveSessionAndToken(agentB, ClientType.MOBILE, createActiveDevice(agentB, null, "aw-b").getId());
        customerToken = createActiveSessionAndToken(customer, ClientType.MOBILE, createActiveDevice(customer, other.getId(), "aw-c").getId());
        subuserToken = createActiveSessionAndToken(subuser, ClientType.MOBILE, createActiveDevice(subuser, other.getId(), "aw-s").getId());

        importAs(ownerToken, "vs.xlsx", workbook(new String[] {"state", "ac_no", "part_no", "serial_no", "voter_id"}, List.<Object[]>of(
                new Object[] {"MADHYA PRADESH", 201, 1, 1, "VSA0001"}, new Object[] {"MADHYA PRADESH", 201, 2, 2, "VSA0002"})), "VS_DS", "VIDHAN_SABHA");
        importAs(subownerToken, "pan.xlsx", workbook(new String[] {"state", "district", "panchayat_no", "panchayat_samiti_name_en", "booth_no", "serial_no", "voter_id"}, List.<Object[]>of(
                new Object[] {"CHHATTISGARH", "AUDIT SUB DISTRICT", 501, "AUDIT SAMITI", 1, 1, "PAN0001"},
                new Object[] {"CHHATTISGARH", "AUDIT SUB DISTRICT", 501, "AUDIT SAMITI", 2, 2, "PAN0002"})), "PAN_DS", "PANCHAYAT");
        vidhanDatasetId = jdbcTemplate.queryForObject("SELECT id FROM dataset WHERE name = 'VS_DS'", Long.class);
        panchayatDatasetId = jdbcTemplate.queryForObject("SELECT id FROM dataset WHERE name = 'PAN_DS'", Long.class);
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

    private void importAs(String token, String file, byte[] bytes, String name, String type) throws Exception {
        mockMvc.perform(multipart("/api/v1/datasets/upload")
                        .file(new MockMultipartFile("file", file, XLSX, bytes))
                        .file(new MockMultipartFile("metadata", "", JSON,
                                ("{\"name\":\"" + name + "\",\"electionTypeCode\":\"" + type + "\",\"storageTier\":\"MASTER\"}").getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    private ResultActions postJson(String token, String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(JSON).content(body));
    }

    private ResultActions getJson(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private long createCustomer(String token, String phone, String extraJson) throws Exception {
        String body = postJson(token, "/api/v1/customers", "{\"customerName\":\"Cust " + phone + "\",\"contactPhone\":\"" + phone + "\"" + extraJson + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private static String sale(long customerId, short electionTypeId, String tier, String key, String criteria, String extra) {
        return "{\"customerAccountId\":" + customerId + ",\"electionTypeId\":" + electionTypeId + ",\"storageTier\":\"" + tier
                + "\",\"idempotencyKey\":\"" + key + "\"" + extra + ",\"lines\":[{\"criteria\":" + criteria + ",\"durationValue\":1,\"durationUnit\":\"MONTH\"" + extra + "}]}";
    }

    private static final String VS_AREA = "{\"state\":\"MADHYA PRADESH\",\"ac_no\":201}";
    private static final String PAN_AREA = "{\"state\":\"CHHATTISGARH\",\"district\":\"AUDIT SUB DISTRICT\",\"panchayat_no\":501}";

    // ---------- Customer creation ----------

    @Test
    @DisplayName("An Agent provisions a Customer it owns; injected owner/role/status are ignored; no phone number in the audit row")
    void agentCreatesOwnCustomer() throws Exception {
        String body = postJson(agentAToken, "/api/v1/customers", "{\"customerName\":\"Audit Customer\",\"partyOrOrgName\":\"Party\","
                + "\"contactPhone\":\"+919000038400\",\"contactEmail\":\"A@Example.test\",\"owningAgentUserId\":" + agentB.getId()
                + ",\"role\":\"OWNER\",\"status\":\"SUSPENDED\",\"activationStatus\":\"ACTIVATED\",\"createdByUserId\":1}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activationStatus").value("PENDING"))
                .andExpect(jsonPath("$.activationCode").isString())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).get("id").asLong();

        CustomerAccount account = customerAccountRepository.findById(id).orElseThrow();
        assertEquals(agentA.getId(), account.getOwningAgentUserId());
        assertEquals(agentA.getId(), account.getCreatedByUserId());
        assertEquals(UserStatus.ACTIVE, account.getStatus());
        assertEquals("a@example.test", account.getContactEmail());
        AppUser login = userRepository.findByCustomerAccountIdAndRole(id, Role.CUSTOMER).orElseThrow();
        assertEquals(UserStatus.PENDING, login.getStatus());
        assertEquals(null, login.getPasswordHash(), "the primary login has no password until activation");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM app_user WHERE customer_account_id = ?", Integer.class, id));

        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action_type = 'CUSTOMER_PROVISION' AND actor_user_id = ?", Integer.class, agentA.getId()));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE details::text LIKE '%9000038400%'", Integer.class), "no phone number in audit details");

        // the activation code is returned once and never again
        getJson(agentAToken, "/api/v1/customers/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.activationCode").doesNotExist());
        getJson(agentAToken, "/api/v1/customers").andExpect(status().isOk()).andExpect(jsonPath("$.content[0].activationCode").doesNotExist());

        // duplicates and invalid input create nothing
        postJson(agentBToken, "/api/v1/customers", "{\"customerName\":\"Dup\",\"contactPhone\":\"+919000038400\"}").andExpect(status().isConflict());
        postJson(agentAToken, "/api/v1/customers", "{}").andExpect(status().isBadRequest());
        postJson(agentAToken, "/api/v1/customers", "{\"customerName\":\"X\",\"contactPhone\":\"9000038401\"}").andExpect(status().isBadRequest());
        postJson(agentAToken, "/api/v1/customers", "{bad").andExpect(status().isBadRequest());
        assertEquals(2, customerAccountRepository.count(), "the fixture's unrelated customer plus the one created");
    }

    @Test
    @DisplayName("Agents cannot see, re-issue codes for, or sell to each other's Customers; Customer and Subuser cannot provision")
    void customerIsolationBetweenAgents() throws Exception {
        long aCustomer = createCustomer(agentAToken, "+919000038410", "");
        long bCustomer = createCustomer(agentBToken, "+919000038411", "");

        getJson(agentBToken, "/api/v1/customers/" + aCustomer).andExpect(status().isNotFound());
        getJson(agentAToken, "/api/v1/customers/" + bCustomer).andExpect(status().isNotFound());
        postJson(agentBToken, "/api/v1/customers/" + aCustomer + "/reissue-activation-code", "{}").andExpect(status().isNotFound());
        postJson(agentAToken, "/api/v1/customers/" + bCustomer + "/suspend", "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        getJson(agentAToken, "/api/v1/customers").andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(aCustomer));

        postJson(agentAToken, "/api/v1/sales", sale(bCustomer, VIDHAN, "MASTER", "k-a-to-b", VS_AREA, "")).andExpect(status().isNotFound());
        postJson(agentBToken, "/api/v1/sales", sale(aCustomer, VIDHAN, "MASTER", "k-b-to-a", VS_AREA, "")).andExpect(status().isNotFound());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM agent_sale", Integer.class));
        getJson(agentBToken, "/api/v1/entitlements/customer/" + aCustomer).andExpect(status().isNotFound());
        getJson(agentBToken, "/api/v1/sales/customer/" + aCustomer).andExpect(status().isNotFound());

        for (String token : new String[] {customerToken, subuserToken}) {
            postJson(token, "/api/v1/customers", "{\"customerName\":\"X\",\"contactPhone\":\"+919000038412\"}").andExpect(status().isForbidden());
            postJson(token, "/api/v1/sales", sale(aCustomer, VIDHAN, "MASTER", "k-c", VS_AREA, "")).andExpect(status().isForbidden());
            getJson(token, "/api/v1/datasets").andExpect(status().isForbidden());
        }
    }

    // ---------- sale and dataset resolution ----------

    @Test
    @DisplayName("The backend resolves VIDHAN_SABHA to the Vidhan dataset and PANCHAYAT to the Panchayat dataset; a client-sent datasetId is ignored")
    void saleResolvesDatasetFromElectionTypeTierAndGeography() throws Exception {
        long vsCustomer = createCustomer(agentAToken, "+919000038420", "");
        long panCustomer = createCustomer(agentBToken, "+919000038421", "");
        long vsFingerprint = fingerprint(vidhanDatasetId);
        long panFingerprint = fingerprint(panchayatDatasetId);

        // each client tries to steer the resolution to the OTHER dataset
        String vsBody = postJson(agentAToken, "/api/v1/sales", sale(vsCustomer, VIDHAN, "MASTER", "k-vs", VS_AREA, ",\"datasetId\":" + panchayatDatasetId))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.electionTypeCode").value("VIDHAN_SABHA"))
                .andExpect(jsonPath("$.lines[0].scopeLabel").value("Vidhan Sabha · MADHYA PRADESH · AC 201"))
                .andExpect(jsonPath("$.lines[0].voterCountSnapshot").value(2)).andReturn().getResponse().getContentAsString();
        String panBody = postJson(agentBToken, "/api/v1/sales", sale(panCustomer, PANCHAYAT, "MASTER", "k-pan", PAN_AREA, ",\"datasetId\":" + vidhanDatasetId))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.electionTypeCode").value("PANCHAYAT"))
                .andExpect(jsonPath("$.lines[0].scopeLabel").value("Panchayat · CHHATTISGARH · AUDIT SUB DISTRICT · Panchayat 501")).andReturn().getResponse().getContentAsString();

        assertEquals(vidhanDatasetId, resolvedDataset(vsBody));
        assertEquals(panchayatDatasetId, resolvedDataset(panBody));
        assertEquals(vidhanDatasetId, jdbcTemplate.queryForObject("SELECT dataset_id FROM entitlement WHERE customer_account_id = ?", Long.class, vsCustomer));
        assertEquals(panchayatDatasetId, jdbcTemplate.queryForObject("SELECT dataset_id FROM entitlement WHERE customer_account_id = ?", Long.class, panCustomer));
        // the entitlement holds only the purchased geography
        assertEquals("{\"ac_no\": 201, \"state\": \"MADHYA PRADESH\"}", jdbcTemplate.queryForObject("SELECT criteria::text FROM entitlement WHERE customer_account_id = ?", String.class, vsCustomer));
        // nothing about datasets reaches the Agent
        for (String response : new String[] {vsBody, panBody}) {
            for (String secret : new String[] {"datasetId", "VS_DS", "PAN_DS", ".xlsx", "sha256", "\"dataset"}) {
                assertFalse(response.contains(secret), "sale response leaked " + secret);
            }
        }
        // the sale does not touch the imported rows
        assertEquals(vsFingerprint, fingerprint(vidhanDatasetId));
        assertEquals(panFingerprint, fingerprint(panchayatDatasetId));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM voter WHERE dataset_id = ?", Integer.class, vidhanDatasetId));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM voter WHERE dataset_id = ?", Integer.class, panchayatDatasetId));
    }

    @Test
    @DisplayName("Wrong lens, wrong geography, wrong tier, unknown election type, bad criteria and over-long validity are rejected and create nothing")
    void saleRejectionsCreateNothing() throws Exception {
        long customer = createCustomer(agentAToken, "+919000038430", "");
        String[] badBodies = {
                sale(customer, PANCHAYAT, "MASTER", "r1", VS_AREA, ""),                                        // Vidhan criteria under Panchayat
                sale(customer, VIDHAN, "MASTER", "r2", "{\"state\":\"CHHATTISGARH\",\"ac_no\":501}", ""),       // Panchayat geography under Vidhan
                sale(customer, PANCHAYAT, "MASTER", "r3", "{\"state\":\"MADHYA PRADESH\",\"district\":\"D\",\"panchayat_no\":1}", ""),
                sale(customer, VIDHAN, "LONG_TERM", "r4", VS_AREA, ""),                                        // right area, other tier
                sale(customer, (short) 99, "MASTER", "r5", VS_AREA, ""),                                      // unsupported election type
                sale(customer, VIDHAN, "MASTER", "r6", "{\"state\":\"MADHYA PRADESH\"}", ""),                   // missing FILTER column
                sale(customer, VIDHAN, "MASTER", "r7", "{\"state\":\"MADHYA PRADESH\",\"ac_no\":201,\"voter_id\":\"VSA0001\"}", ""), // DISPLAY column
                sale(customer, VIDHAN, "MASTER", "r8", "{\"state\":\"MADHYA PRADESH\",\"ac_no\":999}", ""),     // unmatched area
        };
        for (String body : badBodies) {
            postJson(agentAToken, "/api/v1/sales", body).andExpect(status().is4xxClientError());
        }
        postJson(agentAToken, "/api/v1/sales", "{\"customerAccountId\":" + customer + ",\"electionTypeId\":2,\"storageTier\":\"MASTER\",\"idempotencyKey\":\"r9\",\"lines\":[{\"criteria\":" + VS_AREA + ",\"durationValue\":24,\"durationUnit\":\"MONTH\"}]}")
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM agent_sale", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM entitlement", Integer.class));
    }

    @Test
    @DisplayName("Sales visibility: an Agent sees only its own; the Owner sees who sold what to whom; the Subowner sees no sales")
    void salesVisibility() throws Exception {
        long customerA = createCustomer(agentAToken, "+919000038440", "");
        long customerB = createCustomer(agentBToken, "+919000038441", "");
        long saleA = objectMapper.readTree(postJson(agentAToken, "/api/v1/sales", sale(customerA, VIDHAN, "MASTER", "v-a", VS_AREA, ""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long saleB = objectMapper.readTree(postJson(agentBToken, "/api/v1/sales", sale(customerB, PANCHAYAT, "MASTER", "v-b", PAN_AREA, ""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        getJson(agentAToken, "/api/v1/sales/my").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(saleA));
        getJson(agentAToken, "/api/v1/sales/" + saleA).andExpect(status().isOk());
        getJson(agentAToken, "/api/v1/sales/" + saleB).andExpect(status().isNotFound());
        getJson(agentBToken, "/api/v1/sales/" + saleA).andExpect(status().isNotFound());
        getJson(agentAToken, "/api/v1/entitlements/customer/" + customerA).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        getJson(agentAToken, "/api/v1/reports/sales").andExpect(status().isOk()).andExpect(jsonPath("$.totalSales").value(1));

        JsonNode ownerA = objectMapper.readTree(getJson(ownerToken, "/api/v1/sales/" + saleA).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(agentA.getId(), ownerA.get("soldByUserId").asLong());
        assertEquals(customerA, ownerA.get("customerAccountId").asLong());
        JsonNode ownerB = objectMapper.readTree(getJson(ownerToken, "/api/v1/sales/" + saleB).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(agentB.getId(), ownerB.get("soldByUserId").asLong());
        getJson(ownerToken, "/api/v1/reports/sales").andExpect(status().isOk()).andExpect(jsonPath("$.totalSales").value(2));
        getJson(ownerToken, "/api/v1/reports/sales?agentId=" + agentB.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.totalSales").value(1));

        for (String path : new String[] {"/api/v1/sales/" + saleA, "/api/v1/sales/customer/" + customerA, "/api/v1/reports/sales"}) {
            getJson(subownerToken, path).andExpect(status().isForbidden());
            getJson(customerToken, path).andExpect(status().isForbidden());
            getJson(subuserToken, path).andExpect(status().isForbidden());
        }
        // the Subowner still sees the Customer, with its phone masked
        getJson(subownerToken, "/api/v1/customers/" + customerA).andExpect(status().isOk()).andExpect(jsonPath("$.contactPhone").value("+91*****440"));
        assertTrue(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'agent_sale' AND column_name ~* '(price|amount|payment|commission)'", Integer.class) == 0,
                "the sale model carries no money fields");
    }

    private long fingerprint(long datasetId) {
        return jdbcTemplate.queryForObject("SELECT hashtext(string_agg(source_record_key_hash || voter_id, ',' ORDER BY source_record_key_hash)) FROM voter WHERE dataset_id = ?", Long.class, datasetId);
    }

    private long resolvedDataset(String saleResponse) throws Exception {
        long saleId = objectMapper.readTree(saleResponse).get("id").asLong();
        return jdbcTemplate.queryForObject("SELECT dataset_id FROM agent_sale_line WHERE sale_id = ?", Long.class, saleId);
    }
}

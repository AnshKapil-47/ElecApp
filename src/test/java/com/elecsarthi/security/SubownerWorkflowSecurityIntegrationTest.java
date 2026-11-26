package com.elecsarthi.security;

import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Owner audit batch 2: what a SUBOWNER may and may not do, over HTTP through the real security filter chain with
 * real session rows and signed access tokens. Covers the dataset import/delete permission, the Panchayat XLSX import,
 * Agent creation, the Agents-only management scope, voter-data and sales boundaries, device-revocation scope and the
 * audited concurrent-session limit.
 */
class SubownerWorkflowSecurityIntegrationTest extends SecurityIntegrationTestSupport {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String[] PANCHAYAT_HEADER = {"state", "district", "panchayat_no", "panchayat_name_en",
            "panchayat_samiti_name_en", "booth_no", "serial_no", "voter_id", "v_fname_en"};
    private static final String PASSWORD = "Passw0rd!xyz";

    @Autowired private AppUserRepository userRepository;
    @Autowired private CustomerAccountRepository customerAccountRepository;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private AppUser owner;
    private AppUser subowner;
    private AppUser otherSubowner;
    private AppUser agent;
    private AppUser customerUser;
    private AppUser subuser;
    private CustomerAccount account;
    private String ownerToken;
    private String subownerToken;
    private String agentToken;
    private UserDevice agentDevice;
    private UserDevice customerDevice;
    private UserDevice subuserDevice;

    @BeforeEach
    void setUpFixture() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();

        owner = user("sw_owner", Role.OWNER, null);
        subowner = user("sw_subowner", Role.SUBOWNER, null);
        otherSubowner = user("sw_other_subowner", Role.SUBOWNER, null);
        agent = user("sw_agent", Role.AGENT, null);
        account = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Subowner Audit Customer").contactPhone("+919123456730")
                .status(UserStatus.ACTIVE).createdByUserId(owner.getId()).owningAgentUserId(agent.getId()).build());
        // A provisioned Customer's login name is the Customer's phone number.
        customerUser = user("+919123456730", Role.CUSTOMER, account.getId());
        subuser = user("sw_subuser", Role.SUBUSER, account.getId());

        ownerToken = createActiveSessionAndToken(owner, ClientType.WEB, null);
        subownerToken = createActiveSessionAndToken(subowner, ClientType.WEB, null);
        agentDevice = createActiveDevice(agent, null, "sw-agent");
        agentToken = createActiveSessionAndToken(agent, ClientType.MOBILE, agentDevice.getId());
        customerDevice = createActiveDevice(customerUser, account.getId(), "sw-cust");
        subuserDevice = createActiveDevice(subuser, account.getId(), "sw-sub");
    }

    private AppUser user(String username, Role role, Long customerAccountId) {
        return userRepository.save(AppUser.builder().username(username).fullName(username)
                .passwordHash(passwordEncoder.encode(PASSWORD)).role(role).status(UserStatus.ACTIVE)
                .customerAccountId(customerAccountId).build());
    }

    private static RequestBuilder authed(AbstractMockHttpServletRequestBuilder<?> builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
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

    private static List<Object[]> panchayatRows(String prefix, int count, String state) {
        java.util.ArrayList<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(new Object[]{state, "AUDIT DISTRICT", 501, "AUDIT GRAM PANCHAYAT", "AUDIT SAMITI", i <= count / 2 ? 1 : 2, i,
                    prefix + String.format("%04d", i), "Name" + i});
        }
        return rows;
    }

    private org.springframework.test.web.servlet.ResultActions upload(String token, String fileName, byte[] bytes, String name, String type)
            throws Exception {
        return mockMvc.perform(authed(multipart("/api/v1/datasets/upload")
                .file(new MockMultipartFile("file", fileName, XLSX, bytes))
                .file(new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                        ("{\"name\":\"" + name + "\",\"electionTypeCode\":\"" + type + "\",\"storageTier\":\"MASTER\"}").getBytes())), token));
    }

    private long voterRows(long datasetId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM voter WHERE dataset_id = ?", Long.class, datasetId);
    }

    // ---------- dataset import / listing / delete ----------

    @Test
    @DisplayName("Subowner imports a Panchayat workbook; importer is the Subowner, the Owner's dataset stays visible and untouched")
    void subownerImportsPanchayatDatasetAndSeesOwnerDataset() throws Exception {
        upload(ownerToken, "owner-vs.xlsx", workbook(new String[] {"state", "ac_no", "part_no", "serial_no", "voter_id"},
                List.<Object[]>of(new Object[] {"MADHYA PRADESH", 201, 1, 1, "OWN0001"}, new Object[] {"MADHYA PRADESH", 201, 1, 2, "OWN0002"})),
                "OWNER_DS", "VIDHAN_SABHA").andExpect(status().isCreated());
        long ownerDatasetId = datasetRepository.findAll().get(0).getId();
        long ownerKeyFingerprint = jdbcTemplate.queryForObject("SELECT hashtext(string_agg(source_record_key_hash, ',' ORDER BY source_record_key_hash)) FROM voter WHERE dataset_id = ?", Long.class, ownerDatasetId);

        upload(subownerToken, "sub-pan.xlsx", workbook(PANCHAYAT_HEADER, panchayatRows("SUBAUD", 10, "CHHATTISGARH")),
                "SUB_PANCHAYAT", "PANCHAYAT")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.importedRows").value(10))
                .andExpect(jsonPath("$.failedRows").value(0))
                .andExpect(jsonPath("$.importedByUserId").value(subowner.getId()));

        long panchayatId = datasetRepository.findAll().stream().filter(d -> d.getName().equals("SUB_PANCHAYAT")).findFirst().orElseThrow().getId();
        assertEquals(10, voterRows(panchayatId));
        assertEquals(10, jdbcTemplate.queryForObject("SELECT count(DISTINCT voter_id) FROM voter WHERE dataset_id = ?", Integer.class, panchayatId));
        assertEquals(10, jdbcTemplate.queryForObject("SELECT count(DISTINCT source_record_key_hash) FROM voter WHERE dataset_id = ?", Integer.class, panchayatId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM voter WHERE dataset_id = ? AND (mobile_no_enc IS NOT NULL OR panchayat_no IS NULL)", Integer.class, panchayatId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action_type = 'DATASET_IMPORT' AND outcome = 'SUCCESS' AND actor_user_id = ? AND dataset_id = ?", Integer.class, subowner.getId(), panchayatId));

        // The Subowner sees the Owner's dataset metadata (matrix: dataset inventory) with the importer's username...
        mockMvc.perform(authed(get("/api/v1/datasets/" + ownerDatasetId), subownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("OWNER_DS")).andExpect(jsonPath("$.importedRows").value(2));
        mockMvc.perform(authed(get("/api/v1/datasets/" + panchayatId), subownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.importedByUsername").value("sw_subowner"));
        mockMvc.perform(authed(get("/api/v1/datasets"), subownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2));
        // ...but cannot reach any voter-record API, and the Owner's rows were not changed by the Subowner's import.
        assertEquals(2, voterRows(ownerDatasetId));
        assertEquals(ownerKeyFingerprint, jdbcTemplate.queryForObject("SELECT hashtext(string_agg(source_record_key_hash, ',' ORDER BY source_record_key_hash)) FROM voter WHERE dataset_id = ?", Long.class, ownerDatasetId));

        // The same file cannot be imported twice and an overlapping correction is refused, without touching dataset data.
        upload(subownerToken, "sub-pan.xlsx", workbook(PANCHAYAT_HEADER, panchayatRows("SUBAUD", 10, "CHHATTISGARH")), "AGAIN", "PANCHAYAT")
                .andExpect(status().isConflict());
        List<Object[]> altered = panchayatRows("SUBAUD", 10, "CHHATTISGARH");
        altered.get(0)[8] = "Changed";
        upload(subownerToken, "sub-pan2.xlsx", workbook(PANCHAYAT_HEADER, altered), "OVERLAP", "PANCHAYAT").andExpect(status().isConflict());
        assertEquals(10, voterRows(panchayatId));
    }

    @Test
    @DisplayName("Subowner imports that are invalid are 4xx, leave no IMPORTED dataset, no voter rows and no partition")
    void invalidSubownerImportsLeaveNothingBehind() throws Exception {
        upload(subownerToken, "good.xlsx", workbook(PANCHAYAT_HEADER, panchayatRows("GOOD", 4, "CHHATTISGARH")), "GOOD", "PANCHAYAT")
                .andExpect(status().isCreated());
        long goodId = datasetRepository.findAll().get(0).getId();

        byte[] fake = "plain text pretending to be xlsx".getBytes();
        byte[] headerOnly = workbook(PANCHAYAT_HEADER, List.of());

        upload(subownerToken, "fake.xlsx", fake, "N1", "PANCHAYAT").andExpect(status().isBadRequest());
        upload(subownerToken, "empty.xlsx", new byte[0], "N2", "PANCHAYAT").andExpect(status().isBadRequest());
        upload(subownerToken, "rows.csv", fake, "N3", "PANCHAYAT").andExpect(status().isBadRequest());
        upload(subownerToken, "header.xlsx", headerOnly, "N4", "PANCHAYAT").andExpect(status().isBadRequest());
        upload(subownerToken, "etype.xlsx", headerOnly, "N8", "NOT_A_TYPE").andExpect(status().isBadRequest());

        assertEquals(List.of(goodId), datasetRepository.findAll().stream().filter(d -> d.getStatus() == DatasetStatus.IMPORTED).map(d -> d.getId()).toList());
        assertEquals(4, jdbcTemplate.queryForObject("SELECT count(*) FROM voter", Integer.class), "only the good dataset's rows exist");
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM import_issue", Integer.class));
        datasetRepository.findAll().stream().filter(d -> d.getId() != goodId).forEach(d -> {
            assertEquals(DatasetStatus.FAILED, d.getStatus());
            assertFalse(Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT to_regclass('public.voter_d" + d.getId() + "') IS NOT NULL", Boolean.class)));
        });
        assertEquals(4, voterRows(goodId));
    }

    @Test
    @DisplayName("Refined files import completely: a missing voter id column, a repeated record and a non-numeric number never reject a row")
    void incompleteButRefinedFilesImportEveryRow() throws Exception {
        byte[] noVoterId = workbook(new String[] {"state", "district", "panchayat_no", "serial_no"},
                List.<Object[]>of(new Object[] {"X STATE", "D", 1, 1}));
        List<Object[]> dup = panchayatRows("DUP", 2, "NEG STATE A");
        dup.set(1, dup.get(0));
        byte[] exactDuplicate = workbook(PANCHAYAT_HEADER, dup);
        List<Object[]> badGeo = panchayatRows("GEO", 3, "NEG STATE B");
        badGeo.forEach(r -> r[2] = "ZZ");
        byte[] invalidGeography = workbook(PANCHAYAT_HEADER, badGeo);

        upload(subownerToken, "novoter.xlsx", noVoterId, "R1", "PANCHAYAT").andExpect(status().isCreated());
        upload(subownerToken, "dup.xlsx", exactDuplicate, "R2", "PANCHAYAT").andExpect(status().isCreated());
        upload(subownerToken, "geo.xlsx", invalidGeography, "R3", "PANCHAYAT").andExpect(status().isCreated());

        assertEquals(3, datasetRepository.findAll().stream().filter(d -> d.getStatus() == DatasetStatus.IMPORTED).count());
        assertEquals(1 + 2 + 3, jdbcTemplate.queryForObject("SELECT count(*) FROM voter", Integer.class), "every row of every file is stored");
        assertEquals(0, jdbcTemplate.queryForObject("SELECT sum(failed_rows) FROM dataset", Integer.class));
    }

    @Test
    @DisplayName("Subowner may delete a disposable dataset (audited, partitions dropped); Agent, Customer and Subuser may not")
    void datasetDeletePermission() throws Exception {
        upload(subownerToken, "disposable.xlsx", workbook(PANCHAYAT_HEADER, panchayatRows("DISP", 3, "DISP STATE")), "DISPOSABLE", "PANCHAYAT")
                .andExpect(status().isCreated());
        long id = datasetRepository.findAll().get(0).getId();
        String customerToken = createActiveSessionAndToken(customerUser, ClientType.MOBILE, customerDevice.getId());

        mockMvc.perform(authed(delete("/api/v1/datasets/" + id), agentToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/v1/datasets/" + id), customerToken)).andExpect(status().isForbidden());
        assertEquals(3, voterRows(id));

        mockMvc.perform(authed(delete("/api/v1/datasets/" + id), subownerToken)).andExpect(status().isNoContent());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM voter", Integer.class));
        assertFalse(Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT to_regclass('public.voter_d" + id + "') IS NOT NULL", Boolean.class)));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action_type = 'DATASET_DELETE' AND actor_user_id = ?", Integer.class, subowner.getId()));
    }

    // ---------- Agent creation / Agents-only management ----------

    @Test
    @DisplayName("Subowner creates exactly one Agent: role/status/creator are server-set, injected fields ignored, creation audited without secrets")
    void subownerCreatesAgent() throws Exception {
        long before = userRepository.count();
        mockMvc.perform(authed(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sw_new_agent\",\"fullName\":\"New Agent\",\"password\":\"" + PASSWORD
                                + "\",\"role\":\"OWNER\",\"status\":\"SUSPENDED\",\"createdByUserId\":" + owner.getId() + ",\"customerAccountId\":" + account.getId() + "}"),
                subownerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("AGENT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdByUserId").value(subowner.getId()));
        assertEquals(before + 1, userRepository.count());

        AppUser created = userRepository.findByUsername("sw_new_agent").orElseThrow();
        assertEquals(Role.AGENT, created.getRole());
        assertEquals(UserStatus.ACTIVE, created.getStatus());
        assertEquals(subowner.getId(), created.getCreatedByUserId());
        assertEquals(null, created.getCustomerAccountId());
        assertEquals(0, deviceRepository.findAll().stream().filter(d -> d.getUserId().equals(created.getId())).count(), "no device is registered");
        assertEquals(0, sessionRepository.findAll().stream().filter(s -> s.getUserId().equals(created.getId())).count());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action_type = 'USER_CREATE' AND outcome = 'SUCCESS' AND actor_user_id = ? AND target_id = ?",
                Integer.class, subowner.getId(), created.getId().toString()));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE details::text ILIKE '%Passw0rd%'", Integer.class));

        // duplicates (any case) and invalid input create nothing
        mockMvc.perform(authed(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"SW_NEW_AGENT\",\"fullName\":\"Dup\",\"password\":\"" + PASSWORD + "\"}"), subownerToken)).andExpect(status().isConflict());
        mockMvc.perform(authed(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON).content("{}"), subownerToken)).andExpect(status().isBadRequest());
        assertEquals(before + 1, userRepository.count());

        // the new Agent is visible to the Subowner's Agent management, and the Subowner cannot create a Subowner
        mockMvc.perform(authed(get("/api/v1/users/" + created.getId()), subownerToken)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("AGENT"));
        mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"sw_more\",\"fullName\":\"M\",\"password\":\"" + PASSWORD + "\"}"), subownerToken)).andExpect(status().isForbidden());
        assertTrue(userRepository.findByUsername("sw_more").isEmpty());
    }

    @Test
    @DisplayName("Subowner can only read and manage Agents: Owner, other Subowners, Customer logins and Subusers are 404 (read) or 403 (manage) and unchanged")
    void subownerScopeIsAgentsOnly() throws Exception {
        // Reading: Agents only; everything else looks exactly like a missing user (a Customer login's username is a phone number).
        mockMvc.perform(authed(get("/api/v1/users/" + agent.getId()), subownerToken)).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/v1/users/" + subowner.getId()), subownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("SUBOWNER")); // its own profile
        for (AppUser outOfScope : new AppUser[] {owner, otherSubowner, customerUser, subuser}) {
            mockMvc.perform(authed(get("/api/v1/users/" + outOfScope.getId()), subownerToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("User not found with ID: " + outOfScope.getId()));
        }
        mockMvc.perform(authed(get("/api/v1/users/999999"), subownerToken)).andExpect(status().isNotFound());
        // The Owner still sees everyone.
        mockMvc.perform(authed(get("/api/v1/users/" + customerUser.getId()), ownerToken)).andExpect(status().isOk());

        // Managing: refused for every non-Agent, nothing changes.
        String ownerHash = userRepository.findById(owner.getId()).orElseThrow().getPasswordHash();
        for (AppUser target : new AppUser[] {owner, otherSubowner, subowner, customerUser, subuser}) {
            mockMvc.perform(authed(patch("/api/v1/users/" + target.getId() + "/status").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"SUSPENDED\"}"), subownerToken)).andExpect(status().isForbidden());
            mockMvc.perform(authed(post("/api/v1/users/" + target.getId() + "/reset-password").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"newPassword\":\"Changed!Pass123\"}"), subownerToken)).andExpect(status().isForbidden());
            mockMvc.perform(authed(post("/api/v1/users/" + target.getId() + "/terminate-sessions"), subownerToken)).andExpect(status().isForbidden());
            assertEquals(UserStatus.ACTIVE, userRepository.findById(target.getId()).orElseThrow().getStatus());
        }
        assertEquals(ownerHash, userRepository.findById(owner.getId()).orElseThrow().getPasswordHash());
        assertTrue(sessionRepository.findAll().stream().anyMatch(s -> s.getUserId().equals(owner.getId())
                && s.getStatus() == com.elecsarthi.enums.SessionStatus.ACTIVE), "the Owner's session was not ended");
        // ...while managing an Agent is allowed.
        mockMvc.perform(authed(post("/api/v1/users/" + agent.getId() + "/terminate-sessions"), subownerToken)).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Subowner may revoke an Agent's device but not a Customer's or Subuser's (Owner-only); Owner may revoke all")
    void deviceRevocationScope() throws Exception {
        mockMvc.perform(authed(post("/api/v1/devices/" + customerDevice.getId() + "/revoke"), subownerToken)).andExpect(status().isNotFound());
        mockMvc.perform(authed(post("/api/v1/devices/" + subuserDevice.getId() + "/revoke"), subownerToken)).andExpect(status().isNotFound());
        assertEquals(DeviceStatus.ACTIVE, deviceRepository.findById(customerDevice.getId()).orElseThrow().getStatus());
        assertEquals(DeviceStatus.ACTIVE, deviceRepository.findById(subuserDevice.getId()).orElseThrow().getStatus());

        mockMvc.perform(authed(post("/api/v1/devices/" + agentDevice.getId() + "/revoke"), subownerToken)).andExpect(status().isOk());
        assertEquals(DeviceStatus.REVOKED, deviceRepository.findById(agentDevice.getId()).orElseThrow().getStatus());

        mockMvc.perform(authed(post("/api/v1/devices/" + customerDevice.getId() + "/revoke"), ownerToken)).andExpect(status().isOk());
        assertEquals(DeviceStatus.REVOKED, deviceRepository.findById(customerDevice.getId()).orElseThrow().getStatus());
    }

    // ---------- voter data, sales and Owner-only boundaries ----------

    @Test
    @DisplayName("Subowner is refused on voter, sync, lease, turnout, sales, report and subuser endpoints and on Owner-only actions (valid bodies)")
    void subownerRestrictedOperations() throws Exception {
        String json = MediaType.APPLICATION_JSON_VALUE;
        mockMvc.perform(authed(post("/api/v1/voters/search").contentType(json).content("{\"entitlementId\":1,\"page\":0,\"limit\":10}"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/voters/1").param("entitlementId", "1"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/voters/1/family").param("entitlementId", "1"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/voters/1/slip").param("entitlementId", "1"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/sync/sessions").contentType(json).content("{\"entitlementId\":1,\"protocolVersion\":1}"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/leases/booth").contentType(json).content("{\"boothAssignmentId\":1}"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/turnout/mark").contentType(json).content("{\"entitlementId\":1,\"voterRowId\":1}"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/sales/my"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/sales/1"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/sales/customer/" + account.getId()), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/sales/1/void").contentType(json).content("{\"voidReason\":\"x\"}"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/reports/sales").param("agentId", agent.getId().toString()), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/entitlements/my"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/subusers").contentType(json)
                .content("{\"username\":\"sw_subx\",\"fullName\":\"X\",\"password\":\"" + PASSWORD + "\",\"contactPhone\":\"+919000000001\"}"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(put("/api/v1/voters/system/mobile-numbers").contentType(json).content("{\"enabled\":true}"), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(patch("/api/v1/customers/" + account.getId() + "/reassign-agent").contentType(json)
                .content("{\"owningAgentUserId\":" + agent.getId() + "}"), subownerToken)).andExpect(status().isForbidden());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM agent_sale", Integer.class));
        assertTrue(userRepository.findByUsername("sw_subx").isEmpty());
    }

    @Test
    @DisplayName("A Subowner sees Customer phone numbers masked; the Owner sees them in full")
    void customerPhoneIsMaskedForSubowner() throws Exception {
        mockMvc.perform(authed(get("/api/v1/customers/" + account.getId()), subownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contactPhone").value("+91*****730"));
        mockMvc.perform(authed(get("/api/v1/customers/" + account.getId()), ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contactPhone").value("+919123456730"));
    }

    @Test
    @DisplayName("An Agent (real session) gets 403 on dataset inventory and details; the sale-form filter response carries no dataset fields")
    void agentHasNoDatasetInventory() throws Exception {
        upload(subownerToken, "inv.xlsx", workbook(PANCHAYAT_HEADER, panchayatRows("INV", 2, "INV STATE")), "INVENTORY_DS", "PANCHAYAT").andExpect(status().isCreated());
        long id = datasetRepository.findAll().get(0).getId();
        mockMvc.perform(authed(get("/api/v1/datasets"), agentToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/datasets/" + id), agentToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/v1/datasets/" + id), agentToken)).andExpect(status().isForbidden());

        String body = mockMvc.perform(authed(get("/api/v1/election-types/PANCHAYAT/filters").param("tier", "MASTER").param("target", "state"), agentToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("{\"targetField\":\"state\",\"values\":[\"INV STATE\"]}", body);
        assertFalse(body.contains("INVENTORY_DS") || body.contains("inv.xlsx") || body.contains("\"id\""));
        // no table links an Agent to a dataset
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'dataset' AND column_name ILIKE '%agent%'", Integer.class));
    }

    // ---------- login limit ----------

    @Test
    @DisplayName("A Subowner's 4th concurrent web login is rejected (never evicts) and audited as DENIED; Owner logout/terminate frees a slot")
    void sessionLimitIsEnforcedAndAudited() throws Exception {
        String login = "{\"identifier\":\"sw_other_subowner\",\"password\":\"" + PASSWORD + "\",\"clientType\":\"WEB\"}";
        String firstToken = null;
        for (int i = 0; i < 3; i++) {
            String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("SUBOWNER")).andReturn().getResponse().getContentAsString();
            if (firstToken == null) {
                firstToken = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("accessToken").asText();
            }
        }
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_LIMIT_EXCEEDED"));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action_type = 'LOGIN' AND outcome = 'DENIED' "
                + "AND actor_user_id = ? AND details->>'reason' = 'Concurrent session limit reached'", Integer.class, otherSubowner.getId()));
        // earlier sessions were not evicted by the rejected attempt
        mockMvc.perform(authed(get("/api/v1/agents"), firstToken)).andExpect(status().isOk());

        mockMvc.perform(authed(post("/api/v1/users/" + otherSubowner.getId() + "/terminate-sessions"), ownerToken)).andExpect(status().isNoContent());
        mockMvc.perform(authed(get("/api/v1/agents"), firstToken)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login)).andExpect(status().isOk());
    }
}

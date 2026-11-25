package com.elecsarthi.security;

import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review v4 critical finding: GET /api/v1/election-types/{code}/filters must be Owner/Subowner/Agent only, and
 * both the target and every narrowing filter must be a FILTER column (or the guard column) of that election type.
 * Everything runs over HTTP with the real security chain and the seeded election_type_field metadata.
 */
class ElectionTypeFilterEndpointSecurityIntegrationTest extends SecurityIntegrationTestSupport {

    private static final String VIDHAN = "/api/v1/election-types/VIDHAN_SABHA/filters";
    private static final String PALIKA = "/api/v1/election-types/NAGAR_PALIKA/filters";

    @Autowired private AppUserRepository userRepository;
    @Autowired private CustomerAccountRepository customerAccountRepository;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WebApplicationContext webApplicationContext;
    @MockitoSpyBean private JdbcTemplate spiedJdbcTemplate;

    private MockMvc mockMvc;
    private String ownerToken;
    private String subownerToken;
    private String agentToken;
    private String customerToken;
    private String subuserToken;

    @BeforeEach
    void setUpFixture() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();

        AppUser owner = user("flt_owner", Role.OWNER, null);
        AppUser subowner = user("flt_subowner", Role.SUBOWNER, null);
        AppUser agent = user("flt_agent", Role.AGENT, null);
        CustomerAccount account = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Filter Customer").contactPhone("+919123456710")
                .status(UserStatus.ACTIVE).createdByUserId(owner.getId()).build());
        AppUser customer = user("flt_customer", Role.CUSTOMER, account.getId());
        AppUser subuser = user("flt_subuser", Role.SUBUSER, account.getId());

        ownerToken = createActiveSessionAndToken(owner, ClientType.WEB, null);
        subownerToken = createActiveSessionAndToken(subowner, ClientType.WEB, null);
        agentToken = createActiveSessionAndToken(agent, ClientType.MOBILE, createActiveDevice(agent, null, "flt-agent").getId());
        customerToken = createActiveSessionAndToken(customer, ClientType.MOBILE, createActiveDevice(customer, account.getId(), "flt-cust").getId());
        subuserToken = createActiveSessionAndToken(subuser, ClientType.MOBILE, createActiveDevice(subuser, account.getId(), "flt-sub").getId());

        Instant now = Instant.now();
        Dataset ds = datasetRepository.save(Dataset.builder().name("Filter DS").originalFileName("v.xlsx")
                .fileSha256("c".repeat(64)).storageTier(DatasetTier.MASTER).status(DatasetStatus.IMPORTED)
                .deleteAfter(now.plus(60, ChronoUnit.DAYS)).importedByUserId(owner.getId()).createdAt(now).build());
        jdbcTemplate.execute("SELECT create_dataset_partitions(" + ds.getId() + ");");
        String sql = "INSERT INTO voter_d" + ds.getId() + " (dataset_id, source_record_key_hash, state, district, ac_no, part_no, "
                + "booth_no, serial_no, voter_id, v_fname_en, v_lname_en, vr_fname_en, vr_lname_en, house_no, age, sex, "
                + "municipal_name_nagar_palika_en) VALUES (?, ?, ?, 'PUNE', ?, ?, ?, ?, ?, 'ANSH', 'KAPIL', 'RAM', 'KAPIL', '101', 30, 'M', ?)";
        jdbcTemplate.update(sql, ds.getId(), "f1", "MAHARASHTRA", 12, 1, 1, 1, "EPIC-SECRET-1", "PUNE CITY");
        jdbcTemplate.update(sql, ds.getId(), "f2", "MAHARASHTRA", 13, 2, 2, 2, "EPIC-SECRET-2", "PUNE CITY");
        jdbcTemplate.update(sql, ds.getId(), "f3", "GUJARAT", 7, 3, 3, 3, "EPIC-SECRET-3", "SURAT");
        Mockito.clearInvocations(spiedJdbcTemplate);
    }

    // ---------- authorization ----------

    @Test
    @DisplayName("CUSTOMER and SUBUSER get 403 and no voter query runs")
    void customerAndSubuserForbidden() throws Exception {
        for (String token : new String[] {customerToken, subuserToken}) {
            mockMvc.perform(request(token, VIDHAN, "target", "voter_id")).andExpect(status().isForbidden());
            // even a perfectly legitimate sale-form request is forbidden for them
            mockMvc.perform(request(token, VIDHAN, "target", "ac_no", "state", "MAHARASHTRA")).andExpect(status().isForbidden());
        }
        assertNoVoterQuery();
    }

    @Test
    @DisplayName("Unauthenticated request is rejected")
    void unauthenticatedRejected() throws Exception {
        mockMvc.perform(get(VIDHAN).param("target", "ac_no")).andExpect(status().is4xxClientError()).andExpect(result -> assertNotEquals(200, result.getResponse().getStatus()));
        assertNoVoterQuery();
    }

    @Test
    @DisplayName("OWNER, SUBOWNER and AGENT can use a legitimate sale-form filter")
    void ownerSubownerAgentAllowed() throws Exception {
        for (String token : new String[] {ownerToken, subownerToken, agentToken}) {
            mockMvc.perform(request(token, VIDHAN, "target", "ac_no", "state", "MAHARASHTRA"))
                    .andExpect(status().isOk());
        }
    }

    // ---------- column validation ----------

    @Test
    @DisplayName("DISPLAY columns as target are 400 and never reach the voter table")
    void displayTargetRejected() throws Exception {
        for (String col : new String[] {"voter_id", "v_fname_en", "vr_fname_en", "age", "sex", "house_no",
                "part_no", "serial_no", "booth_address_en"}) {
            mockMvc.perform(request(ownerToken, VIDHAN, "target", col, "state", "MAHARASHTRA"))
                    .andExpect(status().isBadRequest());
        }
        assertNoVoterQuery();
    }

    @Test
    @DisplayName("Unknown / other-type / encrypted columns as target are 400")
    void unknownTargetRejected() throws Exception {
        for (String col : new String[] {"no_such_column", "mobile_no_enc", "mobile_no", "pc_no", "nagar_palika_ward_no", "", " "}) {
            mockMvc.perform(request(ownerToken, VIDHAN, "target", col)).andExpect(status().isBadRequest());
        }
        assertNoVoterQuery();
    }

    @Test
    @DisplayName("DISPLAY columns as parent filters are 400, even with a blank value")
    void displayParentFilterRejected() throws Exception {
        for (String col : new String[] {"voter_id", "part_no", "serial_no", "house_no", "v_fname_en"}) {
            mockMvc.perform(request(ownerToken, VIDHAN, "target", "ac_no", col, "1")).andExpect(status().isBadRequest());
            mockMvc.perform(request(ownerToken, VIDHAN, "target", "ac_no", col, "")).andExpect(status().isBadRequest());
        }
        assertNoVoterQuery();
    }

    @Test
    @DisplayName("Unknown parent filter columns are 400")
    void unknownParentFilterRejected() throws Exception {
        mockMvc.perform(request(ownerToken, VIDHAN, "target", "ac_no", "no_such_column", "x")).andExpect(status().isBadRequest());
        mockMvc.perform(request(ownerToken, VIDHAN, "target", "ac_no", "mobile_no_enc", "x")).andExpect(status().isBadRequest());
        assertNoVoterQuery();
    }

    @Test
    @DisplayName("Guard column of the election type is accepted although it is a DISPLAY field")
    void guardColumnAccepted() throws Exception {
        mockMvc.perform(request(ownerToken, PALIKA, "target", "municipal_name_nagar_palika_en", "state", "MAHARASHTRA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values[0]").value("PUNE CITY"));
        // as a narrowing filter too
        mockMvc.perform(request(ownerToken, PALIKA, "target", "state", "municipal_name_nagar_palika_en", "SURAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values[0]").value("GUJARAT"));
        // ... but only for its own election type
        mockMvc.perform(request(ownerToken, VIDHAN, "target", "municipal_name_nagar_palika_en"))
                .andExpect(status().isBadRequest());
    }

    // ---------- SQL safety ----------

    @Test
    @DisplayName("Column-name injection is rejected before any SQL; value injection stays a bound parameter")
    void injectionCannotAlterSql() throws Exception {
        int before = jdbcTemplate.queryForObject("SELECT count(*) FROM voter", Integer.class);

        for (String evil : new String[] {"ac_no FROM voter--", "ac_no; DROP TABLE voter;--", "ac_no,voter_id",
                "(SELECT voter_id FROM voter LIMIT 1)", "AC_NO --"}) {
            mockMvc.perform(request(ownerToken, VIDHAN, "target", evil)).andExpect(status().isBadRequest());
            mockMvc.perform(request(ownerToken, VIDHAN, "target", "ac_no", evil, "1")).andExpect(status().isBadRequest());
        }
        assertNoVoterQuery();

        // A hostile VALUE is just a string that matches nothing.
        mockMvc.perform(request(ownerToken, VIDHAN, "target", "ac_no", "state", "' OR '1'='1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values").isEmpty());

        assertEquals(before, jdbcTemplate.queryForObject("SELECT count(*) FROM voter", Integer.class));
    }

    // ---------- legitimate cascade still works ----------

    @Test
    @DisplayName("Sale-form cascade state -> ac_no still returns the expected options")
    void cascadeStillWorks() throws Exception {
        mockMvc.perform(request(agentToken, VIDHAN, "target", "state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetField").value("state"))
                .andExpect(jsonPath("$.values[0]").value("GUJARAT"))
                .andExpect(jsonPath("$.values[1]").value("MAHARASHTRA"));
        mockMvc.perform(request(agentToken, VIDHAN, "target", "ac_no", "state", "MAHARASHTRA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.length()").value(2))
                .andExpect(jsonPath("$.values[0]").value(12))
                .andExpect(jsonPath("$.values[1]").value(13));
        // Case and whitespace of a valid column name are still tolerated as before.
        mockMvc.perform(request(agentToken, VIDHAN, "target", " AC_NO ", "state", "GUJARAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values[0]").value(7));
        // A non-numeric value for an INT filter is a 400 with no voter query.
        Mockito.clearInvocations(spiedJdbcTemplate);
        mockMvc.perform(request(agentToken, VIDHAN, "target", "state", "ac_no", "abc")).andExpect(status().isBadRequest());
        assertNoVoterQuery();
    }

    // ---------- helpers ----------

    private AppUser user(String name, Role role, Long customerAccountId) {
        return userRepository.save(AppUser.builder().username(name).fullName(name)
                .passwordHash(passwordEncoder.encode("Password123!")).role(role).status(UserStatus.ACTIVE)
                .customerAccountId(customerAccountId).build());
    }

    private MockHttpServletRequestBuilder request(String token, String path, String... keyValues) {
        MockHttpServletRequestBuilder b = get(path).header("Authorization", "Bearer " + token);
        for (int i = 0; i < keyValues.length; i += 2) {
            b.param(keyValues[i], keyValues[i + 1]);
        }
        return b;
    }

    /** The voter-data SELECT DISTINCT is the only query this endpoint issues through JdbcTemplate. */
    private void assertNoVoterQuery() {
        long voterQueries = Mockito.mockingDetails(spiedJdbcTemplate).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("query")
                        && inv.getArguments().length > 0
                        && inv.getArguments()[0] instanceof String sql
                        && sql.startsWith("SELECT DISTINCT"))
                .count();
        assertEquals(0, voterQueries, "an invalid request must never reach the voter table");
    }
}

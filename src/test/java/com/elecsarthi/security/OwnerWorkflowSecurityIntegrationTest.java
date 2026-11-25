package com.elecsarthi.security;

import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Owner audit batch 1: who may use the Owner's dataset, Subowner and Agent management endpoints, and that the
 * Owner's creation endpoints answer client mistakes with 4xx. Everything runs over HTTP through the real security
 * filter chain with real session rows and signed access tokens.
 */
class OwnerWorkflowSecurityIntegrationTest extends SecurityIntegrationTestSupport {

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
    void setUpFixture() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();

        AppUser owner = user("own_owner", Role.OWNER, null);
        AppUser subowner = user("own_subowner", Role.SUBOWNER, null);
        AppUser agent = user("own_agent", Role.AGENT, null);
        CustomerAccount account = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Owner Audit Customer").contactPhone("+919123456720")
                .status(UserStatus.ACTIVE).createdByUserId(owner.getId()).build());
        AppUser customer = user("own_customer", Role.CUSTOMER, account.getId());
        AppUser subuser = user("own_subuser", Role.SUBUSER, account.getId());

        ownerToken = createActiveSessionAndToken(owner, ClientType.WEB, null);
        subownerToken = createActiveSessionAndToken(subowner, ClientType.WEB, null);
        agentToken = createActiveSessionAndToken(agent, ClientType.MOBILE, createActiveDevice(agent, null, "own-agent").getId());
        customerToken = createActiveSessionAndToken(customer, ClientType.MOBILE, createActiveDevice(customer, account.getId(), "own-cust").getId());
        subuserToken = createActiveSessionAndToken(subuser, ClientType.MOBILE, createActiveDevice(subuser, account.getId(), "own-sub").getId());
    }

    private AppUser user(String username, Role role, Long customerAccountId) {
        return userRepository.save(AppUser.builder().username(username).fullName(username)
                .passwordHash(passwordEncoder.encode("Passw0rd!xyz")).role(role).status(UserStatus.ACTIVE)
                .customerAccountId(customerAccountId).build());
    }

    private static RequestBuilder authed(AbstractMockHttpServletRequestBuilder<?> builder, String token) {
        return token == null ? builder : builder.header("Authorization", "Bearer " + token);
    }

    private static String body(String username, String password) {
        return "{\"username\":\"" + username + "\",\"fullName\":\"Audit Person\",\"password\":\"" + password + "\"}";
    }

    private MockMultipartFile metadata() {
        return new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                "{\"name\":\"X\",\"electionTypeCode\":\"VIDHAN_SABHA\",\"storageTier\":\"MASTER\"}".getBytes());
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "x.xlsx", "application/octet-stream", new byte[] {1, 2, 3});
    }

    // ---------- who may use the Owner's dataset / user-management endpoints ----------

    @Test
    @DisplayName("Unauthenticated callers (and a garbage token) cannot reach any Owner endpoint")
    void unauthenticatedCannotReachOwnerEndpoints() throws Exception {
        for (String token : new String[] {null, "garbage.token.value"}) {
            for (String path : new String[] {"/api/v1/datasets", "/api/v1/datasets/1", "/api/v1/subowners", "/api/v1/agents", "/api/v1/users/1"}) {
                int code = mockMvc.perform(authed(get(path), token)).andReturn().getResponse().getStatus();
                assertTrue(code == 401 || code == 403, path + " answered " + code);
            }
            int upload = mockMvc.perform(authed(multipart("/api/v1/datasets/upload").file(file()).file(metadata()), token))
                    .andReturn().getResponse().getStatus();
            assertTrue(upload == 401 || upload == 403, "upload answered " + upload);
            int create = mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.APPLICATION_JSON)
                    .content(body("anon_sub", "Passw0rd!xyz")), token)).andReturn().getResponse().getStatus();
            assertTrue(create == 401 || create == 403, "create answered " + create);
        }
        assertEquals(0, userRepository.findAll().stream().filter(u -> u.getUsername().startsWith("anon_")).count());
    }

    @Test
    @DisplayName("AGENT, CUSTOMER and SUBUSER get 403 on dataset inventory, import, delete and user management")
    void nonAdministrativeRolesAreForbidden() throws Exception {
        for (String token : new String[] {agentToken, customerToken, subuserToken}) {
            mockMvc.perform(authed(get("/api/v1/datasets"), token)).andExpect(status().isForbidden());
            mockMvc.perform(authed(get("/api/v1/datasets/1"), token)).andExpect(status().isForbidden());
            mockMvc.perform(authed(delete("/api/v1/datasets/1"), token)).andExpect(status().isForbidden());
            mockMvc.perform(authed(multipart("/api/v1/datasets/upload").file(file()).file(metadata()), token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(authed(get("/api/v1/subowners"), token)).andExpect(status().isForbidden());
            mockMvc.perform(authed(get("/api/v1/agents"), token)).andExpect(status().isForbidden());
            mockMvc.perform(authed(get("/api/v1/users/1"), token)).andExpect(status().isForbidden());
            mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.APPLICATION_JSON)
                    .content(body("sneaky_sub", "Passw0rd!xyz")), token)).andExpect(status().isForbidden());
            mockMvc.perform(authed(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                    .content(body("sneaky_agent", "Passw0rd!xyz")), token)).andExpect(status().isForbidden());
        }
        assertEquals(0, userRepository.findAll().stream().filter(u -> u.getUsername().startsWith("sneaky_")).count());
    }

    @Test
    @DisplayName("SUBOWNER cannot create or list Subowners, but may create Agents and see the dataset inventory")
    void subownerCannotManageSubowners() throws Exception {
        mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.APPLICATION_JSON)
                .content(body("sub_made_sub", "Passw0rd!xyz")), subownerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/subowners"), subownerToken)).andExpect(status().isForbidden());
        assertEquals(0, userRepository.findAll().stream().filter(u -> u.getUsername().equals("sub_made_sub")).count());

        mockMvc.perform(authed(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                .content(body("sub_made_agent", "Passw0rd!xyz")), subownerToken))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("AGENT"));
        mockMvc.perform(authed(get("/api/v1/datasets"), subownerToken)).andExpect(status().isOk());
    }

    // ---------- Owner creation endpoints ----------

    @Test
    @DisplayName("Owner creates a Subowner and an Agent with exactly the endpoint's role; client-sent role/status are ignored")
    void ownerCreatesSubownerAndAgent() throws Exception {
        mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"made_sub\",\"fullName\":\"Made Sub\",\"password\":\"Passw0rd!xyz\",\"role\":\"OWNER\",\"status\":\"SUSPENDED\"}"),
                ownerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("SUBOWNER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mockMvc.perform(authed(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                        .content(body("made_agent", "Passw0rd!xyz")), ownerToken))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("AGENT"));

        AppUser sub = userRepository.findByUsername("made_sub").orElseThrow();
        assertEquals(Role.SUBOWNER, sub.getRole());
        assertEquals(UserStatus.ACTIVE, sub.getStatus());
        assertEquals(userRepository.findByUsername("own_owner").orElseThrow().getId(), sub.getCreatedByUserId());
        assertTrue(passwordEncoder.matches("Passw0rd!xyz", sub.getPasswordHash()));
        assertEquals(Role.AGENT, userRepository.findByUsername("made_agent").orElseThrow().getRole());
    }

    @Test
    @DisplayName("Owner creation endpoints reject duplicates (any letter case) and invalid input with 4xx, never 500")
    void ownerCreationRejectsBadInput() throws Exception {
        mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.APPLICATION_JSON)
                .content(body("dup_user", "Passw0rd!xyz")), ownerToken)).andExpect(status().isCreated());

        for (String duplicate : new String[] {"dup_user", "DUP_USER", " dup_user "}) {
            mockMvc.perform(authed(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                    .content(body(duplicate, "Passw0rd!xyz")), ownerToken)).andExpect(status().isConflict());
        }
        // validation
        mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.APPLICATION_JSON).content("{}"), ownerToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.APPLICATION_JSON)
                .content(body("ab", "Passw0rd!xyz")), ownerToken)).andExpect(status().isBadRequest());
        mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.APPLICATION_JSON)
                .content(body("short_pw_user", "short")), ownerToken)).andExpect(status().isBadRequest());
        // malformed JSON and wrong media type are client errors
        mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.APPLICATION_JSON).content("{bad"), ownerToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.TEXT_PLAIN).content("x"), ownerToken))
                .andExpect(status().isUnsupportedMediaType());
        // BCrypt cannot hash more than 72 bytes: that is a 400, not a 500 (also when multi-byte characters push it over)
        mockMvc.perform(authed(post("/api/v1/subowners").contentType(MediaType.APPLICATION_JSON)
                .content(body("long_pw_user", "a1!".repeat(34))), ownerToken)).andExpect(status().isBadRequest());
        mockMvc.perform(authed(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                .content(body("utf8_pw_user", "é".repeat(40))), ownerToken)).andExpect(status().isBadRequest());
        mockMvc.perform(authed(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                .content(body("max_pw_user", "a".repeat(72))), ownerToken)).andExpect(status().isCreated());

        assertEquals(0, userRepository.findAll().stream().filter(u ->
                u.getUsername().equals("long_pw_user") || u.getUsername().equals("utf8_pw_user") || u.getUsername().equals("short_pw_user")).count());
    }

    @Test
    @DisplayName("Dataset upload with a missing part, bad metadata, non-multipart body or unknown path is a 4xx for the Owner")
    void malformedRequestsAreClientErrors() throws Exception {
        mockMvc.perform(authed(multipart("/api/v1/datasets/upload").file(metadata()), ownerToken)).andExpect(status().isBadRequest());
        mockMvc.perform(authed(multipart("/api/v1/datasets/upload").file(file()), ownerToken)).andExpect(status().isBadRequest());
        MockMultipartFile badType = new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                "{\"name\":\"X\",\"electionTypeCode\":\"NOPE\",\"storageTier\":\"MASTER\"}".getBytes());
        mockMvc.perform(authed(multipart("/api/v1/datasets/upload").file(file()).file(badType), ownerToken)).andExpect(status().isBadRequest());
        mockMvc.perform(authed(post("/api/v1/datasets/upload").contentType(MediaType.APPLICATION_JSON).content("{}"), ownerToken))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(authed(get("/api/v1/users/not-a-number"), ownerToken)).andExpect(status().isBadRequest());
        mockMvc.perform(authed(delete("/api/v1/subowners"), ownerToken)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(authed(get("/api/v1/does-not-exist"), ownerToken)).andExpect(status().isNotFound());
        // the empty/unknown-resource answers do not reveal anything about datasets
        mockMvc.perform(authed(get("/api/v1/datasets/999999"), ownerToken)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A web-console login by an Agent, Customer or Subuser is refused and audited as DENIED")
    void wrongClientTypeLoginIsAudited() throws Exception {
        for (String username : new String[] {"own_agent", "own_customer", "own_subuser"}) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"identifier\":\"" + username + "\",\"password\":\"Passw0rd!xyz\",\"clientType\":\"WEB\"}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"own_owner\",\"password\":\"Passw0rd!xyz\",\"clientType\":\"MOBILE\",\"installationId\":\"i\"}"))
                .andExpect(status().isForbidden());

        Integer denied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action_type = 'LOGIN' AND outcome = 'DENIED' "
                        + "AND details->>'reason' = 'Client type not allowed for role'", Integer.class);
        assertEquals(4, denied);
        Integer leaked = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE details::text ILIKE '%Passw0rd%'", Integer.class);
        assertEquals(0, leaked, "no password in any audit detail");
    }
}

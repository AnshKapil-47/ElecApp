package com.elecsarthi.security;

import com.elecsarthi.dto.request.StartSyncSessionRequest;
import com.elecsarthi.dto.request.VoterSearchRequest;
import com.elecsarthi.dto.response.SyncSessionResponse;
import com.elecsarthi.dto.response.VoterDetailResponse;
import com.elecsarthi.dto.response.VoterSummaryResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.DevicePlatform;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.ElectionTypeCode;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.SyncService;
import com.elecsarthi.service.VoterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A Customer receives only the voter columns that its election type lists in {@code election_type_field}
 * (FILTER and DISPLAY), on every retrieval path: search, detail, family, slip and sync chunks.
 *
 * <p>Every voter column of the two fixtures holds a unique marker value, so a leaked column is detected by its
 * marker in the serialized response. The expected column set is read independently from the metadata table.
 */
public class CustomerVoterFieldVisibilityIntegrationTest extends SecurityIntegrationTestSupport {

    private static final String DATASET_NAME_MARK = "SECRET_DATASET_NAME_MARK";
    private static final String FILE_NAME_MARK = "secret_file_name_mark.xlsx";
    /** Columns the fixtures give real (non-marker) values so that scope, booth and family logic work. */
    private static final Set<String> FIXED_VALUE_COLUMNS = Set.of(
            "serial_no", "voter_id", "part_no", "booth_no", "house_no", "age", "sex");

    @Autowired private AppUserRepository userRepository;
    @Autowired private CustomerAccountRepository customerAccountRepository;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private ElectionTypeRepository electionTypeRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private VoterService voterService;
    @Autowired private SyncService syncService;
    @Autowired private WebApplicationContext webApplicationContext;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MockMvc mockMvc;

    private record Fixture(AppUser user, CustomerAccount account, Entitlement entitlement, Long voterId, Long housemateId, Set<String> realValued) {}

    private AppUser owner;
    private Fixture vidhan;
    private Fixture panchayat;
    private Map<String, Pattern> markers;

    @BeforeEach
    void fixtures() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        owner = userRepository.save(AppUser.builder().username("vis_owner").fullName("Owner")
                .passwordHash(passwordEncoder.encode("Password123!")).role(Role.OWNER).status(UserStatus.ACTIVE).build());
        markers = markerPatterns();
        vidhan = fixture(ElectionTypeCode.VIDHAN_SABHA, "vis_cust_vidhan", "+919800000001",
                "{\"ac_no\":201,\"state\":\"MADHYA PRADESH\"}",
                Map.of("state", "MADHYA PRADESH", "ac_no", 201), "a1");
        panchayat = fixture(ElectionTypeCode.PANCHAYAT, "vis_cust_panch", "+919800000002",
                "{\"district\":\"AUDIT SUB DISTRICT\",\"panchayat_no\":501,\"state\":\"CHHATTISGARH\"}",
                Map.of("state", "CHHATTISGARH", "district", "AUDIT SUB DISTRICT", "panchayat_no", 501), "b2");
    }

    // ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Vidhan Sabha: list, detail, family, slip and sync show only the configured columns")
    void vidhanSabhaAllowlist() throws Exception {
        assertOnlyConfiguredColumns(vidhan, "VIDHAN_SABHA");
    }

    @Test
    @DisplayName("Panchayat: list, detail, family, slip and sync show only the configured columns")
    void panchayatAllowlist() throws Exception {
        assertOnlyConfiguredColumns(panchayat, "PANCHAYAT");
    }

    @Test
    @DisplayName("Cross-election leakage: Vidhan-only values never reach a Panchayat Customer, and the reverse")
    void crossElectionFieldsDoNotLeak() throws Exception {
        VoterDetailResponse v = voterService.getVoterDetail(vidhan.user().getId(), Role.CUSTOMER, vidhan.account().getId(),
                vidhan.entitlement().getId(), vidhan.voterId());
        assertNull(v.getPcNo(), "PC number is not configured for Vidhan Sabha");
        assertNull(v.getPcNameEn(), "PC name is not configured for Vidhan Sabha");
        assertNotNull(v.getAcNo());
        assertNotNull(v.getAcNameEn());
        String vJson = json.writeValueAsString(v);
        assertFalse(vJson.contains("MARK_panchayat_name_en_X"));
        assertFalse(vJson.contains("MARK_zilla_parishad_name_en_X"));
        assertFalse(vJson.contains("MARK_panchayat_samiti_name_en_X"));

        VoterDetailResponse p = voterService.getVoterDetail(panchayat.user().getId(), Role.CUSTOMER, panchayat.account().getId(),
                panchayat.entitlement().getId(), panchayat.voterId());
        assertNull(p.getAcNo(), "AC number is Vidhan/Lok Sabha only");
        assertNull(p.getAcNameEn(), "AC name is Vidhan/Lok Sabha only");
        assertNull(p.getPcNo());
        assertNull(p.getPcNameEn());
        assertNull(p.getSectionNo(), "section number is not configured for Panchayat");
        assertNotNull(p.getAdditionalFields());
        assertTrue(p.getAdditionalFields().containsKey("panchayat_name_en"), "Panchayat names are configured display fields");
        assertFalse(p.getAdditionalFields().containsKey("tehsil"));
        assertFalse(p.getAdditionalFields().containsKey("pincode"));
        assertFalse(json.writeValueAsString(p).contains("MARK_ac_name_en_X"));
    }

    @Test
    @DisplayName("Nothing internal is returned: no dataset id, name or file, hash, encrypted number, image key")
    void internalColumnsNeverReturned() throws Exception {
        for (Fixture f : List.of(vidhan, panchayat)) {
            String all = json.writeValueAsString(List.of(
                    voterService.getVoterDetail(f.user().getId(), Role.CUSTOMER, f.account().getId(), f.entitlement().getId(), f.voterId()),
                    voterService.searchVoters(f.user().getId(), Role.CUSTOMER, f.account().getId(), search(f)).getContent(),
                    voterService.getFamilyMembers(f.user().getId(), Role.CUSTOMER, f.account().getId(), f.entitlement().getId(), f.voterId()),
                    voterService.getVoterSlip(f.user().getId(), Role.CUSTOMER, f.account().getId(), f.entitlement().getId(), f.voterId())));
            for (String forbidden : List.of("datasetId", "dataset_id", "sourceRecordKeyHash", "source_record_key_hash", "mobileNoEnc",
                    "mobile_no_enc", "mobileKeyVersion", "voterImageRef", "voter_image_ref", "createdAt", DATASET_NAME_MARK, FILE_NAME_MARK)) {
                assertFalse(all.contains(forbidden), "response must not contain " + forbidden);
            }
        }
    }

    @Test
    @DisplayName("Unsupported columns cannot be requested: sort, extra properties and injection attempts change nothing")
    void unsupportedFieldRequestsAreHarmless() throws Exception {
        UserDevice device = realKeyDevice(vidhan.user());
        KeyPair kp = lastKeyPair;
        String token = createActiveSessionAndToken(vidhan.user(), ClientType.MOBILE, device.getId());
        String body = "{\"entitlementId\":" + vidhan.entitlement().getId()
                + ",\"sortBy\":\"mobile_no_enc; DROP TABLE voter\",\"sortDirection\":\"DESC\""
                + ",\"columns\":[\"mobile_no_enc\",\"dataset_id\"],\"datasetId\":999,\"fields\":\"*\"}";
        MvcResult result = signedPost("/api/v1/voters/search", body, token, device, kp, 1).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("\"totalElements\":3"), content);
        assertNoDisallowedMarker(content, "VIDHAN_SABHA", vidhan);
        assertFalse(content.contains("dataset"));
        assertEquals(6L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voter", Long.class), "the voter table is intact");
    }

    @Test
    @DisplayName("Search and sync-start work when page, limit and protocolVersion are omitted from the JSON")
    void pagingDefaultsApplyWhenOmitted() throws Exception {
        UserDevice device = realKeyDevice(vidhan.user());
        KeyPair kp = lastKeyPair;
        String token = createActiveSessionAndToken(vidhan.user(), ClientType.MOBILE, device.getId());

        MvcResult search = signedPost("/api/v1/voters/search", "{\"entitlementId\":" + vidhan.entitlement().getId() + "}", token, device, kp, 1).andReturn();
        assertEquals(200, search.getResponse().getStatus(), "defaults must apply: page 0, limit 50");
        assertTrue(search.getResponse().getContentAsString().contains("\"totalElements\":3"));

        MvcResult sync = signedPost("/api/v1/sync/sessions", "{\"entitlementId\":" + vidhan.entitlement().getId() + "}", token, device, kp, 2).andReturn();
        assertEquals(201, sync.getResponse().getStatus(), "protocol version 1 is assumed");
        assertTrue(sync.getResponse().getContentAsString().contains("\"totalRecords\":3"));
    }

    @Test
    @DisplayName("Another Customer's entitlement id is a 404, never a hint that it exists")
    void otherCustomersEntitlementIsNotFound() {
        Long foreign = panchayat.entitlement().getId();
        assertThrows(ResourceNotFoundException.class, () -> voterService.searchVoters(vidhan.user().getId(), Role.CUSTOMER,
                vidhan.account().getId(), VoterSearchRequest.builder().entitlementId(foreign).build()));
        assertThrows(ResourceNotFoundException.class, () -> voterService.getVoterDetail(vidhan.user().getId(), Role.CUSTOMER,
                vidhan.account().getId(), foreign, panchayat.voterId()));
        // own entitlement, but a voter of the other dataset: also 404
        assertThrows(ResourceNotFoundException.class, () -> voterService.getVoterDetail(vidhan.user().getId(), Role.CUSTOMER,
                vidhan.account().getId(), vidhan.entitlement().getId(), panchayat.voterId()));
        assertThrows(ResourceNotFoundException.class, () -> voterService.getFamilyMembers(vidhan.user().getId(), Role.CUSTOMER,
                vidhan.account().getId(), vidhan.entitlement().getId(), panchayat.voterId()));
    }

    // ---------------------------------------------------------------------------------------------------

    private void assertOnlyConfiguredColumns(Fixture f, String typeCode) throws Exception {
        Set<String> allowed = allowedColumns(typeCode);

        // detail: every configured, marker-valued column is present, no other column is
        VoterDetailResponse detail = voterService.getVoterDetail(f.user().getId(), Role.CUSTOMER, f.account().getId(),
                f.entitlement().getId(), f.voterId());
        String detailJson = json.writeValueAsString(detail);
        for (Map.Entry<String, Pattern> e : markers.entrySet()) {
            if (f.realValued().contains(e.getKey())) {
                continue;
            }
            boolean present = e.getValue().matcher(detailJson).find();
            assertEquals(allowed.contains(e.getKey()), present,
                    typeCode + " detail: column " + e.getKey() + (present ? " leaked" : " is configured but missing"));
        }

        // list, family, slip, sync chunk: nothing outside the configured set
        List<VoterSummaryResponse> list = voterService.searchVoters(f.user().getId(), Role.CUSTOMER, f.account().getId(), search(f)).getContent();
        assertEquals(3, list.size());
        assertNoDisallowedMarker(json.writeValueAsString(list), typeCode, f);
        List<VoterSummaryResponse> family = voterService.getFamilyMembers(f.user().getId(), Role.CUSTOMER, f.account().getId(),
                f.entitlement().getId(), f.voterId());
        assertEquals(2, family.size(), "the two housemates form the family");
        assertTrue(family.stream().anyMatch(m -> m.getId().equals(f.housemateId())));
        assertNoDisallowedMarker(json.writeValueAsString(family), typeCode, f);
        assertNoDisallowedMarker(json.writeValueAsString(voterService.getVoterSlip(f.user().getId(), Role.CUSTOMER,
                f.account().getId(), f.entitlement().getId(), f.voterId())), typeCode, f);

        UserDevice device = realKeyDevice(f.user());
        SyncSessionResponse session = syncService.startSyncSession(f.user().getId(), Role.CUSTOMER, f.account().getId(),
                device.getId(), StartSyncSessionRequest.builder().entitlementId(f.entitlement().getId()).build());
        assertNoDisallowedMarker(json.writeValueAsString(syncService.getChunk(f.user().getId(), Role.CUSTOMER, f.account().getId(),
                session.getSessionId(), 0)), typeCode, f);
    }

    private void assertNoDisallowedMarker(String serialized, String typeCode, Fixture f) {
        Set<String> allowed = allowedColumns(typeCode);
        for (Map.Entry<String, Pattern> e : markers.entrySet()) {
            if (!allowed.contains(e.getKey()) && !f.realValued().contains(e.getKey())) {
                assertFalse(e.getValue().matcher(serialized).find(), typeCode + ": column " + e.getKey() + " leaked");
            }
        }
    }

    private Set<String> allowedColumns(String typeCode) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT f.source_column FROM election_type_field f JOIN election_type e ON e.id = f.election_type_id WHERE e.code = ?",
                String.class, typeCode));
    }

    private VoterSearchRequest search(Fixture f) {
        return VoterSearchRequest.builder().entitlementId(f.entitlement().getId()).page(0).limit(50).build();
    }

    /** Marker detectors for every voter column that does not carry a functional value in every fixture. */
    private Map<String, Pattern> markerPatterns() {
        Map<String, Pattern> result = new LinkedHashMap<>();
        int idx = 0;
        for (Map<String, Object> col : voterColumns()) {
            String name = (String) col.get("column_name");
            if (!FIXED_VALUE_COLUMNS.contains(name)) {
                result.put(name, isInteger(col) ? Pattern.compile("(?<![0-9])" + (9_000_001 + idx) + "(?![0-9])")
                        : Pattern.compile("\"MARK_" + name + "_X\""));
            }
            idx++;
        }
        return result;
    }

    private List<Map<String, Object>> voterColumns() {
        return jdbcTemplate.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'voter' "
                        + "AND column_name NOT IN ('id','dataset_id','source_record_key_hash','mobile_no_enc','mobile_key_version','voter_image_ref','created_at') "
                        + "ORDER BY ordinal_position");
    }

    private static boolean isInteger(Map<String, Object> col) {
        return "integer".equals(col.get("data_type"));
    }

    private Fixture fixture(ElectionTypeCode code, String username, String phone, String criteria,
                            Map<String, Object> scopeValues, String tag) {
        AppUser user;
        CustomerAccount account = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Visibility " + tag).contactPhone(phone).createdByUserId(owner.getId())
                .status(UserStatus.ACTIVE).activationStatus(ActivationStatus.ACTIVATED).build());
        user = userRepository.save(AppUser.builder().username(username).fullName("Customer " + tag)
                .passwordHash(passwordEncoder.encode("Password123!")).role(Role.CUSTOMER)
                .customerAccountId(account.getId()).status(UserStatus.ACTIVE).build());

        Dataset dataset = datasetRepository.save(Dataset.builder().name(DATASET_NAME_MARK + tag).originalFileName(FILE_NAME_MARK)
                .fileSha256(tag.repeat(32)).storageTier(DatasetTier.MASTER).status(DatasetStatus.IMPORTED)
                .states(List.of("ANY")).deleteAfter(Instant.now().plus(60, ChronoUnit.DAYS)).importedByUserId(owner.getId()).build());
        jdbcTemplate.execute("SELECT create_dataset_partitions(" + dataset.getId() + ");");

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Entitlement entitlement = entitlementRepository.save(Entitlement.builder().customerAccountId(account.getId())
                .electionTypeId(electionTypeRepository.findByCode(code).orElseThrow().getId()).datasetId(dataset.getId())
                .storageTier(DatasetTier.MASTER).criteria(criteria).criteriaHash(tag.repeat(32)).scopeLabel("visibility " + tag)
                .source(EntitlementSource.DIRECT).status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.DAYS)).validUntil(now.plus(10, ChronoUnit.DAYS))
                .mobileAccess(false).createdByUserId(owner.getId()).createdAt(now).build());

        // two housemates (same part/booth and house) and one voter in another house
        Long first = insertVoter(dataset.getId(), 1, "H1", 1, scopeValues, tag + "1");
        Long housemate = insertVoter(dataset.getId(), 2, "H1", 1, scopeValues, tag + "2");
        insertVoter(dataset.getId(), 3, "H2", 2, scopeValues, tag + "3");
        return new Fixture(user, account, entitlement, first, housemate, Set.copyOf(scopeValues.keySet()));
    }

    private Long insertVoter(Long datasetId, int serial, String house, int part, Map<String, Object> scopeValues, String key) {
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        int idx = 0;
        for (Map<String, Object> col : voterColumns()) {
            String name = (String) col.get("column_name");
            Object value;
            switch (name) {
                case "serial_no" -> value = serial;
                case "voter_id" -> value = "VIS" + key;
                case "part_no", "booth_no" -> value = part;
                case "house_no" -> value = house;
                case "age" -> value = 40;
                case "sex" -> value = "M";
                default -> value = scopeValues.containsKey(name) ? scopeValues.get(name)
                        : (isInteger(col) ? (Object) (9_000_001 + idx) : "MARK_" + name + "_X");
            }
            names.add(name);
            values.add(value);
            idx++;
        }
        String sql = "INSERT INTO voter (dataset_id, source_record_key_hash, " + String.join(", ", names) + ") VALUES (?, ?, "
                + "?, ".repeat(names.size() - 1) + "?) RETURNING id";
        List<Object> params = new ArrayList<>();
        params.add(datasetId);
        params.add(CryptoUtils.sha256Hex(("vis-" + datasetId + "-" + key).getBytes(StandardCharsets.UTF_8)));
        params.addAll(values);
        return jdbcTemplate.queryForObject(sql, Long.class, params.toArray());
    }

    // ---- signed requests -----------------------------------------------------------------------------

    private KeyPair lastKeyPair;

    private UserDevice realKeyDevice(AppUser user) {
        lastKeyPair = generateEcKeyPair();
        Instant now = Instant.now();
        return deviceRepository.save(UserDevice.builder().userId(user.getId()).customerAccountId(user.getCustomerAccountId())
                .userRole(user.getRole()).platform(DevicePlatform.ANDROID).deviceName("vis-device")
                .installationId("vis-" + UUID.randomUUID())
                .publicKey(Base64.getEncoder().encodeToString(lastKeyPair.getPublic().getEncoded()))
                .status(DeviceStatus.ACTIVE).activatedAt(now).lastUsedAt(now).lastRequestCounter(0L).createdAt(now).build());
    }

    private org.springframework.test.web.servlet.ResultActions signedPost(String path, String body, String token,
                                                                          UserDevice device, KeyPair kp, long counter) throws Exception {
        long ts = System.currentTimeMillis();
        String nonce = "n-" + UUID.randomUUID();
        String payload = String.join("\n", "POST", path, CryptoUtils.sha256Hex(body.getBytes(StandardCharsets.UTF_8)),
                String.valueOf(ts), nonce, String.valueOf(counter), "");
        String signature = signData(kp.getPrivate(), payload.getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + token)
                .header("X-Device-Id", device.getId().toString())
                .header("X-Device-Signature", signature)
                .header("X-Device-Timestamp", String.valueOf(ts))
                .header("X-Device-Nonce", nonce)
                .header("X-Device-Counter", String.valueOf(counter)));
    }
}

package com.elecsarthi.mapper;

import com.elecsarthi.dto.response.VoterDetailResponse;
import com.elecsarthi.dto.response.VoterSummaryResponse;
import com.elecsarthi.entity.ElectionTypeField;
import com.elecsarthi.repository.ElectionTypeFieldRepository;
import com.elecsarthi.security.authorization.MobileNumberPresenter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps the rows of {@code SELECT v.*, vt.marked_at AS turnout_marked_at, (vt.marked_at IS NOT NULL) AS is_voted}
 * to voter responses. The booth shown is the election type's booth column; the voter's dataset is never part
 * of a response.
 *
 * <p>What a response may contain is decided on the server by the election type's field metadata
 * ({@code election_type_field}: FILTER and DISPLAY columns, the same table that drives the area filters and the
 * slip). A column the type does not list is never copied into a response, whatever the voter row holds, so a
 * Panchayat Customer never receives Vidhan-Sabha-only values. Columns that are configured but have no fixed
 * property in the DTO are returned in {@code additionalFields}, keyed by column name.
 */
@Component
@RequiredArgsConstructor
public class VoterMapper {

    /** Columns that are never returned, even if election_type_field were mis-configured to list them. */
    private static final Set<String> NEVER_RETURNED = Set.of(
            "id", "dataset_id", "source_record_key_hash", "mobile_no_enc", "mobile_key_version",
            "voter_image_ref", "created_at", "turnout_marked_at", "is_voted");

    /** Configured columns that already have a dedicated property (or a dedicated rule, like the mobile number). */
    private static final Set<String> HAS_FIXED_PROPERTY = Set.of(
            "voter_id", "serial_no", "state", "district", "pc_no", "pc_name_en", "ac_no", "ac_name_en",
            "part_no", "booth_no", "booth_name_en", "booth_address_en", "v_fname_en", "v_lname_en", "v_fname_mt",
            "v_lname_mt", "vr_fname_en", "vr_lname_en", "house_no", "section_no", "section_name_en", "age", "sex",
            "mobile_no", "voter_image");

    private final MobileNumberPresenter mobileNumbers;
    private final ElectionTypeFieldRepository fieldRepository;

    /** The configured columns of one election type, in display order, minus anything that must never leave the server. */
    public record FieldPolicy(Set<String> allowed, List<String> additional) {
        public boolean allows(String column) {
            return allowed.contains(column);
        }
    }

    public FieldPolicy fieldPolicy(Short electionTypeId) {
        Set<String> allowed = new LinkedHashSet<>();
        List<String> additional = new java.util.ArrayList<>();
        for (ElectionTypeField field : fieldRepository.findByElectionTypeIdOrderByDisplayOrderAsc(electionTypeId)) {
            String column = field.getSourceColumn().toLowerCase(java.util.Locale.ROOT);
            if (NEVER_RETURNED.contains(column) || !column.matches("[a-z][a-z0-9_]*")) {
                continue;
            }
            allowed.add(column);
            if (!HAS_FIXED_PROPERTY.contains(column)) {
                additional.add(column);
            }
        }
        return new FieldPolicy(Set.copyOf(allowed), List.copyOf(additional));
    }

    public RowMapper<VoterSummaryResponse> summaryRowMapper(String boothColumn, boolean mobileUnmasked, Short electionTypeId) {
        FieldPolicy policy = fieldPolicy(electionTypeId);
        return (rs, rowNum) -> VoterSummaryResponse.builder()
                .id(rs.getLong("id"))
                .voterId(text(rs, policy, "voter_id"))
                .serialNo(number(rs, policy, "serial_no"))
                .partNo(number(rs, policy, "part_no"))
                .boothNo(boothNo(rs, boothColumn))
                .firstNameEn(text(rs, policy, "v_fname_en"))
                .lastNameEn(text(rs, policy, "v_lname_en"))
                .firstNameMt(text(rs, policy, "v_fname_mt"))
                .lastNameMt(text(rs, policy, "v_lname_mt"))
                .relationFirstNameEn(text(rs, policy, "vr_fname_en"))
                .relationLastNameEn(text(rs, policy, "vr_lname_en"))
                .houseNo(text(rs, policy, "house_no"))
                .age(number(rs, policy, "age"))
                .gender(text(rs, policy, "sex"))
                .sectionNameEn(text(rs, policy, "section_name_en"))
                .mobileNumber(policy.allows("mobile_no") ? mobileNumbers.present(rs.getBytes("mobile_no_enc"), mobileUnmasked) : null)
                .voted(rs.getBoolean("is_voted"))
                .votedAt(votedAt(rs))
                .build();
    }

    public RowMapper<VoterDetailResponse> detailRowMapper(String boothColumn, boolean mobileUnmasked, Short electionTypeId) {
        FieldPolicy policy = fieldPolicy(electionTypeId);
        return (rs, rowNum) -> VoterDetailResponse.builder()
                .id(rs.getLong("id"))
                .voterId(text(rs, policy, "voter_id"))
                .serialNo(number(rs, policy, "serial_no"))
                .state(text(rs, policy, "state"))
                .district(text(rs, policy, "district"))
                .pcNo(number(rs, policy, "pc_no"))
                .pcNameEn(text(rs, policy, "pc_name_en"))
                .acNo(number(rs, policy, "ac_no"))
                .acNameEn(text(rs, policy, "ac_name_en"))
                .partNo(number(rs, policy, "part_no"))
                .boothNo(boothNo(rs, boothColumn))
                .boothNameEn(text(rs, policy, "booth_name_en"))
                .boothAddressEn(text(rs, policy, "booth_address_en"))
                .firstNameEn(text(rs, policy, "v_fname_en"))
                .lastNameEn(text(rs, policy, "v_lname_en"))
                .firstNameMt(text(rs, policy, "v_fname_mt"))
                .lastNameMt(text(rs, policy, "v_lname_mt"))
                .relationFirstNameEn(text(rs, policy, "vr_fname_en"))
                .relationLastNameEn(text(rs, policy, "vr_lname_en"))
                .houseNo(text(rs, policy, "house_no"))
                .sectionNo(number(rs, policy, "section_no"))
                .sectionNameEn(text(rs, policy, "section_name_en"))
                .age(number(rs, policy, "age"))
                .gender(text(rs, policy, "sex"))
                .mobileNumber(policy.allows("mobile_no") ? mobileNumbers.present(rs.getBytes("mobile_no_enc"), mobileUnmasked) : null)
                .voted(rs.getBoolean("is_voted"))
                .votedAt(votedAt(rs))
                .additionalFields(additionalFields(rs, policy))
                .build();
    }

    /** Configured columns without a fixed property, e.g. tehsil, pincode, or the Panchayat names and ward numbers. */
    private static Map<String, Object> additionalFields(ResultSet rs, FieldPolicy policy) throws SQLException {
        if (policy.additional().isEmpty()) {
            return null;
        }
        ResultSetMetaData meta = rs.getMetaData();
        Set<String> present = new java.util.HashSet<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            present.add(meta.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT));
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        for (String column : policy.additional()) {
            if (present.contains(column)) {
                extra.put(column, rs.getObject(column));
            }
        }
        return extra.isEmpty() ? null : extra;
    }

    private static String text(ResultSet rs, FieldPolicy policy, String column) throws SQLException {
        return policy.allows(column) ? rs.getString(column) : null;
    }

    private static Integer number(ResultSet rs, FieldPolicy policy, String column) throws SQLException {
        return policy.allows(column) ? rs.getObject(column, Integer.class) : null;
    }

    private static Integer boothNo(ResultSet rs, String boothColumn) throws SQLException {
        Integer boothValue = rs.getObject(boothColumn, Integer.class);
        return boothValue != null ? boothValue : rs.getObject("booth_no", Integer.class);
    }

    private static Instant votedAt(ResultSet rs) throws SQLException {
        Timestamp markedAt = rs.getTimestamp("turnout_marked_at");
        return markedAt != null ? markedAt.toInstant() : null;
    }
}

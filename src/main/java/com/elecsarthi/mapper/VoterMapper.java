package com.elecsarthi.mapper;

import com.elecsarthi.dto.response.VoterDetailResponse;
import com.elecsarthi.dto.response.VoterSummaryResponse;
import com.elecsarthi.security.authorization.MobileNumberPresenter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Maps the rows of {@code SELECT v.*, vt.marked_at AS turnout_marked_at, (vt.marked_at IS NOT NULL) AS is_voted}
 * to voter responses. The booth shown is the election type's booth column; the voter's dataset is never part
 * of a response.
 */
@Component
@RequiredArgsConstructor
public class VoterMapper {

    private final MobileNumberPresenter mobileNumbers;

    public RowMapper<VoterSummaryResponse> summaryRowMapper(String boothColumn, boolean mobileUnmasked) {
        return (rs, rowNum) -> VoterSummaryResponse.builder()
                .id(rs.getLong("id"))
                .voterId(rs.getString("voter_id"))
                .serialNo(rs.getObject("serial_no", Integer.class))
                .partNo(rs.getObject("part_no", Integer.class))
                .boothNo(boothNo(rs, boothColumn))
                .firstNameEn(rs.getString("v_fname_en"))
                .lastNameEn(rs.getString("v_lname_en"))
                .firstNameMt(rs.getString("v_fname_mt"))
                .lastNameMt(rs.getString("v_lname_mt"))
                .relationFirstNameEn(rs.getString("vr_fname_en"))
                .relationLastNameEn(rs.getString("vr_lname_en"))
                .houseNo(rs.getString("house_no"))
                .age(rs.getObject("age", Integer.class))
                .gender(rs.getString("sex"))
                .sectionNameEn(rs.getString("section_name_en"))
                .mobileNumber(mobileNumbers.present(rs.getBytes("mobile_no_enc"), mobileUnmasked))
                .voted(rs.getBoolean("is_voted"))
                .votedAt(votedAt(rs))
                .build();
    }

    public RowMapper<VoterDetailResponse> detailRowMapper(String boothColumn, boolean mobileUnmasked) {
        return (rs, rowNum) -> VoterDetailResponse.builder()
                .id(rs.getLong("id"))
                .voterId(rs.getString("voter_id"))
                .serialNo(rs.getObject("serial_no", Integer.class))
                .state(rs.getString("state"))
                .district(rs.getString("district"))
                .pcNo(rs.getObject("pc_no", Integer.class))
                .pcNameEn(rs.getString("pc_name_en"))
                .acNo(rs.getObject("ac_no", Integer.class))
                .acNameEn(rs.getString("ac_name_en"))
                .partNo(rs.getObject("part_no", Integer.class))
                .boothNo(boothNo(rs, boothColumn))
                .boothNameEn(rs.getString("booth_name_en"))
                .boothAddressEn(rs.getString("booth_address_en"))
                .firstNameEn(rs.getString("v_fname_en"))
                .lastNameEn(rs.getString("v_lname_en"))
                .firstNameMt(rs.getString("v_fname_mt"))
                .lastNameMt(rs.getString("v_lname_mt"))
                .relationFirstNameEn(rs.getString("vr_fname_en"))
                .relationLastNameEn(rs.getString("vr_lname_en"))
                .houseNo(rs.getString("house_no"))
                .sectionNo(rs.getObject("section_no", Integer.class))
                .sectionNameEn(rs.getString("section_name_en"))
                .age(rs.getObject("age", Integer.class))
                .gender(rs.getString("sex"))
                .mobileNumber(mobileNumbers.present(rs.getBytes("mobile_no_enc"), mobileUnmasked))
                .voted(rs.getBoolean("is_voted"))
                .votedAt(votedAt(rs))
                .build();
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

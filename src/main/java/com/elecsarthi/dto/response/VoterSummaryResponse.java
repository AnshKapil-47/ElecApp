package com.elecsarthi.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoterSummaryResponse {
    private Long id;
    private String voterId; // EPIC
    private Integer serialNo;
    private Integer partNo;
    private Integer boothNo;
    private String firstNameEn;
    private String lastNameEn;
    private String firstNameMt;
    private String lastNameMt;
    private String relationFirstNameEn;
    private String relationLastNameEn;
    private String houseNo;
    private Integer age;
    private String gender;
    private String sectionNameEn;
    private String mobileNumber; // decrypted if permitted, or masked, or null
    private boolean voted;
    private Instant votedAt;
}

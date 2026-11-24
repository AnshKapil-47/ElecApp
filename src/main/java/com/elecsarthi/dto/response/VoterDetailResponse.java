package com.elecsarthi.dto.response;

import lombok.*;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoterDetailResponse {
    private Long id;
    private Long datasetId;
    private String voterId;
    private Integer serialNo;
    private String state;
    private String district;
    private Integer pcNo;
    private String pcNameEn;
    private Integer acNo;
    private String acNameEn;
    private Integer partNo;
    private Integer boothNo;
    private String boothNameEn;
    private String boothAddressEn;
    private String firstNameEn;
    private String lastNameEn;
    private String firstNameMt;
    private String lastNameMt;
    private String relationFirstNameEn;
    private String relationLastNameEn;
    private String houseNo;
    private Integer sectionNo;
    private String sectionNameEn;
    private Integer age;
    private String gender;
    private String mobileNumber;
    private boolean voted;
    private Instant votedAt;
    private Map<String, Object> additionalFields;
}

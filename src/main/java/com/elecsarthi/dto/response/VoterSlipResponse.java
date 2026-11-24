package com.elecsarthi.dto.response;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoterSlipResponse {
    private Long voterRowId;
    private String voterId; // EPIC
    private Integer serialNo;
    private Integer partNo;
    private Integer boothNo;
    private String fullNameEn;
    private String fullNameMt;
    private String relationNameEn;
    private String houseNo;
    private Integer age;
    private String gender;
    private String pollingStationEn;
    private String pollingStationAddressEn;
    private List<VoterSlipFieldResponse> slipFields;
}

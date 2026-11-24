package com.elecsarthi.dto.request;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoterSearchRequest {
    private Long entitlementId;
    private String epic;
    private String name;
    private Integer booth;
    private Integer sectionNo;
    private String gender;
    private Integer minAge;
    private Integer maxAge;
    private String houseNo;
    private Boolean voted;
    @Builder.Default
    private int page = 0;
    @Builder.Default
    private int limit = 50;
    private String sortBy;
    @Builder.Default
    private String sortDirection = "ASC";
}

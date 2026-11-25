package com.elecsarthi.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
    /** Optional; the service applies page 0 and 50 rows when they are omitted (a primitive int made them mandatory in JSON). */
    private Integer page;
    private Integer limit;
    private String sortBy;
    @Builder.Default
    private String sortDirection = "ASC";
}

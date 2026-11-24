package com.elecsarthi.dto.response;

import com.elecsarthi.enums.ElectionTypeCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ElectionTypeResponse {
    private Short id;
    private ElectionTypeCode code;
    private String displayName;
    private String boothColumn;
    private String guardColumn;
    private boolean active;
    private List<ElectionTypeFieldResponse> fields;
}

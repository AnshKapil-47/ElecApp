package com.elecsarthi.dto.response;

import com.elecsarthi.enums.FieldUsage;
import com.elecsarthi.enums.FieldValueType;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ElectionTypeFieldResponse {
    private Long id;
    private String sourceColumn;
    private FieldValueType valueType;
    private String labelEn;
    private String labelMt;
    private FieldUsage usage;
    private Short filterOrder;
    private Short displayOrder;
    private boolean onSlip;
}

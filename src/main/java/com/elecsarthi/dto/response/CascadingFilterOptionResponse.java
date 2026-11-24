package com.elecsarthi.dto.response;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CascadingFilterOptionResponse {
    private String targetField;
    private List<Object> values;
}

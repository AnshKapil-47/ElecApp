package com.elecsarthi.dto.response;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportIssueResponse {
    private Long id;
    private String sheet;
    private Integer rowNo;
    private String columnName;
    private String issueCode;
    private String issueDetail;
    private Instant createdAt;
}

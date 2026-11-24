package com.elecsarthi.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoterId implements Serializable {
    private Long datasetId;
    private Long id;
}

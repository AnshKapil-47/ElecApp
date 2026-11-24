package com.elecsarthi.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceNonceId implements Serializable {
    private UUID deviceId;
    private String nonce;
}

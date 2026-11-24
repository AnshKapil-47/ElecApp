package com.elecsarthi.dto.request;

/**
 * The X-Device-* headers of a signed mobile request: proof that the caller holds the private key of the device the
 * session is bound to. Values are exactly as received (possibly null or blank); the service validates them.
 */
public record DeviceProof(String deviceId, String signature, String timestamp, String nonce, String counter, String contextId) {

    /** A request that carries no proof; always rejected for mobile sessions. */
    public static DeviceProof absent() {
        return new DeviceProof(null, null, null, null, null, null);
    }
}

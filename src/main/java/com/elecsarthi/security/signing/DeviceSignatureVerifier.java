package com.elecsarthi.security.signing;

import com.elecsarthi.security.device.CryptoUtils;
import org.springframework.stereotype.Component;

/**
 * Verifies cryptographic signatures from mobile client devices.
 * Uses ECDSA P-256 challenge-response verification.
 */
@Component
public class DeviceSignatureVerifier {

    /**
     * Verifies an ECDSA P-256 signature against the device's public key and raw challenge data.
     *
     * @param base64PublicKey Device public key (X.509 encoded in Base64)
     * @param data             Data that was signed (e.g. challenge nonce or request payload)
     * @param base64Signature  Signature in Base64
     * @return true if valid, false otherwise
     */
    public boolean verifySignature(String base64PublicKey, byte[] data, String base64Signature) {
        if (base64PublicKey == null || data == null || base64Signature == null) {
            return false;
        }
        return CryptoUtils.verifyEcdsaP256Signature(base64PublicKey, data, base64Signature);
    }
}

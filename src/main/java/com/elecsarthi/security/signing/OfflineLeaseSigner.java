package com.elecsarthi.security.signing;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.security.device.CryptoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Handles Ed25519 digital signing and verification for offline booth leases.
 */
@Component
@RequiredArgsConstructor
public class OfflineLeaseSigner {

    private final AppProperties appProperties;

    /**
     * Signs the SHA-256 hash of a lease payload using the server's Ed25519 private key.
     *
     * @param payloadSha256 Hex-encoded SHA-256 hash of the canonical JSON lease payload
     * @return Base64-encoded Ed25519 digital signature
     */
    public String signLeasePayload(String payloadSha256) {
        String privateKeyHex = appProperties.getSecurity().getEd25519PrivateKeyHex();
        byte[] privateKeyBytes = HexFormat.of().parseHex(privateKeyHex);
        byte[] signatureBytes = CryptoUtils.signEd25519(
                payloadSha256.getBytes(StandardCharsets.UTF_8), privateKeyBytes);
        return Base64.getEncoder().encodeToString(signatureBytes);
    }

    /**
     * Returns the active signing key version configured on this server.
     */
    public short getActiveKeyVersion() {
        return (short) appProperties.getSecurity().getEd25519KeyVersion();
    }
}

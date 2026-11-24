package com.elecsarthi.security.authorization;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.security.device.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * The only place a stored voter mobile number is decrypted. The caller decides once per request whether the
 * number may be shown ({@link VoterAccessAuthorizer#isMobileUnmasked}); every other number is masked, and a
 * number that cannot be decrypted is never returned in any form.
 */
@Slf4j
@Component
public class MobileNumberPresenter {

    private final byte[] aesKey;

    public MobileNumberPresenter(AppProperties appProperties) {
        this.aesKey = HexFormat.of().parseHex(appProperties.getSecurity().getVoterMobileAesKeyHex());
    }

    /** Returns the plain number, a masked form like {@code +91*****1234}, or null when absent or undecryptable. */
    public String present(byte[] encryptedMobile, boolean unmasked) {
        if (encryptedMobile == null || encryptedMobile.length == 0) {
            return null;
        }
        try {
            String plain = new String(CryptoUtils.aesGcmDecrypt(encryptedMobile, aesKey), StandardCharsets.UTF_8).trim();
            if (unmasked) {
                return plain;
            }
            return plain.length() >= 4 ? "+91*****" + plain.substring(plain.length() - 4) : "+91**********";
        } catch (Exception e) {
            log.warn("Voter mobile number decryption failed: {}", e.getMessage());
            return null;
        }
    }
}

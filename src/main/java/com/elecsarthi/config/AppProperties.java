package com.elecsarthi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "elecsarthi")
public class AppProperties {

    private boolean mobileNumbersEnabled = false;
    private JwtProperties jwt = new JwtProperties();
    private SecurityProperties security = new SecurityProperties();
    private CorsProperties cors = new CorsProperties();

    @Getter
    @Setter
    public static class CorsProperties {
        private java.util.List<String> allowedOrigins = java.util.List.of(
                "http://localhost:3000",
                "http://localhost:5173",
                "https://console.elecsarthi.com",
                "https://admin.elecsarthi.com"
        );
        private boolean allowCredentials = false;
        private java.util.List<String> allowedMethods = java.util.List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        private java.util.List<String> allowedHeaders = java.util.List.of(
                "Authorization", "Content-Type", "X-Device-Id", "X-Device-Signature",
                "X-Device-Timestamp", "X-Device-Nonce", "X-Device-Counter",
                "X-Sync-Session-Id", "X-Offline-Lease-Id"
        );
        private long maxAge = 3600L;
    }

    @Getter
    @Setter
    public static class JwtProperties {
        private String issuer = "elecsarthi-auth";
        private String secret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
        private int accessTokenExpirationMinutes = 15;
        private int refreshTokenExpirationDays = 30;
        private int sessionAbsoluteExpirationDays = 90;
        private int webIdleExpirationMinutes = 30;
        private int webAbsoluteExpirationHours = 12;
    }

    @Getter
    @Setter
    public static class SecurityProperties {
        private String activationPepper = "elecsarthi-activation-dev-pepper-2026";
        private int activationCodeExpirationHours = 72;
        private int activationMaxAttempts = 5;
        private int deviceChallengeExpirationMinutes = 2;
        private int deviceSignatureWindowMinutes = 5;
        private String ed25519PrivateKeyHex = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60e0a5ced346296312484a3b7d13e71789c9e83ec54c9da459048a1cb364718428";
        private int ed25519KeyVersion = 1;
        private String voterMobileAesKeyHex = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4";
        private int voterMobileKeyVersion = 1;
    }
}

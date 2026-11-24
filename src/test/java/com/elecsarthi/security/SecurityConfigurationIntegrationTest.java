package com.elecsarthi.security;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.security.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Production start-up validation of secrets and CORS. */
public class SecurityConfigurationIntegrationTest extends SecurityIntegrationTestSupport {

    @Test
    @DisplayName("Finding 19: Production Secret Validation - Rejects default development keys and placeholder values in prod")
    void testProductionSecretValidation() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        AppProperties prodProps = new AppProperties();
        prodProps.getJwt().setSecret("replace_with_a_real_production_secret_at_least_64_characters_long_now");
        prodProps.getSecurity().setActivationPepper("valid-pepper-12345");
        prodProps.getSecurity().setEd25519PrivateKeyHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60e0a5ced346296312484a3b7d13e71789c9e83ec54c9da459048a1cb364718428");
        prodProps.getSecurity().setVoterMobileAesKeyHex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");

        SecurityConfig secConfig = new SecurityConfig(null, null, prodEnv, prodProps);

        // Must throw IllegalStateException because secret contains placeholder "replace"
        IllegalStateException ex = assertThrows(IllegalStateException.class, secConfig::validateProductionSecurity);
        assertTrue(ex.getMessage().contains("placeholder") || ex.getMessage().contains("JWT secret"));
    }

    @Test
    @DisplayName("Finding N7 & Point 4: Production security validation enforces strict HTTPS CORS allow-list and allowCredentials=false")
    void testProductionCorsAllowListValidation() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        AppProperties prodProps = new AppProperties();
        prodProps.getJwt().setSecret("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        prodProps.getSecurity().setActivationPepper("production-pepper-real-high-entropy-random-string-2026");
        prodProps.getSecurity().setEd25519PrivateKeyHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        prodProps.getSecurity().setEd25519KeyVersion(1);
        prodProps.getSecurity().setVoterMobileAesKeyHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        prodProps.getSecurity().setVoterMobileKeyVersion(1);

        // Disallowed wildcard and wildcard subdomain
        prodProps.getCors().setAllowedOrigins(List.of("*"));
        SecurityConfig configWildcard = new SecurityConfig(null, null, prodEnv, prodProps);
        IllegalStateException wildEx = assertThrows(IllegalStateException.class, configWildcard::validateProductionSecurity);
        assertTrue(wildEx.getMessage().contains("wildcard"));

        prodProps.getCors().setAllowedOrigins(List.of("https://*.elecsarthi.com"));
        SecurityConfig configSubWildcard = new SecurityConfig(null, null, prodEnv, prodProps);
        IllegalStateException subWildEx = assertThrows(IllegalStateException.class, configSubWildcard::validateProductionSecurity);
        assertTrue(subWildEx.getMessage().contains("wildcard"));

        // Disallowed localhost / loopback
        prodProps.getCors().setAllowedOrigins(List.of("http://localhost:3000"));
        SecurityConfig configLocal = new SecurityConfig(null, null, prodEnv, prodProps);
        IllegalStateException localEx = assertThrows(IllegalStateException.class, configLocal::validateProductionSecurity);
        assertTrue(localEx.getMessage().contains("localhost"));

        prodProps.getCors().setAllowedOrigins(List.of("http://127.0.0.1:5173"));
        SecurityConfig configLoopback = new SecurityConfig(null, null, prodEnv, prodProps);
        assertThrows(IllegalStateException.class, configLoopback::validateProductionSecurity);

        // Disallowed non-HTTPS origin in production
        prodProps.getCors().setAllowedOrigins(List.of("http://console.elecsarthi.com"));
        SecurityConfig configHttp = new SecurityConfig(null, null, prodEnv, prodProps);
        assertThrows(IllegalStateException.class, configHttp::validateProductionSecurity);

        // Disallowed allowCredentials=true in production
        prodProps.getCors().setAllowedOrigins(List.of("https://console.elecsarthi.com"));
        prodProps.getCors().setAllowCredentials(true);
        SecurityConfig configCredsTrue = new SecurityConfig(null, null, prodEnv, prodProps);
        assertThrows(IllegalStateException.class, configCredsTrue::validateProductionSecurity);

        // Valid production CORS + explicit allowCredentials=false verification
        prodProps.getCors().setAllowCredentials(false);
        prodProps.getCors().setAllowedOrigins(List.of("https://console.elecsarthi.com", "https://admin.elecsarthi.com"));
        SecurityConfig configValid = new SecurityConfig(null, null, prodEnv, prodProps);
        assertDoesNotThrow(configValid::validateProductionSecurity);

        org.springframework.web.cors.CorsConfiguration corsCfg =
                configValid.corsConfigurationSource().getCorsConfiguration(new org.springframework.mock.web.MockHttpServletRequest());
        assertNotNull(corsCfg);
        assertEquals(Boolean.FALSE, corsCfg.getAllowCredentials(), "CORS allowCredentials must be explicitly false");
        assertEquals(List.of("https://console.elecsarthi.com", "https://admin.elecsarthi.com"), corsCfg.getAllowedOrigins());
    }
}

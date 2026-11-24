package com.elecsarthi.security.config;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.security.filter.JwtAuthenticationFilter;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final Environment environment;
    private final AppProperties appProperties;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          Environment environment,
                          AppProperties appProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.environment = environment;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void validateProductionSecurity() {
        if (environment.matchesProfiles("prod")) {
            // 1. JWT Secret Validation
            String jwtSecret = appProperties.getJwt().getSecret();
            if (jwtSecret == null || jwtSecret.isBlank()) {
                throw new IllegalStateException("FATAL: JWT secret is required in production!");
            }
            if ("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970".equalsIgnoreCase(jwtSecret)) {
                throw new IllegalStateException("FATAL: Default development JWT secret cannot be used in production!");
            }
            if (jwtSecret.length() < 64) {
                throw new IllegalStateException("FATAL: JWT secret must be at least 256 bits (64 hex characters) in production!");
            }

            // 2. Activation Pepper Validation
            String pepper = appProperties.getSecurity().getActivationPepper();
            if (pepper == null || pepper.isBlank()) {
                throw new IllegalStateException("FATAL: Customer activation pepper is required in production!");
            }
            if ("elecsarthi-activation-dev-pepper-2026".equals(pepper)) {
                throw new IllegalStateException("FATAL: Default development activation pepper cannot be used in production!");
            }
            if (pepper.length() < 16) {
                throw new IllegalStateException("FATAL: Customer activation pepper must be at least 16 characters in production!");
            }

            // 3. Ed25519 Private Key Validation
            String ed25519Key = appProperties.getSecurity().getEd25519PrivateKeyHex();
            if (ed25519Key == null || ed25519Key.isBlank()) {
                throw new IllegalStateException("FATAL: Ed25519 private key is required for offline lease signing in production!");
            }
            if ("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60e0a5ced346296312484a3b7d13e71789c9e83ec54c9da459048a1cb364718428".equalsIgnoreCase(ed25519Key)) {
                throw new IllegalStateException("FATAL: Default development Ed25519 private key cannot be used in production!");
            }
            if (!ed25519Key.matches("^[0-9a-fA-F]{64}$|^[0-9a-fA-F]{128}$")) {
                throw new IllegalStateException("FATAL: Ed25519 private key must be a valid 64-char or 128-char hex string in production!");
            }
            if (appProperties.getSecurity().getEd25519KeyVersion() <= 0) {
                throw new IllegalStateException("FATAL: Ed25519 key version must be a positive integer in production!");
            }

            // 4. Voter Mobile AES Key Validation
            String aesKey = appProperties.getSecurity().getVoterMobileAesKeyHex();
            if (aesKey == null || aesKey.isBlank()) {
                throw new IllegalStateException("FATAL: Voter mobile AES key is required for phone encryption in production!");
            }
            if ("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".equalsIgnoreCase(aesKey)) {
                throw new IllegalStateException("FATAL: Default development voter mobile AES key cannot be used in production!");
            }
            if (!aesKey.matches("^[0-9a-fA-F]{64}$")) {
                throw new IllegalStateException("FATAL: Voter mobile AES key must be a 64-character hex string (256 bits) in production!");
            }
            if (appProperties.getSecurity().getVoterMobileKeyVersion() <= 0) {
                throw new IllegalStateException("FATAL: Voter mobile AES key version must be a positive integer in production!");
            }
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/auth/**",
                    "/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/actuator/health"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

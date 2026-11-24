package com.elecsarthi.security.service;

import com.elecsarthi.enums.Role;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
public class ElecSarthiUserPrincipal implements UserDetails {

    private final UUID sessionId;
    private final Long userId;
    private final String username;
    private final Role role;
    private final Long customerAccountId;
    private final UUID deviceId;
    private final Collection<? extends GrantedAuthority> authorities;

    public ElecSarthiUserPrincipal(UUID sessionId, Long userId, String username, Role role, Long customerAccountId, UUID deviceId) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.customerAccountId = customerAccountId;
        this.deviceId = deviceId;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}

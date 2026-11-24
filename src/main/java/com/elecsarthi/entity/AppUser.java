package com.elecsarthi.entity;

import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_account_id")
    private Long customerAccountId;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "username", nullable = false, unique = true, columnDefinition = "citext")
    private String username;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private boolean mustChangePassword = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}

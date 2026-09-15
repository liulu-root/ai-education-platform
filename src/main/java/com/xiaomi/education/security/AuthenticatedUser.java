package com.xiaomi.education.security;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class AuthenticatedUser implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String userId;
    private final String tenantId;
    private final String displayName;
    private final String email;
    private final UserRole role;
    private String passwordHash;

    public AuthenticatedUser(
            String userId,
            String tenantId,
            String displayName,
            String email,
            UserRole role,
            String passwordHash
    ) {
        this.userId = Objects.requireNonNull(userId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.displayName = Objects.requireNonNull(displayName);
        this.email = Objects.requireNonNull(email);
        this.role = Objects.requireNonNull(role);
        this.passwordHash = Objects.requireNonNull(passwordHash);
    }

    public String userId() {
        return userId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String displayName() {
        return displayName;
    }

    public String email() {
        return email;
    }

    public UserRole role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}

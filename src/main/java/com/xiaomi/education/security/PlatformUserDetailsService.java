package com.xiaomi.education.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class PlatformUserDetailsService implements UserDetailsService {

    private final UserAccountRepository repository;

    public PlatformUserDetailsService(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var normalizedEmail = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return repository.findActiveByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}

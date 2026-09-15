package com.xiaomi.education.tenant;

import java.util.Objects;

public record TenantContext(String tenantId, String userId, String role) {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    public TenantContext {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(role);
    }

    public static TenantContext current() {
        var context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("An authenticated tenant context is required");
        }
        return context;
    }

    public static Scope open(TenantContext context) {
        Objects.requireNonNull(context);
        var previous = CURRENT.get();
        CURRENT.set(context);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}

package com.xiaomi.education.tenant;

public record TenantContext(String tenantId, String userId, String role) {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    public static TenantContext current() {
        var context = CURRENT.get();
        if (context == null) {
            return new TenantContext("tenant-demo", "learner-001", "LEARNER");
        }
        return context;
    }

    static void set(TenantContext context) {
        CURRENT.set(context);
    }

    static void clear() {
        CURRENT.remove();
    }
}

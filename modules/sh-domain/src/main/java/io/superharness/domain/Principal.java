package io.superharness.domain;

public record Principal(String tenant, String user) {
    public Principal {
        if (tenant == null
                || tenant.isBlank()
                || tenant.length() > 80
                || user == null
                || user.isBlank()
                || user.length() > 120) {
            throw new IllegalArgumentException("Invalid tenant or user");
        }
    }
}

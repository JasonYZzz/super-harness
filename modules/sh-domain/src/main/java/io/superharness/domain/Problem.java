package io.superharness.domain;

/** Stable API error code shared by the application services and HTTP translation. */
public class Problem extends RuntimeException {
    public final int status;

    public Problem(int status, String code) {
        super(code);
        this.status = status;
    }

    public static Problem missing() {
        return new Problem(404, "RESOURCE_NOT_FOUND");
    }

    public static Problem denied() {
        return new Problem(403, "ACCESS_DENIED");
    }
}

package com.marginfuse;

/** What happened to a provider call. */
public enum Outcome {
    SUCCESS("success"),
    PROVIDER_ERROR("provider_error"),
    APP_CANCELLED("app_cancelled"),
    TIMEOUT("timeout");

    private final String wire;

    Outcome(String wire) { this.wire = wire; }

    public String wire() { return wire; }
}

package com.marginfuse;

/** What the application actually did with a decision. */
public enum Acknowledgment {
    PROCEEDED_AS_REQUESTED("proceeded_as_requested"),
    USED_DOWNGRADE_MODEL("used_downgrade_model"),
    PRESENTED_TOPUP("presented_topup"),
    BLOCKED_BEFORE_PROVIDER_CALL("blocked_before_provider_call"),
    FAILED_TO_APPLY("failed_to_apply");

    private final String wire;

    Acknowledgment(String wire) { this.wire = wire; }

    public String wire() { return wire; }
}

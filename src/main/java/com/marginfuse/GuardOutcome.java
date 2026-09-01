package com.marginfuse;

/** The result of the whole guard loop. */
public final class GuardOutcome {
    /** What guard did. */
    public enum Kind { COMPLETED, BLOCKED, TOPUP_REQUIRED }

    private final Kind kind;
    private final Decision decision;
    private final Object result;

    GuardOutcome(Kind kind, Decision decision, Object result) {
        this.kind = kind;
        this.decision = decision;
        this.result = result;
    }

    public Kind kind() { return kind; }
    public Decision decision() { return decision; }
    /** Your callback's own return value. Null unless kind is COMPLETED. */
    public Object result() { return result; }
}

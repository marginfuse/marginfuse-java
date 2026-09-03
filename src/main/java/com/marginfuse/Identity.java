package com.marginfuse;

/**
 * What MarginFuse recorded for a customer.
 *
 * <p>{@link #ok()} is the only thing to branch on. When it is false the call
 * changed nothing and {@link #error()} says what happened; the SDK still did
 * not throw.
 *
 * <p>Unlike {@link MarginFuse#track(TrackParams)}, identify reports its
 * failures. track has a safe default, retry later, and "I could not record what
 * this customer pays" has none: a wrong plan is a wrong margin.
 */
public final class Identity {
    private final boolean ok;
    private final String customerId;
    private final String plan;
    private final String periodStart;
    private final String periodEnd;
    private final String error;

    Identity(boolean ok, String customerId, String plan,
             String periodStart, String periodEnd, String error) {
        this.ok = ok;
        this.customerId = customerId;
        this.plan = plan;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.error = error;
    }

    public boolean ok() { return ok; }
    public String customerId() { return customerId; }

    /** The declared plan now in force, or null when the customer is on none. */
    public String plan() { return plan; }

    public String periodStart() { return periodStart; }
    public String periodEnd() { return periodEnd; }

    /** Why the call changed nothing, when {@link #ok()} is false. */
    public String error() { return error; }
}

package com.marginfuse;

/**
 * A verdict from MarginFuse.
 *
 * <p>{@code degraded} is true when MarginFuse could not reach a verdict and the
 * request was allowed through unprotected. {@code id} is null in that case,
 * which is exactly why enforcement must depend on {@link #action()} alone.
 */
public final class Decision {
    private final String id;
    private final Action action;
    private final String model;
    private final String provider;
    private final String topupContext;
    private final boolean degraded;
    private final String degradedReason;

    Decision(String id, Action action, String model, String provider,
             String topupContext, boolean degraded, String degradedReason) {
        this.id = id;
        this.action = action;
        this.model = model;
        this.provider = provider;
        this.topupContext = topupContext;
        this.degraded = degraded;
        this.degradedReason = degradedReason;
    }

    public String id() { return id; }
    public Action action() { return action; }
    /** The model to actually call. A downgrade verdict changes it. */
    public String model() { return model; }
    public String provider() { return provider; }
    public String topupContext() { return topupContext; }
    public boolean degraded() { return degraded; }
    public String degradedReason() { return degradedReason; }

    @Override
    public String toString() {
        return "Decision{action=" + action.wire() + ", model=" + model
                + ", degraded=" + degraded + "}";
    }
}

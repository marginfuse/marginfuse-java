package com.marginfuse;

/**
 * What your callback did, handed back to guard so it can be reported.
 *
 * <p>{@code costUsd} is a decimal string, not a double: money that round-trips
 * through a floating point number stops being what the provider charged.
 */
public final class ProviderCall {
    private final Usage usage;
    private final Object result;
    private final String costUsd;
    private final Outcome outcome;

    private ProviderCall(Builder b) {
        this.usage = b.usage == null ? Usage.EMPTY : b.usage;
        this.result = b.result;
        this.costUsd = b.costUsd;
        this.outcome = b.outcome == null ? Outcome.SUCCESS : b.outcome;
    }

    public static Builder builder() { return new Builder(); }

    public Usage usage() { return usage; }
    public Object result() { return result; }
    public String costUsd() { return costUsd; }
    public Outcome outcome() { return outcome; }

    public static final class Builder {
        private Usage usage;
        private Object result;
        private String costUsd;
        private Outcome outcome;

        public Builder usage(Usage v) { this.usage = v; return this; }
        public Builder result(Object v) { this.result = v; return this; }
        public Builder costUsd(String v) { this.costUsd = v; return this; }
        public Builder outcome(Outcome v) { this.outcome = v; return this; }

        public ProviderCall build() { return new ProviderCall(this); }
    }
}

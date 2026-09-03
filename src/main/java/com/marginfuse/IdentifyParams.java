package com.marginfuse;

import java.time.Instant;
import java.util.Map;

/**
 * Says who a customer is and what plan they pay for.
 *
 * <p>{@code plan} is the key of a plan you declared in MarginFuse Settings, not
 * a Stripe price id. Leave it unset to change nothing about the plan; set
 * {@code clearPlan} to take the customer off plans entirely.
 */
public final class IdentifyParams {
    private final String customerId;
    private final String plan;
    private final boolean clearPlan;
    private final Instant periodStart;
    private final String name;
    private final String email;
    private final Map<String, String> metadata;

    private IdentifyParams(Builder b) {
        if (b.customerId == null || b.customerId.isEmpty()) {
            throw new IllegalArgumentException("marginfuse: customerId is required");
        }
        if (b.plan != null && b.clearPlan) {
            throw new IllegalArgumentException("marginfuse: set either plan or clearPlan, not both");
        }
        this.customerId = b.customerId;
        this.plan = b.plan;
        this.clearPlan = b.clearPlan;
        this.periodStart = b.periodStart;
        this.name = b.name;
        this.email = b.email;
        this.metadata = b.metadata;
    }

    public static Builder builder() { return new Builder(); }

    public String customerId() { return customerId; }
    public String plan() { return plan; }
    public boolean clearPlan() { return clearPlan; }

    /** When this customer's current cycle started, if earlier than now. */
    public Instant periodStart() { return periodStart; }

    public String name() { return name; }
    public String email() { return email; }

    /** Short labels segment policies can match on, for example tier=legacy. */
    public Map<String, String> metadata() { return metadata; }

    public static final class Builder {
        private String customerId;
        private String plan;
        private boolean clearPlan;
        private Instant periodStart;
        private String name;
        private String email;
        private Map<String, String> metadata;

        public Builder customerId(String v) { this.customerId = v; return this; }
        public Builder plan(String v) { this.plan = v; return this; }
        public Builder clearPlan(boolean v) { this.clearPlan = v; return this; }
        public Builder periodStart(Instant v) { this.periodStart = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder email(String v) { this.email = v; return this; }
        public Builder metadata(Map<String, String> v) { this.metadata = v; return this; }

        public IdentifyParams build() { return new IdentifyParams(this); }
    }
}

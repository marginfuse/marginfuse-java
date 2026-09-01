package com.marginfuse;

/** Asks about the call you are about to make. */
public final class DecideParams {
    private final String customerId;
    private final String provider;
    private final String model;
    private final String feature;
    private final Usage expectedUsage;

    private DecideParams(Builder b) {
        if (b.customerId == null || b.customerId.isEmpty()) {
            throw new IllegalArgumentException("marginfuse: customerId is required");
        }
        if (b.provider == null || b.provider.isEmpty()) {
            throw new IllegalArgumentException("marginfuse: provider is required");
        }
        if (b.model == null || b.model.isEmpty()) {
            throw new IllegalArgumentException("marginfuse: model is required");
        }
        this.customerId = b.customerId;
        this.provider = b.provider;
        this.model = b.model;
        this.feature = b.feature;
        this.expectedUsage = b.expectedUsage;
    }

    public static Builder builder() { return new Builder(); }

    public String customerId() { return customerId; }
    public String provider() { return provider; }
    public String model() { return model; }
    public String feature() { return feature; }
    public Usage expectedUsage() { return expectedUsage; }

    public static final class Builder {
        private String customerId;
        private String provider;
        private String model;
        private String feature;
        private Usage expectedUsage;

        public Builder customerId(String v) { this.customerId = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder feature(String v) { this.feature = v; return this; }
        public Builder expectedUsage(Usage v) { this.expectedUsage = v; return this; }

        public DecideParams build() { return new DecideParams(this); }
    }
}

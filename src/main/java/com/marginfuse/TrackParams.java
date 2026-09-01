package com.marginfuse;

import java.time.Instant;

/**
 * Reports a call that already happened.
 *
 * <p>{@code eventId} is the idempotency key. Leave it unset and one is
 * generated; set it yourself when you already have an id you can safely retry
 * with.
 */
public final class TrackParams {
    private final String eventId;
    private final String customerId;
    private final String provider;
    private final String model;
    private final String feature;
    private final String requestedModel;
    private final Usage usage;
    private final String costUsd;
    private final Instant occurredAt;
    private final Outcome outcome;
    private final String decisionId;
    private final String retryOfEventId;
    private final String correctsEventId;

    private TrackParams(Builder b) {
        if (b.customerId == null || b.customerId.isEmpty()) {
            throw new IllegalArgumentException("marginfuse: customerId is required");
        }
        if (b.provider == null || b.provider.isEmpty()) {
            throw new IllegalArgumentException("marginfuse: provider is required");
        }
        if (b.model == null || b.model.isEmpty()) {
            throw new IllegalArgumentException("marginfuse: model is required");
        }
        this.eventId = b.eventId;
        this.customerId = b.customerId;
        this.provider = b.provider;
        this.model = b.model;
        this.feature = b.feature;
        this.requestedModel = b.requestedModel;
        this.usage = b.usage == null ? Usage.EMPTY : b.usage;
        this.costUsd = b.costUsd;
        this.occurredAt = b.occurredAt;
        this.outcome = b.outcome == null ? Outcome.SUCCESS : b.outcome;
        this.decisionId = b.decisionId;
        this.retryOfEventId = b.retryOfEventId;
        this.correctsEventId = b.correctsEventId;
    }

    public static Builder builder() { return new Builder(); }

    public String eventId() { return eventId; }
    public String customerId() { return customerId; }
    public String provider() { return provider; }
    public String model() { return model; }
    public String feature() { return feature; }
    public String requestedModel() { return requestedModel; }
    public Usage usage() { return usage; }
    public String costUsd() { return costUsd; }
    public Instant occurredAt() { return occurredAt; }
    public Outcome outcome() { return outcome; }
    public String decisionId() { return decisionId; }
    public String retryOfEventId() { return retryOfEventId; }
    public String correctsEventId() { return correctsEventId; }

    public static final class Builder {
        private String eventId;
        private String customerId;
        private String provider;
        private String model;
        private String feature;
        private String requestedModel;
        private Usage usage;
        private String costUsd;
        private Instant occurredAt;
        private Outcome outcome;
        private String decisionId;
        private String retryOfEventId;
        private String correctsEventId;

        public Builder eventId(String v) { this.eventId = v; return this; }
        public Builder customerId(String v) { this.customerId = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder feature(String v) { this.feature = v; return this; }
        public Builder requestedModel(String v) { this.requestedModel = v; return this; }
        public Builder usage(Usage v) { this.usage = v; return this; }
        public Builder costUsd(String v) { this.costUsd = v; return this; }
        public Builder occurredAt(Instant v) { this.occurredAt = v; return this; }
        public Builder outcome(Outcome v) { this.outcome = v; return this; }
        public Builder decisionId(String v) { this.decisionId = v; return this; }
        public Builder retryOfEventId(String v) { this.retryOfEventId = v; return this; }
        public Builder correctsEventId(String v) { this.correctsEventId = v; return this; }

        public TrackParams build() { return new TrackParams(this); }
    }
}

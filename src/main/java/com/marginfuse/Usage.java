package com.marginfuse;

/**
 * What a provider call consumed.
 *
 * <p>Every field is nullable and unset means <em>not reported</em>, not "used
 * none": an unset field is left off the request entirely, because claiming a
 * call used zero input tokens is a different statement from not knowing what
 * it used. Report what you have.
 */
public final class Usage {
    public static final Usage EMPTY = builder().build();

    private final Integer inputTokens;
    private final Integer outputTokens;
    private final Integer cachedInputTokens;
    private final Integer cacheCreationTokens;
    private final Integer images;
    private final Double audioSeconds;

    private Usage(Builder b) {
        this.inputTokens = b.inputTokens;
        this.outputTokens = b.outputTokens;
        this.cachedInputTokens = b.cachedInputTokens;
        this.cacheCreationTokens = b.cacheCreationTokens;
        this.images = b.images;
        this.audioSeconds = b.audioSeconds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Integer inputTokens() { return inputTokens; }
    public Integer outputTokens() { return outputTokens; }
    public Integer cachedInputTokens() { return cachedInputTokens; }
    public Integer cacheCreationTokens() { return cacheCreationTokens; }
    public Integer images() { return images; }
    public Double audioSeconds() { return audioSeconds; }

    public static final class Builder {
        private Integer inputTokens;
        private Integer outputTokens;
        private Integer cachedInputTokens;
        private Integer cacheCreationTokens;
        private Integer images;
        private Double audioSeconds;

        public Builder inputTokens(Integer v) { this.inputTokens = v; return this; }
        public Builder outputTokens(Integer v) { this.outputTokens = v; return this; }
        public Builder cachedInputTokens(Integer v) { this.cachedInputTokens = v; return this; }
        public Builder cacheCreationTokens(Integer v) { this.cacheCreationTokens = v; return this; }
        public Builder images(Integer v) { this.images = v; return this; }
        public Builder audioSeconds(Double v) { this.audioSeconds = v; return this; }

        public Usage build() { return new Usage(this); }
    }
}

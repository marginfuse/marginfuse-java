package com.marginfuse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * OpenRouter helper.
 *
 * <p>OpenRouter returns a {@code usage} object carrying the provider-final
 * {@code cost}. Forwarding it is what makes an OpenRouter integration exact
 * rather than estimated: MarginFuse cannot know what a gateway charged,
 * because routing, fees and BYOK terms are not visible in a usage event.
 *
 * <p>Two details this helper exists to get right, both of which silently
 * misstate margin when hand-rolled:
 *
 * <ol>
 *   <li>{@code prompt_tokens} is the TOTAL input count. Cached reads and cache
 *       writes are already inside it, and MarginFuse prices input, cached input
 *       and cache creation as three separate charges and adds them up, so
 *       passing the total through double-counts every cached token at the full
 *       uncached rate.</li>
 *   <li>{@code cost} is a floating point number, and {@code Double.toString}
 *       renders small ones in exponent notation ({@code "1.2E-7"}), which the
 *       API rejects as a decimal string.</li>
 * </ol>
 */
public final class OpenRouter {

    private OpenRouter() {}

    /** What {@link #from} produced: the usage fields and, when present, the cost. */
    public static final class Mapped {
        private final Usage usage;
        private final String costUsd;

        Mapped(Usage usage, String costUsd) {
            this.usage = usage;
            this.costUsd = costUsd;
        }

        public Usage usage() { return usage; }

        /**
         * The gateway's own cost as a decimal string, or null when the response
         * carried none. Null lets the event fall through to MarginFuse's own
         * pricing instead of claiming a $0 charge.
         */
        public String costUsd() { return costUsd; }
    }

    /**
     * Maps a decoded OpenRouter {@code usage} object.
     *
     * <p>Takes a Map rather than a typed response so no particular HTTP or JSON
     * library is implied: hand it whatever your client decoded.
     *
     * <pre>{@code
     * OpenRouter.Mapped m = OpenRouter.from(usageMap);
     * mf.track(TrackParams.builder()
     *     .customerId(customerId)
     *     .provider("openrouter")
     *     .model(model)
     *     .usage(m.usage())
     *     .costUsd(m.costUsd())
     *     .build());
     * }</pre>
     */
    public static Mapped from(Map<String, ?> usage) {
        if (usage == null) return new Mapped(Usage.EMPTY, null);

        Object detailsRaw = usage.get("prompt_tokens_details");
        Map<?, ?> details = detailsRaw instanceof Map ? (Map<?, ?>) detailsRaw : null;

        int cached = toInt(details == null ? null : details.get("cached_tokens"));
        int cacheWrites = toInt(details == null ? null : details.get("cache_write_tokens"));
        // What is left after the cached parts is what was billed at the full
        // input rate. Clamped at zero so a provider reporting these differently
        // degrades to "no fresh input" rather than a negative charge.
        int fresh = Math.max(0, toInt(usage.get("prompt_tokens")) - cached - cacheWrites);
        int completion = toInt(usage.get("completion_tokens"));

        Usage mapped = Usage.builder()
                .inputTokens(fresh > 0 ? fresh : null)
                .outputTokens(completion > 0 ? completion : null)
                .cachedInputTokens(cached > 0 ? cached : null)
                .cacheCreationTokens(cacheWrites > 0 ? cacheWrites : null)
                .build();

        Object cost = usage.get("cost");
        if (!(cost instanceof Number)) return new Mapped(mapped, null);
        double value = ((Number) cost).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
            return new Mapped(mapped, null);
        }
        return new Mapped(mapped, creditsToUsd(value));
    }

    private static int toInt(Object value) {
        if (!(value instanceof Number)) return 0;
        double d = ((Number) value).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || d <= 0) return 0;
        return (int) Math.round(d);
    }

    /**
     * OpenRouter credits (1 credit = 1 USD) as a decimal string the API takes.
     *
     * <p>Fixed point to nano precision: {@code Double.toString} emits exponent
     * notation for the small costs cheap models produce, and money below a nano
     * cannot be represented at all, so it rounds down rather than pretending
     * otherwise.
     */
    static String creditsToUsd(double cost) {
        BigDecimal quantized = BigDecimal.valueOf(cost)
                .setScale(9, RoundingMode.DOWN)
                .stripTrailingZeros();
        String text = quantized.toPlainString();
        if (text.isEmpty() || text.equals("-0")) return "0";
        return text;
    }
}

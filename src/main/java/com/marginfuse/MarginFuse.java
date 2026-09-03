package com.marginfuse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Server-side SDK for MarginFuse: profitability guardrails for AI SaaS.
 *
 * <p>Reliability contract: this SDK never throws into application code and
 * never blocks a request on MarginFuse availability. {@link #decide} fails open
 * to {@link Action#ALLOW} on any timeout or error; {@link #track} and
 * {@link #acknowledge} retry on a background thread and surface problems only
 * through the error handler.
 *
 * <p>Zero dependencies. Server side only: it carries a secret API key.
 *
 * <pre>{@code
 * try (MarginFuse mf = MarginFuse.builder().apiKey(System.getenv("MARGINFUSE_KEY")).build()) {
 *     mf.track(TrackParams.builder()
 *         .customerId("cus_8x2m91")
 *         .provider("openai")
 *         .model("gpt-4.1")
 *         .usage(Usage.builder().inputTokens(1204).outputTokens(388).build())
 *         .build());
 * }
 * }</pre>
 */
public final class MarginFuse implements AutoCloseable {

    private static final String DEFAULT_BASE_URL = "https://api.marginfuse.com";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(1500);
    private static final int TRACK_RETRIES = 3;
    // Identify is not on the request hot path - it runs at sign-in - so it
    // gets room to answer rather than the decision budget.
    private static final Duration IDENTIFY_TIMEOUT = Duration.ofSeconds(5);
    /**
     * The released version of this library, as sent in the user-agent.
     *
     * <p>Checked against the version Gradle publishes by {@code VersionTest};
     * a literal nobody compares to anything drifts, which is how the Node SDK
     * came to ship two releases still reporting 0.1.0.
     */
    public static final String VERSION = "0.2.0";

    // Package private so VersionTest can read it without the production class
    // growing a method that exists only for tests.
    static final String USER_AGENT = "marginfuse-java/" + VERSION;

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final BiConsumer<Throwable, String> onError;
    private final HttpClient http;
    private final ExecutorService background;
    private final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();

    private MarginFuse(Builder b) {
        if (b.apiKey == null || b.apiKey.isEmpty()) {
            throw new IllegalArgumentException("marginfuse: apiKey is required");
        }
        this.apiKey = b.apiKey;
        String url = b.baseUrl == null ? DEFAULT_BASE_URL : b.baseUrl;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        this.baseUrl = url;
        this.timeout = b.timeout == null ? DEFAULT_TIMEOUT : b.timeout;
        this.onError = b.onError;
        this.http = b.httpClient != null ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        // One thread: events for a project are cheap and ordering them costs
        // nothing, while an unbounded pool would let a slow network spawn
        // threads inside somebody else's application.
        this.background = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "marginfuse");
            t.setDaemon(true); // never hold a JVM open
            return t;
        });
    }

    public static Builder builder() { return new Builder(); }

    // ---------------------------------------------------------------- public

    /**
     * Asks whether the next call should run. Always returns a verdict.
     *
     * <p>There is no checked exception and no null return on purpose. A failed
     * decision is not a condition to branch on: it is an allow with
     * {@link Decision#degraded()} set, because MarginFuse being unreachable
     * must never become your outage.
     */
    public Decision decide(DecideParams params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", params.customerId());
        body.put("plan", params.plan());
        body.put("feature", params.feature());
        body.put("provider", params.provider());
        body.put("model", params.model());
        if (params.expectedUsage() != null) {
            Map<String, Object> usage = usagePayload(params.expectedUsage());
            if (!usage.isEmpty()) body.put("expectedUsage", usage);
        }

        try {
            HttpResponse<String> res = post("/v1/decisions", body, timeout);
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                report(new IOException("decide: HTTP " + res.statusCode()), "decide");
                return failOpen(params, "server responded " + res.statusCode());
            }
            Map<String, Object> parsed = Json.readObject(res.body());
            String model = Json.string(parsed, "model");
            String provider = Json.string(parsed, "provider");
            return new Decision(
                    Json.string(parsed, "id"),
                    Action.fromWire(Json.string(parsed, "action")),
                    model == null ? params.model() : model,
                    provider == null ? params.provider() : provider,
                    Json.string(parsed, "topupContext"),
                    Json.bool(parsed, "degraded"),
                    Json.string(parsed, "degradedReason"));
        } catch (HttpTimeoutException e) {
            report(e, "decide");
            return failOpen(params, "timeout");
        } catch (Exception e) {
            report(e, "decide");
            return failOpen(params, "unreachable");
        }
    }

    /**
     * Reports a call that already happened. Returns immediately and sends on a
     * background thread with retries.
     *
     * <p>Call {@link #flush()} before the process exits, or the last events go
     * with it. The client is {@link AutoCloseable}, so a try-with-resources
     * block does this for you.
     */
    public void track(TrackParams params) {
        Instant when = params.occurredAt() == null ? Instant.now() : params.occurredAt();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", params.eventId() == null
                ? "evt_" + UUID.randomUUID() : params.eventId());
        event.put("customerId", params.customerId());
        event.put("plan", params.plan());
        event.put("feature", params.feature());
        event.put("provider", params.provider());
        event.put("model", params.model());
        event.put("requestedModel", params.requestedModel());
        event.put("usage", usagePayload(params.usage()));
        event.put("costUsd", params.costUsd());
        event.put("occurredAt", DateTimeFormatter.ISO_INSTANT.format(when));
        event.put("outcome", params.outcome().wire());
        event.put("decisionId", params.decisionId());
        event.put("retryOfEventId", params.retryOfEventId());
        event.put("correctsEventId", params.correctsEventId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", java.util.Collections.singletonList(event));

        submit(() -> {
            Exception last = null;
            for (int attempt = 0; attempt < TRACK_RETRIES; attempt++) {
                try {
                    HttpResponse<String> res = post("/v1/events", body, Duration.ofSeconds(5));
                    int status = res.statusCode();
                    if (status >= 200 && status < 300) return;
                    if (status >= 400 && status < 500 && status != 429) {
                        // A malformed event is malformed on every attempt.
                        report(new IOException("track: HTTP " + status + " " + snippet(res.body())),
                                "track");
                        return;
                    }
                    last = new IOException("track: HTTP " + status);
                } catch (Exception e) {
                    last = e;
                }
                try {
                    Thread.sleep(250L * (1L << attempt));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (last != null) report(last, "track");
        });
    }

    /** track for jobs and scripts that must not exit early. */
    public void trackAndWait(TrackParams params) {
        track(params);
        flush();
    }

    /**
     * Tells MarginFuse who a customer is and what plan they are on.
     *
     * <p>{@code plan} is the key of a plan you declared in MarginFuse Settings,
     * not a Stripe price id. MarginFuse derives that customer's revenue from
     * the plan's price for every cycle, which is what makes margin per customer
     * and margin policies work with no revenue source connected. Those figures
     * are labeled as a declared price wherever they appear, because nobody
     * confirmed collection.
     *
     * <p>Safe to call on every sign-in: sending the plan the customer is
     * already on changes nothing. Sending a different one ends the current
     * cycle at that moment and prorates what accrued.
     *
     * <p>Unlike {@link #track(TrackParams)}, this blocks until the server
     * answers and reports failure. track has a safe default, retry later, and
     * "I could not record what this customer pays" has none, because a wrong
     * plan is a wrong margin. It still does not throw: check
     * {@link Identity#ok()}, and the error handler is called too.
     */
    public Identity identify(IdentifyParams params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", params.customerId());
        body.put("plan", params.plan());
        if (params.clearPlan()) body.put("clearPlan", Boolean.TRUE);
        if (params.periodStart() != null) {
            body.put("periodStart", DateTimeFormatter.ISO_INSTANT.format(params.periodStart()));
        }
        body.put("name", params.name());
        body.put("email", params.email());
        if (params.metadata() != null && !params.metadata().isEmpty()) {
            body.put("metadata", params.metadata());
        }

        try {
            HttpResponse<String> res = post("/v1/identify", body, IDENTIFY_TIMEOUT);
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                IOException e = new IOException("identify: HTTP " + res.statusCode());
                report(e, "identify");
                return new Identity(false, null, null, null, null, e.getMessage());
            }
            Map<String, Object> parsed = Json.readObject(res.body());
            return new Identity(
                    true,
                    Json.string(parsed, "customerId"),
                    Json.string(parsed, "plan"),
                    Json.string(parsed, "periodStart"),
                    Json.string(parsed, "periodEnd"),
                    null);
        } catch (Exception e) {
            report(e, "identify");
            return new Identity(false, null, null, null, null, String.valueOf(e.getMessage()));
        }
    }

    /** Tells MarginFuse what your application did with a decision. */
    public void acknowledge(String decisionId, Acknowledgment acknowledgment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("acknowledgment", acknowledgment.wire());
        submit(() -> {
            try {
                HttpResponse<String> res = post(
                        "/v1/decisions/" + decisionId + "/ack", body, Duration.ofSeconds(5));
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    report(new IOException("ack: HTTP " + res.statusCode()), "acknowledge");
                }
            } catch (Exception e) {
                report(e, "acknowledge");
            }
        });
    }

    /**
     * Runs the whole loop: ask, run, report, acknowledge.
     *
     * <p>Takes a callback rather than returning a decision for you to act on,
     * because enforcement must not depend on the caller remembering to check
     * anything. When the verdict is block, the callback is never invoked.
     *
     * <p>An exception from the callback propagates unchanged: your error
     * handling owns provider failures. The attempt is recorded first, because
     * the provider may still have charged for it.
     */
    public GuardOutcome guard(DecideParams params, Function<Decision, ProviderCall> run) {
        Decision decision = decide(params);

        // Enforcement depends on the ACTION alone. A missing id costs an
        // acknowledgment; it must never turn a block into a provider call.
        if (decision.action() == Action.BLOCK) {
            if (decision.id() != null) {
                acknowledge(decision.id(), Acknowledgment.BLOCKED_BEFORE_PROVIDER_CALL);
            }
            return new GuardOutcome(GuardOutcome.Kind.BLOCKED, decision, null);
        }
        if (decision.action() == Action.TOPUP_REQUIRED) {
            if (decision.id() != null) {
                acknowledge(decision.id(), Acknowledgment.PRESENTED_TOPUP);
            }
            return new GuardOutcome(GuardOutcome.Kind.TOPUP_REQUIRED, decision, null);
        }

        String modelUsed = decision.action() == Action.DOWNGRADE
                ? decision.model() : params.model();

        ProviderCall call;
        try {
            call = run.apply(decision);
        } catch (RuntimeException | Error e) {
            track(TrackParams.builder()
                    .customerId(params.customerId())
                    .plan(params.plan())
                    .feature(params.feature())
                    .provider(params.provider())
                    .model(modelUsed)
                    .requestedModel(params.model())
                    .outcome(Outcome.PROVIDER_ERROR)
                    .decisionId(decision.id())
                    .build());
            if (decision.id() != null) {
                acknowledge(decision.id(), Acknowledgment.PROCEEDED_AS_REQUESTED);
            }
            throw e;
        }

        track(TrackParams.builder()
                .customerId(params.customerId())
                .plan(params.plan())
                .feature(params.feature())
                .provider(params.provider())
                .model(modelUsed)
                .requestedModel(params.model())
                .usage(call.usage())
                .costUsd(call.costUsd())
                .outcome(call.outcome())
                .decisionId(decision.id())
                .build());
        if (decision.id() != null) {
            acknowledge(decision.id(), decision.action() == Action.DOWNGRADE
                    ? Acknowledgment.USED_DOWNGRADE_MODEL
                    : Acknowledgment.PROCEEDED_AS_REQUESTED);
        }
        return new GuardOutcome(GuardOutcome.Kind.COMPLETED, decision, call.result());
    }

    /** Waits for queued events and acknowledgments. Never throws. */
    public void flush() {
        for (CompletableFuture<Void> f : new java.util.ArrayList<>(pending)) {
            try {
                f.join();
            } catch (RuntimeException ignored) {
                // already surfaced through the error handler
            }
        }
    }

    /** Flushes, then stops the background thread. */
    @Override
    public void close() {
        flush();
        background.shutdown();
    }

    // --------------------------------------------------------------- private

    private Decision failOpen(DecideParams params, String reason) {
        return new Decision(null, Action.ALLOW, params.model(), params.provider(),
                null, true, reason);
    }

    private void submit(Runnable task) {
        CompletableFuture<Void> future;
        try {
            future = CompletableFuture.runAsync(task, background);
        } catch (RuntimeException e) {
            // The executor is shut down. Losing an event is bad; throwing into
            // the caller's code is worse.
            return;
        }
        pending.add(future);
        future.whenComplete((v, t) -> pending.remove(future));
    }

    private void report(Throwable error, String context) {
        if (onError == null) return;
        try {
            onError.accept(error, context);
        } catch (RuntimeException | Error ignored) {
            // a broken handler is not our failure mode
        }
    }

    private static Map<String, Object> usagePayload(Usage usage) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (usage == null) return out;
        out.put("inputTokens", usage.inputTokens());
        out.put("outputTokens", usage.outputTokens());
        out.put("cachedInputTokens", usage.cachedInputTokens());
        out.put("cacheCreationTokens", usage.cacheCreationTokens());
        out.put("images", usage.images());
        out.put("audioSeconds", usage.audioSeconds());
        out.values().removeIf(java.util.Objects::isNull);
        return out;
    }

    private HttpResponse<String> post(String path, Map<String, Object> body, Duration budget)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(budget)
                .header("authorization", "Bearer " + apiKey)
                .header("content-type", "application/json")
                .header("user-agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String snippet(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    /** Builds a client. Only the API key is required. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private Duration timeout;
        private BiConsumer<Throwable, String> onError;
        private HttpClient httpClient;

        /** Your project API key. Required. */
        public Builder apiKey(String v) { this.apiKey = v; return this; }

        /** Point at your own deployment in development. */
        public Builder baseUrl(String v) { this.baseUrl = v; return this; }

        /** How long decide waits before failing open. Default 1.5 seconds. */
        public Builder timeout(Duration v) { this.timeout = v; return this; }

        /**
         * Receives transport failures the SDK swallowed, with a context string.
         * Without it they are silent by design: this SDK is in your request
         * path and must not become your outage.
         */
        public Builder onError(BiConsumer<Throwable, String> v) { this.onError = v; return this; }

        /** Replaces the default client. Useful for proxies and test doubles. */
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }

        public MarginFuse build() { return new MarginFuse(this); }
    }
}

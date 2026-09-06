package com.marginfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/**
 * What guard reports and what it acknowledges, which is the whole point of it
 * running the loop for you.
 *
 * <p>A downgrade can cross vendors: the server can answer an OpenAI request
 * with an Anthropic model. The event has to name the vendor that actually ran,
 * or the call is priced from the wrong catalogue and the saving the downgrade
 * exists to prove is computed against the wrong basis.
 *
 * <p>Driven through the builder's httpClient hook rather than a socket, so
 * these assertions are about the payloads themselves.
 */
class GuardTest {

    /** A downgrade that also moves vendor, which is the case that separates. */
    private static final String CROSS_VENDOR_DOWNGRADE =
            "{\"id\":\"dec_1\",\"action\":\"downgrade\","
                    + "\"model\":\"claude-haiku-4.5\",\"provider\":\"anthropic\"}";

    /** An allow, with no provider of its own: the ordinary case. */
    private static final String ALLOW = "{\"id\":\"dec_2\",\"action\":\"allow\"}";

    @Test
    void aCrossVendorDowngradeIsReportedAgainstTheVendorThatRan() {
        Recorder recorder = new Recorder(CROSS_VENDOR_DOWNGRADE);
        try (MarginFuse mf = client(recorder)) {
            mf.guard(openAiCall(), decision -> ProviderCall.builder()
                    .result("ok")
                    .usage(Usage.builder().inputTokens(1204).outputTokens(388).build())
                    .build());
        }

        Map<String, Object> event = only(recorder.events);
        assertEquals("anthropic", Json.string(event, "provider"));
        assertEquals("claude-haiku-4.5", Json.string(event, "model"));
        assertEquals("gpt-4.1", Json.string(event, "requestedModel"));
        assertEquals("used_downgrade_model", only(recorder.acknowledgments));
    }

    @Test
    void aDowngradeThatFailsStillAcknowledgesTheDowngrade() {
        Recorder recorder = new Recorder(CROSS_VENDOR_DOWNGRADE);
        IllegalStateException exploded = new IllegalStateException("provider exploded");

        try (MarginFuse mf = client(recorder)) {
            RuntimeException propagated = assertThrows(IllegalStateException.class,
                    () -> mf.guard(openAiCall(), decision -> { throw exploded; }));
            // The application's own error, unchanged: guard records the
            // attempt, it does not take ownership of the failure.
            assertSame(exploded, propagated);
        }

        Map<String, Object> event = only(recorder.events);
        assertEquals("anthropic", Json.string(event, "provider"));
        assertEquals("claude-haiku-4.5", Json.string(event, "model"));
        assertEquals("provider_error", Json.string(event, "outcome"));
        assertEquals("used_downgrade_model", only(recorder.acknowledgments));
    }

    @Test
    void anOrdinaryCallIsStillReportedAgainstTheCaller() {
        // The decision defaults its provider to the caller's when the server
        // sends none, so nothing about an allow moves.
        Recorder recorder = new Recorder(ALLOW);
        try (MarginFuse mf = client(recorder)) {
            mf.guard(openAiCall(), decision -> ProviderCall.builder().result("ok").build());
        }

        Map<String, Object> event = only(recorder.events);
        assertEquals("openai", Json.string(event, "provider"));
        assertEquals("gpt-4.1", Json.string(event, "model"));
        assertEquals("proceeded_as_requested", only(recorder.acknowledgments));
    }

    @Test
    void anOrdinaryCallThatFailsStillAcknowledgesProceeding() {
        Recorder recorder = new Recorder(ALLOW);
        try (MarginFuse mf = client(recorder)) {
            assertThrows(IllegalStateException.class, () -> mf.guard(openAiCall(),
                    decision -> { throw new IllegalStateException("provider exploded"); }));
        }

        assertEquals("openai", Json.string(only(recorder.events), "provider"));
        assertEquals("proceeded_as_requested", only(recorder.acknowledgments));
    }

    @Test
    void malformedDecisionsFailOpenAndReportAnError() {
        String[] bodies = {
            "{}",
            "{\"action\":\"unknown\"}",
            "{\"action\":5}",
            "{\"action\":\"downgrade\"}",
            "{\"action\":\"downgrade\",\"model\":null}",
            "{\"action\":\"downgrade\",\"model\":\" \"}",
            "{\"action\":\"allow\",\"model\":123}",
            "{\"action\":\"allow\",\"provider\":false}",
            "{\"action\":\"allow\",\"provider\":\" \"}",
            "{\"action\":\"allow\",\"model\":\"\"}",
            "{\"action\":\"allow\",\"degraded\":\"false\"}",
            "{\"action\":\"allow\",\"id\":3}"
        };
        for (String body : bodies) {
            List<String> errors = new ArrayList<>();
            try (MarginFuse mf = MarginFuse.builder().apiKey("mf_test")
                    .httpClient(new Recorder(body))
                    .onError((error, context) -> errors.add(context)).build()) {
                Decision decision = mf.decide(openAiCall());
                assertEquals(Action.ALLOW, decision.action(), body);
                assertEquals(true, decision.degraded(), body);
                assertEquals("gpt-4.1", decision.model(), body);
                assertEquals("openai", decision.provider(), body);
                assertEquals(Collections.singletonList("decide"), errors, body);
            }
        }
    }

    @Test
    void blockWithoutIdStillPreventsTheProviderCall() {
        try (MarginFuse mf = client(new Recorder("{\"action\":\"block\"}"))) {
            GuardOutcome outcome = mf.guard(openAiCall(), decision -> {
                throw new AssertionError("blocked provider must not run");
            });
            assertEquals(GuardOutcome.Kind.BLOCKED, outcome.kind());
        }
    }

    // --------------------------------------------------------------- fixture

    private static MarginFuse client(Recorder recorder) {
        return MarginFuse.builder()
                .apiKey("mf_test")
                .baseUrl("https://mock.invalid")
                .httpClient(recorder)
                .build();
    }

    private static DecideParams openAiCall() {
        return DecideParams.builder()
                .customerId("cus_8x2m91")
                .plan("pro")
                .feature("summarise")
                .provider("openai")
                .model("gpt-4.1")
                .build();
    }

    private static <T> T only(List<T> sent) {
        assertEquals(1, sent.size(), "expected exactly one, got " + sent);
        return sent.get(0);
    }

    /**
     * Answers the decision from a canned body and keeps what the SDK sends
     * afterwards. The builder takes an HttpClient for exactly this.
     */
    private static final class Recorder extends HttpClient {
        private final String decision;
        final List<Map<String, Object>> events =
                Collections.synchronizedList(new ArrayList<>());
        final List<String> acknowledgments =
                Collections.synchronizedList(new ArrayList<>());

        Recorder(String decision) { this.decision = decision; }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            String path = request.uri().getPath();
            if (path.equals("/v1/events")) {
                Map<String, Object> body = Json.readObject(bodyOf(request));
                for (Object event : (List<?>) body.get("events")) {
                    events.add((Map<String, Object>) event);
                }
            } else if (path.endsWith("/ack")) {
                acknowledgments.add(
                        Json.string(Json.readObject(bodyOf(request)), "acknowledgment"));
            }
            return (HttpResponse<T>) new Canned(
                    200, path.equals("/v1/decisions") ? decision : "{}");
        }

        /** The body as sent. ofString publishes on subscribe, so this is done
         *  by the time subscribe returns. */
        private static String bodyOf(HttpRequest request) {
            StringBuilder body = new StringBuilder();
            request.bodyPublisher().orElseThrow(IllegalStateException::new)
                    .subscribe(new Flow.Subscriber<ByteBuffer>() {
                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                            subscription.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(ByteBuffer chunk) {
                            body.append(StandardCharsets.UTF_8.decode(chunk));
                        }

                        @Override
                        public void onError(Throwable error) {
                            throw new AssertionError(error);
                        }

                        @Override
                        public void onComplete() {}
                    });
            return body.toString();
        }

        // Nothing below is reached: the SDK only ever calls send.

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public SSLParameters sslParameters() { throw new UnsupportedOperationException(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    /** The only two parts of a response this SDK reads. */
    private static final class Canned implements HttpResponse<String> {
        private final int status;
        private final String body;

        Canned(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override public int statusCode() { return status; }
        @Override public String body() { return body; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(Collections.emptyMap(), (name, value) -> true);
        }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://mock.invalid"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}

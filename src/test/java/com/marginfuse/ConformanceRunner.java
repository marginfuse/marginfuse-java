package com.marginfuse;

// Deliberately in the SDK's own package, in the test source set: the runner
// needs the package-private JSON layer, and making that public would expose an
// implementation detail in the published API forever so a conformance harness
// could read it.
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Java conformance runner.
 *
 * <p>Reads one scenario as JSON on stdin, drives this SDK against the mock
 * server the driver started, and prints one JSON report on stdout. See
 * contract/harness/runners/README.md for the contract.
 *
 * <p>Exits non-zero only if the runner itself broke. An SDK misbehaving is a
 * report for the driver to judge, not a crash here.
 */
public final class ConformanceRunner {

    private ConformanceRunner() {}

    public static void main(String[] args) throws Exception {
        Map<String, Object> scenario = Json.readObject(readStdin());

        List<Map<String, Object>> providerCalls = new ArrayList<>();
        List<String> onErrorContexts = new ArrayList<>();

        MarginFuse.Builder builder = MarginFuse.builder()
                .apiKey(getenv("MARGINFUSE_API_KEY"))
                .baseUrl(getenv("MARGINFUSE_BASE_URL"))
                .onError((error, context) -> onErrorContexts.add(context));

        Map<String, Object> options = map(scenario.get("options"));
        Number timeoutMs = options == null ? null : (Number) options.get("timeoutMs");
        if (timeoutMs != null) {
            builder.timeout(Duration.ofMillis(timeoutMs.longValue()));
        }

        MarginFuse mf = builder.build();
        Map<String, Object> params = map(scenario.get("params"));
        String action = (String) scenario.get("action");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("outcome", "returned");

        try {
            switch (action) {
                case "decide": {
                    report.put("result", decisionJson(mf.decide(decideParams(params))));
                    break;
                }
                case "track": {
                    mf.track(trackParams(params));
                    break;
                }
                case "acknowledge": {
                    mf.acknowledge((String) params.get("decisionId"),
                            Acknowledgment.valueOf(
                                    ((String) params.get("acknowledgment")).toUpperCase()));
                    break;
                }
                case "identify": {
                    // The one call that reports failure instead of failing
                    // open: a wrong plan is a wrong margin, so the application
                    // has to be able to see it.
                    IdentifyParams.Builder ib = IdentifyParams.builder()
                            .customerId((String) params.get("customerId"))
                            .plan((String) params.get("plan"))
                            .clearPlan(Boolean.TRUE.equals(params.get("clearPlan")))
                            .name((String) params.get("name"))
                            .email((String) params.get("email"));
                    String periodStart = (String) params.get("periodStart");
                    if (periodStart != null) ib.periodStart(Instant.parse(periodStart));
                    Map<String, Object> meta = map(params.get("metadata"));
                    if (meta != null) {
                        Map<String, String> labels = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> e : meta.entrySet()) {
                            labels.put(e.getKey(), String.valueOf(e.getValue()));
                        }
                        ib.metadata(labels);
                    }

                    Identity identity = mf.identify(ib.build());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("ok", identity.ok());
                    result.put("customerId", identity.customerId());
                    result.put("plan", identity.plan());
                    result.put("periodStart", identity.periodStart());
                    result.put("periodEnd", identity.periodEnd());
                    result.put("error", identity.error());
                    report.put("result", result);
                    break;
                }
                case "guard": {
                    Map<String, Object> provider = map(scenario.get("provider"));
                    boolean throwsProvider = provider != null
                            && Boolean.TRUE.equals(provider.get("throws"));
                    Usage providerUsage = usage(provider == null ? null : provider.get("usage"));

                    GuardOutcome out = mf.guard(decideParams(params), decision -> {
                        Map<String, Object> call = new LinkedHashMap<>();
                        call.put("model", decision.model());
                        call.put("provider", decision.provider());
                        providerCalls.add(call);
                        if (throwsProvider) throw new IllegalStateException("provider exploded");
                        return ProviderCall.builder().result("ok").usage(providerUsage).build();
                    });

                    // Only the discriminant and the decision travel; the
                    // application's own result means nothing to another language.
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("kind", out.kind().name().toLowerCase());
                    result.put("decision", decisionJson(out.decision()));
                    report.put("result", result);
                    break;
                }
                default:
                    System.err.println("unknown action " + action);
                    System.exit(1);
            }
        } catch (RuntimeException | Error e) {
            report.put("outcome", "threw");
            report.put("threw", e.getMessage() == null ? e.toString() : e.getMessage());
        }

        // Always flush, including after a throw: the driver asserts on what the
        // SDK sent, and guard records the attempt before it rethrows.
        mf.close();

        report.put("providerCalls", providerCalls);
        report.put("onErrorContexts", onErrorContexts);
        System.out.println(Json.write(report));
    }

    private static DecideParams decideParams(Map<String, Object> p) {
        return DecideParams.builder()
                .customerId((String) p.get("customerId"))
                .plan((String) p.get("plan"))
                .feature((String) p.get("feature"))
                .provider((String) p.get("provider"))
                .model((String) p.get("model"))
                .expectedUsage(usage(p.get("expectedUsage")))
                .build();
    }

    private static TrackParams trackParams(Map<String, Object> p) {
        TrackParams.Builder b = TrackParams.builder()
                .eventId((String) p.get("eventId"))
                .customerId((String) p.get("customerId"))
                .plan((String) p.get("plan"))
                .feature((String) p.get("feature"))
                .provider((String) p.get("provider"))
                .model((String) p.get("model"))
                .requestedModel((String) p.get("requestedModel"))
                .usage(usage(p.get("usage")))
                .costUsd((String) p.get("costUsd"))
                .decisionId((String) p.get("decisionId"));
        String outcome = (String) p.get("outcome");
        if (outcome != null) b.outcome(Outcome.valueOf(outcome.toUpperCase()));
        return b.build();
    }

    private static Usage usage(Object raw) {
        Map<String, Object> u = map(raw);
        if (u == null) return Usage.EMPTY;
        return Usage.builder()
                .inputTokens(integer(u.get("inputTokens")))
                .outputTokens(integer(u.get("outputTokens")))
                .cachedInputTokens(integer(u.get("cachedInputTokens")))
                .cacheCreationTokens(integer(u.get("cacheCreationTokens")))
                .images(integer(u.get("images")))
                .audioSeconds(u.get("audioSeconds") instanceof Number
                        ? ((Number) u.get("audioSeconds")).doubleValue() : null)
                .build();
    }

    private static Map<String, Object> decisionJson(Decision d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", d.id());
        out.put("action", d.action().wire());
        out.put("model", d.model());
        out.put("provider", d.provider());
        out.put("topupContext", d.topupContext());
        out.put("degraded", d.degraded());
        out.put("degradedReason", d.degradedReason());
        return out;
    }

    private static Integer integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static String getenv(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v;
    }

    private static String readStdin() throws Exception {
        try (InputStream in = System.in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

package com.marginfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Driven entirely by contract/conformance/gateway-vectors.json, which every SDK
 * in every language reads.
 *
 * <p>Assertions written here instead would be a second copy of the truth, and
 * this SDK would slowly stop agreeing with the others. To add a case, edit the
 * vector file, not this test.
 */
class OpenRouterVectorTest {

    private static final Pattern DECIMAL = Pattern.compile("^\\d+(\\.\\d+)?$");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases() throws Exception {
        String raw = new String(
                Files.readAllBytes(Paths.get("contract/conformance/gateway-vectors.json")),
                StandardCharsets.UTF_8);
        Map<String, Object> doc = Json.readObject(raw);
        Map<String, Object> adapters = (Map<String, Object>) doc.get("adapters");
        assertNotNull(adapters, "gateway-vectors.json has no adapters");
        Map<String, Object> openRouter = (Map<String, Object>) adapters.get("fromOpenRouter");
        assertNotNull(openRouter, "no fromOpenRouter adapter in the vector file");
        List<Map<String, Object>> list = (List<Map<String, Object>>) openRouter.get("cases");
        assertFalse(list.isEmpty(), "fromOpenRouter has no cases");
        return list;
    }

    @SuppressWarnings("unchecked")
    private static OpenRouter.Mapped run(Map<String, Object> kase) {
        if (Boolean.TRUE.equals(kase.get("omitInput"))) return OpenRouter.from(null);
        return OpenRouter.from((Map<String, ?>) kase.get("input"));
    }

    /** Only the fields the adapter actually set, in the vectors' wire names. */
    private static Map<String, Object> produced(Usage usage) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (usage.inputTokens() != null) out.put("inputTokens", usage.inputTokens());
        if (usage.outputTokens() != null) out.put("outputTokens", usage.outputTokens());
        if (usage.cachedInputTokens() != null) out.put("cachedInputTokens", usage.cachedInputTokens());
        if (usage.cacheCreationTokens() != null) {
            out.put("cacheCreationTokens", usage.cacheCreationTokens());
        }
        if (usage.images() != null) out.put("images", usage.images());
        if (usage.audioSeconds() != null) out.put("audioSeconds", usage.audioSeconds());
        return out;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> gatewayVectors() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> kase : cases()) {
            tests.add(DynamicTest.dynamicTest((String) kase.get("name"), () -> {
                OpenRouter.Mapped mapped = run(kase);

                Map<String, Object> expected = (Map<String, Object>) kase.get("expected");
                Map<String, Object> wantUsage = (Map<String, Object>) expected.get("usage");
                Map<String, Object> gotUsage = produced(mapped.usage());

                assertEquals(wantUsage.size(), gotUsage.size(),
                        "usage fields: got " + gotUsage + ", want " + wantUsage);
                for (Map.Entry<String, Object> e : wantUsage.entrySet()) {
                    Number want = (Number) e.getValue();
                    Number got = (Number) gotUsage.get(e.getKey());
                    assertNotNull(got, "usage." + e.getKey() + " missing");
                    assertEquals(want.doubleValue(), got.doubleValue(), "usage." + e.getKey());
                }

                String wantCost = (String) expected.get("costUsd");
                if (wantCost == null) {
                    // Absent must mean absent, not present-and-zero: omitting
                    // the cost lets MarginFuse price the call, where "0" would
                    // claim it was free.
                    assertNull(mapped.costUsd(), "costUsd should have been omitted");
                } else {
                    assertEquals(wantCost, mapped.costUsd());
                }
            }));
        }
        return tests;
    }

    @Test
    void neverProducesACostTheApiWouldReject() throws Exception {
        // The decimal-string pattern from the API's own schema. Exponent
        // notation is the failure this guards, and it is silent everywhere else.
        for (Map<String, Object> kase : cases()) {
            String cost = run(kase).costUsd();
            if (cost != null) {
                assertTrue(DECIMAL.matcher(cost).matches(),
                        kase.get("name") + ": " + cost + " is not a decimal string");
            }
        }
    }
}

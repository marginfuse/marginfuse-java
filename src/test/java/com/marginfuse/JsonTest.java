package com.marginfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The JSON layer is hand written so the package can have no dependencies,
 * which means its edge cases are this SDK's problem rather than a library's.
 * These are those edge cases.
 */
class JsonTest {

    @Test
    void writesAndReadsBackEveryEscape() {
        String awkward = "quote\" backslash\\ newline\n tab\t unicode ç control ";
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("s", awkward);
        String encoded = Json.write(in);
        assertEquals(awkward, Json.string(Json.readObject(encoded), "s"));
    }

    @Test
    void omitsNullValuesRatherThanSendingNull() {
        // The API rejects unknown shapes, and an explicit null is a value the
        // schema does not accept for an optional field.
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("present", "yes");
        in.put("absent", null);
        assertEquals("{\"present\":\"yes\"}", Json.write(in));
    }

    @Test
    void writesNestedStructures() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", 12);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("usage", usage);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", Arrays.asList(event));
        assertEquals("{\"events\":[{\"usage\":{\"inputTokens\":12}}]}", Json.write(body));
    }

    @Test
    void readsTheShapesTheApiReturns() {
        Map<String, Object> d = Json.readObject(
                "{\"id\":\"dec_1\",\"action\":\"downgrade\",\"degraded\":false,"
                        + "\"n\":1.5,\"big\":9007199254740993}");
        assertEquals("dec_1", Json.string(d, "id"));
        assertEquals("downgrade", Json.string(d, "action"));
        assertEquals(false, Json.bool(d, "degraded"));
        assertEquals(1.5d, d.get("n"));
        // Beyond a double's exact range: a long, not a lossy double.
        assertEquals(9007199254740993L, d.get("big"));
    }

    @Test
    void readsEscapesIncludingUnicode() {
        Map<String, Object> d = Json.readObject("{\"s\":\"a\\u00e7\\n\\t\\\"\\\\\\/\"}");
        assertEquals("aç\n\t\"\\/", Json.string(d, "s"));
    }

    @Test
    void toleratesWhitespaceAndEmptyContainers() {
        Map<String, Object> d = Json.readObject("  {\n \"a\" : { } , \"b\" : [ ]  }\n");
        assertTrue(((Map<?, ?>) d.get("a")).isEmpty());
        assertTrue(((java.util.List<?>) d.get("b")).isEmpty());
    }

    @Test
    void rejectsMalformedInputInsteadOfGuessing() {
        // decide() catches this and fails open. It must never silently return
        // a half-parsed decision, which is the failure that would let a
        // gateway's HTML error page read as an allow.
        String[] bad = {"<html>gateway error</html>", "{\"a\":}", "{\"a\":1",
                "{\"a\" 1}", "", "{}x", "{\"a\":\"unterminated"};
        for (String input : bad) {
            assertThrows(RuntimeException.class, () -> Json.readObject(input), input);
        }
    }

    @Test
    void missingKeysReadAsAbsentRatherThanBlowingUp() {
        Map<String, Object> d = Json.readObject("{}");
        assertNull(Json.string(d, "nope"));
        assertEquals(false, Json.bool(d, "nope"));
    }
}

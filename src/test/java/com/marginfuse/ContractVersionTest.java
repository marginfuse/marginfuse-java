package com.marginfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The exported contract version has to be the one this build was actually
 * verified against, or it is a claim rather than a fact.
 */
class ContractVersionTest {

    @Test
    void matchesThePinnedContract() throws Exception {
        String raw = new String(
                Files.readAllBytes(Paths.get("contract/conformance/behavior-scenarios.json")),
                StandardCharsets.UTF_8);
        Map<String, Object> pinned = Json.readObject(raw);
        assertEquals(((Number) pinned.get("version")).intValue(), Contract.VERSION);
    }
}

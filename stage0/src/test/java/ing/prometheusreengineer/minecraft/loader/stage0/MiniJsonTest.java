package ing.prometheusreengineer.minecraft.loader.stage0;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniJsonTest {
    private static void bad(String json, String why) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(json), json);
        assertTrue(e.getMessage().contains(why), json + ": " + e.getMessage());
    }

    @Test
    void aGithubRelease() {
        Map<?, ?> release = (Map<?, ?>) MiniJson.parse("{ \"tag_name\" : \"v2.0.0\", \"draft\": false, \"prerelease\": true,"
                + " \"body\": null, \"assets\": [ {\"name\": \"prometheus-core-2.0.0.jar\", \"size\": 46671} ] }");
        assertEquals("v2.0.0", release.get("tag_name"));
        assertEquals(Boolean.FALSE, release.get("draft"));
        assertEquals(Boolean.TRUE, release.get("prerelease"));
        assertTrue(release.containsKey("body"));
        assertNull(release.get("body"));
        Map<?, ?> asset = (Map<?, ?>) ((List<?>) release.get("assets")).get(0);
        assertEquals("prometheus-core-2.0.0.jar", asset.get("name"));
        assertEquals(46671.0, asset.get("size"));
    }

    @Test
    void emptyAndNested() {
        assertEquals(new LinkedHashMap<>(), MiniJson.parse("{}"));
        assertEquals(Collections.emptyList(), MiniJson.parse("[ ]"));
        assertEquals(Arrays.asList(Arrays.asList(1.0, -2.5e3), "x"), MiniJson.parse("[[1, -2.5e3], \"x\"]"));
    }

    @Test
    void escapes() {
        assertEquals("\" \\ / \b \f \n \r \t \u00e9", MiniJson.parse("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u00e9\""));
    }

    @Test
    void errors() {
        bad("", "unexpected end");
        bad("{} x", "trailing characters");
        bad("{1: 2}", "expected a key");
        bad("{\"a\": 1,", "expected a key");
        bad("{\"a\"", "expected ':'");
        bad("{\"a\": 1 \"b\": 2}", "expected ','");
        bad("[1 2]", "expected ','");
        bad("\"open", "unterminated string");
        bad("\"ends in a backslash\\", "unterminated string");
        bad("\"\\u12\"", "bad unicode escape");
        bad("-", "bad number");
        bad("tru", "expected true");
        bad("fals", "expected false");
        bad("nul", "expected null");
        bad("?", "unexpected '?'");
        bad("#", "unexpected '#'");
    }
}

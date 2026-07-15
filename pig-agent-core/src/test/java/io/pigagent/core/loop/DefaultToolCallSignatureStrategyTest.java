package io.pigagent.core.loop;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultToolCallSignatureStrategyTest {

    private final DefaultToolCallSignatureStrategy strategy = new DefaultToolCallSignatureStrategy();

    @Test
    void readFile_sameFileNearbyLineRanges_shareSignature() {
        // Arrange: same path, start lines all within the same 200-line bucket (0..199).
        // Act
        String s0 = strategy.signature("readFile", Map.of("path", "/a.txt", "offset", 0));
        String s50 = strategy.signature("readFile", Map.of("path", "/a.txt", "offset", 50));
        String s100 = strategy.signature("readFile", Map.of("path", "/a.txt", "offset", 100));

        // Assert
        assertThat(s0).isEqualTo(s50).isEqualTo(s100);
    }

    @Test
    void readFile_paramlessRead_bucketsAsZero_soRepeatsCollapse() {
        // A paramless readFile of one path must collapse to the same signature as offset 0.
        String noOffset = strategy.signature("readFile", Map.of("path", "/a.txt"));
        String offsetZero = strategy.signature("readFile", Map.of("path", "/a.txt", "offset", 0));

        assertThat(noOffset).isEqualTo(offsetZero);
    }

    @Test
    void readFile_differentBuckets_differentSignatures() {
        String bucket0 = strategy.signature("readFile", Map.of("path", "/a.txt", "offset", 10));
        String bucket1 = strategy.signature("readFile", Map.of("path", "/a.txt", "offset", 250));
        String bucket2 = strategy.signature("readFile", Map.of("path", "/a.txt", "offset", 401));

        assertThat(bucket0).isNotEqualTo(bucket1);
        assertThat(bucket1).isNotEqualTo(bucket2);
        assertThat(bucket0).isNotEqualTo(bucket2);
    }

    @Test
    void readFile_differentPaths_differentSignatures() {
        String a = strategy.signature("readFile", Map.of("path", "/a.txt"));
        String b = strategy.signature("readFile", Map.of("path", "/b.txt"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void readFile_startLineAsString_parsedIntoBucket() {
        String asInt = strategy.signature("readFile", Map.of("path", "/a.txt", "offset", 100));
        String asString = strategy.signature("readFile", Map.of("path", "/a.txt", "offset", "100"));

        assertThat(asInt).isEqualTo(asString);
    }

    @Test
    void writeFile_sameContent_sameSignature_differentContent_differs() {
        String write1 = strategy.signature("writeFile", Map.of("path", "/a.txt", "content", "hello"));
        String write1Again = strategy.signature("writeFile", Map.of("path", "/a.txt", "content", "hello"));
        String write2 = strategy.signature("writeFile", Map.of("path", "/a.txt", "content", "world"));

        assertThat(write1).isEqualTo(write1Again);
        assertThat(write1).isNotEqualTo(write2); // writes hash full args, never bucketed
    }

    @Test
    void genericTool_argumentOrderIndependent() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("a", 1);
        ordered.put("b", 2);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("b", 2);
        reordered.put("a", 1);

        assertThat(strategy.signature("executeCommand", ordered))
                .isEqualTo(strategy.signature("executeCommand", reordered));
    }

    @Test
    void differentToolNames_differentSignatures() {
        assertThat(strategy.signature("executeCommand", Map.of("cmd", "ls")))
                .isNotEqualTo(strategy.signature("webSearch", Map.of("cmd", "ls")));
    }

    @Test
    void nullOrEmptyInput_doesNotThrow_andIsStable() {
        assertThat(strategy.signature("executeCommand", null))
                .isEqualTo(strategy.signature("executeCommand", Map.of()));
    }
}

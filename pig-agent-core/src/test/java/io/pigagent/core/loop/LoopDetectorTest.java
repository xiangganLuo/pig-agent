package io.pigagent.core.loop;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoopDetectorTest {

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    void noRepeats_alwaysOk() {
        LoopDetector detector = new LoopDetector(); // defaults: window 20, warn 3, stop 5

        for (int i = 0; i < 10; i++) {
            LoopDecision decision = detector.observe("readFile", args("path", "/file" + i + ".txt"));
            assertThat(decision).isEqualTo(LoopDecision.OK);
        }
    }

    @Test
    void thirdIdenticalCall_warns() {
        LoopDetector detector = new LoopDetector();
        Map<String, Object> call = args("cmd", "mvn test");

        assertThat(detector.observe("executeCommand", call)).isEqualTo(LoopDecision.OK);
        assertThat(detector.observe("executeCommand", call)).isEqualTo(LoopDecision.OK);
        assertThat(detector.observe("executeCommand", call)).isEqualTo(LoopDecision.WARN);
    }

    @Test
    void fifthIdenticalCall_stops() {
        LoopDetector detector = new LoopDetector();
        Map<String, Object> call = args("cmd", "mvn test");

        LoopDecision d1 = detector.observe("executeCommand", call);
        LoopDecision d2 = detector.observe("executeCommand", call);
        LoopDecision d3 = detector.observe("executeCommand", call);
        LoopDecision d4 = detector.observe("executeCommand", call);
        LoopDecision d5 = detector.observe("executeCommand", call);

        assertThat(d1).isEqualTo(LoopDecision.OK);
        assertThat(d2).isEqualTo(LoopDecision.OK);
        assertThat(d3).isEqualTo(LoopDecision.WARN);
        assertThat(d4).isEqualTo(LoopDecision.WARN);
        assertThat(d5).isEqualTo(LoopDecision.STOP);
    }

    @Test
    void readFile_nearbyLineRanges_accumulateToWarn() {
        // Same file, slightly different offsets within the same 200-line bucket → counted as repeats.
        LoopDetector detector = new LoopDetector();

        assertThat(detector.observe("readFile", args("path", "/big.log", "offset", 0)))
                .isEqualTo(LoopDecision.OK);
        assertThat(detector.observe("readFile", args("path", "/big.log", "offset", 40)))
                .isEqualTo(LoopDecision.OK);
        assertThat(detector.observe("readFile", args("path", "/big.log", "offset", 120)))
                .isEqualTo(LoopDecision.WARN);
    }

    @Test
    void readFile_differentBuckets_doNotTrigger() {
        LoopDetector detector = new LoopDetector();

        assertThat(detector.observe("readFile", args("path", "/big.log", "offset", 0)))
                .isEqualTo(LoopDecision.OK);
        assertThat(detector.observe("readFile", args("path", "/big.log", "offset", 200)))
                .isEqualTo(LoopDecision.OK);
        assertThat(detector.observe("readFile", args("path", "/big.log", "offset", 400)))
                .isEqualTo(LoopDecision.OK);
    }

    @Test
    void differentToolsAndArgs_neverTrigger() {
        LoopDetector detector = new LoopDetector();

        for (int i = 0; i < 6; i++) {
            assertThat(detector.observe("executeCommand", args("cmd", "step-" + i)))
                    .isEqualTo(LoopDecision.OK);
            assertThat(detector.observe("writeFile", args("path", "/f.txt", "content", "v" + i)))
                    .isEqualTo(LoopDecision.OK);
        }
    }

    @Test
    void windowEviction_dropsOldOccurrences() {
        // window 3, warn 2: an occurrence that scrolls out of the window no longer counts.
        LoopDetector detector = new LoopDetector(3, 2, 5);
        Map<String, Object> a = args("cmd", "A");

        assertThat(detector.observe("t", a)).isEqualTo(LoopDecision.OK);       // [A]      count=1
        assertThat(detector.observe("t", args("cmd", "B"))).isEqualTo(LoopDecision.OK); // [A,B]
        assertThat(detector.observe("t", args("cmd", "C"))).isEqualTo(LoopDecision.OK); // [A,B,C]
        // 4th A: window becomes [B,C,A] (first A evicted) → A count is 1 again → OK, not WARN.
        assertThat(detector.observe("t", a)).isEqualTo(LoopDecision.OK);
    }

    @Test
    void withinWindow_repeatWarns_provingEvictionTestIsMeaningful() {
        LoopDetector detector = new LoopDetector(3, 2, 5);
        Map<String, Object> a = args("cmd", "A");

        assertThat(detector.observe("t", a)).isEqualTo(LoopDecision.OK);   // count 1
        assertThat(detector.observe("t", a)).isEqualTo(LoopDecision.WARN); // count 2 within window
    }

    @Test
    void reset_clearsCounts() {
        LoopDetector detector = new LoopDetector();
        Map<String, Object> call = args("cmd", "mvn test");
        detector.observe("executeCommand", call);
        detector.observe("executeCommand", call);
        assertThat(detector.observe("executeCommand", call)).isEqualTo(LoopDecision.WARN);

        detector.reset();

        assertThat(detector.observe("executeCommand", call)).isEqualTo(LoopDecision.OK);
    }

    @Test
    void constructor_clampsInvalidPolicyToSafeValues() {
        LoopDetector detector = new LoopDetector(0, 0, -5);

        assertThat(detector.windowSize()).isEqualTo(1);
        assertThat(detector.warnThreshold()).isEqualTo(1);
        assertThat(detector.stopThreshold()).isGreaterThanOrEqualTo(detector.warnThreshold());
    }

    @Test
    void constructor_stopNeverBelowWarn() {
        LoopDetector detector = new LoopDetector(20, 5, 2); // stop < warn requested

        assertThat(detector.warnThreshold()).isEqualTo(5);
        assertThat(detector.stopThreshold()).isEqualTo(5); // clamped up to warn
    }
}

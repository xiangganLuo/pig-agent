package io.pigagent.core.loop;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoopMessagesTest {

    @Test
    void warnText_mentionsToolNameAndThreshold() {
        String text = LoopMessages.warnText("readFile", 3);

        assertThat(text).contains("readFile").contains("3");
    }

    @Test
    void warnText_blankToolName_fallsBackToGenericLabel() {
        String text = LoopMessages.warnText("  ", 3);

        assertThat(text).contains("该工具");
    }

    @Test
    void sentinelStopText_tellsModelToStopAndAnswer() {
        assertThat(LoopMessages.SENTINEL_STOP_TEXT)
                .contains("循环")
                .isNotBlank();
    }
}

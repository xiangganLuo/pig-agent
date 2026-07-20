package io.pigagent.tool.deferred;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 登记表：关键词命中排序、空/无匹配、揭示后移出、连带同组揭示。 */
class DeferredToolRegistryTest {

    private static DeferredTool tool(String name, String desc, String group, String... kw) {
        return new DeferredTool(name, desc, Set.of(kw), group);
    }

    @Test
    void searchRanksKeywordMatchesFirst() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        reg.add(tool("getWeather", "current weather forecast", "g1", "weather", "forecast"));
        reg.add(tool("sendEmail", "send an email message", "g2", "email", "message"));

        List<DeferredTool> hits = reg.search("weather", 5);

        assertThat(hits).extracting(DeferredTool::name).containsExactly("getWeather");
    }

    @Test
    void moreRelevantToolRanksHigher() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        // 两者都提到 email，但 sendEmail 名称+关键词直接命中，应排前
        reg.add(tool("logEvent", "log an event, maybe about email", "g1", "log", "event"));
        reg.add(tool("sendEmail", "send an email", "g2", "email", "send"));

        List<DeferredTool> hits = reg.search("email", 5);

        assertThat(hits).extracting(DeferredTool::name).containsExactly("sendEmail", "logEvent");
    }

    @Test
    void chineseKeywordMatchesChineseDescription() {
        // 语料常为中文：CJK unigram+bigram 分词（与 memory_search 同源）应让中文 query 命中中文描述
        DeferredToolRegistry reg = new DeferredToolRegistry();
        reg.add(tool("chaTianQi", "查询城市天气预报", "g1", "天气"));
        reg.add(tool("faYouJian", "发送电子邮件", "g2", "邮件"));

        List<DeferredTool> hits = reg.search("天气", 5);

        assertThat(hits).extracting(DeferredTool::name).containsExactly("chaTianQi");
    }

    @Test
    void blankQueryReturnsEmpty() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        reg.add(tool("getWeather", "weather", "g1", "weather"));
        assertThat(reg.search("", 5)).isEmpty();
        assertThat(reg.search("   ", 5)).isEmpty();
    }

    @Test
    void noMatchReturnsEmpty() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        reg.add(tool("getWeather", "weather", "g1", "weather"));
        assertThat(reg.search("database", 5)).isEmpty();
    }

    @Test
    void searchHonoursLimit() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        reg.add(tool("t1", "shared keyword alpha", "g1", "alpha"));
        reg.add(tool("t2", "shared keyword alpha", "g2", "alpha"));
        reg.add(tool("t3", "shared keyword alpha", "g3", "alpha"));

        assertThat(reg.search("alpha", 2)).hasSize(2);
    }

    @Test
    void revealedToolNoLongerSearchable() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        reg.add(tool("getWeather", "weather", "g1", "weather"));

        List<DeferredTool> moved = reg.markRevealed("getWeather");

        assertThat(moved).extracting(DeferredTool::name).containsExactly("getWeather");
        assertThat(reg.search("weather", 5)).isEmpty();
        assertThat(reg.find("getWeather")).isEmpty();
        assertThat(reg.revealedNames()).containsExactly("getWeather");
    }

    @Test
    void revealingOneRevealsSiblingsSharingGroup() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        // 同一 MCP 服务器分组的两个工具：揭示其一 → 连带揭示另一
        reg.add(tool("mcpA", "tool a", "mcp:srv", "alpha"));
        reg.add(tool("mcpB", "tool b", "mcp:srv", "beta"));

        List<DeferredTool> moved = reg.markRevealed("mcpA");

        assertThat(moved).extracting(DeferredTool::name).containsExactlyInAnyOrder("mcpA", "mcpB");
        assertThat(reg.deferredNames()).isEmpty();
    }

    @Test
    void markRevealedUnknownReturnsEmpty() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        assertThat(reg.markRevealed("nope")).isEmpty();
    }

    @Test
    void isEmptyReflectsContent() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        assertThat(reg.isEmpty()).isTrue();
        reg.add(tool("t", "d", "g", "k"));
        assertThat(reg.isEmpty()).isFalse();
    }
}

package io.pigagent.tool.deferred;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** tool_search：命中→名称+描述+揭示；空/无匹配→提示且不揭示；空登记表→提示；异常→{"error"}。 */
class ToolSearchToolTest {

    private final List<String> revealed = new ArrayList<>();

    private DeferredToolReveal recordingReveal() {
        return name -> {
            revealed.add(name);
            return true;
        };
    }

    private static DeferredToolRegistry registryWith(DeferredTool... tools) {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        for (DeferredTool t : tools) {
            reg.add(t);
        }
        return reg;
    }

    private static DeferredTool tool(String name, String desc, String group, String... kw) {
        return new DeferredTool(name, desc, Set.of(kw), group);
    }

    @Test
    void matchReturnsNamesAndDescriptionsAndReveals() {
        DeferredToolRegistry reg = registryWith(
                tool("getWeather", "Get the weather forecast", "g1", "weather", "forecast"),
                tool("sendEmail", "Send an email", "g2", "email"));
        ToolSearchTool tool = new ToolSearchTool(reg, recordingReveal());

        String out = tool.toolSearch("weather");

        assertThat(out).contains("getWeather").contains("Get the weather forecast");
        assertThat(out).doesNotContain("sendEmail");
        assertThat(revealed).containsExactly("getWeather");
    }

    @Test
    void noMatchReturnsHintWithAvailableNamesAndDoesNotReveal() {
        DeferredToolRegistry reg = registryWith(
                tool("getWeather", "weather", "g1", "weather"),
                tool("sendEmail", "email", "g2", "email"));
        ToolSearchTool tool = new ToolSearchTool(reg, recordingReveal());

        String out = tool.toolSearch("database migration");

        assertThat(out).contains("No tools matched");
        assertThat(out).contains("getWeather").contains("sendEmail"); // 可搜索工具名线索
        assertThat(revealed).isEmpty();
    }

    @Test
    void blankQueryReturnsHintAndDoesNotReveal() {
        DeferredToolRegistry reg = registryWith(tool("getWeather", "weather", "g1", "weather"));
        ToolSearchTool tool = new ToolSearchTool(reg, recordingReveal());

        String out = tool.toolSearch("   ");

        assertThat(out).contains("No tools matched");
        assertThat(revealed).isEmpty();
    }

    @Test
    void emptyRegistryReturnsNothingAvailable() {
        ToolSearchTool tool = new ToolSearchTool(new DeferredToolRegistry(), recordingReveal());
        String out = tool.toolSearch("anything");
        assertThat(out).contains("No additional tools are available");
        assertThat(revealed).isEmpty();
    }

    @Test
    void internalFailureReturnsCanonicalError() {
        DeferredToolRegistry reg = registryWith(tool("getWeather", "weather", "g1", "weather"));
        // reveal 抛异常 → tool_search 应捕获并返回 {"error":...}，不外抛
        DeferredToolReveal boom = name -> {
            throw new IllegalStateException("kaboom");
        };
        ToolSearchTool tool = new ToolSearchTool(reg, boom);

        String out = tool.toolSearch("weather");

        assertThat(out).startsWith("{\"error\":");
        assertThat(out).contains("tool_search failed");
    }

    @Test
    void limitsResultsToMax() {
        DeferredToolRegistry reg = new DeferredToolRegistry();
        for (int i = 0; i < ToolSearchTool.MAX_RESULTS + 3; i++) {
            reg.add(tool("alphaTool" + i, "shared alpha capability", "g" + i, "alpha"));
        }
        ToolSearchTool tool = new ToolSearchTool(reg, recordingReveal());

        String out = tool.toolSearch("alpha");

        // 返回条数受 MAX_RESULTS 限制
        assertThat(revealed).hasSize(ToolSearchTool.MAX_RESULTS);
        assertThat(out).contains("Found " + ToolSearchTool.MAX_RESULTS + " tool(s)");
    }
}

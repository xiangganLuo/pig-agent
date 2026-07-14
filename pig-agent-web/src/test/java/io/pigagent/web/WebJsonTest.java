package io.pigagent.web;

import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.agent.kernel.KernelEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebJsonTest {

    private final WebJson json = new WebJson();

    @Test
    void agent_projectsIdentityModelAndActiveFlag() {
        AgentSpec spec = AgentSpec.create("b", "Beta").withModelId("gpt");
        AgentInstance inst = new AgentInstance("b", spec, mock(PigAgent.class));

        Map<String, Object> m = json.agent(inst, "b");

        assertThat(m).containsEntry("id", "b").containsEntry("name", "Beta")
                .containsEntry("model", "gpt").containsEntry("active", true)
                .containsEntry("autonomous", false).containsEntry("tools", "all");
    }

    @Test
    void agent_defaultModel_whenNull_andNotActive() {
        AgentInstance inst = new AgentInstance("a", AgentSpec.create("a", "Alpha"), mock(PigAgent.class));
        Map<String, Object> m = json.agent(inst, "other");
        assertThat(m).containsEntry("model", "(default)").containsEntry("active", false);
    }

    @Test
    void event_serializesTypeAgentAndMessage() {
        String out = json.toJson(json.event(KernelEvent.of(KernelEvent.Type.RUN_STARTED, "b", "Beta")));
        assertThat(out).contains("RUN_STARTED").contains("\"agentId\":\"b\"").contains("Beta");
    }

    @Test
    void chatFrame_hasTypeAndText() {
        Map<String, Object> frame = json.chatFrame("answer", "hi");
        assertThat(frame).containsEntry("type", "answer").containsEntry("text", "hi");
        assertThat(json.chatFrame("done", null)).containsEntry("text", "");
    }

    @Test
    void parse_returnsEmptyMap_onBlankOrInvalid() {
        assertThat(json.parse("")).isEmpty();
        assertThat(json.parse("not-json")).isEmpty();
        assertThat(json.parse("{\"id\":\"x\"}")).containsEntry("id", "x");
    }
}

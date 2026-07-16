package io.pigagent.channel.gateway;

import io.pigagent.config.PigAgentConfig.ChannelConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * av2 Gateway enhancement — {@link NativeChannelFactory} opt-in gating + graceful degradation + pure
 * props mapping. The {@code agentscope-extensions-channel-*} artifacts are <b>absent</b> in the offline
 * build, so construction always degrades to empty; the factory MUST do so without throwing and only when
 * the channel is enabled + flagged {@code native:true} for a kind pig maps.
 */
class NativeChannelFactoryTest {

    private final NativeChannelFactory factory = new NativeChannelFactory("default");

    private static ChannelConfig cfg(boolean enabled, boolean nativeAdapter, Map<String, String> props) {
        ChannelConfig c = new ChannelConfig();
        c.setEnabled(enabled);
        c.setNative(nativeAdapter);
        c.setProps(props);
        return c;
    }

    @Test
    void nativeFalseReturnsEmpty() {
        // Enabled but not flagged native → the custom adapter is used (opt-in gate).
        assertThat(factory.create("dingtalk", cfg(true, false, Map.of()))).isEmpty();
    }

    @Test
    void disabledChannelReturnsEmpty() {
        assertThat(factory.create("dingtalk", cfg(false, true, Map.of()))).isEmpty();
    }

    @Test
    void unknownNativeIdReturnsEmpty() {
        // A kind pig has no native mapping for (e.g. telegram) → empty, custom adapter used.
        assertThat(factory.create("telegram", cfg(true, true, Map.of()))).isEmpty();
    }

    @Test
    void artifactAbsentDegradesGracefullyWithoutThrowing() {
        ChannelConfig c = cfg(true, true, Map.of(
                "appKey", "k", "appSecret", "s", "robotCode", "r"));
        // Offline: the extension jar is not on the classpath → graceful fallback (empty), never a throw.
        assertThatCode(() -> factory.create("dingtalk", c)).doesNotThrowAnyException();
        assertThat(factory.create("dingtalk", c))
                .as("native adapter artifact absent offline → degrade to empty (custom fallback)")
                .isEmpty();
    }

    @Test
    void propsMappingPassesThroughConfiguredProps() {
        ChannelConfig c = cfg(true, true, Map.of("appKey", "k", "appSecret", "s", "robotCode", "r"));
        Map<String, String> props = factory.props(NativeChannelType.DINGTALK, c);
        assertThat(props).containsEntry("appKey", "k")
                .containsEntry("appSecret", "s").containsEntry("robotCode", "r");
    }

    @Test
    void propsMappingToleratesMissingRequiredKeys() {
        // Missing required keys are logged (by name only) but never throw — the adapter validates later.
        ChannelConfig c = cfg(true, true, Map.of("appKey", "k"));
        assertThatCode(() -> factory.props(NativeChannelType.DINGTALK, c)).doesNotThrowAnyException();
        assertThat(factory.props(NativeChannelType.DINGTALK, c)).containsOnlyKeys("appKey");
    }

    @Test
    void nativeChannelTypeRegistryIsConsistent() {
        assertThat(NativeChannelType.fromId("dingtalk")).contains(NativeChannelType.DINGTALK);
        assertThat(NativeChannelType.fromId("nope")).isEmpty();
        assertThat(NativeChannelType.DINGTALK.artifactId()).isEqualTo("agentscope-extensions-channel-dingtalk");
        assertThat(NativeChannelType.FEISHU.needsSpring()).isTrue();
        assertThat(NativeChannelType.DINGTALK.needsSpring()).isFalse();
    }
}

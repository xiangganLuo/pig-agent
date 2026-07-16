package io.pigagent.channel.gateway;

import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.pigagent.config.PigAgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a <b>native AgentScope 2.0 channel adapter</b> ({@link Channel}) from pig config, opt-in behind
 * {@code channels.<id>.native: true} (av2 Gateway enhancement). The native adapters live in separate
 * {@code agentscope-extensions-channel-*} artifacts that pig does <b>not</b> declare as compile
 * dependencies (they aren't resolvable in the offline build and need real platform endpoints anyway),
 * so this factory loads them <b>reflectively</b> via the documented
 * {@code XxxChannel.fromProperties(String id, ChannelConfig config, Map<String,String> props)} factory —
 * and <b>degrades gracefully</b> when the artifact is absent: it logs a helpful "add this artifact"
 * message and returns {@link Optional#empty()} so the caller falls back to pig's custom channel. The
 * {@link ChannelConfig} value object it passes IS compile-visible (it ships in {@code agentscope-harness}).
 *
 * <p>This never throws: any reflection/instantiation failure is logged (without echoing credential
 * values) and yields empty, so a misconfigured native channel can never break channel startup.
 */
public final class NativeChannelFactory {

    private static final Logger log = LoggerFactory.getLogger(NativeChannelFactory.class);

    private final String mainAgentId;

    public NativeChannelFactory(String mainAgentId) {
        this.mainAgentId = (mainAgentId == null || mainAgentId.isBlank()) ? "default" : mainAgentId;
    }

    /**
     * Build the native adapter for {@code id} if it is enabled, flagged {@code native:true}, a kind pig
     * maps ({@link NativeChannelType}), and its artifact is on the runtime classpath. Returns empty in
     * every other case (opt-out gate, unknown kind, or graceful degradation when the artifact is
     * missing) — the caller then uses pig's custom channel.
     */
    public Optional<Channel> create(String id, PigAgentConfig.ChannelConfig cfg) {
        if (id == null || cfg == null || !cfg.isEnabled() || !cfg.isNative()) {
            return Optional.empty();
        }
        Optional<NativeChannelType> type = NativeChannelType.fromId(id);
        if (type.isEmpty()) {
            log.warn("Channel '{}' requested native adapter but pig has no native mapping for it — "
                    + "using the custom adapter", id);
            return Optional.empty();
        }
        return instantiate(type.get(), id, props(type.get(), cfg));
    }

    /**
     * The native adapter's property map, derived from {@code channels.<id>.props}. Pure and
     * credential-safe to unit-test (no class loading). Missing required keys are logged by name only
     * (never the value) as a hint; the adapter itself validates on construction.
     */
    Map<String, String> props(NativeChannelType type, PigAgentConfig.ChannelConfig cfg) {
        Map<String, String> props = new LinkedHashMap<>(cfg.getProps());
        for (String required : type.requiredProps()) {
            if (!props.containsKey(required) || isBlank(props.get(required))) {
                log.warn("Native channel '{}' is missing required prop '{}' (set channels.{}.props.{})",
                        type.id(), required, type.id(), required);
            }
        }
        return props;
    }

    /** Reflectively invoke {@code XxxChannel.fromProperties(id, ChannelConfig, props)}; empty on any failure. */
    private Optional<Channel> instantiate(NativeChannelType type, String id, Map<String, String> props) {
        ChannelConfig nativeConfig = ChannelConfig.of(id, mainAgentId);
        try {
            Class<?> adapter = Class.forName(type.className());
            Method factory = adapter.getMethod("fromProperties", String.class, ChannelConfig.class, Map.class);
            Object channel = factory.invoke(null, id, nativeConfig, props);
            log.info("Native channel '{}' constructed via {} (agent '{}')", id, type.className(), mainAgentId);
            return Optional.of((Channel) channel);
        } catch (ClassNotFoundException e) {
            // Graceful degradation — the artifact is not on the classpath (always the case offline).
            log.warn("Native channel '{}' requested but {} is not on the classpath — add the '{}' "
                            + "dependency to enable it; falling back to pig's custom channel",
                    id, type.className(), type.artifactId());
            return Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Never let a native-adapter failure break channel startup; do not echo any credential value.
            log.warn("Native channel '{}' failed to construct ({}) — falling back to pig's custom channel",
                    id, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

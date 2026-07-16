package io.pigagent.channel.gateway;

import java.util.List;
import java.util.Optional;

/**
 * The single registry of <b>native AgentScope 2.0 channel adapters</b> pig can opt a channel into
 * (av2 Gateway enhancement). Each constant carries the pig channel id (the {@code channels.<id>} key),
 * the fully-qualified adapter class name, its Maven artifact, whether it needs a Spring runtime, and
 * the required {@code props} keys — a single source of truth mirroring {@link io.pigagent.channel.ChannelType}.
 *
 * <h2>Offline honesty (live-verification-required)</h2>
 * The {@code agentscope-extensions-channel-*} artifacts are <b>not</b> resolvable in the offline build
 * (only {@code agentscope-core}/{@code agentscope-harness}/{@code agentscope-extensions-model-*} are
 * cached), so the adapter classes cannot be compiled against or unit-tested by construction here. This
 * registry therefore names the class + artifact for the wiring layer ({@link NativeChannelFactory},
 * which loads them reflectively and degrades gracefully when absent) and documents the exact POM
 * addition needed. The FQCN follows the model-extension convention
 * ({@code io.agentscope.extensions.model.<p>.<P>ChatModel} → {@code io.agentscope.extensions.channel
 * .<platform>.<Platform>Channel}); it and the {@code fromProperties(String, ChannelConfig, Map)} factory
 * are documented but <b>must be verified against the real artifact</b> (needs a live endpoint anyway,
 * since these adapters connect to real platforms).
 */
public enum NativeChannelType {

    DINGTALK("dingtalk",
            "io.agentscope.extensions.channel.dingtalk.DingTalkChannel",
            "agentscope-extensions-channel-dingtalk",
            false,
            List.of("appKey", "appSecret", "robotCode")),

    FEISHU("feishu",
            "io.agentscope.extensions.channel.feishu.FeishuChannel",
            "agentscope-extensions-channel-feishu",
            true,
            List.of("appId", "appSecret")),

    GITHUB("github",
            "io.agentscope.extensions.channel.github.GitHubChannel",
            "agentscope-extensions-channel-github",
            false,
            List.of("token", "webhookSecret")),

    GITLAB("gitlab",
            "io.agentscope.extensions.channel.gitlab.GitLabChannel",
            "agentscope-extensions-channel-gitlab",
            false,
            List.of("token")),

    WECOM("wecom",
            "io.agentscope.extensions.channel.wecom.WeComChannel",
            "agentscope-extensions-channel-wecom",
            true,
            List.of("corpId", "agentId", "secret", "token", "encodingAesKey"));

    private final String id;
    private final String className;
    private final String artifactId;
    private final boolean needsSpring;
    private final List<String> requiredProps;

    NativeChannelType(String id, String className, String artifactId, boolean needsSpring,
                      List<String> requiredProps) {
        this.id = id;
        this.className = className;
        this.artifactId = artifactId;
        this.needsSpring = needsSpring;
        this.requiredProps = requiredProps;
    }

    /** The {@code channels.<id>} key this native adapter corresponds to. */
    public String id() {
        return id;
    }

    /** Fully-qualified adapter class name (loaded reflectively; see class javadoc on verification). */
    public String className() {
        return className;
    }

    /** The Maven artifact that provides the adapter (add to a POM to enable it at runtime). */
    public String artifactId() {
        return artifactId;
    }

    /** Whether the adapter requires a Spring runtime (HTTP-callback adapters do). */
    public boolean needsSpring() {
        return needsSpring;
    }

    /** The {@code props} keys the adapter's {@code fromProperties} requires. */
    public List<String> requiredProps() {
        return requiredProps;
    }

    /** Look up a native adapter kind by its {@code channels.<id>} key; empty if pig has no mapping. */
    public static Optional<NativeChannelType> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (NativeChannelType type : values()) {
            if (type.id.equals(id)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}

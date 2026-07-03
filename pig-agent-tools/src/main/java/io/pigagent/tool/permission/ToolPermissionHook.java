package io.pigagent.tool.permission;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.pigagent.config.PermissionMode;
import io.pigagent.config.PigAgentConfig.PermissionConfig;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 统一权限门：在 {@link PreActingEvent}（工具执行前）按当前模式 + 风险 + allowlist 判定，
 * 否决则把待执行 {@link ToolUseBlock} 改写为 {@link PermissionDeniedTool} 哨兵（策略 B，见 spike）。
 *
 * <p>{@code priority()=0} 最高优先级，早于日志 hook。交互回合用 {@code resolveMode}，
 * 渠道回合（{@code channel=true}）用 {@code resolveChannelMode} 且通常无 confirmer（ASK→fail-closed）。
 */
public final class ToolPermissionHook implements Hook {

    private final Supplier<PermissionConfig> configSupplier;
    private final PermissionConfirmer confirmer;
    private final AllowlistWriter writer;
    private final boolean channel;

    public ToolPermissionHook(Supplier<PermissionConfig> configSupplier,
                              PermissionConfirmer confirmer, AllowlistWriter writer) {
        this(configSupplier, confirmer, writer, false);
    }

    public ToolPermissionHook(Supplier<PermissionConfig> configSupplier,
                              PermissionConfirmer confirmer, AllowlistWriter writer, boolean channel) {
        this.configSupplier = configSupplier;
        this.confirmer = confirmer;
        this.writer = writer;
        this.channel = channel;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent pre) {
            ToolUseBlock tu = pre.getToolUse();
            if (tu != null && !PermissionDeniedTool.TOOL_NAME.equals(tu.getName())) {
                PermissionConfig cfg = configSupplier.get();
                PermissionMode mode = channel ? cfg.resolveChannelMode() : cfg.resolveMode();
                boolean allow = PermissionResolver.resolve(
                        cfg, mode, tu.getName(), tu.getInput(), confirmer, writer);
                if (!allow) {
                    pre.setToolUse(ToolUseBlock.builder()
                            .id(tu.getId())
                            .name(PermissionDeniedTool.TOOL_NAME)
                            .input(Map.of())
                            .build());
                }
            }
        }
        return Mono.just(event);
    }
}

package io.pigagent.tool.permission;

import java.util.Map;

/** EXEC 工具逐命令粒度用的规范化命令键：取 {@code command} 参数的首 token。 */
public final class CommandKeys {

    /**
     * The name of the shell-execution tool ({@code executeCommand}) whose per-command allowlist is
     * honored via {@link CommandPermissionTool}. Single source of truth so the permission mapper and
     * the wiring layer agree on which tool carries the command-granular check.
     */
    public static final String COMMAND_TOOL_NAME = "executeCommand";

    private CommandKeys() {
    }

    /** @return 命令首 token（如 {@code "git status"} → {@code "git"}）；无命令返回 {@code null}。 */
    public static String of(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        Object c = input.get("command");
        if (c == null) {
            return null;
        }
        String s = c.toString().strip();
        if (s.isEmpty()) {
            return null;
        }
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }
}

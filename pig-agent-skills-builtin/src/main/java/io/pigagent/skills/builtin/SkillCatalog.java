package io.pigagent.skills.builtin;

import io.pigagent.tool.skills.Skill;

import java.util.List;

/**
 * The single source of truth for the built-in skill set: a curated list of skill names, each backed
 * by a {@code skills/<name>/SKILL.md} classpath resource. Used for programmatic introspection and
 * tests; the runtime discovery path is {@link BuiltinSkillProvider} declared in
 * {@code META-INF/services/io.pigagent.tool.skills.spi.SkillProvider} and driven by
 * {@code ClasspathSkillSource}.
 *
 * <p>Mirrors {@code io.pigagent.plugin.builtin.PluginCatalog}. {@link #all()} and the classpath
 * resource directory are two views of the same set; a consistency test asserts they match, so adding
 * a skill name without shipping its {@code SKILL.md} (or vice-versa) is caught as drift.
 *
 * <p>The set is a starter kit for a coding agent — general engineering methods (code review, debugging,
 * TDD, …) plus everyday operating-system operations (shell, files, processes/ports, logs, system health,
 * networking, environment, archives) — each an actionable, agent-facing guide (title + when-to-use + a
 * concrete step-by-step method / checklist).
 */
public final class SkillCatalog {

    /** Resource directory prefix for a skill's {@code SKILL.md}. */
    static final String RESOURCE_PREFIX = "skills/";
    static final String SKILL_FILE = "SKILL.md";

    /** Curated built-in skill names, in a stable order. */
    public static final List<String> SKILL_NAMES = List.of(
            // Engineering methods
            "code-review",
            "systematic-debugging",
            "tdd",
            "refactoring",
            "git-commit",
            "security-review",
            "planning",
            // Operating-system operations (cross-platform: bash + PowerShell)
            "shell-commands",
            "file-operations",
            "process-and-ports",
            "log-triage",
            "system-health",
            "networking-diagnostics",
            "env-and-path",
            "archive-and-compress",
            // Environment / toolchain setup
            "python-environment",
            "install-tools",
            // Windows usage scenarios (PowerShell; honest about elevation / GUI / sandbox-blocked ops)
            "windows-services",
            "windows-scheduled-tasks",
            "windows-startup-apps",
            "windows-firewall",
            "windows-network-config",
            "windows-wifi",
            "windows-user-accounts",
            "windows-event-logs",
            "windows-updates",
            "windows-disk-management",
            "windows-registry",
            "windows-defender",
            "windows-power",
            "windows-printers",
            "windows-remote-access",
            "windows-file-permissions",
            "windows-disk-cleanup",
            "windows-hosts-file",
            "windows-datetime",
            "windows-slow-boot-triage");

    private SkillCatalog() {
    }

    /** Every built-in skill as a lazily-loaded {@link Skill}, in {@link #SKILL_NAMES} order. */
    public static List<Skill> all() {
        ClassLoader loader = SkillCatalog.class.getClassLoader();
        return SKILL_NAMES.stream()
                .map(name -> (Skill) new ClasspathSkill(name, resourcePath(name), loader))
                .toList();
    }

    /** The classpath resource path of a skill's {@code SKILL.md}. */
    static String resourcePath(String name) {
        return RESOURCE_PREFIX + name + "/" + SKILL_FILE;
    }
}

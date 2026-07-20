package io.pigagent.tool.spi;

import io.pigagent.core.outreach.NotificationService;
import io.pigagent.task.TaskManager;
import io.pigagent.tool.sandbox.SandboxPolicy;
import io.pigagent.tool.skills.authoring.SkillStagingArea;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Runtime dependencies a {@link ToolProvider} may need to build its tool. Immutable value holder;
 * fields not needed by a given provider may be {@code null}. Extend with new accessors as new tools
 * require new collaborators.
 */
public final class ToolContext {

    private final TaskManager taskManager;
    private final Path skillsDir;
    private final Path workspaceRoot;
    private final List<String> webAllowedHosts;
    private final SandboxPolicy sandboxPolicy;
    private final NotificationService notificationService;
    private final BooleanSupplier outreachEnabled;
    private final SkillStagingArea skillStaging;
    private final BooleanSupplier autonomousSkillsEnabled;
    private final Path userProfileFile;
    private final BooleanSupplier userProfileEnabled;

    public ToolContext(TaskManager taskManager, Path skillsDir) {
        this(taskManager, skillsDir, null, null);
    }

    public ToolContext(TaskManager taskManager, Path skillsDir, Path workspaceRoot,
                       List<String> webAllowedHosts) {
        this(taskManager, skillsDir, workspaceRoot, webAllowedHosts, null);
    }

    public ToolContext(TaskManager taskManager, Path skillsDir, Path workspaceRoot,
                       List<String> webAllowedHosts, SandboxPolicy sandboxPolicy) {
        this(taskManager, skillsDir, workspaceRoot, webAllowedHosts, sandboxPolicy, null, null);
    }

    public ToolContext(TaskManager taskManager, Path skillsDir, Path workspaceRoot,
                       List<String> webAllowedHosts, SandboxPolicy sandboxPolicy,
                       NotificationService notificationService, BooleanSupplier outreachEnabled) {
        this(taskManager, skillsDir, workspaceRoot, webAllowedHosts, sandboxPolicy,
                notificationService, outreachEnabled, null, null, null, null);
    }

    /** Convenience: autonomous-skills params only (user-profile params default to null/disabled). */
    public ToolContext(TaskManager taskManager, Path skillsDir, Path workspaceRoot,
                       List<String> webAllowedHosts, SandboxPolicy sandboxPolicy,
                       NotificationService notificationService, BooleanSupplier outreachEnabled,
                       SkillStagingArea skillStaging, BooleanSupplier autonomousSkillsEnabled) {
        this(taskManager, skillsDir, workspaceRoot, webAllowedHosts, sandboxPolicy,
                notificationService, outreachEnabled, skillStaging, autonomousSkillsEnabled, null, null);
    }

    /** Convenience: user-profile params only (autonomous-skills params default to null/disabled). */
    public ToolContext(TaskManager taskManager, Path skillsDir, Path workspaceRoot,
                       List<String> webAllowedHosts, SandboxPolicy sandboxPolicy,
                       NotificationService notificationService, BooleanSupplier outreachEnabled,
                       Path userProfileFile, BooleanSupplier userProfileEnabled) {
        this(taskManager, skillsDir, workspaceRoot, webAllowedHosts, sandboxPolicy,
                notificationService, outreachEnabled, null, null, userProfileFile, userProfileEnabled);
    }

    public ToolContext(TaskManager taskManager, Path skillsDir, Path workspaceRoot,
                       List<String> webAllowedHosts, SandboxPolicy sandboxPolicy,
                       NotificationService notificationService, BooleanSupplier outreachEnabled,
                       SkillStagingArea skillStaging, BooleanSupplier autonomousSkillsEnabled,
                       Path userProfileFile, BooleanSupplier userProfileEnabled) {
        this.taskManager = taskManager;
        this.skillsDir = skillsDir;
        this.workspaceRoot = workspaceRoot;
        this.webAllowedHosts = webAllowedHosts == null ? List.of() : List.copyOf(webAllowedHosts);
        this.sandboxPolicy = sandboxPolicy;
        this.notificationService = notificationService;
        this.outreachEnabled = outreachEnabled == null ? () -> false : outreachEnabled;
        this.skillStaging = skillStaging;
        this.autonomousSkillsEnabled = autonomousSkillsEnabled == null ? () -> false : autonomousSkillsEnabled;
        this.userProfileFile = userProfileFile;
        this.userProfileEnabled = userProfileEnabled == null ? () -> false : userProfileEnabled;
    }

    /**
     * A fluent builder — the preferred way to construct a {@code ToolContext}. Adding a new tool
     * dependency means adding one builder setter instead of a new telescoping constructor overload,
     * so future tools no longer widen the constructor list. The convenience constructors above are
     * kept for backward compatibility and delegate to the same canonical constructor, so a
     * builder-built context is byte-for-byte equivalent to the constructor-built one (same defaults).
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link ToolContext}; unset fields default exactly as the constructors do. */
    public static final class Builder {
        private TaskManager taskManager;
        private Path skillsDir;
        private Path workspaceRoot;
        private List<String> webAllowedHosts;
        private SandboxPolicy sandboxPolicy;
        private NotificationService notificationService;
        private BooleanSupplier outreachEnabled;
        private SkillStagingArea skillStaging;
        private BooleanSupplier autonomousSkillsEnabled;
        private Path userProfileFile;
        private BooleanSupplier userProfileEnabled;

        private Builder() {
        }

        public Builder taskManager(TaskManager v) {
            this.taskManager = v;
            return this;
        }

        public Builder skillsDir(Path v) {
            this.skillsDir = v;
            return this;
        }

        public Builder workspaceRoot(Path v) {
            this.workspaceRoot = v;
            return this;
        }

        public Builder webAllowedHosts(List<String> v) {
            this.webAllowedHosts = v;
            return this;
        }

        public Builder sandboxPolicy(SandboxPolicy v) {
            this.sandboxPolicy = v;
            return this;
        }

        public Builder notificationService(NotificationService v) {
            this.notificationService = v;
            return this;
        }

        public Builder outreachEnabled(BooleanSupplier v) {
            this.outreachEnabled = v;
            return this;
        }

        public Builder skillStaging(SkillStagingArea v) {
            this.skillStaging = v;
            return this;
        }

        public Builder autonomousSkillsEnabled(BooleanSupplier v) {
            this.autonomousSkillsEnabled = v;
            return this;
        }

        public Builder userProfileFile(Path v) {
            this.userProfileFile = v;
            return this;
        }

        public Builder userProfileEnabled(BooleanSupplier v) {
            this.userProfileEnabled = v;
            return this;
        }

        public ToolContext build() {
            return new ToolContext(taskManager, skillsDir, workspaceRoot, webAllowedHosts,
                    sandboxPolicy, notificationService, outreachEnabled, skillStaging,
                    autonomousSkillsEnabled, userProfileFile, userProfileEnabled);
        }
    }

    /** The task manager (for the task tool); may be {@code null} in contexts that don't need it. */
    public TaskManager taskManager() {
        return taskManager;
    }

    /** The skills directory (for the skills tool); may be {@code null} in contexts that don't need it. */
    public Path skillsDir() {
        return skillsDir;
    }

    /**
     * The workspace root (for the file tool's credential-file blacklist); may be {@code null} in
     * contexts that don't need it, in which case the blacklist is disabled.
     */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /**
     * Optional host allowlist for the web-fetch tool. Empty (never {@code null}) means "no
     * allowlist" — only the SSRF IP guard applies.
     */
    public List<String> webAllowedHosts() {
        return webAllowedHosts;
    }

    /**
     * The command-execution sandbox policy (for the shell tool); may be {@code null} in contexts that
     * don't need it, in which case {@code ShellTools} uses {@link SandboxPolicy#defaults()}.
     */
    public SandboxPolicy sandboxPolicy() {
        return sandboxPolicy;
    }

    /**
     * The proactive-outreach notification service (for the {@code notifyUser} tool); {@code null} when
     * outreach is not wired, in which case the provider registers no tool.
     */
    public NotificationService notificationService() {
        return notificationService;
    }

    /**
     * Whether proactive outreach is enabled — read live (a supplier) so the {@code notifyUser} tool's
     * availability tracks config. Never {@code null}; defaults to {@code false}.
     */
    public BooleanSupplier outreachEnabled() {
        return outreachEnabled;
    }

    /**
     * The autonomous-skills staging area (for the {@code proposeSkill}/{@code skillManage} tools);
     * {@code null} when autonomous skills are not wired, in which case the provider registers no tool.
     */
    public SkillStagingArea skillStaging() {
        return skillStaging;
    }

    /**
     * Whether autonomous skills are enabled — read live (a supplier) so the {@code proposeSkill}/
     * {@code skillManage} tools' availability tracks config. Never {@code null}; defaults to {@code false}.
     */
    public BooleanSupplier autonomousSkillsEnabled() {
        return autonomousSkillsEnabled;
    }

    /**
     * The curated user-profile file ({@code USER.md}) for the {@code updateProfile} tool
     * ({@code user-profile}); {@code null} when profile is not wired, in which case the provider
     * registers no tool.
     */
    public Path userProfileFile() {
        return userProfileFile;
    }

    /**
     * Whether the user profile is enabled — read live (a supplier) so the {@code updateProfile} tool's
     * availability tracks config. Never {@code null}; defaults to {@code false}.
     */
    public BooleanSupplier userProfileEnabled() {
        return userProfileEnabled;
    }
}

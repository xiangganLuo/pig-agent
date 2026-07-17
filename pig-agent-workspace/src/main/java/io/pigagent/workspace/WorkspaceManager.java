package io.pigagent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages the workspace directory structure.
 * Default workspace: ~/.pig-agent/workspace/
 */
public final class WorkspaceManager {

    private static final String DEFAULT_WORKSPACE_DIR = ".pig-agent/workspace";
    private final Path rootPath;

    public WorkspaceManager(Path rootPath) {
        this.rootPath = rootPath;
    }

    public static WorkspaceManager defaultWorkspace() {
        Path home = Path.of(System.getProperty("user.home"));
        return new WorkspaceManager(home.resolve(DEFAULT_WORKSPACE_DIR));
    }

    public void initialize() throws IOException {
        Files.createDirectories(rootPath);
        Files.createDirectories(rootPath.resolve("context"));
        Files.createDirectories(rootPath.resolve("skills"));
        Files.createDirectories(rootPath.resolve("tasks"));
        Files.createDirectories(rootPath.resolve("tasks/recurring"));
        Files.createDirectories(rootPath.resolve("tasks").resolve(todayDir()));
        Files.createDirectories(rootPath.resolve("sessions"));
        Files.createDirectories(rootPath.resolve("agents"));
        Files.createDirectories(rootPath.resolve("reports"));
        Files.createDirectories(rootPath.resolve("plugins"));
        createIfAbsent(rootPath.resolve("AGENT.md"), defaultAgentMd());
        // Seed an inert AGENTS.md (plural) alongside AGENT.md (singular). The underlying harness
        // scans the workspace for AGENTS.md whenever a workspace root is set on the agent builder
        // (every build + every model-switch / MCP / memory rebuild); pig seeds only AGENT.md, so
        // that scan otherwise logs a WARN on every (re)build. A minimal file makes the scan find
        // something and stay silent. pig's editable system prompt remains AGENT.md (see the note).
        createIfAbsent(rootPath.resolve("AGENTS.md"), defaultAgentsMd());
        createIfAbsent(rootPath.resolve("INFO.md"), defaultInfoMd());
        createIfAbsent(rootPath.resolve("application.yaml"), defaultConfigYaml());
    }

    public Path getRootPath() { return rootPath; }
    public Path getAgentMd() { return rootPath.resolve("AGENT.md"); }
    public Path getInfoMd() { return rootPath.resolve("INFO.md"); }
    /** The curated user-profile file ({@code USER.md}, capability {@code user-profile}); path is config-overridable. */
    public Path getUserMd() { return rootPath.resolve("USER.md"); }
    public Path getContextDir() { return rootPath.resolve("context"); }
    public Path getSkillsDir() { return rootPath.resolve("skills"); }
    public Path getTasksDir() { return rootPath.resolve("tasks"); }
    public Path getRecurringTasksDir() { return rootPath.resolve("tasks/recurring"); }
    public Path getTodayTaskDir() { return rootPath.resolve("tasks").resolve(todayDir()); }
    public Path getSessionsDir() { return rootPath.resolve("sessions"); }
    public Path getAgentsDir() { return rootPath.resolve("agents"); }
    public Path getReportsDir() { return rootPath.resolve("reports"); }
    /** External plugin jars directory ({@code workspace/plugins/}); scanned by {@code DirectoryPluginSource}. */
    public Path getPluginsDir() { return rootPath.resolve("plugins"); }
    public Path getModelsFile() { return rootPath.resolve("models.json"); }
    public Path getMcpFile() { return rootPath.resolve("mcp.json"); }

    public String readAgentMd() throws IOException {
        return Files.readString(getAgentMd());
    }

    public String readInfoMd() throws IOException {
        return Files.readString(getInfoMd());
    }

    private void createIfAbsent(Path path, String defaultContent) throws IOException {
        if (!Files.exists(path)) {
            Files.writeString(path, defaultContent);
        }
    }

    private String todayDir() {
        return java.time.LocalDate.now().toString();
    }

    private String defaultAgentMd() {
        return """
                # PigAgent — System Prompt

                You are **PigAgent**, a capable and autonomous coding and automation agent that runs
                directly in the user's terminal. You help with software engineering, file and system
                operations, research and general task automation. You are equipped with real tools that
                act on the user's machine — prefer using them to accomplish the goal rather than only
                describing what could be done.

                This file (`AGENT.md`) is your editable system prompt: adjust it to shape your behavior.

                ## Operating principles
                - **Think before acting.** Understand the request, inspect the relevant context (read files,
                  list directories, check state) and form a short plan before you change anything.
                - **Prefer the right tool.** Use the most specific tool for each step instead of improvising
                  (e.g. read a file with `readFile`, do not shell out to `cat`/`type`).
                - **Be concise and direct.** Lead with the answer or result; keep explanations proportional
                  to the task. Avoid filler and repetition.
                - **Report outcomes faithfully.** State what you actually did and what you observed. Never
                  claim success you have not verified — if you changed code, build or test it, or say plainly
                  that you did not.
                - **Ask only when genuinely blocked.** If the request is ambiguous in a way that changes the
                  outcome, ask one focused question; otherwise proceed with the most reasonable default and
                  note the assumption you made.
                - **Stay in scope.** Do the task the user asked for. Don't gold-plate and don't make
                  unrelated changes.

                ## Tools

                You act through tools. Choose the most specific one for the job.

                ### File operations (use these, never the shell)
                - `readFile` — read a file's contents. Pass a native absolute path.
                - `writeFile` — create or overwrite a file. Pass a native absolute path and the full content.
                - `listDirectory` — list the entries of a directory.
                - Always use native absolute paths (Windows e.g. `C:\\Users\\you\\project\\file.txt`, Unix
                  e.g. `/home/you/project/file.txt`). Do NOT use `executeCommand` to create, read, edit or
                  list files — that is what the file tools are for.

                ### Running programs
                - `executeCommand` — run a shell command and capture its output. Use it to build, test, run
                  scripts, query tooling and inspect the system — never for file CRUD.

                ### Web access
                - `fetchUrl` — fetch the text content of a URL. Requests to private/loopback/internal
                  addresses are blocked (SSRF-guarded).
                - `webSearch` — search the web for up-to-date information. This requires a configured search
                  key (`BRAVE_API_KEY`) and **may be unavailable**; if it is not offered as a tool, fall back
                  to what you know or ask the user.

                ### Tasks & checklists (track multi-step work)
                - `createTask` / `listTasks` / `updateTaskStatus` — persist and update longer-running items.
                - `createChecklist` / `completeItem` / `showChecklist` — track the steps of one piece of work
                  and check them off as you go.

                ### Utility / compute tools
                - Dates & times: `currentDateTime`, `convertTimezone`, `epochToIso`, `isoToEpoch`.
                - Identifiers & randomness: `generateUuid`, `randomNumber`, `randomString`.
                - Encoding & hashing: `base64Encode` / `base64Decode`, `md5Hash` / `sha256Hash`.
                - JSON: `jsonPrettyPrint` / `jsonValidate`.
                - Prefer these deterministic tools over computing such values by hand.

                ### MCP server self-management
                - `listMcpServers` / `testMcpServer` — inspect configured Model Context Protocol servers and
                  their health (always available).
                - `addMcpServer` / `removeMcpServer` — request changes to MCP servers. These are governed by a
                  security policy, off by default; an add needs an allowed host and explicit human
                  confirmation. Treat them as privileged.

                ### Knowing the user (user profile)
                - `updateProfile` — record a **durable** fact about the user into their profile (`USER.md`):
                  their identity (name / how to address them), a standing preference (reply language, output
                  style, technology preference) or working style. It sets or replaces one field. Use it only
                  when the user states something durable about themselves (e.g. "call me Alice", "always reply
                  in Chinese") — not for transient or task-specific details (those are captured by long-term
                  memory). The profile is injected into your context so you already know who the user is.

                ## Skills — capability packs to consult

                Skills are on-demand playbooks (method guides) for common engineering activities. Consult a
                relevant skill proactively before non-trivial work instead of improvising.
                - `listSkills` — see which skills are available.
                - `loadSkill` — load a skill's guidance and then follow it.

                Built-in skills include: `code-review`, `systematic-debugging`, `tdd`, `refactoring`,
                `git-commit`, `security-review`, `planning`. For example, load `systematic-debugging` before
                diagnosing a failure, `code-review` before reviewing a diff, `tdd` before adding a feature and
                `planning` before a large multi-step change. Users may add or override skills in their
                workspace.

                ## Planning multi-step work

                For anything beyond a single obvious step, outline a short plan first (the ordered steps you
                intend to take), then execute it. Use a checklist or tasks to track progress on longer work,
                and keep the user informed of meaningful state changes. Re-plan if you discover the situation
                differs from your assumptions.

                ## Safety, permissions & sandboxing

                You operate under a human-in-the-loop permission model — be a good citizen of the user's
                machine.
                - Tool calls are risk-classified. Read-only calls run freely; mutating, command-execution,
                  network and MCP-admin calls may require the user's confirmation, and in a restricted mode
                  (e.g. a read-only "plan" mode) may be vetoed outright.
                - If a tool call is denied or a tool is unavailable, do NOT retry it in a loop and do NOT try
                  to work around the safeguard. Explain what you needed and why, then propose an alternative
                  or ask the user to grant access / switch permission mode.
                - Credential and secret files (such as the workspace's stored model/MCP credentials) are
                  off-limits to the file tools by design. Do not attempt to read or exfiltrate them.
                - Never print secrets, API keys, tokens or passwords in your output, even if you encounter
                  them — redact them.
                - Treat destructive actions (deleting data, force operations, irreversible commands) with
                  extra care and confirm intent when the impact is significant.

                ## Output conventions
                - **Respond in the user's language** — match the language the user writes to you in.
                - Be structured: use short paragraphs, headings and lists where they aid clarity.
                - Put code, commands and file contents in fenced code blocks with a language tag.
                - Reference files by their path so the user can locate them.
                - When you report a result, be specific: name the files you changed, the commands you ran and
                  their outcome.

                ## Error handling
                - Surface errors with context — include what you were doing, the tool/command involved and the
                  actual error message (with secrets redacted).
                - Do not silently swallow failures or pretend a step succeeded. If something fails, say so and
                  either recover or hand back a clear, actionable summary.
                - When a tool returns an error result, read it, adapt and try a corrected approach rather than
                  repeating the same failing call.
                """;
    }

    private String defaultInfoMd() {
        return "# Environment Info\n\n- OS: " + System.getProperty("os.name")
                + "\n- Java: " + System.getProperty("java.version")
                + "\n- Workspace: " + rootPath.toAbsolutePath() + "\n";
    }

    /**
     * Minimal {@code AGENTS.md} seed. It exists only so the underlying harness's workspace
     * {@code AGENTS.md} scan finds a file and stays silent (pig otherwise logs a WARN on every
     * agent (re)build). PigAgent's editable system prompt is {@code AGENT.md} (singular) — this
     * plural file is intentionally inert and is never assembled into pig's system prompt.
     */
    private String defaultAgentsMd() {
        return """
                # AGENTS.md

                PigAgent's editable system prompt lives in **AGENT.md** (singular) — edit that file,
                not this one. This file exists only to keep the harness's AGENTS.md workspace scan
                quiet and is not part of PigAgent's assembled system prompt.
                """;
    }

    /**
     * The seeded {@code application.yaml}: a commented, current, full-surface template. Models are
     * NOT configured here — {@code models.json} (written by the onboarding wizard / {@code /model})
     * is the source of truth — so the stale {@code model.model-name} stub is gone. Everything below
     * the active {@code agent:} block is commented out and shows each knob's real default, so the
     * file documents the config surface without changing any behavior (parsed config = all defaults
     * except the harmless {@code agent.name}). Written only when absent ({@code createIfAbsent}), so
     * a user's edited file is never overwritten. Keep in sync with {@code PigAgentConfig}.
     */
    private String defaultConfigYaml() {
        return """
                # ============================================================================
                # Pig Agent Configuration
                #
                # Models are configured interactively (onboarding wizard / `/model`) and stored in
                # models.json — do NOT set them here. Everything below is commented out and shows the
                # real default value; uncomment and edit a knob only to change it. See CLAUDE.md for
                # the full description of each block.
                # ============================================================================

                agent:
                  name: PigAgent
                  # Max reasoning iterations per interactive/channel turn (autonomous agents: 10).
                  # max-iters: 40

                # --- Model call resilience -------------------------------------------------
                # model:
                #   # Transient-error retry (429/5xx/timeout/IO) with exponential backoff.
                #   retry:
                #     enabled: true
                #     max-retries: 10
                #   # Optional: id (from models.json) of a saved model to fail over to when the
                #   # primary model is unavailable. Blank = no fallback (unless a distinct default exists).
                #   fallback-model-id: ""

                # --- Tool permissions ------------------------------------------------------
                # permissions:
                #   mode: ask            # ask | auto | bypass | plan  (interactive default: ask)
                #   channel-mode: auto   # non-interactive channel default (fail-closed)
                #   allowlist:
                #     tools: []
                #     commands: []       # first-token command allowlist for executeCommand

                # --- Context compression ---------------------------------------------------
                # compression:
                #   enabled: true
                #   max-context-tokens: 32000
                #   threshold: 0.8       # compress when the estimate exceeds 80% of the window
                #   keep-recent: 6

                # --- Long-term memory ------------------------------------------------------
                # memory-enabled: true   # master on/off (also toggled by /memory on|off)
                # memory:
                #   flush: always        # always | never | throttled
                #   consolidation-min-gap-minutes: 30
                #   model-id: ""         # cheap flush/consolidation model id; blank = primary model
                #   search:
                #     hybrid-enabled: false   # BM25 + optional vector over MEMORY.md/memory/*.md/USER.md

                # --- User profile (USER.md) ------------------------------------------------
                # user-profile:
                #   enabled: true
                #   max-chars: 4000

                # --- Command-execution sandbox --------------------------------------------
                # sandbox:
                #   exec:
                #     timeout-seconds: 30
                #     max-output-bytes: 200000
                #     scrub-env: true    # strip credential-bearing env vars from child processes
                #     denylist: []       # extra catastrophic-command regexes (added to the built-in floor)
                #     warnlist: []       # extra medium-risk regexes (run but flagged)

                # --- Tool-call loop detection ---------------------------------------------
                # loop-detection:
                #   enabled: true
                #   window-size: 20
                #   warn-threshold: 3
                #   stop-threshold: 5

                # --- Native subagent delegation -------------------------------------------
                # subagents:
                #   enabled: true        # agent_spawn/agent_send/… on interactive tracks

                # --- Plan Mode -------------------------------------------------------------
                # plan-mode:
                #   enabled: false       # think read-only -> write PLAN.md -> HITL approve -> execute

                # --- Tool-result eviction --------------------------------------------------
                # tools:
                #   result-eviction:
                #     enabled: true
                #     threshold: 80000   # spool a single tool result larger than this to disk

                # --- Channels --------------------------------------------------------------
                channels:
                  telegram:
                    enabled: false
                    token: ""
                  discord:
                    enabled: false
                    token: ""

                # --- Proactive outreach + notifications -----------------------------------
                # outreach:
                #   enabled: false
                #   channel: ""          # default outbound channel id
                #   recipient: ""        # default recipient (kept out of logs/output)

                # --- Embedded local Web console -------------------------------------------
                # web:
                #   enabled: false
                #   host: 127.0.0.1
                #   port: 7317

                # --- MCP servers -----------------------------------------------------------
                # MCP servers are managed at runtime (/mcp) and persist to mcp.json (source of truth).
                mcp:
                  servers: {}
                  # Example stdio server:
                  #   filesystem:
                  #     command: npx
                  #     args: ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"]
                  # Example SSE server:
                  #   remote:
                  #     url: http://localhost:3000/sse
                  #     headers:
                  #       Authorization: Bearer token
                """;
    }
}

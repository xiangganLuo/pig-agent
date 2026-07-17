package io.pigagent.cli.repl;

import io.pigagent.tool.skills.ClasspathSkillSource;
import io.pigagent.tool.skills.Skill;
import io.pigagent.tool.skills.SkillLimits;
import io.pigagent.tool.skills.SkillRegistry;
import io.pigagent.tool.skills.WorkspaceSkillSource;
import io.pigagent.tool.skills.authoring.SkillContentScanner;
import io.pigagent.tool.skills.authoring.SkillDraft;
import io.pigagent.tool.skills.authoring.SkillGate;
import io.pigagent.tool.skills.authoring.SkillStagingArea;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /skill} human gate: review lists staged drafts, approve promotes, reject discards. */
class SkillCommandTest {

    @TempDir
    Path skillsDir;

    private SkillStagingArea staging;

    private CommandLine build(ByteArrayOutputStream out) throws IOException {
        staging = new SkillStagingArea(skillsDir, ".pending", SkillLimits.defaults());
        WorkspaceSkillSource ws = new WorkspaceSkillSource(skillsDir);
        SkillGate gate = new SkillGate(staging, SkillContentScanner.defaults(),
                new SkillRegistry(List.of(ws, new ClasspathSkillSource())), ws);
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true).streams(new ByteArrayInputStream(new byte[0]), out).build();
        ReplContext ctx = new ReplContext(
                null, null, null, null, null, null, null, null, null, null,
                terminal, new AtomicBoolean(true), new AtomicReference<LineReader>(), null, null, gate);
        return ReplCommands.build(ctx, CommandLine.defaultFactory());
    }

    private void stage(String name) throws IOException {
        staging.stage(new SkillDraft(name, "desc for " + name, List.of("k"), "# " + name + "\n\nbody"));
    }

    private List<String> workspaceNames() {
        return new WorkspaceSkillSource(skillsDir).discover().stream().map(Skill::name).toList();
    }

    @Test
    void reviewListsStagedDrafts() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine cmd = build(out);
        stage("my-skill");

        int code = cmd.execute("/skill", "review");

        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("my-skill").contains("scan ok");
    }

    @Test
    void approvePromotesDraftIntoWorkspace() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine cmd = build(out);
        stage("promote-me");

        int code = cmd.execute("/skill", "approve", "promote-me");

        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Promoted");
        assertThat(Files.isRegularFile(skillsDir.resolve("promote-me").resolve("SKILL.md"))).isTrue();
        assertThat(workspaceNames()).contains("promote-me");
    }

    @Test
    void rejectDiscardsDraft() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine cmd = build(out);
        stage("drop-me");

        cmd.execute("/skill", "reject", "drop-me");

        assertThat(staging.exists("drop-me")).isFalse();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Discarded");
    }

    @Test
    void approveUnknownSkillReportsNotFound() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine cmd = build(out);

        cmd.execute("/skill", "approve", "ghost");

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("No staged skill");
    }
}

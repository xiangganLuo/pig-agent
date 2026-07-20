package io.pigagent.tool.skills.curator;

import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.skill.curator.SkillCurator;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adopts the AgentScope 2.0 native {@link SkillCurator} as a pure library for pig's skill "metabolism"
 * (skill-curator-and-graded-promotion, S3): skill aging + archive to {@code skills/.archive/} +
 * {@code umbrellaPassMode} semantic umbrella-merge. Driven standalone over a {@link LocalFilesystem}
 * (no model, no native prompt path — "engine, not mouth").
 *
 * <p><b>Read-only by default.</b> When {@code autoArchive} is false the pass is <b>non-destructive</b>
 * — it only runs the umbrella dry-run report and derives the "would-archive" candidates from the usage
 * store — so an operator can observe curator suggestions before enabling real archival. Only when
 * {@code autoArchive} is true does it call {@link SkillCurator#runOnce(Instant)} to actually move stale
 * agent-created skills to {@code .archive}.
 *
 * <p><b>The repository MUST be writable</b> (spike finding R-Spike-S3-1): the 3-arg
 * {@link WorkspaceSkillRepository} constructor defaults to read-only, which silently ignores the
 * archive move; the {@link #forWorkspace} convenience builds a writable one.
 */
public final class SkillCuratorService {

    private static final Logger log = LoggerFactory.getLogger(SkillCuratorService.class);
    private static final String SKILLS_DIR = "skills";

    private final SkillUsageStore usageStore;
    private final SkillCurator curator;
    private final boolean autoArchive;

    /** Primary (test-friendly): inject the native pieces directly. */
    public SkillCuratorService(SkillUsageStore usageStore, SkillCurator curator, boolean autoArchive) {
        this.usageStore = usageStore;
        this.curator = curator;
        this.autoArchive = autoArchive;
    }

    /**
     * Convenience: build the native engine over a {@link LocalFilesystem} rooted at the workspace, with
     * a <b>writable</b> {@link WorkspaceSkillRepository} (required for archival) and an empty
     * {@link RuntimeContext} supplier (telemetry is agent-scoped, not user-scoped).
     */
    public static SkillCuratorService forWorkspace(Path workspaceRoot, SkillCuratorConfig config,
                                                   boolean autoArchive) {
        LocalFilesystem fs = new LocalFilesystem(workspaceRoot);
        SkillUsageStore store = new SkillUsageStore(fs);
        WorkspaceSkillRepository repo =
                new WorkspaceSkillRepository(fs, SKILLS_DIR, RuntimeContext::empty, "workspace", true);
        SkillCurator curator = new SkillCurator(fs, store, repo, config);
        return new SkillCuratorService(store, curator, autoArchive);
    }

    /**
     * Run one curator pass. In dry-run mode ({@code autoArchive=false}) this is non-destructive; in
     * apply mode it performs real {@code stale→.archive} transitions. Never throws — a failure degrades
     * to a status-only summary.
     */
    public CuratorRunSummary runOnce() {
        Instant now = Instant.now();
        try {
            List<String> candidates = staleCandidates(now);
            int tracked = trackedCount();
            if (!autoArchive) {
                String reportPath = curator.runUmbrellaDryRunReport(now);
                return CuratorRunSummary.dryRun(tracked, candidates, reportPath, now);
            }
            SkillCurator.CuratorRunReport report = curator.runOnce(now);
            SkillCurator.TransitionCounts t = report.transitions();
            return CuratorRunSummary.applied(t.checked(), t.markedStale(), t.archived(), t.reactivated(),
                    tracked, candidates, report.ranAt());
        } catch (RuntimeException e) {
            log.warn("Skill curator run failed: {}", e.toString());
            return CuratorRunSummary.status(0, List.of(), now);
        }
    }

    /** Read-only status: usage-tracked count + would-archive candidates. Never mutates, never throws. */
    public CuratorRunSummary status() {
        Instant now = Instant.now();
        try {
            return CuratorRunSummary.status(trackedCount(), staleCandidates(now), now);
        } catch (RuntimeException e) {
            log.warn("Skill curator status failed: {}", e.toString());
            return CuratorRunSummary.status(0, List.of(), now);
        }
    }

    /** For the scheduler: run a pass and log a one-line summary (fire-and-forget). */
    public void runScheduled() {
        CuratorRunSummary summary = runOnce();
        log.info("Skill curator pass: {}", summary.describe());
    }

    /** Agent-created skills whose latest activity is older than {@code archiveAfterDays}, not archived. */
    private List<String> staleCandidates(Instant now) {
        Instant cutoff = now.minus(curator.config().archiveAfterDays(), ChronoUnit.DAYS);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, SkillUsageRecord> e : usageStore.load().entrySet()) {
            SkillUsageRecord r = e.getValue();
            if (r == null || !r.isAgentCreated() || r.pinned()) {
                continue;
            }
            if (r.state() == SkillUsageRecord.State.ARCHIVED) {
                continue;
            }
            Instant activity = r.latestActivityAt();
            if (activity != null && activity.isBefore(cutoff)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private int trackedCount() {
        int n = 0;
        for (SkillUsageRecord r : usageStore.load().values()) {
            if (r != null && r.isAgentCreated()) {
                n++;
            }
        }
        return n;
    }
}

package io.pigagent.session;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.AgentModelSwitcher;
import io.pigagent.core.agent.PigAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline behavioural coverage for the {@link SessionManager} orchestrator: session lifecycle
 * (initialize/activate/create/fork/delete/rename), the per-session model switch handshake
 * ({@link AgentModelSwitcher}), and memory toggling ({@code memory-enabled} config + rebuild hook).
 * Uses a real {@link FileSystemSessionRepository} on a {@code @TempDir}, a real {@link PigAgent} on an
 * in-memory {@code AgentStateStore} with a stub {@link Model} (never called), and a recording model
 * switcher — no network. Long-term memory is now the AgentScope 2.0 native workspace-level tier
 * ({@code pa-memory-native}); there is no session-tier temp memory to swap.
 */
class SessionManagerTest {

    @TempDir
    Path root;

    private Path sessionsDir;
    private SessionRepository repository;
    private ConfigurationManager configManager;
    private final List<String> ensureModelCalls = new ArrayList<>();
    private final AtomicInteger memoryRebuilds = new AtomicInteger();
    private AgentHolder holder;
    private SessionManager manager;

    @BeforeEach
    void setUp() {
        sessionsDir = root.resolve("sessions");
        repository = new FileSystemSessionRepository(sessionsDir);
        configManager = new ConfigurationManager(root.resolve("application.yaml"));
        // A real PigAgent on a real on-disk JsonFileAgentStateStore so delete-removes-native-slot is
        // exercised end-to-end; the stub model is never called (no turns are run).
        holder = new AgentHolder(
                PigAgent.builder().name("t").sysPrompt("s").model(stubModel())
                        .stateStore(new JsonFileAgentStateStore(root.resolve("state")))
                        .workspace(root).build());
        AgentModelSwitcher switcher = id -> ensureModelCalls.add(id);
        manager = new SessionManager(holder, switcher, repository, configManager, sessionsDir,
                memoryRebuilds::incrementAndGet);
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    @Test
    void initialize_noSessions_createsAndActivatesBlankDefault() {
        manager.initialize();

        assertThat(manager.getCurrentSessionId()).isNotNull();
        assertThat(manager.list()).hasSize(1);
        assertThat(manager.isMemoryEnabled()).isTrue(); // config default memory-enabled=true
        assertThat(configManager.getConfig().getCurrentSessionId())
                .isEqualTo(manager.getCurrentSessionId());
    }

    @Test
    void initialize_restoresConfiguredSession() {
        Session a = repository.save(Session.create("a"));
        repository.save(Session.create("b"));
        configManager.updateConfig(c -> c.setCurrentSessionId(a.id()));

        manager.initialize();

        assertThat(manager.getCurrentSessionId()).isEqualTo(a.id());
    }

    @Test
    void initialize_noConfigured_restoresMostRecentlyActive() {
        repository.save(Session.create("old").withLastActiveAt(Instant.ofEpochMilli(1_000)));
        Session newer = repository.save(Session.create("new").withLastActiveAt(Instant.ofEpochMilli(2_000)));

        manager.initialize();

        assertThat(manager.getCurrentSessionId()).isEqualTo(newer.id());
    }

    @Test
    void activate_ensuresBoundModel_andPersistsCurrent() {
        Session s = repository.save(Session.create("bound").withModelId("m-9"));

        manager.activate(s.id());

        assertThat(ensureModelCalls).containsExactly("m-9");
        assertThat(manager.getCurrentSessionId()).isEqualTo(s.id());
        assertThat(configManager.getConfig().getCurrentSessionId()).isEqualTo(s.id());
    }

    @Test
    void activate_savesPreviousSessionBeforeSwitching() {
        Session first = manager.createBlank("first");
        ensureModelCalls.clear();
        Session second = repository.save(Session.create("second"));

        manager.activate(second.id());

        // Switching away persists the previous session, then activates the target.
        assertThat(manager.getCurrentSessionId()).isEqualTo(second.id());
        assertThat(repository.findById(first.id())).isPresent();
    }

    @Test
    void createBlank_createsActivatesAndNames() {
        Session created = manager.createBlank("research");

        assertThat(created.name()).isEqualTo("research");
        assertThat(manager.getCurrentSessionId()).isEqualTo(created.id());
        assertThat(manager.getCurrentSession()).get().extracting(Session::name).isEqualTo("research");
    }

    @Test
    void bindCurrentSessionModel_savesBinding() {
        manager.createBlank("x");

        manager.bindCurrentSessionModel("gpt-5");

        assertThat(repository.findById(manager.getCurrentSessionId()))
                .get().extracting(Session::modelId).isEqualTo("gpt-5");
    }

    @Test
    void bindCurrentSessionModel_noCurrent_isNoOp() {
        manager.bindCurrentSessionModel("m"); // no active session yet

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void rename_currentSession() {
        manager.createBlank("orig");

        manager.rename("renamed");

        assertThat(manager.getCurrentSession()).get().extracting(Session::name).isEqualTo("renamed");
    }

    @Test
    void rename_blankIsIgnored() {
        manager.createBlank("keep");

        manager.rename("   ");

        assertThat(manager.getCurrentSession()).get().extracting(Session::name).isEqualTo("keep");
    }

    @Test
    void noteUserMessage_renamesWhenStillDefaultName() {
        manager.createBlank(null); // default name

        assertThat(manager.getCurrentSession()).get().extracting(Session::hasDefaultName).isEqualTo(true);
        manager.noteUserMessage("Deploy the release tonight");

        assertThat(manager.getCurrentSession().get().hasDefaultName()).isFalse();
        assertThat(manager.getCurrentSession().get().name()).isEqualTo("Deploy the release tonight");
    }

    @Test
    void noteUserMessage_keepsNonDefaultName() {
        manager.createBlank("Named");

        manager.noteUserMessage("something else entirely");

        assertThat(manager.getCurrentSession().get().name()).isEqualTo("Named");
    }

    @Test
    void delete_currentSession_activatesReplacement() {
        Session keep = repository.save(Session.create("keep").withLastActiveAt(Instant.ofEpochMilli(5_000)));
        manager.createBlank("temp"); // now current, newest
        String tempId = manager.getCurrentSessionId();

        manager.delete(List.of(tempId));

        assertThat(manager.getCurrentSessionId()).isEqualTo(keep.id());
        assertThat(repository.findById(tempId)).isEmpty();
    }

    @Test
    void delete_currentSession_noOthers_createsBlank() {
        manager.createBlank("only");
        String id = manager.getCurrentSessionId();

        manager.delete(List.of(id));

        assertThat(manager.getCurrentSessionId()).isNotNull().isNotEqualTo(id);
        assertThat(manager.list()).hasSize(1);
    }

    @Test
    void delete_removesNativeConversationStateSlot() {
        manager.createBlank("keep"); // stays current; not deleted
        Session target = repository.save(Session.create("target"));
        PigAgent agent = holder.get();
        // Seed and persist the (pig, target) native conversation slot.
        agent.getMemory(target.id()).addMessage(user("hi"));
        agent.saveTo(target.id());
        assertThat(agent.loadIfExists(target.id())).isTrue();

        manager.delete(List.of(target.id()));

        // Both the native conversation slot AND the metadata sidecar are removed (no orphan on disk).
        assertThat(agent.loadIfExists(target.id())).isFalse();
        assertThat(repository.findById(target.id())).isEmpty();
    }

    @Test
    void clearConversation_invokesSnapshotResetHook() {
        List<String> resets = new ArrayList<>();
        SessionManager mgr = new SessionManager(holder, id -> ensureModelCalls.add(id),
                repository, configManager, sessionsDir, null, resets::add);
        Session created = mgr.createBlank("c");

        mgr.clearConversation(false);

        assertThat(resets).containsExactly(created.id());
    }

    @Test
    void fork_copiesModelBinding() {
        manager.createBlank("src");
        manager.bindCurrentSessionModel("m-fork");

        Session forked = manager.fork("mine");

        assertThat(forked.name()).isEqualTo("mine");
        assertThat(forked.modelId()).isEqualTo("m-fork");
        assertThat(manager.getCurrentSessionId()).isEqualTo(forked.id());
    }

    @Test
    void fork_withNoCurrentSession_createsBlank() {
        Session forked = manager.fork("brand-new");

        assertThat(forked.name()).isEqualTo("brand-new");
        assertThat(manager.getCurrentSessionId()).isEqualTo(forked.id());
    }

    @Test
    void setMemoryEnabled_togglesConfig_andTriggersRebuild() {
        manager.setMemoryEnabled(false);

        assertThat(manager.isMemoryEnabled()).isFalse();
        assertThat(configManager.getConfig().isMemoryEnabled()).isFalse();
        assertThat(memoryRebuilds.get()).isEqualTo(1); // rebuild hook fired

        manager.setMemoryEnabled(true);
        assertThat(manager.isMemoryEnabled()).isTrue();
        assertThat(memoryRebuilds.get()).isEqualTo(2);
    }

    @Test
    void clearConversation_withTempMemory_deletesLegacyTempFile() throws IOException {
        manager.createBlank("c");
        Path temp = sessionsDir.resolve(manager.getCurrentSessionId()).resolve("temp-memory.md");
        Files.createDirectories(temp.getParent());
        Files.writeString(temp, "x");

        manager.clearConversation(true);

        assertThat(Files.exists(temp)).isFalse();
    }

    @Test
    void lineageOf_unknownOrNull_returnsEmpty() {
        assertThat(manager.lineageOf(null)).isEmpty();
        assertThat(manager.lineageOf("does-not-exist")).isEmpty();
    }

    @Test
    void lineageOf_knownSession_returnsIt() {
        Session s = repository.save(Session.create("k"));

        assertThat(manager.lineageOf(s.id())).get().extracting(Session::id).isEqualTo(s.id());
    }

    @Test
    void list_ordersMostRecentlyActiveFirst() {
        Session a = repository.save(Session.create("a").withLastActiveAt(Instant.ofEpochMilli(1_000)));
        Session b = repository.save(Session.create("b").withLastActiveAt(Instant.ofEpochMilli(3_000)));
        Session c = repository.save(Session.create("c").withLastActiveAt(Instant.ofEpochMilli(2_000)));

        assertThat(manager.list()).extracting(Session::id).containsExactly(b.id(), c.id(), a.id());
    }

    private static Model stubModel() {
        return new Model() {
            @Override
            public String getModelName() {
                return "stub";
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                return Flux.empty();
            }
        };
    }
}

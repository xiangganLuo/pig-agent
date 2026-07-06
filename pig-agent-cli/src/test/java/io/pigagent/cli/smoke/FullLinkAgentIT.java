package io.pigagent.cli.smoke;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.agent.AgentFactory;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.memory.CompositeLongTermMemory;
import io.pigagent.core.memory.FileSystemLongTermMemory;
import io.pigagent.mcp.JsonMcpStore;
import io.pigagent.mcp.McpManager;
import io.pigagent.mcp.McpServerSpec;
import io.pigagent.model.JsonModelStore;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.provider.anthropic.AnthropicProtocol;
import io.pigagent.provider.dashscope.DashScopeProtocol;
import io.pigagent.provider.gemini.GeminiProtocol;
import io.pigagent.provider.ollama.OllamaProtocol;
import io.pigagent.provider.openai.OpenAiProtocol;
import io.pigagent.provider.registry.ProtocolRegistry;
import io.pigagent.session.FileSystemSessionRepository;
import io.pigagent.session.Session;
import io.pigagent.session.SessionManager;
import io.pigagent.task.FileSystemTaskRepository;
import io.pigagent.task.Task;
import io.pigagent.task.TaskManager;
import io.pigagent.tool.checklist.CheckListTool;
import io.pigagent.tool.filesystem.FileSystemTools;
import io.pigagent.tool.mcp.McpConfirmer;
import io.pigagent.tool.mcp.McpTool;
import io.pigagent.tool.shell.ShellTools;
import io.pigagent.tool.skills.SkillsTool;
import io.pigagent.tool.task.TaskTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路集成测试：用真实 anthropic 模型（经自定义 baseUrl）构建与 {@code PigAgentCli} 同构的
 * agent + 工具集 + 会话/MCP 基础设施，逐模块验证核心功能跑通。
 *
 * <p>命名为 {@code *IT}，不进默认 {@code mvn test}（会真实调用 LLM、耗 token）；
 * 显式运行：{@code mvn -pl pig-agent-cli -am test -Dtest=FullLinkAgentIT}。
 *
 * <p>覆盖 5 模块：tools（FileSystemTools，LLM 驱动）、task（TaskTool，LLM 驱动）、
 * skills（SkillsTool，LLM 驱动）、session（SessionManager 持久化往返）、
 * mcp（McpManager + JsonMcpStore 导入/容错/去重）。
 */
class FullLinkAgentIT {

    @TempDir
    Path tmp;

    private TaskManager taskManager;
    private McpManager mcpManager;
    private Toolkit toolkit;
    private AgentHolder agentHolder;
    private AgentFactory agentFactory;
    private ModelManager modelManager;
    private CompositeLongTermMemory memory;
    private ConfigurationManager configManager;
    private Path skillsDir;
    private Path sessionsDir;

    @BeforeEach
    void buildRealAgent() throws Exception {
        // 真实 models.json（anthropic + 自定义 baseUrl），其余目录隔离到临时工作区。
        Path realModels = Path.of(System.getProperty("user.home"), ".pig-agent", "workspace", "models.json");
        assertThat(realModels).as("需要已配置的 anthropic models.json").exists();

        ProtocolRegistry registry = new ProtocolRegistry();
        registry.register(new OpenAiProtocol());
        registry.register(new AnthropicProtocol());
        registry.register(new GeminiProtocol());
        registry.register(new OllamaProtocol());
        registry.register(new DashScopeProtocol());

        modelManager = new ModelManager(registry, new JsonModelStore(realModels));
        StoredModel def = modelManager.getDefault().orElseThrow();
        Model model = modelManager.buildModel(def);

        skillsDir = Files.createDirectories(tmp.resolve("skills"));
        sessionsDir = Files.createDirectories(tmp.resolve("sessions"));
        Path tasksDir = Files.createDirectories(tmp.resolve("tasks"));
        Path contextDir = Files.createDirectories(tmp.resolve("context"));

        taskManager = new TaskManager(new FileSystemTaskRepository(tasksDir));

        mcpManager = new McpManager();
        mcpManager.initialize(new JsonMcpStore(tmp.resolve("mcp.json")), new Toolkit(), null);

        toolkit = new Toolkit();
        toolkit.registration().tool(new TaskTool(taskManager)).apply();
        toolkit.registration().tool(new FileSystemTools()).apply();
        toolkit.registration().tool(new ShellTools()).apply();
        toolkit.registration().tool(new CheckListTool()).apply();
        toolkit.registration().tool(new SkillsTool(skillsDir)).apply();
        McpConfirmer denyAll = prompt -> false;
        toolkit.registration().tool(new McpTool(mcpManager,
                () -> new PigAgentConfig.AgentManagementConfig(), denyAll)).apply();

        memory = new CompositeLongTermMemory(
                new FileSystemLongTermMemory(contextDir.resolve("memory.md")), true);

        String sysPrompt = "你是一个集成测试助理。当用户要求进行文件、任务、技能、MCP 等操作时，"
                + "你必须调用对应的工具完成，而不是凭空回答。";
        agentFactory = new AgentFactory("test-agent", sysPrompt, toolkit, List.of(), memory);
        agentHolder = new AgentHolder(agentFactory.create(model));

        configManager = new ConfigurationManager(tmp.resolve("application.yaml"));
    }

    private String ask(String text) {
        Msg msg = Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
        Msg reply = agentHolder.get().call(msg);
        String out = reply.getTextContent();
        System.out.println("[FULL-LINK] reply: " + (out == null ? "(null)" : out.replaceAll("\\s+", " ")));
        return out == null ? "" : out;
    }

    @Test
    void toolsModule_agentWritesAndReadsFile() throws Exception {
        Path target = tmp.resolve("flink.txt");
        ask("请用你的文件工具在绝对路径 " + target.toAbsolutePath()
                + " 写入且仅写入文本 PIG_FULL_LINK_OK，然后读回并告诉我内容。");
        assertThat(target).as("agent 应通过文件工具真实创建文件").exists();
        assertThat(Files.readString(target)).contains("PIG_FULL_LINK_OK");
    }

    @Test
    void taskModule_agentCreatesTask() {
        ask("请用你的任务工具创建一个任务，标题为 flink-task，描述为 full link test。");
        List<Task> tasks = taskManager.getAllTasks();
        System.out.println("[FULL-LINK] tasks=" + tasks.size());
        assertThat(tasks).as("agent 应通过任务工具真实创建任务").isNotEmpty();
        assertThat(tasks).anyMatch(t -> t.title() != null && t.title().contains("flink-task"));
    }

    @Test
    void skillsModule_agentListsSeededSkill() throws Exception {
        Files.createDirectories(skillsDir.resolve("demo-skill"));
        String out = ask("请用你的技能工具列出当前可用的技能。");
        assertThat(out).as("应能列出已种入的技能目录 demo-skill").contains("demo-skill");
    }

    @Test
    void sessionModule_persistAndRestoreRoundTrip() {
        SessionManager sm = newSessionManager();
        sm.initialize();
        Session alpha = sm.createBlank("alpha");
        Session beta = sm.createBlank("beta");
        assertThat(sm.list()).extracting(Session::name).contains("alpha", "beta");
        sm.activate(alpha.id());
        assertThat(sm.getCurrentSession().orElseThrow().name()).isEqualTo("alpha");
        sm.saveCurrent();

        // 用同一目录新建一个 SessionManager，应从持久化恢复到 alpha。
        SessionManager restored = newSessionManager();
        restored.initialize();
        assertThat(restored.getCurrentSessionId()).isEqualTo(alpha.id());
        assertThat(restored.list()).extracting(Session::name).contains("alpha", "beta");
        assertThat(beta.id()).isNotEqualTo(alpha.id());
    }

    private SessionManager newSessionManager() {
        JsonSession agentSession = new JsonSession(sessionsDir);
        return new SessionManager(agentHolder, modelManager, agentSession, memory,
                new FileSystemSessionRepository(sessionsDir), configManager, sessionsDir);
    }

    @Test
    void mcpModule_legacyImportAndResilienceAndDedup() throws Exception {
        // 1) 旧 application.yaml.mcp.servers 一次性导入 mcp.json（走真实 YAML → Jackson → 导入 链路）
        Path legacyYaml = tmp.resolve("legacy.yaml");
        Files.writeString(legacyYaml, """
                mcp:
                  servers:
                    legacy-srv:
                      url: https://example.invalid/sse
                """);
        PigAgentConfig.McpConfig legacy =
                new ConfigurationManager(legacyYaml).getConfig().getMcp();
        assertThat(legacy.getServers()).as("YAML 应被解析出 legacy-srv").containsKey("legacy-srv");

        McpManager mgr = new McpManager();
        Path mcpJson = tmp.resolve("mcp2.json");
        mgr.initialize(new JsonMcpStore(mcpJson), new Toolkit(), legacy);
        assertThat(mgr.findByName("legacy-srv")).as("旧配置应被导入").isPresent();
        assertThat(mcpJson).exists();

        // 2) 坏服务器连通测试只返回失败、绝不抛出/崩溃
        McpManager.TestResult bad = mgr.test(new McpServerSpec(
                "bad", "definitely-not-a-real-cmd-xyz", List.of(), Map.of(), null, false, Map.of(), true));
        assertThat(bad.ok()).isFalse();

        // 3) 重名拒绝
        McpServerSpec dup = new McpServerSpec("legacy-srv", null, List.of(), Map.of(),
                "https://example.invalid/sse", false, Map.of(), false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mgr.add(dup))
                .isInstanceOf(IllegalStateException.class);

        // 4) 实时健康列表可用（未连接 → toolCount = -1）
        assertThat(mgr.list()).isNotEmpty();
    }
}

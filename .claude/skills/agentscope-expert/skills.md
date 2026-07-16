# Skills

> 源: D:\Users\admin\Documents\en\docs\harness\skill.md
> 源: D:\Users\admin\Documents\en\integration\skill\{overview,git-repository,mysql-repository,postgresql-repository}.md

## 核心抽象

在 AgentScope Java 2.0（Harness 层）中，一个 skill 是一个打包好的能力目录：

```
code-reviewer/
├── SKILL.md           # 必填 — YAML frontmatter (name + description) + 指令正文
├── references/        # 可选 — 长文档，agent 按需通过工具读取
│   └── style-guide.md
└── scripts/           # 可选 — agent 可通过 shell 调用的脚本
    └── run-checks.sh
```

SKILL.md 格式（frontmatter + Markdown 正文）：

```markdown
---
name: code-reviewer
description: Use when the user asks for code review, style feedback, or PR audits.
---

# Code Reviewer

Steps:
1. Read `references/style-guide.md` for project conventions.
2. Run `scripts/run-checks.sh <target-path>` and summarize the output.
```

> **重要**：`description` 决定 agent 是否选用该 skill；必须写具体场景，避免泛化描述如 "a tool"。

核心接口 / 类：

| 名称 | 说明 |
|------|------|
| `AgentSkill` | skill 的内存表示（Markdown 内容 + 资源文件） |
| `AgentSkillRepository`（接口）| 统一 API：`getAllSkills()`, `getSkill(name)`, `getAllSkillNames()`, `skillExists(name)`, `save(list, overwrite)`, `delete(name)` |
| `HarnessAgent.builder().skillRepository(repo)` | Harness builder 入口，可多次调用（后者优先）|

低层 Toolkit 接线（非 Harness 场景）：

```java
AgentSkillRepository repo = ...;
List<AgentSkill> skills = repo.getAllSkills();
Toolkit toolkit = new Toolkit();
skills.forEach(toolkit::registerSkill);
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .build();
```

Harness builder（推荐）：

```java
HarnessAgent agent = HarnessAgent.builder()
        .name("assistant")
        .model(model)
        .workspace(workspace)
        .skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
        .build();
```

## Agent 如何发现和调用 Skill

推理阶段，system prompt 中注入 `<available_skills>` 块：

```xml
<available_skills>
<skill>
  <name>code-reviewer</name>
  <description>Use when the user asks for code review, style feedback, or PR audits.</description>
  <skill-id>code-reviewer_workspace-namespaced</skill-id>
  <files-root>/workspace/skills/code-reviewer</files-root>
</skill>
</available_skills>
```

Agent 通过内置工具激活 skill：
- `load_skill_through_path(skillId, path="SKILL.md")` — 读取 SKILL.md 正文
- `load_skill_through_path(skillId, path="references/style-guide.md")` — 读取任意资源文件

脚本执行路径来自 `<files-root>`：

| 文件系统模式 | workspace skill `<files-root>` | marketplace skill `<files-root>` |
|-------------|-------------------------------|----------------------------------|
| Sandbox | `/workspace/skills/<name>` | `/workspace/.skills-cache/<source>/<name>` |
| Local+shell | `<wsRoot>/skills/<name>` | `<wsRoot>/.skills-cache/<source>/<name>` |
| Local 无 shell | （不渲染）| （不渲染）|

Marketplace skill 在每次推理前被物化到主机 `<wsRoot>/.skills-cache/<source>/<name>/`（SHA-256 去重，仅变化文件重写）；sandbox 模式下再投影到容器内 `/workspace`。

## Skill 优先级（低 → 高）

| 优先级 | 来源 | 配置方式 |
|--------|------|----------|
| 1（最低）| Project-global dir | `projectGlobalSkillsDir(Path)`，如 `~/.agentscope/skills/` |
| 2 | Marketplaces | `skillRepository(...)`，同层后注册者胜出 |
| 3 | Workspace shared | `workspace/skills/` |
| 4（最高）| Per-user | `<userId>/skills/`（需在 `RuntimeContext` 传 `userId`）|

低优先级来源中不冲突的 skill 仍然可见，只有同名冲突时才被覆盖。

## Skill Repository 实现

### Git Repository

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-git-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

类名：`io.agentscope.core.skill.repository.GitSkillRepository`（底层使用 JGit，支持 HTTPS 和 SSH）

```java
// 最简（公开仓库，autoSync=true）
GitSkillRepository repo = new GitSkillRepository(
    "https://github.com/agentscope/skills.git"
);

// 完整参数
GitSkillRepository repo = new GitSkillRepository(
    "https://github.com/agentscope/skills.git",
    "develop",                    // branch
    Path.of("/var/skills/repo"),  // local path（null = 临时目录）
    "agentscope-public",          // source label（Toolkit 可见）
    true                          // autoSync
);

// 手动同步模式
GitSkillRepository repo = new GitSkillRepository(remoteUrl, false);
repo.sync();  // 按需调用，或定期调度
```

- `autoSync=true`（默认）：每次读取先 `ls-remote`，仅 remote HEAD 变化时才 pull
- 不在 Java 层管理凭据：HTTPS 依赖系统 credential helper，SSH 依赖 `~/.ssh/` + ssh-agent
- 若 repo 有 `skills/` 子目录，以其为根；否则以 repo 根为根
- 作为单例 Spring Bean 持有；shutdown 时 `close()` 清理临时目录

### MySQL Repository

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-mysql-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

类名：`io.agentscope.core.skill.repository.mysql.MysqlSkillRepository`

```java
// 最简（自动建库建表）
MysqlSkillRepository repo = new MysqlSkillRepository(dataSource, true);

// Builder（自定义库名 / 表名）
MysqlSkillRepository repo = MysqlSkillRepository.builder(dataSource)
        .databaseName("agentscope")
        .skillsTableName("skills")
        .createIfNotExist(true)
        .writeable(true)   // false = 只读分发
        .build();
```

`createIfNotExist=true` 时自动创建两张表：
- `agentscope_skills`（`name UNIQUE`, `skill_content LONGTEXT`）
- `agentscope_skill_resources`（级联删除，`ON DELETE CASCADE`）

`save(list, overwrite)` 是按 `name` 去重的 upsert；写操作在事务中执行。

### PostgreSQL Repository

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-postgresql-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

类名：`io.agentscope.core.skill.repository.postgresql.PostgresSkillRepository`

```java
// 最简
PostgresSkillRepository repo = new PostgresSkillRepository(dataSource, true, true);

// Builder
PostgresSkillRepository repo = PostgresSkillRepository.builder(dataSource)
        .schemaName("my_schema")
        .skillsTableName("my_skills")
        .resourcesTableName("my_resources")
        .createIfNotExist(true)
        .writeable(true)
        .build();
```

与 MySQL 差异：以 **schema**（非 database）作命名空间隔离；数据库由 JDBC URL 指定。默认 schema = `agentscope`。

### Nacos Repository

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-skill</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
NacosSkillRepository market = new NacosSkillRepository(aiService, "namespace");
// market 是 AutoCloseable，shutdown 时 close() 释放订阅
```

适合在线分发 + 变更订阅场景。

### Classpath Repository

```java
.skillRepository(new ClasspathSkillRepository("skills"))
// src/main/resources/skills/<name>/SKILL.md 打进 fat JAR
```

## 多 Repository 叠加

```java
HarnessAgent.builder()
        .skillRepository(communityMarket)    // 最低（marketplace 层内）
        .skillRepository(internalRegistry)
        .skillRepository(teamGitRepo)        // 最高（marketplace 层内）
        .build();
```

## Self-Learning Loop（可选，分三步启用）

```java
// Step 1：允许 agent 写 skill（propose_skill + skill_manage 工具）
HarnessAgent.builder()
    .enableSkillManageTool(SkillManageConfig.defaults())
    .build();
// 草稿写入 skills/_drafts/<name>/；使用计数自动记录到 skills/.usage.json

// Step 2：审核门 + 可见性过滤器
.enableSkillPromotionGate(
    new LocalApprovalGate(LocalApprovalGate.defaultPrompter()),
    new CompositeFilter(List.of(
        new EnvironmentFilter("prod", skillUsageStore),
        new CanaryFilter(0.10, skillUsageStore)
    )))
.environment("prod")

// Step 3：后台定期整理（stale → archive）
.enableSkillCurator(SkillCuratorConfig.builder()
    .intervalHours(7 * 24)
    .minIdleHours(2)
    .staleAfterDays(30)
    .archiveAfterDays(90)   // 移入 skills/.archive/
    .build())
```

编程式触发：

```java
agent.runCuratorOnce().subscribe(report -> ...);
agent.promoteSkill("notes-taker", "alice").subscribe(result -> ...);
List<SkillAuditLog.Entry> entries = agent.queryAudit(LocalDate.now(), e -> true);
```

## 常用 Builder 选项

| 方法 | 说明 |
|------|------|
| `skillRepository(repo)` | 追加一个 marketplace；可多次调用 |
| `skillRepositories(list)` | 一次性替换全部 marketplace |
| `projectGlobalSkillsDir(path)` | 启用 project-global 目录（路径不存在则跳过）|
| `disableDynamicSkills()` | 关闭"每次推理前重建技能集"；适合一次性任务或慢 store |

子 agent 自动继承父 agent 的 marketplace 和 project-global dir。

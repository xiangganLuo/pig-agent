## 1. 配置面（PigAgentConfig）

- [x] 1.1 `PigAgentConfig` 新增 `@JsonProperty("sandbox")` 字段 + getter，类型 `SandboxConfig`（默认 `new SandboxConfig()`）。
- [x] 1.2 新增 `SandboxConfig`（含 `exec` 子块）与 `ExecSandboxConfig`（`max-output-bytes` 默认 200_000、`timeout-seconds` 默认 30、`denylist` 默认空 `List`、`scrub-env` 默认 true、`working-dir` 默认 null），全字段 getter/setter，默认安全、向后兼容。

## 2. SandboxPolicy（配置值对象）

- [x] 2.1 新建包 `io.pigagent.tool.sandbox`；`SandboxPolicy`（`final` + `withXxx()` 拷贝方法）承载 maxOutputBytes/timeoutSeconds/extraDenyPatterns/scrubEnv/workingDir。
- [x] 2.2 构造器容错钳制：`maxOutputBytes<=0`→默认、`timeoutSeconds<=0`→默认、`extraDenyPatterns=null`→空、`extraDenyPatterns` 拷贝为不可变。`SandboxPolicy.defaults()` 静态工厂。
- [x] 2.3 单测：默认值正确；非法值被钳制为默认；`withXxx` 返回新实例不改原对象；`extraDenyPatterns` 不可变。

## 3. CommandGuard（Strategy）+ CappedOutput

- [x] 3.1 `CappedOutput` 记录 `{String text, boolean truncated}`（不可变）。
- [x] 3.2 `CommandGuard`：由 `SandboxPolicy` 构造；内置**灾难性模式**常量表（安全底线），追加 `policy.extraDenyPatterns()`（`CASE_INSENSITIVE` 编译，非法正则跳过 + warn）。SLF4J logger。
- [x] 3.3 `checkDenied(command) → Optional<String>`：规范化命令（`\s+`→单空格）后**两遍分类**（见 3.3a），命中返回**类别**拒因（不回显命令）。内置模式：`rm -rf /`|`~`（前瞻：rm∧递归∧根/家）、`Remove-Item` 递归删根/家（前瞻）、`mkfs`、`format <盘>:`、`diskpart`、`curl|wget → sh/bash`、`iex` 下载执行（双向）、fork bomb + `while true … & done`、`shutdown`/`reboot`/`halt`/`poweroff`、`dd of=/dev/…`、`chmod -R 777 /`（前瞻）。单测：**逐条**灾难命令被拦。
- [x] 3.3a 两遍分类（防操作符绕过）：Pass 1 结构型规则跑整串（fork bomb/while-loop/管道入 shell/`… | iex`）+ 用户追加正则；Pass 2 引号感知按 `;`/`&&`/`||`/`|`/`&` 拆分（`splitSubCommands`），单命令规则**只**跑各子命令。单测：`echo ok && rm -rf /`/`true; rm -rf ~`/`cat x | rm -rf /` 被拦；`git commit -m "a && b"`/`echo "safe ; text"` 不误伤。
- [x] 3.3b 输入校验（正则前）：空/空白→"empty command"、`>10_000` 字符→"command too long"、含 NUL→"null byte in command"，一律拒绝。单测：三类各被拒。
- [x] 3.4 单测（红线）：一组正常开发命令**不**被拦——`mvn -q test`、`git commit -m "..."`、`npm install`/`npm run format`、`ls -la`、`cat pom.xml`、`rm -rf target`、`rm -rf ./node_modules`、`rm -f file.txt`、`chmod -R 755 ./dir`、`chmod 755 run.sh`、`Remove-Item -Recurse -Force .\target`、`curl https://api.example.com/data`、`dd if=in of=out.bin`、`docker build .`、`echo "shutdown later"`。
- [x] 3.5 `capOutput(InputStream) → CappedOutput`：有界读取至 `maxOutputBytes`，超限继续 drain 丢弃、追加截断标记、置 `truncated`。单测（`ByteArrayInputStream`，不 spawn）：小输入原样不截断；大输入截断至上限内 + 含标记 + `truncated=true`；恰好等于上限不截断。
- [x] 3.6 `buildEnv(Map) → Map`：`scrubEnv=false` 原样拷贝；`true` 剔除凭据类键（`TOKEN`/`SECRET`/`PASSWORD`/`PASSWD`/`CREDENTIAL`/`APIKEY`/`_KEY`/结尾 `KEY`），保留其余，产出新 map。单测：剔除 `ANTHROPIC_API_KEY`/`GITHUB_TOKEN`/`AWS_SECRET_ACCESS_KEY`/`MY_PASSWORD`，保留 `PATH`/`HOME`/`LANG`/`JAVA_HOME`；`scrub-env=false` 全保留。
- [x] 3.7 `applyTo(ProcessBuilder)`：设置 cwd（配置非空时 `pb.directory(...)`）+ 就地脱敏 `pb.environment()`（清空后 `putAll(buildEnv(...))`）。单测（不 spawn）：注入假密钥 env → `applyTo` 后密钥消失、`PATH` 留存；`working-dir` 非空 → `pb.directory()` == 该目录（`@TempDir`）；`working-dir` 空 → `pb.directory()` 保持 null。

## 4. ShellTools 组合沙箱

- [x] 4.1 `ShellTools` 新增 `SandboxPolicy policy` + `CommandGuard guard` 字段；无参构造器用 `SandboxPolicy.defaults()`；`ShellTools(SandboxPolicy)`（null→默认）。SLF4J logger。
- [x] 4.2 `executeCommand` 组合：`guard.checkDenied` 命中 → `ToolErrors.message("blocked: <reason>")`（warn 日志，命令经 `auditSummary` 凭据脱敏 + 截断，不执行）；否则 `pb.redirectErrorStream(true)` → `guard.applyTo(pb)` → `start()` → `waitFor(policy.timeoutSeconds())` → `guard.capOutput(stream)` → 原有 exit code / timeout 语义。`buildInvocation` 不动。

## 5. 装配接入

- [x] 5.1 `ToolContext` 新增可空 `SandboxPolicy sandboxPolicy` 字段 + `sandboxPolicy()` 访问器；保留既有构造器，新增带 `sandboxPolicy` 的重载（旧重载 delegate 传 null）。
- [x] 5.2 `ShellToolsProvider.create`：从 `context.sandboxPolicy()` 取策略，为空→`new ShellTools()`，否则→`new ShellTools(policy)`。
- [x] 5.3 `AgentBootstrap`：从 `config.getSandbox().getExec()` 构造 `SandboxPolicy` 注入 `ToolContext`；手动装配 fallback 分支的 `new ShellTools()` 改为 `new ShellTools(sandboxPolicy)`。

## 6. 验收

- [x] 6.1 单测：`ToolContext` 携带/不带 `sandboxPolicy` 均可（向后兼容）；`ShellToolsProvider` 依策略产出带/不带策略的 `ShellTools`。
- [x] 6.2 `mvn -q test` **单线程**绿（读 surefire XML 计数）；既有 `ShellToolsTest`/权限/契约单测回归不破。
- [x] 6.3 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [x] 6.4 `CLAUDE.md` 增补「命令执行沙箱」段（新层 + 配置面 + 与权限分层 + 保守默认 + 诚实局限）。
- [x] 6.5 `openspec validate exec-sandbox --strict` 通过。

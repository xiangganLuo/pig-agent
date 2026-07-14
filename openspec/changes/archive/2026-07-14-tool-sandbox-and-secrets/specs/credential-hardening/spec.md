## ADDED Requirements

### Requirement: 凭据录入终端掩码
交互式录入 API key 与敏感凭据值时，系统 MUST 不在终端回显字符、不留入 scrollback。落点：`OnboardingWizard` 的 API key 录入、`ReplCommands` 的 `/model add` 与 `/model edit` 的 API key 录入、`McpCommand` 的 `/mcp add` 与 `/mcp edit` 中键名匹配 `*token*`/`*key*`/`authorization`/`*secret*`（不区分大小写）的值录入。JLine 环境 MUST 用 `reader.readLine(prompt, maskChar)` 掩码；`OnboardingWizard`（非 JLine）MUST 用 `System.console().readPassword(...)`。当无可用 console（`System.console()==null`）无法掩码时，系统 MUST 回退可见读取并打印一次告警，MUST NOT 阻断录入流程。非敏感字段（协议号、base URL、模型名、非敏感 env 键）不受影响、照常可见录入。

#### Scenario: REPL 录入 API key 不回显
- **WHEN** 用户在 `/model add` 或 `/model edit` 输入 API key
- **THEN** 输入字符以掩码显示、明文不出现在终端与 scrollback

#### Scenario: 首启向导录入 API key 不回显
- **WHEN** `OnboardingWizard` 提示输入 API key 且 `System.console()` 可用
- **THEN** 经 `readPassword` 读取，字符不回显

#### Scenario: MCP 敏感键掩码、普通键可见
- **WHEN** `/mcp add` 录入 header/env，键名为 `Authorization`（敏感）与 `REGION`（普通）
- **THEN** `Authorization` 的值掩码读取，`REGION` 的值照常可见读取

#### Scenario: 无 console 时降级不阻断
- **WHEN** 运行环境无交互 console（管道/IDE/`mvn exec`）导致无法掩码
- **THEN** 回退可见读取、打印一次告警，录入仍可完成

### Requirement: 凭据文件权限收敛
写入凭据文件（`models.json`、`mcp.json`）后，系统 MUST 在 POSIX 平台将文件权限收敛为 `rw-------`（`0600`，仅属主可读写）。非 POSIX 平台（如 Windows）MUST 静默忽略（捕获 `UnsupportedOperationException`/`IOException`），MUST NOT 因权限收敛失败而影响写入成功。收敛 MUST 在每次持久化写入后施加。

#### Scenario: POSIX 落盘为 0600
- **WHEN** 在 POSIX 平台保存模型或 MCP 配置触发 `persist()`
- **THEN** 文件权限被设为 `rw-------`，其它用户不可读

#### Scenario: 非 POSIX 平台不报错
- **WHEN** 在 Windows 上 `persist()` 写入后尝试收敛权限
- **THEN** 权限操作被静默忽略，写入本身成功、不抛异常

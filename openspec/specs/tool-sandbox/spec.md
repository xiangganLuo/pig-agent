# tool-sandbox Specification

## Purpose
约束 agent 工具的**出口边界**：文件工具对工作区凭据文件的敏感文件黑名单，与 `fetchUrl` 基于解析 IP 的 SSRF 防护（拒私网/回环/元数据 + 可选主机白名单）。作为 `tool-permissions` veto、`tool-availability` 门控之外的一道正交出口过滤（可见且获准的工具能触达什么）。

## Requirements
### Requirement: 凭据文件访问阻断
文件工具（`readFile`/`writeFile`）MUST 拒绝访问工作区凭据文件——`~/.pig-agent/workspace/models.json`、`mcp.json` 及其 `.bak` 兄弟文件。判定 MUST 基于**规范化后的真实路径**：目标存在时用 `toRealPath()`（解引用符号链接），不存在时用 `toAbsolutePath().normalize()`，据此使 `../` 相对路径与符号链接间接指向同样被拦。被拒 MUST 返回规范错误结果（`{"error": ...}`），MUST NOT 读取/写入文件内容，MUST NOT 抛异常中断回合。策略为凭据文件黑名单，MUST NOT 限制对其它任意路径（用户项目文件）的访问。缺省（未注入工作区根）时黑名单为空、不拦截，保持向后兼容。

#### Scenario: 直接读凭据文件被拒
- **WHEN** agent 调用 `readFile("~/.pig-agent/workspace/models.json")`（或其绝对路径等价形式）
- **THEN** 工具不读取内容，返回 `{"error":"access denied: credential file"}`，明文 API key 不进入模型上下文

#### Scenario: 经 ../ 或符号链接间接访问同样被拒
- **WHEN** agent 传入指向凭据文件的相对路径（`.../workspace/../workspace/models.json`）或指向它的符号链接
- **THEN** 路径规范化后命中黑名单，访问被拒

#### Scenario: 写凭据文件被拒
- **WHEN** agent 调用 `writeFile` 目标为 `models.json` / `mcp.json`
- **THEN** 工具不写入，返回规范错误，凭据文件不被篡改

#### Scenario: 普通项目文件不受影响
- **WHEN** agent 读写工作目录下任意非凭据文件（如 `D:\work\pig-agent\pom.xml`）
- **THEN** 工具照常执行，黑名单不影响正常文件访问

### Requirement: fetchUrl SSRF 出口防护
`fetchUrl` 在发起请求前 MUST 校验目标：scheme MUST ∈ {`http`,`https`}；MUST 用 `InetAddress.getAllByName(host)` 解析目标 host 的**全部** IP，任一地址命中回环 / anyLocal / 链路本地（含 `169.254.169.254`）/ 私网（`10/8`、`172.16/12`、`192.168/16`）/ IPv6 ULA（`fc00::/7`）/ multicast 即 MUST 拒绝。判定 MUST 基于解析后的 IP 而非字面 host，使十进制/十六进制 IP、`[::1]`、DNS 指向内网等绕过失效。HTTP 客户端 MUST 保持 `Redirect.NEVER`。被拒 MUST 返回规范错误（不回显解析出的内网 IP），MUST NOT 抛异常。

#### Scenario: 云 metadata 端点被拒
- **WHEN** agent 调用 `fetchUrl("http://169.254.169.254/latest/meta-data/...")`
- **THEN** 解析地址命中链路本地，请求被拒、不发出，返回规范错误

#### Scenario: 回环与私网被拒
- **WHEN** agent 请求 `http://localhost/`、`http://127.0.0.1/`、`http://10.0.0.5/` 或 `http://192.168.1.1/`
- **THEN** 各自解析地址命中回环/私网，请求被拒

#### Scenario: 绕过写法失效
- **WHEN** agent 用十进制 IP（`http://2130706433`）、`http://[::1]`、或一个解析到内网 IP 的域名请求
- **THEN** 基于解析 IP 的判定命中，请求被拒

#### Scenario: 公网地址正常放行
- **WHEN** agent 请求一个解析到公网 IP 的正常 URL
- **THEN** 通过 SSRF 校验，请求正常发出并返回内容

### Requirement: fetchUrl 主机白名单
系统 SHALL 支持可选 `tools.web.allowed-hosts`（`List<String>`，默认空）。当白名单**非空**时，`fetchUrl` MUST 仅允许 host（规范化：小写、去末尾点）命中白名单的请求，其余一律拒绝（叠加在 SSRF IP 守卫之上）。当白名单为空时，仅施加 SSRF IP 守卫。

#### Scenario: 非空白名单只放行白名单内主机
- **WHEN** `allowed-hosts=[api.example.com]` 且 agent 请求 `https://evil.example.org/`
- **THEN** host 不在白名单，请求被拒

#### Scenario: 空白名单退化为仅 IP 守卫
- **WHEN** `allowed-hosts` 为空且 agent 请求一个公网 URL
- **THEN** 不做白名单限制，仅经 SSRF IP 守卫后放行

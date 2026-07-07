# tool-json-contract Specification

## Purpose
TBD - created by archiving change tool-json-contract. Update Purpose after archive.
## Requirements
### Requirement: 工具统一返回契约

内建 `@Tool` 工具 SHALL 对成功与失败都返回规范结果；失败 SHALL 以规范错误形态（`{"error": "<message>"}` 或等价错误对象）表达，MUST NOT 以抛出异常作为对模型的错误反馈。错误消息 MUST NOT 包含凭据/密钥值。

#### Scenario: 工具失败返回规范错误
- **WHEN** 某工具执行遇到可预期失败（如参数非法、前置缺失）
- **THEN** 返回规范错误结果（含可读原因，不含凭据），而非抛出异常

#### Scenario: 成功返回不受影响
- **WHEN** 某工具正常执行
- **THEN** 返回其正常结果，形态与引入本契约前一致

### Requirement: 分发层双层兜底

工具分发/调用路径 SHALL 包裹错误兜底：即使某工具违反契约抛出异常，分发层 MUST 捕获并转换为规范错误结果返回给模型，异常 MUST NOT 穿透中断当前回合。

#### Scenario: 违规异常被兜底
- **WHEN** 某工具 handler 抛出未预期异常
- **THEN** 分发层捕获并返回规范错误结果，回合不中断，模型可据此继续

#### Scenario: 兜底错误不泄露凭据
- **WHEN** 分发层把异常转为错误结果
- **THEN** 该错误消息不包含凭据/密钥值


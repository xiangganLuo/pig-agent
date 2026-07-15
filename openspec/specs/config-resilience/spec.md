# config-resilience Specification

## Purpose
配置加载对未知字段容错（忽略而非整份回退默认），使配置 schema 前后漂移时用户既有的可识别设置不被静默丢弃。

## Requirements
### Requirement: 配置加载对未知字段容错
加载 `application.yaml` 时，配置对象 MUST 忽略未知/无法识别的字段，而非因此让整份配置加载失败并回退全默认。已识别字段 MUST 正常绑定；仅未知字段被忽略。这样当配置 schema 前后漂移（如新版本新增字段、或旧版本读到新字段）时，用户既有的可识别设置（permissions、compression、current-session-id 等）MUST 被保留，不被静默丢弃。

#### Scenario: 未知字段被忽略、既有设置保留
- **WHEN** `application.yaml` 含一个当前配置类不认识的顶层字段，同时含有效的 `permissions`/`compression` 等设置
- **THEN** 加载成功，未知字段被忽略，`permissions`/`compression` 等既有设置被正确绑定、不回退默认

#### Scenario: 真正损坏仍回退默认
- **WHEN** `application.yaml` 本身语法损坏（非"未知字段"，而是无法解析的 YAML）
- **THEN** 沿用既有容错：加载失败、回退默认并记录，不崩溃

## 1. 配置最小扩展（channel-expansion）

- [x] 1.1 `ChannelConfigTest` 增：`webhook-url`/`sign-secret`/`verification-token` 缺省 null；setter 往返；YAML 往返读取；旧配置（仅 enabled/token）向后兼容
- [x] 1.2 `PigAgentConfig.ChannelConfig` 增 `@JsonProperty("webhook-url") String webhookUrl`、`sign-secret String signSecret`、`verification-token String verificationToken` + getter/setter（旧字段不动）
- [x] 1.3 `ChannelConfigTest` 全绿

## 2. ChannelType 枚举注册表（channel-expansion）

- [x] 2.1 `ChannelTypeTest`：`fromId` 已知/未知（Optional）；每常量 `id`/`displayName` 正确且 id 唯一；`create(cfg)` 对 telegram/discord/webhook/slack/stdin/dingtalk/feishu 返回对应 `Channel` 类型（dingtalk/feishu → `StrategyHttpChannel`）
- [x] 2.2 `ChannelType` 枚举：常量携带 `id`/`displayName`/`Function<ChannelConfig,Channel> builder`；`create(ChannelConfig)`；静态 `fromId(String):Optional<ChannelType>`；既有 5 渠道迁为常量（builder 直接 new），dingtalk/feishu 常量 builder = `new StrategyHttpChannel(new XxxStrategy(...), cfg.getPort(), cfg.getPath())`
- [x] 2.3 `ChannelTypeTest` 全绿

## 3. 策略抽象 + 通用 HTTP 策略渠道（channel-expansion）

- [x] 3.1 `StrategyHttpChannelTest`（用记录型 `FakeStrategy`）：`process` 直接响应透传（405/challenge）、有消息则调 `handler` 且 `sendMessage`→`strategy.send`、其它回 `ack`；`channelId`/`displayName` 委托 strategy；`effectivePort`/`effectivePath` 缺省回落 strategy 默认；start/stop/isRunning + boundPort（自由端口）
- [x] 3.2 `strategy/Inbound`（record：`response`|`message`，静态 `respond`/`route`/`ignore` + `isDirect`/`hasMessage`）；`strategy/ChannelStrategy`（`channelId`/`displayName`/`defaultPort`/`defaultPath`/`inbound`/`ack`/`send`）；`strategy/StrategyHttpChannel implements Channel`（复用 `HttpChannelServer`，`process` 编排，`sendMessage`→`strategy.send`）
- [x] 3.3 `StrategyHttpChannelTest` 全绿

## 4. 出站发送 seam（channel-expansion）

- [x] 4.1 `JdkWebhookSenderTest`：loopback `HttpChannelServer` 起临时端口，`JdkWebhookSender.post(url,body)` → 服务端收到 body、返回 200 → `post` 得 `true`；不可达/坏 URL → `false` 不抛
- [x] 4.2 `http/WebhookSender`（`@FunctionalInterface boolean post(String url,String json)` + 静态 `jdk()`）；`http/JdkWebhookSender`（JDK `HttpClient`，`application/json`，连接超时，永不抛→warn+false）
- [x] 4.3 `JdkWebhookSenderTest` 全绿

## 5. 钉钉自定义机器人渠道（channel-expansion）

- [x] 5.1 `DingTalkCodecTest`：`sign` 确定性 + `verify` 真/篡改/null；`signedUrl` 含 `timestamp=`/`sign=`（url 编码）；`buildTextPayload` 出 `{"msgtype":"text","text":{"content":...}}`（转义）；`extractMessage` 取 `text.content`、空体→空
- [x] 5.2 `dingtalk/DingTalkCodec`（纯）：`sign`/`verify`/`signedUrl`/`buildTextPayload`/`extractMessage`
- [x] 5.3 `DingTalkStrategyTest`（注入假 `WebhookSender`）：非 POST→405；`sign-secret` 配置下头验签无效→401、有效→`route(消息)`；未配 secret→免验 route；空消息→ignore（process 回 ack）；`ack`=200；`send` 有 webhook-url+secret → 假 sender 收到签名 URL + text 载荷；无 webhook-url → 不 post
- [x] 5.4 `dingtalk/DingTalkStrategy implements ChannelStrategy`（id `dingtalk`、默认端口 8688、路径 `/dingtalk`）
- [x] 5.5 钉钉测试全绿

## 6. 飞书/Lark 机器人渠道 + 事件订阅（channel-expansion）

- [x] 6.1 `FeishuCodecTest`：`challenge` 取 url_verification 的 challenge、非验证→空；`extractMessage` 取 `im.message.receive_v1` 的 `event.message.content` 内嵌 `text`、非消息事件→空；`signature` 十六进制 + `verifySignature` 真/篡改/null；出站 `sign`（base64）确定性、`buildTextPayload`/`buildSignedTextPayload` JSON
- [x] 6.2 `feishu/FeishuCodec`（纯）：`challenge`/`extractMessage`/`encryptField`/`signature`/`verifySignature`/`sign`/`buildTextPayload`/`buildSignedTextPayload`
- [x] 6.3 `FeishuStrategyTest`（注入假 `WebhookSender`）：非 POST→405；url_verification→`respond(200 含 challenge)`；message 事件→`route(text)`；`verification-token` 配置下签名无效→401、有效→正常；非消息事件→ignore（回 ack）；`send` 有 webhook-url+secret → 假 sender 收到签名体载荷；无 webhook-url→不 post
- [x] 6.4 `feishu/FeishuStrategy implements ChannelStrategy`（id `feishu`、默认端口 8689、路径 `/feishu`）
- [x] 6.5 飞书测试全绿

## 7. 工厂枚举驱动重构 + wiring（channel-expansion）

- [x] 7.1 `ChannelFactoryTest` 增：`dingtalk`/`feishu` 启用→构造 `StrategyHttpChannel`；既有 telegram/discord/webhook/slack/stdin 断言保留；禁用/未知/null 保留
- [x] 7.2 `ChannelFactory.create` 改为 `ChannelType.fromId(id).map(t→t.create(cfg))`（enabled/null 门控保留、未知 warn 保留），**移除 `switch`**；`createEnabled` 不变
- [x] 7.3 确认 `PigAgentCli.startChannels` 无需改动（仍委托 `ChannelFactory`）
- [x] 7.4 `ChannelFactoryTest` 全绿

## 8. 集成与回归

- [x] 8.1 全量 `mvn test` **单线程** BUILD SUCCESS，0 失败 0 错误（channel 97、config 18、cli 75 等；本变更净增 50 测试：ChannelType 4 + DingTalkCodec 7 + DingTalkStrategy 9 + FeishuCodec 11 + FeishuStrategy 10 + StrategyHttpChannel 6 + JdkWebhookSender 3 + ChannelFactory +1 + ChannelConfig +1；既有渠道零回归）
- [x] 8.2 `mvn -q -pl pig-agent-cli -am compile` BUILD SUCCESS（config+channel+cli wiring 无回归）

## 9. 安全复查

- [x] 9.1 复查无凭据泄漏：`sign-secret`/`verification-token`/`token` 不入日志/HTTP 响应/错误文本；渠道日志只打 id/displayName/port/path/字符数；`WebhookSender` 失败日志不打 body/凭据

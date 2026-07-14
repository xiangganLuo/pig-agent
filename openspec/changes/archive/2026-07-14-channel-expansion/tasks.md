## 1. 配置扩展（channel-expansion）

- [x] 1.1 `ChannelConfigTest`（config 模块，4 测试）：`enabled` 缺省 false、`token`/`path`/`signing-secret` 缺省 null、`port` 缺省 0；setter 往返；YAML 往返；旧配置向后兼容
- [x] 1.2 `PigAgentConfig.ChannelConfig` 增 `@JsonProperty("port") int port=0`、`path String`、`signing-secret String` + getter/setter（旧 enabled/token 不动）
- [x] 1.3 `ChannelConfigTest` 4/4 绿

## 2. Channel 契约文档化（channel-expansion）

- [x] 2.1 `Channel` SPI 补生命周期 Javadoc：`start(handler)` 装配入站 → 入站经 `ChannelAgentBridge` → agent → `sendMessage` 出站 → `stop()` 释放；`isRunning` 反映状态；签名不变（Telegram/Discord/Chat 零回归）
- [x] 2.2 `mvn -q -pl pig-agent-channel -am compile` 绿（仅 Javadoc，无签名变化）

## 3. HTTP 粘合 + 记录（channel-expansion）

- [x] 3.1 `HttpChannelServerTest`（2 测试）：回环 `port=0` 起 server、JDK `HttpClient` POST localhost → `RequestHandler` 回 `OutboundHttp` 原样写回（状态/体/Content-Type）；无体响应无内容
- [x] 3.2 `io.pigagent.channel.http`：`InboundHttp(method,headers,body)`、`OutboundHttp(status,contentType,body)` 记录 + `RequestHandler` 接口 + `HttpChannelServer`（`HttpExchange`↔记录，headers 键小写，守护线程池，`start(port,path,handler)`/`boundPort()`/`stop()`）
- [x] 3.3 `HttpChannelServerTest` 2/2 绿

## 4. Webhook 消息编解码（channel-expansion）

- [x] 4.1 `WebhookCodecTest`（9 测试）：JSON `message` 键、JSON `text` 键、message 优先、纯文本体、空/空白体（→空）、`formatReply` 出 `{"reply":...}`（含转义）、非法 JSON 回退纯文本、无已知键回退整段
- [x] 4.2 `webhook/WebhookCodec`（纯，jackson）：`extractMessage(body,contentType)`、`formatReply(reply)`
- [x] 4.3 `WebhookCodecTest` 9/9 绿

## 5. 通用 Webhook/HTTP 渠道（channel-expansion）

- [x] 5.1 `WebhookChannelTest`（10 测试）：`process(GET)`→405；`process(POST,空体)`→400；`token` 配置下缺失/错误头→401、正确 `X-Auth-Token`/`Bearer`→200；未配 token→免鉴权 200；`process(POST,json)` 经 `bind` 的 stub handler→200 且体含回复；`runAndCapture` 捕获 `sendMessage`；scope 外 `sendMessage` 丢弃；start/stop/isRunning + boundPort（自由端口）
- [x] 5.2 `webhook/WebhookChannel implements Channel`（`channelId="webhook"`）：`start`=`bind`+`HttpChannelServer`（port≤0→8686、path 空→`/webhook`）；`process(InboundHttp)` 纯门控+`runAndCapture`+`WebhookCodec`；`sendMessage` `ThreadLocal` 追加否则 debug；`stop`/`isRunning`
- [x] 5.3 `WebhookChannelTest` 10/10 绿

## 6. CLI/stdin 管道渠道（channel-expansion）

- [x] 6.1 `StdinPipeChannelTest`（3 测试）：`bind`+`pump()` 喂 `"a\n\nb\n"` → handler 得 `a`、`b`（空行跳过）；`sendMessage` 写出输出流；`start`/`stop`/`isRunning` 状态
- [x] 6.2 `cli/StdinPipeChannel implements Channel`（`channelId="stdin"`）：构造 `(InputStream,OutputStream)`（缺省 `System.in`/`System.out`）；`bind`/`pump()`（包私有，同步）；`start`=`bind`+守护线程；`sendMessage`=`out.println`；`stop`=`running=false`+中断
- [x] 6.3 `StdinPipeChannelTest` 3/3 绿

## 7. Slack 事件渠道薄骨架（channel-expansion）

- [x] 7.1 `SlackEventCodecTest`（7 测试）：`url_verification`→取 challenge；`event_callback`+message→取 `event.text`；带 `bot_id`/`subtype`→忽略（空）；非 message 事件→空；`sign`/`verifySignature` 正确 HMAC→true、篡改/null→false
- [x] 7.2 `slack/SlackEventCodec`（纯）：`challenge(body)`、`extractText(body)`、`sign`/`verifySignature`（`v0:` HMAC-SHA256 hex + 常量时间比对）
- [x] 7.3 `SlackChannelTest`（6 测试）：`process(url_verification)`→200 回 challenge；`event_callback`→抽文本触发 handler→200 空体 ack；`process(GET)`→405；`signing-secret` 配置下签名无效→401、有效→处理；出站 `sendMessage` 日志桩不抛
- [x] 7.4 `slack/SlackChannel implements Channel`（`channelId="slack"`）：复用 `HttpChannelServer`（port≤0→8687、path 空→`/slack/events`）；`process` 验签+challenge+事件抽取+`handler.accept`；`sendMessage` 日志桩（出站待真实投递，Javadoc 标注）
- [x] 7.5 Slack 测试 13/13 绿

## 8. 工厂 + 门控 + bridge 路由 + wiring（channel-expansion）

- [x] 8.1 `ChannelFactoryTest`（6 测试）：`enabled=false`→空；`null` cfg/id→空；启用未知 id→空（warn）；`telegram`/`discord`/`webhook`/`slack`/`stdin` 启用→构造对应类型；`createEnabled(Map)` 只出启用+已知；null map→空
- [x] 8.2 `ChannelFactory.create(id,cfg):Optional<Channel>` + `createEnabled(Map):List<Channel>`（channel 模块，依赖 config）
- [x] 8.3 `ChannelAgentBridgeTest`（3 测试，mock `PigAgent`/`AgentKernel`）：入站 → `agent.stream` 回 `AGENT_RESULT` 文本 → `channel.sendMessage(reply)` 收到；`kernel.noteChannelChat(id)` 被调；空白入站被忽略；agent 出错 → 出站 `Error: …`（只含 message）
- [x] 8.4 `pig-agent-channel/pom.xml` 增 `pig-agent-config` 依赖
- [x] 8.5 `PigAgentCli.startChannels` 改委托 `ChannelFactory`（enabled 门控 + 未知 warn 语义等价），包 `ChannelAgentBridge` 后 `start()`
- [x] 8.6 组内测试绿（factory 6 + bridge 3）

## 9. 集成与回归

- [x] 9.1 全量 `mvn test` **单线程** BUILD SUCCESS，0 失败 0 错误（channel 46、cli 75、config 含 ChannelConfig 4 等）；本变更净增 50 测试（channel 46 + config 4），无回归
- [x] 9.2 `mvn -q -pl pig-agent-cli -am compile` BUILD SUCCESS（config+channel+cli wiring 无回归）
- [x] 9.3 复查无凭据泄漏：渠道日志只打 id/displayName/port/path；token/signing-secret 不入日志/响应/错误文本；bridge 出站错误只含 agent message；Slack 出站桩只打字符数

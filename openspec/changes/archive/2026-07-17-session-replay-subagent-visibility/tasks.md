## 1. F2 — session-entry replay

- [x] 1.1 Add pure `render/SessionReplay` (`render(List<Msg>, K)` + `resumeLines(name, msgs, K)`): USER → dim `› text`, ASSISTANT → `MarkdownAnsiRenderer`, tool call/result → `ToolCallFormatter`; last-K bound; credential-redacted; surrogate-safe truncation; empty → nothing.
- [x] 1.2 `SessionReplayTest`: last-K bound, user/assistant/tool rendering, empty→nothing, redaction, surrogate-safe truncation, header + count.
- [x] 1.3 `AgentRepl.run()` replays the current session at startup (`maybeReplayCurrentSession`, best-effort) via `getMemory(sessionId).getMessages()`.
- [x] 1.4 `SessionCommand.switchSession` replays the target slot after switching (best-effort).
- [x] 1.5 `AgentReplReplayTest`: startup replay of the seeded slot; empty session → nothing; null agent → no throw; `/session switch` replays the target slot.

## 2. F3 — background-subagent visibility

- [x] 2.1 In `AgentRepl.onToolEnd`, detect a background `agent_spawn` (result carries `task_id` / `timeout_promoted`) and render a one-line running-notice (dim, credential-safe, short task id); keep synchronous-child rendering unchanged; don't suppress the eventual completion text.
- [x] 2.2 `AgentReplBackgroundSpawnTest`: background result → running-notice + short task id (no raw JSON); `backgroundTaskId` parses first-8 chars; a synchronous spawn renders its normal body.

## 3. Regression + validation

- [x] 3.1 `mvn -pl pig-agent-cli -am test` GREEN; existing REPL/render tests unbroken.
- [x] 3.2 `openspec validate session-replay-subagent-visibility --strict`.

## Why

Two CC-style REPL UX gaps surfaced in real usage:

- **F2 — resumed session looks empty.** The native `AgentStateStore` correctly reloads the `(pig, sessionId)` conversation on restart and on `/session switch` (the data + model context are intact), but the REPL **never replays the restored transcript to the screen**. After "Agent ready." / "Switched to…" the screen is blank, so it *looks* like history was lost. This is a display gap, not data loss.
- **F3 — a background subagent looks like a hang.** When the model spawns a **background** subagent (`agent_spawn` with `timeout_seconds=0` → returns a `task_id`, status often `timeout_promoted`), the REPL shows nothing while it runs (synchronous children already render nested + dim). The user sees a silent hang. The eventual completion arrives as a `<system-reminder>` the parent reasons over, surfacing as normal answer text — but nothing signals the dispatch.

## What Changes

Scoped to the CLI render path (design-pattern-oriented: a pure, terminal-free replay helper + a pure background-spawn detector):

- **Session-entry replay (F2).** A new pure `render/SessionReplay` renders the bounded tail (last **K**, default 8) of a conversation as ordered ANSI lines: a USER message as a subtle dim `› text`, an ASSISTANT message via `MarkdownAnsiRenderer`, and tool call/result via `ToolCallFormatter` (`⏺ name` / dim `└ summary`). It is credential-redacted and surrogate-safe-truncated; an empty conversation renders nothing. `AgentRepl.run()` replays the current session at startup (via `getMemory(sessionId).getMessages()`), and `SessionCommand.switchSession` replays the target slot after switching — both best-effort (a replay never breaks startup / the switch).
- **Background-subagent visibility (F3).** In `AgentRepl.onToolEnd`, a background `agent_spawn` result (carrying a `task_id` / `timeout_promoted`) renders a clear one-line running-notice — `已派发后台子agent（task <id前8位>）· 运行中，完成后会回报` (dim, credential-safe) — instead of a raw JSON dump. The eventual completion still surfaces as parent answer text (not suppressed). Synchronous-child rendering is unchanged.

No **BREAKING**: default behavior is unchanged except the deliberately added replay + notice; no new config required.

## Capabilities

### Modified Capabilities
- `cc-repl`: gains a bounded, credential-safe session-entry replay (startup + `/session switch`) and a background-subagent dispatch notice.

## Impact

- **Code (`pig-agent-cli`)**: new `render/SessionReplay` (pure, unit-tested); `repl/AgentRepl` (`maybeReplayCurrentSession` at startup + background-spawn detection/notice in `onToolEnd`); `repl/ReplCommands` (only `SessionCommand.switchSession` — replay after switch).
- **Tests**: `SessionReplayTest` (last-K bound, user/assistant/tool rendering, empty→nothing, redaction, surrogate-safe truncation), `AgentReplReplayTest` (startup + `/session switch` replay of a seeded slot), `AgentReplBackgroundSpawnTest` (background `agent_spawn` running-notice + task-id parsing + sync-spawn unchanged).

## Why

A real-usage review of the CC-style REPL surfaced a cluster of **UX + correctness** defects, headed by the top user complaint: a failed model call dumps a raw exception / HTTP body to the terminal (ugly, and a Gemini error can leak `?key=AIza…`). Related render bugs garble output: parent answer text buffered without a trailing newline prints *after* a tool block and merges into the next phase; a long-running tool is silent (head only rendered at result); failed tools look identical to successful ones; the native retry (up to ~10 attempts, backoff to 8s) leaves the user staring at `thinking…` for ~a minute with no signal it's retrying; a wide model label wraps and corrupts the inline picker; and the English chrome (`[interrupted]`, `Error:`) is untranslated. `ModelManager.test()` also returns raw errors and can hang forever.

## What Changes

Scoped to the CLI render/model-error path (design-pattern-oriented: a pure taxonomy function + a spinner state-machine phase + pure truncation helpers):

- **Friendly model errors (headline).** New pure `ModelErrorMessages.friendly(Throwable)` (`pig-agent-core`) maps model errors to a localized one-liner by HTTP status / exception type (429 / 5xx+overloaded / 401 / 403 / 400·422 / 404 / timeout / network), fallthrough carries a **credential-redacted** short reason. The REPL error branch renders it (redacted + length-capped) — also the credential-leak floor. `ModelManager.test()` reuses the taxonomy and wraps the probe in a bounded (~25s) timeout so onboarding / `/model add` can't hang.
- **Streaming order.** Flush the parent answer printer on `TextBlockEndEvent` and before any tool line, so buffered partial text prints before a tool block (no merge/garble).
- **Tool feedback.** Print `⏺ name` head immediately on `ToolCallStartEvent`/`ToolResultStartEvent` (keep an activity spinner during execution, append `└ result` at end); render a failed (`ERROR`) result with a distinct red `✗` body.
- **Retry visibility.** Once the reasoning spinner runs past ~6s (no answer text), its label flips to `模型繁忙，重试中…` (tool-execution phase never flips).
- **Credential hardening.** `ToolCallFormatter.redact` extends to bare tokens (`AIza…`, `xox…`/`xapp-…`, `gh[pousr]_…`, JWT `eyJ….….…`).
- **Robustness / polish.** Slash-command failures print a one-line error (+ stack to log) instead of a stack dump; inline picker truncates each row to terminal width (ANSI-aware, surrogate-safe); control chars stripped from user-supplied session name / model label; surrogate-safe truncation in tool/subagent summaries; a dim `[无输出]` marker when a turn renders nothing; localized chrome (`[已中断]` / `[已达最大推理轮次]` / `[所有工具调用被权限策略拒绝]`).

No **BREAKING**: default behavior is unchanged except the deliberately improved error/chrome text; no new config required.

## Capabilities

### Modified Capabilities
- `cc-repl`: 流式富渲染 gains friendly localized model-error rendering (credential-redacted, length-capped), correct flush ordering before tool blocks, immediate tool-call head + distinct failed-tool rendering, a retry-aware spinner label, extended bare-token redaction, and localized chrome / `[无输出]` marker.

## Impact

- **Code (`pig-agent-core`)**: new `model/ModelErrorMessages` (+ test).
- **Code (`pig-agent-model`)**: `ModelManager.test()` friendly + bounded-timeout `runProbe` seam (+ test).
- **Code (`pig-agent-cli`)**: `repl/AgentRepl` (error branch, flush ordering, tool head/end, chrome, `[无输出]`, slash-fail), `render/ToolCallFormatter` (head/body/errorBody split, bare-token redaction, surrogate-safe truncation), `repl/ThinkingSpinner` (retry-label phase), `repl/select/InlineSelector` (width truncation), `repl/StatusLine` + `repl/ModelSelection` (control-char stripping), `render/SubagentEventRenderer` (surrogate-safe truncation).
- **Tests**: `ModelErrorMessagesTest`, `ModelManagerTestProbeTest`, `InlineSelectorTest`, extended `ToolCallFormatterTest` / `ThinkingSpinnerTest` / `AgentReplTurnTest`; `AgentReplErrorPrintTest` / `AgentReplInterruptTest` updated to the new friendly/localized text (once-only + interrupt invariants preserved).

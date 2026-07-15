"use strict";

// Minimal Pig Agent web console. Talks ONLY to the kernel-adapter endpoints:
//   GET  /api/agents            list agents
//   POST /api/agents/{id}/use   switch active agent
//   POST /api/chat              SSE stream of a chat turn (fetch + ReadableStream)
//   GET  /api/events            SSE kernel lifecycle events (EventSource)

const $ = (id) => document.getElementById(id);
const messagesEl = $("messages");
const agentsEl = $("agents");
const eventsEl = $("events");
const inputEl = $("input");
const sendBtn = $("send");
const connEl = $("conn");

let sending = false;

function addMessage(kind, who, text) {
    const wrap = document.createElement("div");
    wrap.className = "msg " + kind;
    const whoEl = document.createElement("div");
    whoEl.className = "who";
    whoEl.textContent = who;
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    bubble.textContent = text;
    wrap.appendChild(whoEl);
    wrap.appendChild(bubble);
    messagesEl.appendChild(wrap);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return bubble;
}

async function loadAgents() {
    try {
        const res = await fetch("/api/agents");
        const agents = await res.json();
        agentsEl.innerHTML = "";
        agents.forEach((a) => {
            const li = document.createElement("li");
            if (a.active) li.classList.add("active");
            const name = document.createElement("span");
            name.textContent = a.name + (a.active ? " ●" : "");
            const model = document.createElement("span");
            model.className = "model";
            model.textContent = a.model;
            li.appendChild(name);
            li.appendChild(model);
            li.onclick = () => useAgent(a.id);
            agentsEl.appendChild(li);
        });
    } catch (e) {
        console.error("loadAgents failed", e);
    }
}

async function useAgent(id) {
    try {
        await fetch("/api/agents/" + encodeURIComponent(id) + "/use", { method: "POST" });
        await loadAgents();
    } catch (e) {
        console.error("useAgent failed", e);
    }
}

// Read the SSE `data:` frames of one /api/chat turn from a fetch stream.
async function sendMessage(text) {
    if (sending) return;
    sending = true;
    sendBtn.disabled = true;
    addMessage("user", "you", text);

    let reasoningBubble = null;
    let answerBubble = null;

    try {
        const res = await fetch("/api/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ message: text }),
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({ error: res.statusText }));
            addMessage("error", "error", err.error || ("HTTP " + res.status));
            return;
        }
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buf = "";
        while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buf += decoder.decode(value, { stream: true });
            let idx;
            while ((idx = buf.indexOf("\n\n")) >= 0) {
                const rawFrame = buf.slice(0, idx);
                buf = buf.slice(idx + 2);
                const line = rawFrame.split("\n").find((l) => l.startsWith("data:"));
                if (!line) continue;
                const frame = JSON.parse(line.slice(5).trim());
                handleFrame(frame);
            }
        }
    } catch (e) {
        addMessage("error", "error", String(e));
    } finally {
        sending = false;
        sendBtn.disabled = false;
        inputEl.focus();
    }

    function handleFrame(frame) {
        if (frame.type === "reasoning") {
            if (!reasoningBubble) reasoningBubble = addMessage("reasoning", "thinking", "");
            reasoningBubble.textContent += frame.text;
        } else if (frame.type === "tool") {
            addMessage("tool", "tool", frame.text);
        } else if (frame.type === "answer") {
            if (!answerBubble) answerBubble = addMessage("agent", "agent", "");
            answerBubble.textContent += frame.text;
        } else if (frame.type === "error") {
            addMessage("error", "error", frame.text);
        }
        // "done" ends the turn (loop exits when the stream closes).
        messagesEl.scrollTop = messagesEl.scrollHeight;
    }
}

function connectEvents() {
    const src = new EventSource("/api/events");
    src.onopen = () => { connEl.textContent = "events: live"; connEl.className = "conn on"; };
    src.onerror = () => { connEl.textContent = "events: reconnecting…"; connEl.className = "conn off"; };
    src.onmessage = (ev) => {
        try {
            const e = JSON.parse(ev.data);
            const li = document.createElement("li");
            const t = document.createElement("span");
            t.className = "etype";
            t.textContent = e.type + " ";
            li.appendChild(t);
            li.appendChild(document.createTextNode(e.agentId + (e.message ? " · " + e.message : "")));
            eventsEl.prepend(li);
            while (eventsEl.children.length > 40) eventsEl.removeChild(eventsEl.lastChild);
            if (e.type === "AGENT_SWITCHED" || e.type === "AGENT_CREATED" || e.type === "AGENT_DELETED") {
                loadAgents();
            }
        } catch (_) { /* ignore malformed frame */ }
    };
}

$("composer").addEventListener("submit", (e) => {
    e.preventDefault();
    const text = inputEl.value.trim();
    if (text) { inputEl.value = ""; sendMessage(text); }
});
inputEl.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        $("composer").requestSubmit();
    }
});
$("refresh").addEventListener("click", loadAgents);

loadAgents();
connectEvents();

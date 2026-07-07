'use strict';
// MVP console: full CLI parity over REST + SSE. No framework, no build step.

const $ = (s) => document.querySelector(s);
const $$ = (s) => document.querySelectorAll(s);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

async function api(path, opts) {
    const res = await fetch(path, opts);
    const text = await res.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch { body = text; }
    return { ok: res.ok, status: res.status, body };
}
const jsonPost = (path, obj, method) => api(path,
    { method: method || 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(obj || {}) });

// ---- tabs -------------------------------------------------------------
const LOADERS = {
    agents: loadAgents, models: loadModels, sessions: loadSessions,
    tasks: loadTasks, mcp: loadMcp, settings: loadSettings,
};
$$('#tabs button').forEach((b) => b.onclick = () => activateTab(b.dataset.tab));
function activateTab(tab) {
    $$('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
    $$('.tab').forEach((s) => s.classList.toggle('active', s.id === 'tab-' + tab));
    if (LOADERS[tab]) LOADERS[tab]();
}
$$('[data-refresh]').forEach((b) => b.onclick = () => LOADERS[b.dataset.refresh]());

// ---- chat (streaming) -------------------------------------------------
$('#chat-form').addEventListener('submit', sendChat);
$('#chat-msg').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendChat(e); }
});
async function sendChat(ev) {
    ev.preventDefault();
    const ta = $('#chat-msg');
    const message = ta.value.trim();
    if (!message) return;
    ta.value = '';
    addChat('user', message);
    const answer = addChat('agent', '');
    try {
        const res = await fetch('/api/chat', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message }),
        });
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buf = '';
        for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            buf += decoder.decode(value, { stream: true });
            let idx;
            while ((idx = buf.indexOf('\n\n')) >= 0) {
                const frame = buf.slice(0, idx); buf = buf.slice(idx + 2);
                if (!frame.startsWith('data:')) continue;
                let e; try { e = JSON.parse(frame.slice(5).trim()); } catch { continue; }
                if (e.type === 'answer') answer.querySelector('.body').textContent += e.text;
                else if (e.type === 'reasoning') meta(answer, '💭 ' + e.text);
                else if (e.type === 'tool') meta(answer, '🔧 ' + e.text);
                else if (e.type === 'error') meta(answer, '⚠ ' + e.text);
                $('#chat-log').scrollTop = $('#chat-log').scrollHeight;
            }
        }
    } catch (err) {
        meta(answer, '⚠ ' + err.message);
    }
}
function addChat(role, text) {
    const li = document.createElement('div');
    li.className = 'msg ' + role;
    li.innerHTML = `<span class="who">${role === 'user' ? '你' : 'agent'}</span><div class="body">${esc(text)}</div>`;
    $('#chat-log').appendChild(li);
    $('#chat-log').scrollTop = $('#chat-log').scrollHeight;
    return li;
}
function meta(el, text) {
    const m = document.createElement('div');
    m.className = 'msg-meta';
    m.textContent = text;
    el.appendChild(m);
}

// ---- agents -----------------------------------------------------------
async function loadAgents() {
    const { body } = await api('/api/agents');
    render('#agents', body, (a) => rowHtml(
        `${esc(a.name)} <span class="dim">[${esc(a.id)}] model=${esc(a.model)}${a.autonomous ? ' ⏰' + esc(a.schedule) : ''}</span>`,
        a.active ? '<span class="badge">当前</span>' : `<button data-act="use-agent" data-id="${esc(a.id)}">切换</button>`
        + (a.autonomous ? ` <button data-act="run-agent" data-id="${esc(a.id)}">运行</button>` : '')
        + ` <button data-act="del-agent" data-id="${esc(a.id)}">删除</button>`));
}
$('#create-agent').addEventListener('submit', async (e) => {
    e.preventDefault(); const f = e.target;
    const r = await jsonPost('/api/agents', { id: f.id.value.trim(), name: f.name.value.trim(), modelId: f.modelId.value.trim() });
    if (r.ok) { f.reset(); loadAgents(); } else alert('失败: ' + (r.body?.error || r.status));
});

// ---- models -----------------------------------------------------------
async function loadModels() {
    const [{ body: models }, { body: protos }] = await Promise.all([api('/api/models'), api('/api/protocols')]);
    const sel = $('#model-protocol');
    sel.innerHTML = (protos || []).map((p) => `<option value="${esc(p.protocolId)}">${esc(p.displayName)}</option>`).join('');
    render('#models', models, (m) => rowHtml(
        `${esc(m.label)} <span class="dim">[${esc(m.id)}]${m.isDefault ? ' [default]' : ''}${m.baseUrl ? ' ' + esc(m.baseUrl) : ''}</span>`,
        (m.isCurrent ? '<span class="badge">在用</span>' : `<button data-act="use-model" data-id="${esc(m.id)}">切换</button>`)
        + ` <button data-act="test-model" data-id="${esc(m.id)}">测试</button>`
        + ` <button data-act="del-model" data-id="${esc(m.id)}">删除</button>`));
}
$('#create-model').addEventListener('submit', async (e) => {
    e.preventDefault(); const f = e.target;
    const r = await jsonPost('/api/models', {
        protocolId: f.protocolId.value, apiKey: f.apiKey.value.trim(),
        baseUrl: f.baseUrl.value.trim(), modelName: f.modelName.value.trim(),
    });
    if (r.ok) { f.reset(); loadModels(); } else alert('失败: ' + (r.body?.error || r.status));
});

// ---- sessions ---------------------------------------------------------
async function loadSessions() {
    const { body } = await api('/api/sessions');
    render('#sessions', body, (s) => rowHtml(
        `${esc(s.name)} <span class="dim">[${esc(s.id)}]</span>`,
        (s.current ? '<span class="badge">当前</span>' : `<button data-act="use-session" data-id="${esc(s.id)}">切换</button>`)
        + ` <button data-act="del-session" data-id="${esc(s.id)}">删除</button>`));
}
$('#create-session').addEventListener('submit', async (e) => {
    e.preventDefault();
    const fork = e.submitter && e.submitter.dataset.fork === 'true';
    const r = await jsonPost('/api/sessions', { name: e.target.name.value.trim(), fork });
    if (r.ok) { e.target.reset(); loadSessions(); } else alert('失败: ' + (r.body?.error || r.status));
});

// ---- tasks ------------------------------------------------------------
const NEXT_STATUS = { TODO: 'IN_PROGRESS', IN_PROGRESS: 'DONE', DONE: 'TODO' };
async function loadTasks() {
    const { body } = await api('/api/tasks');
    render('#tasks', body, (t) => rowHtml(
        `<b>[${esc(t.status)}]</b> ${esc(t.title)} <span class="dim">${esc(t.description || '')}</span>`,
        `<button data-act="cycle-task" data-id="${esc(t.id)}" data-status="${esc(NEXT_STATUS[t.status] || 'TODO')}">→状态</button>`
        + ` <button data-act="del-task" data-id="${esc(t.id)}">删除</button>`));
}
$('#create-task').addEventListener('submit', async (e) => {
    e.preventDefault(); const f = e.target;
    const r = await jsonPost('/api/tasks', { title: f.title.value.trim(), description: f.description.value.trim() });
    if (r.ok) { f.reset(); loadTasks(); } else alert('失败: ' + (r.body?.error || r.status));
});

// ---- mcp --------------------------------------------------------------
async function loadMcp() {
    const { body } = await api('/api/mcp');
    render('#mcp', body, (s) => rowHtml(
        `${esc(s.label)} <span class="dim">${s.connected ? 'connected ' + s.toolCount : 'disconnected'}${s.enabled ? '' : ' (disabled)'}</span>`,
        `<button data-act="mcp-test" data-id="${esc(s.name)}">测试</button>`
        + ` <button data-act="mcp-${s.enabled ? 'disable' : 'enable'}" data-id="${esc(s.name)}">${s.enabled ? '停用' : '启用'}</button>`
        + ` <button data-act="mcp-del" data-id="${esc(s.name)}">删除</button>`));
}
$('#create-mcp').addEventListener('submit', async (e) => {
    e.preventDefault(); const f = e.target;
    const r = await jsonPost('/api/mcp', { name: f.name.value.trim(), url: f.url.value.trim() });
    if (r.ok) { f.reset(); loadMcp(); } else alert('失败: ' + (r.body?.error || r.status));
});

// ---- settings (status / permission / memory / compress) ---------------
async function loadSettings() {
    const { body: st } = await api('/api/status');
    render('#status', Object.entries(st || {}).map(([k, v]) => ({ k, v })),
        (e) => `<div class="row"><span>${esc(e.k)}</span><span class="dim">${esc(v(e))}</span></div>`);
    const { body: perm } = await api('/api/permission');
    if (perm) {
        $('#perm-mode').value = perm.mode;
        $('#perm-lists').textContent = `tools=${(perm.tools || []).length} commands=${(perm.commands || []).length}`;
    }
    const { body: mem } = await api('/api/memory');
    if (mem) {
        $('#mem-enabled').checked = !!mem.enabled;
        $('#mem-global').textContent = mem.global || '(空)';
        $('#mem-session').textContent = mem.session || '(空)';
    }
    const { body: cmp } = await api('/api/compress');
    if (cmp) {
        $('#cmp-enabled').checked = !!cmp.enabled;
        $('#cmp-info').textContent = `${cmp.estimatedTokens}/${cmp.budgetTokens} tok, ${cmp.messageCount} msgs`;
    }
}
const v = (e) => e.v;
$('#perm-mode').addEventListener('change', (e) => jsonPost('/api/permission/mode', { mode: e.target.value }));
$('#mem-enabled').addEventListener('change', (e) => jsonPost('/api/memory', { enabled: e.target.checked }));
$('#cmp-enabled').addEventListener('change', (e) => jsonPost('/api/compress', { enabled: e.target.checked }));
$('#cmp-now').addEventListener('click', async () => { await jsonPost('/api/compress/now', {}); loadSettings(); });

// ---- shared row rendering + action delegation -------------------------
function rowHtml(main, actions) {
    return `<li class="row"><div class="row-main">${main}</div><div class="row-actions">${actions}</div></li>`;
}
function render(sel, items, fn) {
    const el = $(sel);
    if (!items || !items.length) { el.innerHTML = '<li class="dim">（空）</li>'; return; }
    el.innerHTML = items.map(fn).join('');
}
document.addEventListener('click', async (e) => {
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const id = b.dataset.id;
    const A = {
        'use-agent': () => jsonPost(`/api/agents/${enc(id)}/use`, {}).then(loadAgents),
        'run-agent': () => jsonPost(`/api/agents/${enc(id)}/run`, {}),
        'del-agent': () => confirm('删除 ' + id + '?') && api(`/api/agents/${enc(id)}`, { method: 'DELETE' }).then(loadAgents),
        'use-model': () => jsonPost(`/api/models/${enc(id)}/use`, {}).then(loadModels),
        'test-model': async () => { const r = await jsonPost(`/api/models/${enc(id)}/test`, {}); alert(r.body?.ok ? 'OK' : '失败: ' + r.body?.error); },
        'del-model': () => confirm('删除 ' + id + '?') && api(`/api/models/${enc(id)}`, { method: 'DELETE' }).then(loadModels),
        'use-session': () => jsonPost(`/api/sessions/${enc(id)}/use`, {}).then(loadSessions),
        'del-session': () => confirm('删除 ' + id + '?') && api(`/api/sessions/${enc(id)}`, { method: 'DELETE' }).then(loadSessions),
        'cycle-task': () => jsonPost(`/api/tasks/${enc(id)}`, { status: b.dataset.status }, 'PUT').then(loadTasks),
        'del-task': () => confirm('删除任务?') && api(`/api/tasks/${enc(id)}`, { method: 'DELETE' }).then(loadTasks),
        'mcp-test': async () => { const r = await jsonPost(`/api/mcp/${enc(id)}/test`, {}); alert(r.body?.ok ? 'OK tools=' + r.body.toolCount : '失败: ' + r.body?.error); },
        'mcp-enable': () => jsonPost(`/api/mcp/${enc(id)}/enable`, {}).then(loadMcp),
        'mcp-disable': () => jsonPost(`/api/mcp/${enc(id)}/disable`, {}).then(loadMcp),
        'mcp-del': () => confirm('删除 ' + id + '?') && api(`/api/mcp/${enc(id)}`, { method: 'DELETE' }).then(loadMcp),
    };
    if (A[b.dataset.act]) A[b.dataset.act]();
});
const enc = encodeURIComponent;

// ---- lifecycle SSE (connection state + structural refresh) ------------
function connectEvents() {
    const es = new EventSource('/api/events');
    es.onopen = () => setConn(true);
    es.onerror = () => setConn(false);
    es.onmessage = (ev) => {
        let e; try { e = JSON.parse(ev.data); } catch { return; }
        if (['AGENT_CREATED', 'AGENT_DELETED', 'AGENT_SWITCHED'].includes(e.type)
            && $('#tab-agents').classList.contains('active')) loadAgents();
    };
}
function setConn(ok) {
    const el = $('#conn');
    el.textContent = ok ? '已连接' : '离线';
    el.className = 'conn ' + (ok ? 'on' : 'off');
}

connectEvents();

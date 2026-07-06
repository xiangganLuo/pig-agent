'use strict';
// Minimal MVP console: consumes the AgentKernel REST API + the /api/events SSE stream.
// No framework, no build step (design D5).

const $ = (sel) => document.querySelector(sel);
const api = (path, opts) => fetch('/api/agents' + path, opts);

async function loadAgents() {
    try {
        const res = await api('');
        const agents = await res.json();
        renderAgents(agents);
    } catch (e) {
        console.error('load agents failed', e);
    }
}

function renderAgents(agents) {
    const ul = $('#agents');
    ul.innerHTML = '';
    agents.forEach((a) => {
        const li = document.createElement('li');
        li.className = 'agent' + (a.active ? ' active' : '');
        const sched = a.autonomous ? ` ⏰${a.schedule}` : '';
        li.innerHTML =
            `<div class="agent-main">
                 <span class="dot"></span>
                 <span class="name">${esc(a.name)}</span>
                 <span class="meta">[${esc(a.id)}] model=${esc(a.model)} tools=${esc(a.tools)}${esc(sched)}</span>
             </div>`;
        const actions = document.createElement('div');
        actions.className = 'agent-actions';
        actions.appendChild(btn(a.active ? '当前' : '切换', a.active, () => useAgent(a.id)));
        if (a.autonomous) {
            actions.appendChild(btn('运行', false, () => runAgent(a.id)));
        }
        actions.appendChild(btn('删除', false, () => deleteAgent(a.id)));
        li.appendChild(actions);
        ul.appendChild(li);
    });
}

function btn(label, disabled, onClick) {
    const b = document.createElement('button');
    b.textContent = label;
    b.disabled = disabled;
    b.onclick = onClick;
    return b;
}

async function useAgent(id) {
    await api('/' + encodeURIComponent(id) + '/use', { method: 'POST' });
    loadAgents();
}

async function runAgent(id) {
    await api('/' + encodeURIComponent(id) + '/run', { method: 'POST' });
}

async function deleteAgent(id) {
    if (!confirm('删除 agent ' + id + '?')) return;
    await api('/' + encodeURIComponent(id), { method: 'DELETE' });
    loadAgents();
}

$('#create-form').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const f = ev.target;
    const body = { id: f.id.value.trim(), name: f.name.value.trim(), modelId: f.modelId.value.trim() };
    const res = await api('', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (res.ok) {
        f.reset();
        loadAgents();
    } else {
        const e = await res.json().catch(() => ({}));
        alert('新建失败: ' + (e.error || res.status));
    }
});

$('#refresh').onclick = loadAgents;
$('#clear-events').onclick = () => ($('#events').innerHTML = '');

function connectEvents() {
    const es = new EventSource('/api/events');
    es.onopen = () => setConn(true);
    es.onerror = () => setConn(false);
    es.onmessage = (ev) => {
        let e;
        try { e = JSON.parse(ev.data); } catch { return; }
        appendEvent(e);
        // Structural changes refresh the list.
        if (['AGENT_CREATED', 'AGENT_DELETED', 'AGENT_SWITCHED'].includes(e.type)) {
            loadAgents();
        }
    };
}

function appendEvent(e) {
    const ul = $('#events');
    const li = document.createElement('li');
    li.className = 'event ' + e.type;
    li.innerHTML = `<span class="tag">${esc(e.type)}</span>
                    <span class="eid">${esc(e.agentId)}</span>
                    <span class="emsg">${esc(e.message)}</span>`;
    ul.prepend(li);
    while (ul.children.length > 200) ul.removeChild(ul.lastChild);
}

function setConn(ok) {
    const el = $('#conn');
    el.textContent = ok ? '已连接' : '离线';
    el.className = 'conn ' + (ok ? 'on' : 'off');
}

function esc(s) {
    return String(s).replace(/[&<>"']/g, (c) =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

loadAgents();
connectEvents();

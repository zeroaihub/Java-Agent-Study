// ===== AI 聊天机器人前端逻辑 =====
const BASE = '/bot';

// 获取/生成 sessionId
function getSessionId() {
    const el = document.getElementById('sessionId');
    if (!el.value.trim()) {
        el.value = 'sess-' + Date.now();
    }
    return el.value.trim();
}

function updateSessionTag() {
    const sid = document.getElementById('sessionId').value.trim();
    document.getElementById('sessionTag').textContent = sid ? ('会话: ' + sid) : '未开始会话';
}

// 读取可选参数
function getSystemPrompt() {
    const v = document.getElementById('systemPrompt').value.trim();
    return v || null;
}
function getTemperature() {
    const v = document.getElementById('temperature').value.trim();
    return v === '' ? null : parseFloat(v);
}

// ===== 消息渲染 =====
function clearEmptyTip() {
    const tip = document.getElementById('emptyTip');
    if (tip) tip.remove();
}

function addMessage(role, text) {
    clearEmptyTip();
    const box = document.getElementById('messages');
    const wrap = document.createElement('div');
    wrap.className = 'msg ' + role;
    const avatar = role === 'user' ? '我' : (role === 'system' ? '⚙' : 'AI');
    wrap.innerHTML = `<div class="avatar">${avatar}</div><div class="bubble"></div>`;
    wrap.querySelector('.bubble').textContent = text;
    box.appendChild(wrap);
    box.scrollTop = box.scrollHeight;
    return wrap.querySelector('.bubble');
}

function scrollBottom() {
    const box = document.getElementById('messages');
    box.scrollTop = box.scrollHeight;
}

// ===== 输入框事件 =====
function onKey(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendNormal();
    }
}

function setBusy(busy) {
    document.getElementById('sendBtn').disabled = busy;
    document.getElementById('streamBtn').disabled = busy;
}

// ===== 1. 普通聊天 POST /bot/chat =====
async function sendNormal() {
    const input = document.getElementById('input');
    const message = input.value.trim();
    if (!message) return;

    const sessionId = getSessionId();
    updateSessionTag();
    addMessage('user', message);
    input.value = '';
    setBusy(true);

    const thinking = addMessage('ai', '思考中…');

    try {
        const resp = await fetch(BASE + '/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                sessionId,
                message,
                systemPrompt: getSystemPrompt(),
                temperature: getTemperature()
            })
        });
        if (!resp.ok) throw new Error('HTTP ' + resp.status + ': ' + await resp.text());
        const data = await resp.json();
        thinking.textContent = data.answer || '(空回复)';
        if (data.sessionId) document.getElementById('sessionId').value = data.sessionId;
        if (data.usage) renderUsage(data.usage);
        updateSessionTag();
    } catch (err) {
        thinking.textContent = '❌ 出错: ' + err.message;
    } finally {
        setBusy(false);
    }
}

// ===== 2. 流式聊天 GET /bot/chat/stream (SSE) =====
let currentEventSource = null;

function sendStream() {
    const input = document.getElementById('input');
    const message = input.value.trim();
    if (!message) return;

    const sessionId = getSessionId();
    updateSessionTag();
    addMessage('user', message);
    input.value = '';
    setBusy(true);

    const bubble = addMessage('ai', '');
    let buffer = '';

    const params = new URLSearchParams({ message, sessionId });
    const sp = getSystemPrompt();
    if (sp) params.append('systemPrompt', sp);

    if (currentEventSource) currentEventSource.close();
    const es = new EventSource(BASE + '/chat/stream?' + params.toString());
    currentEventSource = es;

    let finished = false;
    const finish = () => {
        if (finished) return;
        finished = true;
        es.close();
        currentEventSource = null;
        setBusy(false);
        loadUsage();
    };

    // 默认事件: 文本增量
    es.onmessage = (e) => {
        buffer += e.data;
        bubble.textContent = buffer;
        scrollBottom();
    };

    // 具名事件: 后端结束时发送 event:done data:[DONE]
    es.addEventListener('done', () => finish());

    es.onerror = () => {
        // 流正常结束或出错都会触发; 若已收到内容则视为正常收尾
        if (!finished && !buffer) bubble.textContent = '❌ 流式连接结束或出错';
        finish();
    };
}

// ===== 3. 对话总结 POST /bot/summarize =====
async function summarize() {
    const sessionId = document.getElementById('sessionId').value.trim();
    if (!sessionId) { alert('请先开始一次会话'); return; }
    try {
        const resp = await fetch(BASE + '/summarize?sessionId=' + encodeURIComponent(sessionId), { method: 'POST' });
        if (!resp.ok) throw new Error('HTTP ' + resp.status + ': ' + await resp.text());
        const data = await resp.json();
        document.getElementById('sumSummary').textContent = data.summary || '-';
        const ul = document.getElementById('sumPoints');
        ul.innerHTML = '';
        (data.keyPoints || []).forEach(p => {
            const li = document.createElement('li');
            li.textContent = p;
            ul.appendChild(li);
        });
        const st = data.sentiment || 'neutral';
        const el = document.getElementById('sumSentiment');
        el.textContent = st;
        el.className = 'sentiment ' + st;
        document.getElementById('modalMask').classList.add('show');
    } catch (err) {
        alert('总结失败: ' + err.message);
    }
}

function closeModal() {
    document.getElementById('modalMask').classList.remove('show');
}

// ===== 4. token 统计 GET /bot/usage =====
async function loadUsage() {
    const sessionId = document.getElementById('sessionId').value.trim();
    if (!sessionId) return;
    try {
        const resp = await fetch(BASE + '/usage?sessionId=' + encodeURIComponent(sessionId));
        if (!resp.ok) return;
        renderUsage(await resp.json());
    } catch (e) { /* 忽略 */ }
}

function renderUsage(u) {
    document.getElementById('stPrompt').textContent = u.promptTokens ?? 0;
    document.getElementById('stCompletion').textContent = u.completionTokens ?? 0;
    document.getElementById('stTotal').textContent = u.totalTokens ?? 0;
    document.getElementById('stCost').textContent = '¥' + (u.costYuan ?? 0).toFixed(6);
    document.getElementById('stCount').textContent = u.callCount ?? 0;
}

// ===== 5. 历史 GET /bot/history =====
async function loadHistory() {
    const sessionId = document.getElementById('sessionId').value.trim();
    if (!sessionId) { alert('请先输入会话 ID'); return; }
    try {
        const resp = await fetch(BASE + '/history?sessionId=' + encodeURIComponent(sessionId));
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const list = await resp.json();
        const box = document.getElementById('messages');
        box.innerHTML = '';
        if (!list.length) { addMessage('system', '该会话暂无历史'); return; }
        list.forEach(m => {
            const content = m.content || m.reasoningContent || '';
            if (m.role === 'user') addMessage('user', content);
            else if (m.role === 'assistant') addMessage('ai', content);
            else addMessage('system', '[' + m.role + '] ' + content);
        });
        updateSessionTag();
    } catch (err) {
        alert('加载历史失败: ' + err.message);
    }
}

// ===== 6. 清空会话 DELETE /bot/session =====
async function clearSession() {
    const sessionId = document.getElementById('sessionId').value.trim();
    if (!sessionId) { alert('没有可清空的会话'); return; }
    if (!confirm('确定清空会话 ' + sessionId + ' 吗？')) return;
    try {
        const resp = await fetch(BASE + '/session?sessionId=' + encodeURIComponent(sessionId), { method: 'DELETE' });
        const text = await resp.text();
        document.getElementById('messages').innerHTML = '';
        addMessage('system', text || '会话已清空');
        renderUsage({ promptTokens: 0, completionTokens: 0, totalTokens: 0, costYuan: 0, callCount: 0 });
    } catch (err) {
        alert('清空失败: ' + err.message);
    }
}
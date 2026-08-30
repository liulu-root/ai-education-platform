const state = { conversationId: null };
const titles = { overview: '运营总览', tutor: '智能助教', learning: '学习路径', grading: '作业批改', risk: '风险预警', audit: 'AI 审计' };

document.querySelectorAll('.nav-item').forEach(button => button.addEventListener('click', () => {
    document.querySelectorAll('.nav-item').forEach(item => item.classList.remove('active'));
    document.querySelectorAll('.page').forEach(page => page.classList.remove('active'));
    button.classList.add('active');
    const target = button.dataset.target;
    document.getElementById(target).classList.add('active');
    document.getElementById('page-title').textContent = titles[target];
    if (target === 'audit') loadAudit();
}));

async function api(path, options = {}) {
    const response = await fetch(path, { headers: { 'Content-Type': 'application/json', ...(options.headers || {}) }, ...options });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.message || `请求失败：${response.status}`);
    return data;
}

function toast(message) {
    const element = document.getElementById('toast');
    element.textContent = message;
    element.classList.add('show');
    setTimeout(() => element.classList.remove('show'), 2600);
}

function number(value) { return Number(value || 0).toLocaleString('zh-CN'); }
function money(value) { return `$${Number(value || 0).toFixed(4)}`; }

async function loadOverview() {
    try {
        const [overview, courses] = await Promise.all([api('/api/dashboard/overview'), api('/api/dashboard/courses')]);
        const business = overview.business;
        const ai = overview.ai;
        document.getElementById('metric-courses').textContent = number(business.courses);
        document.getElementById('metric-enrollments').textContent = number(business.enrollments);
        document.getElementById('metric-risk').textContent = number(business.at_risk);
        document.getElementById('metric-tokens').textContent = number(Number(ai.input_tokens) + Number(ai.output_tokens));
        document.getElementById('ai-requests').textContent = number(ai.request_count);
        document.getElementById('ai-blocked').textContent = number(ai.blocked_count);
        document.getElementById('ai-latency').textContent = `${Math.round(Number(ai.average_latency_ms || 0))}ms`;
        document.getElementById('ai-cost').textContent = money(ai.estimated_cost);
        const requestCount = Number(ai.request_count || 0);
        const success = requestCount ? Math.max(0, (requestCount - Number(ai.failed_count) - Number(ai.blocked_count)) / requestCount * 100) : 100;
        document.getElementById('success-rate').textContent = `${success.toFixed(0)}%`;
        document.getElementById('course-list').innerHTML = courses.map(course => `
            <div class="course-row"><div><strong>${course.title}</strong><small>${course.code} · ${course.enrollments} 人学习</small></div>
            <div class="progress"><i style="width:${Number(course.average_progress)}%"></i></div><span>${Number(course.average_progress).toFixed(0)}%</span></div>`).join('');
    } catch (error) { toast(error.message); }
}

document.getElementById('chat-form').addEventListener('submit', async event => {
    event.preventDefault();
    const button = event.target.querySelector('button');
    const question = document.getElementById('chat-question').value.trim();
    appendMessage('user', question);
    button.disabled = true; button.textContent = '思考中…';
    try {
        const result = await api('/api/ai/tutor/chat', { method: 'POST', body: JSON.stringify({
            conversationId: state.conversationId, courseId: document.getElementById('chat-course').value, question
        }) });
        state.conversationId = result.conversationId;
        appendMessage('assistant', result.answer, result.citations);
        const usage = result.usage;
        document.getElementById('chat-usage').innerHTML = `<strong>traceId</strong><br>${usage.traceId}<br><strong>Token</strong> ${usage.inputTokens} 输入 / ${usage.outputTokens} 输出<br><strong>模型</strong> ${usage.provider} · ${usage.model}<br><strong>延迟</strong> ${usage.latencyMs} ms`;
        loadOverview();
    } catch (error) { appendMessage('assistant', `请求未执行：${error.message}`); }
    finally { button.disabled = false; button.innerHTML = '发送问题 <span>→</span>'; }
});

function appendMessage(role, content, citations = []) {
    const log = document.getElementById('chat-log');
    const item = document.createElement('div');
    item.className = `message ${role}`;
    const citationHtml = citations.length ? `<div class="citations">${citations.map(c => `<small>[${c.label}] ${c.excerpt} · ${(c.score * 100).toFixed(0)}%</small>`).join('')}</div>` : '';
    item.innerHTML = `<span>${role === 'user' ? '我' : 'AI'}</span><div>${escapeHtml(content)}${citationHtml}</div>`;
    log.appendChild(item); log.scrollTop = log.scrollHeight;
}

document.getElementById('path-form').addEventListener('submit', async event => {
    event.preventDefault(); const button = event.target.querySelector('button'); button.disabled = true;
    try {
        const result = await api('/api/ai/learning-paths', { method: 'POST', body: JSON.stringify({
            courseId: document.getElementById('path-course').value,
            goal: document.getElementById('path-goal').value,
            weeklyMinutes: Number(document.getElementById('path-minutes').value)
        }) });
        document.getElementById('path-result').className = 'result-space';
        document.getElementById('path-result').innerHTML = result.items.map(item => `<div class="path-item"><span>${item.sequence}</span><div><strong>${item.skillName}</strong><small>${item.activity} · 当前掌握度 ${(item.currentMastery * 100).toFixed(0)}%</small></div><b>${item.estimatedMinutes} 分钟</b></div>`).join('') + `<div class="path-rationale"><strong>AI 路径说明</strong><br>${escapeHtml(result.rationale)}</div>`;
        toast('学习路径已保存'); loadOverview();
    } catch (error) { toast(error.message); }
    finally { button.disabled = false; }
});

document.getElementById('grade-form').addEventListener('submit', async event => {
    event.preventDefault(); const button = event.target.querySelector('button'); button.disabled = true;
    try {
        const result = await api('/api/ai/grading', { method: 'POST', body: JSON.stringify({
            assignmentId: document.getElementById('grade-assignment').value,
            answer: document.getElementById('grade-answer').value
        }) });
        const dimensions = Object.entries(result.dimensions).map(([key, value]) => `<div class="dimension"><span><b>${key}</b><i>${Math.round(value * 100)}%</i></span><div class="progress"><i style="width:${value * 100}%"></i></div></div>`).join('');
        const card = document.getElementById('grade-result'); card.className = 'panel result-card';
        card.innerHTML = `<p class="eyebrow">${result.reviewStatus}</p><div><span class="score">${result.score}</span> / ${result.maxScore}</div><small>置信度 ${(result.confidence * 100).toFixed(0)}%</small>${dimensions}<div class="feedback"><strong>形成性反馈</strong><br>${escapeHtml(result.feedback)}</div>`;
        toast('批改完成并写入审计'); loadOverview();
    } catch (error) { toast(error.message); }
    finally { button.disabled = false; }
});

document.getElementById('risk-java').addEventListener('click', () => assessRisk('course-java', false));
document.getElementById('risk-data').addEventListener('click', () => assessRisk('course-data', true));
async function assessRisk(courseId, explain) {
    try {
        const path = explain ? `/api/ai/risk/explain?courseId=${courseId}` : `/api/ai/risk/assessment?courseId=${courseId}`;
        const result = await api(path, { method: explain ? 'POST' : 'GET' });
        const container = document.getElementById('risk-result'); container.className = 'result-space risk-card';
        container.innerHTML = `<span class="risk-level">${result.riskLevel} RISK</span><h3>${(result.riskScore * 100).toFixed(1)}%</h3><small>辅助干预风险分</small><h4>主要驱动</h4><ul>${result.drivers.map(d => `<li>${d}</li>`).join('')}</ul><div class="feedback">${escapeHtml(result.explanation)}</div><small>${result.decisionBoundary}</small>`;
        if (explain) loadOverview();
    } catch (error) { toast(error.message); }
}

document.getElementById('refresh-audit').addEventListener('click', loadAudit);
async function loadAudit() {
    try {
        const [overview, events] = await Promise.all([api('/api/ai-audit/overview'), api('/api/ai-audit/events?limit=50')]);
        document.getElementById('audit-requests').textContent = number(overview.request_count);
        document.getElementById('audit-input').textContent = number(overview.input_tokens);
        document.getElementById('audit-output').textContent = number(overview.output_tokens);
        document.getElementById('audit-blocked').textContent = number(overview.blocked_count);
        document.getElementById('audit-rows').innerHTML = events.length ? events.map(item => `<tr><td>${new Date(item.created_at).toLocaleString('zh-CN')}</td><td>${item.scenario}</td><td>${item.model}</td><td>${Number(item.input_tokens) + Number(item.output_tokens)}</td><td>${item.latency_ms} ms</td><td><span class="badge ${String(item.risk_level).toLowerCase()}">${item.risk_level}</span></td><td><span class="badge ${String(item.status).toLowerCase()}">${item.status}</span></td></tr>`).join('') : '<tr><td colspan="7">还没有 AI 调用记录</td></tr>';
    } catch (error) { toast(error.message); }
}

function escapeHtml(value) { const node = document.createElement('div'); node.textContent = value ?? ''; return node.innerHTML; }
loadOverview();

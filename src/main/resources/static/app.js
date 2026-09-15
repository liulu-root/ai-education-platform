const state = {
    conversationId: null,
    tutorAbortController: null,
    latestIntervention: null,
    user: null,
    csrfHeader: null,
    csrfToken: null
};
const titles = { overview: '运营总览', tutor: '智能助教', learning: '学习路径', grading: '作业批改', risk: '风险预警', interventions: '干预审批', audit: 'AI 审计', 'data-browser': '数据查看' };
const roleLabels = { LEARNER: '学习者', INSTRUCTOR: '教师', AUDITOR: '审计员' };
const safeMethods = new Set(['GET', 'HEAD', 'OPTIONS']);

document.querySelectorAll('.nav-item').forEach(button =>
    button.addEventListener('click', () => activatePage(button.dataset.target)));

document.querySelectorAll('.demo-account').forEach(button => button.addEventListener('click', () => {
    document.getElementById('login-email').value = button.dataset.email;
    document.getElementById('login-password').value = 'Demo123!';
    document.getElementById('login-password').focus();
}));

document.getElementById('login-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (!event.currentTarget.reportValidity()) return;

    const email = document.getElementById('login-email').value.trim();
    const password = document.getElementById('login-password').value;
    setLoginBusy(true);
    showLoginError('');
    try {
        const session = await api('/api/auth/login', {
            method: 'POST',
            body: JSON.stringify({ email, password }),
            skipAuthRedirect: true
        });
        updateCsrf(session);
        showApplication(session.user);
    } catch (error) {
        showLoginError(error.message);
    } finally {
        setLoginBusy(false);
    }
});

document.getElementById('logout-button').addEventListener('click', async () => {
    const button = document.getElementById('logout-button');
    button.disabled = true;
    try {
        await api('/api/auth/logout', { method: 'POST' });
        await enterLoggedOutState('已安全退出');
    } catch (error) {
        toast(error.message);
    } finally {
        button.disabled = false;
    }
});

async function bootstrapAuthentication() {
    try {
        const session = await fetchAuthSession();
        updateCsrf(session);
        setLoginBusy(false);
        if (session.authenticated && session.user) {
            showApplication(session.user);
        } else {
            showLogin();
        }
    } catch (error) {
        state.csrfHeader = null;
        state.csrfToken = null;
        setLoginBusy(true, '刷新页面重试');
        showLogin('无法建立安全会话，请刷新页面重试');
    }
}

async function fetchAuthSession() {
    const response = await fetch('/api/auth/session', {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' }
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.message || `请求失败：${response.status}`);
    return data;
}

function updateCsrf(session) {
    state.csrfHeader = session?.csrf?.headerName || null;
    state.csrfToken = session?.csrf?.token || null;
}

function requestHeaders(method, provided = {}, hasBody = false) {
    const headers = { Accept: 'application/json', ...provided };
    if (hasBody && !Object.keys(headers).some(name => name.toLowerCase() === 'content-type')) {
        headers['Content-Type'] = 'application/json';
    }
    if (!safeMethods.has(method) && state.csrfHeader && state.csrfToken) {
        headers[state.csrfHeader] = state.csrfToken;
    }
    return headers;
}

async function api(path, options = {}) {
    const { skipAuthRedirect = false, ...fetchOptions } = options;
    const method = String(fetchOptions.method || 'GET').toUpperCase();
    const response = await fetch(path, {
        ...fetchOptions,
        method,
        credentials: 'same-origin',
        headers: requestHeaders(method, fetchOptions.headers || {}, fetchOptions.body !== undefined)
    });
    const data = response.status === 204 ? null : await response.json().catch(() => ({}));
    if (!response.ok) {
        const error = new Error(data?.message || `请求失败：${response.status}`);
        error.status = response.status;
        error.code = data?.code;
        if (response.status === 401 && !skipAuthRedirect) {
            await enterLoggedOutState('会话已过期，请重新登录');
        }
        throw error;
    }
    return data;
}

function activatePage(target) {
    const button = document.querySelector(`.nav-item[data-target="${target}"]`);
    if (!button || button.hidden) return;

    document.querySelectorAll('.nav-item').forEach(item => item.classList.remove('active'));
    document.querySelectorAll('.page').forEach(page => page.classList.remove('active'));
    button.classList.add('active');
    document.getElementById(target).classList.add('active');
    document.getElementById('page-title').textContent = titles[target];
    if (target === 'overview') loadOverview();
    if (target === 'interventions') loadInterventionWorkspace();
    if (target === 'audit') loadAudit();
    if (target === 'data-browser') loadDataBrowser();
}

function showApplication(user) {
    resetDataBrowser();
    state.user = user;
    state.conversationId = null;
    state.latestIntervention = null;
    resetLatestIntervention();
    document.getElementById('auth-view').hidden = true;
    document.getElementById('app-shell').hidden = false;
    document.getElementById('profile-name').textContent = user.displayName;
    document.getElementById('profile-meta').textContent = `${roleLabels[user.role] || user.role} · ${user.id}`;
    document.getElementById('profile-avatar').textContent = Array.from(user.displayName || '用户').slice(0, 2).join('');

    const allowed = [];
    document.querySelectorAll('.nav-item').forEach(button => {
        const roles = String(button.dataset.roles || '').split(',').filter(Boolean);
        button.hidden = !roles.includes(user.role);
        if (!button.hidden) allowed.push(button);
    });
    if (!allowed.length) {
        showLoginError('当前账号没有可访问的功能');
        document.getElementById('app-shell').hidden = true;
        document.getElementById('auth-view').hidden = false;
        return;
    }
    activatePage(allowed[0].dataset.target);
}

function showLogin(message = '') {
    resetDataBrowser();
    state.user = null;
    state.conversationId = null;
    state.latestIntervention = null;
    resetLatestIntervention();
    abortActiveTutorRequest();
    state.tutorAbortController = null;
    document.getElementById('app-shell').hidden = true;
    document.getElementById('auth-view').hidden = false;
    showLoginError(message);
    queueMicrotask(() => document.getElementById('login-email').focus());
}

function showLoginError(message) {
    document.getElementById('login-error').textContent = message || '';
}

function resetLatestIntervention() {
    const container = document.getElementById('intervention-created');
    container.className = 'latest-request empty-state compact-empty';
    container.textContent = '尚未发起干预请求。';
}

function setLoginBusy(busy, busyLabel = '验证中…') {
    const button = document.querySelector('#login-form button[type="submit"]');
    button.disabled = busy;
    button.textContent = busy ? busyLabel : '安全登录';
}

async function enterLoggedOutState(message = '') {
    setLoginBusy(true, '建立安全会话…');
    showLogin(message);
    try {
        const session = await fetchAuthSession();
        updateCsrf(session);
        setLoginBusy(false);
    } catch {
        state.csrfHeader = null;
        state.csrfToken = null;
        setLoginBusy(true, '刷新页面重试');
        showLoginError('会话已失效，且无法获取新的安全令牌，请刷新页面');
    }
}

function refreshOverviewIfAllowed() {
    if (state.user && ['INSTRUCTOR', 'AUDITOR'].includes(state.user.role)) {
        loadOverview();
    }
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
    if (!question) return;

    abortActiveTutorRequest();
    const controller = new AbortController();
    state.tutorAbortController = controller;

    appendMessage('user', question);
    const assistantMessage = appendMessage('assistant', '');
    button.disabled = true; button.textContent = '生成中…';
    let usedTools = [];
    let completed = false;

    try {
        await streamTutorChat({
            conversationId: state.conversationId, courseId: document.getElementById('chat-course').value, question
        }, controller.signal, (eventType, data) => {
            if (state.tutorAbortController !== controller || completed) return;

            if (eventType === 'start') {
                if (data.conversationId) state.conversationId = data.conversationId;
                if (Array.isArray(data.usedTools)) usedTools = data.usedTools;
                renderCitations(assistantMessage.body, data.citations);
            } else if (eventType === 'delta') {
                button.textContent = '生成中…';
                assistantMessage.content.textContent += String(data.text ?? '');
                scrollChatToBottom();
            } else if (eventType === 'citations') {
                renderCitations(assistantMessage.body, data.citations);
            } else if (eventType === 'status') {
                button.textContent = data.message || '正在整理对话上下文';
            } else if (eventType === 'done') {
                completed = true;
                if (data.conversationId) state.conversationId = data.conversationId;
                if (Array.isArray(data.usedTools)) usedTools = data.usedTools;
                renderCitations(assistantMessage.body, data.citations);
                renderChatUsage(data.usage, usedTools);
            } else if (eventType === 'error') {
                const streamError = new Error(data.message || '模型生成失败');
                streamError.code = data.code;
                throw streamError;
            }
        });

        if (!completed && !controller.signal.aborted) throw new Error('流式响应意外结束');
        refreshOverviewIfAllowed();
    } catch (error) {
        if (error.name !== 'AbortError' && state.tutorAbortController === controller) {
            const separator = assistantMessage.content.textContent ? '\n\n' : '';
            assistantMessage.content.textContent += `${separator}请求未执行：${error.message}`;
            scrollChatToBottom();
        }
    } finally {
        if (state.tutorAbortController === controller) {
            state.tutorAbortController = null;
            button.disabled = false;
            button.innerHTML = '发送问题 <span>→</span>';
        }
    }
});

function appendMessage(role, content, citations = []) {
    const log = document.getElementById('chat-log');
    const item = document.createElement('div');
    item.className = `message ${role}`;

    const avatar = document.createElement('span');
    avatar.textContent = role === 'user' ? '我' : 'AI';
    const body = document.createElement('div');
    const contentElement = document.createElement('span');
    contentElement.textContent = content ?? '';
    body.appendChild(contentElement);
    renderCitations(body, citations);
    item.append(avatar, body);
    log.appendChild(item);
    scrollChatToBottom();
    return { item, body, content: contentElement };
}

function renderCitations(messageBody, citations = []) {
    const previous = messageBody.querySelector('.citations');
    if (previous) previous.remove();
    if (!Array.isArray(citations) || !citations.length) return;

    const container = document.createElement('div');
    container.className = 'citations';
    citations.forEach(citation => {
        const item = document.createElement('small');
        const score = Number(citation?.score);
        const scoreText = Number.isFinite(score) ? ` · ${(score * 100).toFixed(0)}%` : '';
        item.textContent = `[${citation?.label ?? '引用'}] ${citation?.excerpt ?? ''}${scoreText}`;
        container.appendChild(item);
    });
    messageBody.appendChild(container);
}

function renderChatUsage(usage, usedTools = []) {
    if (!usage) return;
    const container = document.getElementById('chat-usage');
    container.replaceChildren();
    const rows = [
        ['traceId', usage.traceId ?? '—'],
        ['Token', `${usage.inputTokens ?? 0} 输入 / ${usage.outputTokens ?? 0} 输出`],
        ['模型', `${usage.provider ?? '—'} · ${usage.model ?? '—'}`],
        ['延迟', `${usage.latencyMs ?? 0} ms`]
    ];
    if (Array.isArray(usedTools) && usedTools.length) rows.push(['工具', usedTools.map(String).join('、')]);

    rows.forEach(([label, value], index) => {
        const title = document.createElement('strong');
        title.textContent = label;
        container.append(title, document.createElement('br'), document.createTextNode(String(value)));
        if (index < rows.length - 1) container.appendChild(document.createElement('br'));
    });
}

async function streamTutorChat(payload, signal, onEvent) {
    const response = await fetch('/api/ai/tutor/chat/stream', {
        method: 'POST',
        credentials: 'same-origin',
        headers: requestHeaders('POST', {
            'Content-Type': 'application/json',
            Accept: 'text/event-stream'
        }, true),
        body: JSON.stringify(payload),
        signal
    });
    if (!response.ok) {
        const data = await response.json().catch(() => ({}));
        const error = new Error(data.message || `请求失败：${response.status}`);
        error.status = response.status;
        error.code = data.code;
        if (response.status === 401) {
            await enterLoggedOutState('会话已过期，请重新登录');
        }
        throw error;
    }
    if (!response.body) throw new Error('当前浏览器不支持流式响应');

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
        while (true) {
            const { value, done } = await reader.read();
            if (done) {
                buffer += decoder.decode();
                consumeSseFrames(buffer, onEvent, true);
                break;
            }
            buffer += decoder.decode(value, { stream: true });
            buffer = consumeSseFrames(buffer, onEvent);
        }
    } catch (error) {
        await reader.cancel().catch(() => {});
        throw error;
    } finally {
        reader.releaseLock();
    }
}

function consumeSseFrames(buffer, onEvent, flush = false) {
    const boundaryPattern = /\r?\n\r?\n|\r\r/;
    let boundary = boundaryPattern.exec(buffer);
    while (boundary) {
        dispatchSseFrame(buffer.slice(0, boundary.index), onEvent);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        boundary = boundaryPattern.exec(buffer);
    }
    if (flush && buffer) {
        dispatchSseFrame(buffer, onEvent);
        return '';
    }
    return buffer;
}

function dispatchSseFrame(frame, onEvent) {
    const lines = frame.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
    let eventType = 'message';
    const dataLines = [];
    lines.forEach(line => {
        if (!line || line.startsWith(':')) return;
        const separator = line.indexOf(':');
        const field = separator < 0 ? line : line.slice(0, separator);
        let value = separator < 0 ? '' : line.slice(separator + 1);
        if (value.startsWith(' ')) value = value.slice(1);
        if (field === 'event') eventType = value;
        if (field === 'data') dataLines.push(value);
    });

    if (!['start', 'delta', 'done', 'error'].includes(eventType) || !dataLines.length) return;
    let data;
    try {
        data = JSON.parse(dataLines.join('\n'));
    } catch {
        throw new Error(`无法解析模型流事件：${eventType}`);
    }
    onEvent(eventType, data ?? {});
}

function abortActiveTutorRequest() {
    if (state.tutorAbortController && !state.tutorAbortController.signal.aborted) {
        state.tutorAbortController.abort();
    }
}

function scrollChatToBottom() {
    const log = document.getElementById('chat-log');
    log.scrollTop = log.scrollHeight;
}

window.addEventListener('pagehide', abortActiveTutorRequest);

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
        toast('学习路径已保存'); refreshOverviewIfAllowed();
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
        toast('批改完成并写入审计'); refreshOverviewIfAllowed();
    } catch (error) { toast(error.message); }
    finally { button.disabled = false; }
});

document.getElementById('risk-java').addEventListener('click', () => assessRisk('course-java', false));
document.getElementById('risk-data').addEventListener('click', () => assessRisk('course-data', true));
async function assessRisk(courseId, explain) {
    try {
        const path = explain ? `/api/ai/risk/explain?learnerId=learner-001&courseId=${courseId}` : `/api/ai/risk/assessment?learnerId=learner-001&courseId=${courseId}`;
        const result = await api(path, { method: explain ? 'POST' : 'GET' });
        const container = document.getElementById('risk-result'); container.className = 'result-space risk-card';
        container.innerHTML = `<span class="risk-level">${result.riskLevel} RISK</span><h3>${(result.riskScore * 100).toFixed(1)}%</h3><small>辅助干预风险分</small><h4>主要驱动</h4><ul>${result.drivers.map(d => `<li>${d}</li>`).join('')}</ul><div class="feedback">${escapeHtml(result.explanation)}</div><small>${result.decisionBoundary}</small>`;
        if (explain) refreshOverviewIfAllowed();
    } catch (error) { toast(error.message); }
}

const approvalStatusLabels = {
    PENDING_APPROVAL: 'PENDING',
    APPROVED: 'APPROVED / 已执行',
    EXECUTED: 'EXECUTED',
    REJECTED: 'REJECTED'
};

document.getElementById('intervention-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (!event.currentTarget.reportValidity()) return;

    const button = event.currentTarget.querySelector('button[type="submit"]');
    button.disabled = true;
    button.textContent = '生成与检查中…';
    try {
        const result = await api('/api/ai/interventions', {
            method: 'POST',
            body: JSON.stringify({
                learnerId: document.getElementById('intervention-learner').value.trim(),
                courseId: document.getElementById('intervention-course').value,
                objective: document.getElementById('intervention-objective').value.trim()
            })
        });
        renderCreatedIntervention(result);
        toast('敏感工具已暂停，等待教师审批');
        await loadInterventionWorkspace();
        refreshOverviewIfAllowed();
    } catch (error) {
        toast(error.message);
    } finally {
        button.disabled = false;
        button.textContent = '生成通知并申请审批';
    }
});

document.getElementById('refresh-interventions').addEventListener('click', loadInterventionWorkspace);
document.getElementById('approval-list').addEventListener('click', async event => {
    const button = event.target.closest('button[data-decision]');
    if (!button) return;

    const card = button.closest('.approval-card');
    const approvalId = decodeURIComponent(button.dataset.approvalId || '');
    const decision = button.dataset.decision;
    if (!approvalId || !['approve', 'reject'].includes(decision)) return;

    const comment = card.querySelector('.approval-comment')?.value.trim() || '';
    const buttons = card.querySelectorAll('button[data-decision]');
    buttons.forEach(item => { item.disabled = true; });
    try {
        const decidedApproval = await api(`/api/ai/interventions/approvals/${encodeURIComponent(approvalId)}/${decision}`, {
            method: 'POST',
            body: JSON.stringify({ comment })
        });
        syncLatestInterventionApproval(decidedApproval);
        toast(decision === 'approve' ? '审批通过，通知工具已受控执行' : '审批已拒绝，通知未发送');
        await loadInterventionWorkspace();
        refreshOverviewIfAllowed();
    } catch (error) {
        toast(error.message);
        if (error.code === 'APPROVAL_ALREADY_DECIDED') {
            await loadInterventionWorkspace();
        } else {
            buttons.forEach(item => { item.disabled = false; });
        }
    }
});

async function loadInterventionWorkspace() {
    const approvals = document.getElementById('approval-list');
    const notifications = document.getElementById('notification-list');
    approvals.className = 'approval-list empty-state';
    notifications.className = 'notification-list empty-state';
    approvals.textContent = '正在加载审批队列…';
    notifications.textContent = '正在加载发送记录…';

    const [approvalResult, notificationResult] = await Promise.allSettled([
        api('/api/ai/interventions/approvals'),
        api('/api/ai/interventions/notifications')
    ]);
    if (approvalResult.status === 'fulfilled') {
        renderApprovalList(approvalResult.value);
    } else {
        approvals.textContent = `审批队列加载失败：${approvalResult.reason.message}`;
    }
    if (notificationResult.status === 'fulfilled') {
        renderNotificationList(notificationResult.value);
    } else {
        notifications.textContent = `发送记录加载失败：${notificationResult.reason.message}`;
    }
    if (approvalResult.status === 'rejected' || notificationResult.status === 'rejected') {
        toast('部分干预数据加载失败');
    }
}

function renderCreatedIntervention(result = {}) {
    const approval = result.approval || {};
    state.latestIntervention = { ...result, approval: { ...approval } };
    const status = String(approval.status || 'PENDING_APPROVAL').toUpperCase();
    const checks = normalisePostModelChecks(result.postModelChecks);
    const container = document.getElementById('intervention-created');
    container.className = 'latest-request';
    container.innerHTML = `
        <div class="latest-request-head">
            <span class="badge ${approvalStatusClass(status)}">${escapeHtml(approvalStatusLabels[status] || status)}</span>
            <span class="risk-chip high">HIGH RISK</span>
        </div>
        <small>${escapeHtml(result.interventionId || approval.interventionId || '新干预请求')}</small>
        <strong>后置处理后的通知预览</strong>
        <p>${escapeHtml(result.message || approval.message || '通知内容正在等待审批')}</p>
        <div class="check-chips">${checks.length
            ? checks.map(check => `<span>✓ ${escapeHtml(check)}</span>`).join('')
            : '<span>✓ FORMAT PASS</span><span>✓ SAFETY PASS</span>'}</div>`;
}

function renderApprovalList(payload) {
    const container = document.getElementById('approval-list');
    const approvals = unwrapItems(payload, 'approvals').slice().sort((left, right) => {
        const leftPending = String(left?.status).toUpperCase() === 'PENDING_APPROVAL' ? 0 : 1;
        const rightPending = String(right?.status).toUpperCase() === 'PENDING_APPROVAL' ? 0 : 1;
        return leftPending - rightPending;
    });
    const latestApprovalId = state.latestIntervention?.approval?.id;
    if (latestApprovalId) {
        syncLatestInterventionApproval(approvals.find(approval => approval?.id === latestApprovalId));
    }
    if (!approvals.length) {
        container.className = 'approval-list empty-state';
        container.textContent = '当前没有工具执行审批。发起一次干预即可演示暂停流程。';
        return;
    }

    container.className = 'approval-list';
    container.innerHTML = approvals.map(approval => {
        const status = String(approval?.status || 'PENDING_APPROVAL').toUpperCase();
        const pending = status === 'PENDING_APPROVAL';
        const id = String(approval?.id || '');
        const safeId = encodeURIComponent(id);
        const riskLevel = String(approval?.riskLevel || 'HIGH').toUpperCase();
        const toolName = approval?.toolName || 'sendLearnerNotification';
        const checks = normalisePostModelChecks(approval?.postModelChecks);
        const decisionMeta = approval?.decidedAt
            ? `<small>由 ${escapeHtml(approval.decidedBy || '教师')} 于 ${escapeHtml(formatDate(approval.decidedAt))} 处理${approval.comment ? ` · ${escapeHtml(approval.comment)}` : ''}</small>`
            : '';
        return `<article class="approval-card ${pending ? 'pending' : ''}">
            <div class="approval-card-head">
                <div><span class="risk-chip ${riskClass(riskLevel)}">${escapeHtml(riskLevel)} RISK</span><code>${escapeHtml(toolName)}</code></div>
                <span class="badge ${approvalStatusClass(status)}">${escapeHtml(approvalStatusLabels[status] || status)}</span>
            </div>
            <dl class="approval-meta">
                <div><dt>学习者</dt><dd>${escapeHtml(approval?.learnerId || '—')}</dd></div>
                <div><dt>课程</dt><dd>${escapeHtml(approval?.courseId || '—')}</dd></div>
                <div><dt>申请时间</dt><dd>${escapeHtml(formatDate(approval?.requestedAt))}</dd></div>
                <div><dt>审批编号</dt><dd>${escapeHtml(id || '—')}</dd></div>
            </dl>
            <div class="notification-preview"><small>AI 后置处理后的通知预览</small><p>${escapeHtml(approval?.message || '暂无通知预览')}</p></div>
            <div class="check-chips">${checks.length
                ? checks.map(check => `<span>✓ ${escapeHtml(check)}</span>`).join('')
                : '<span>后置检查记录缺失</span>'}<span>⏸ PRE-TOOL GATE</span></div>
            ${pending ? `<div class="approval-actions">
                <input class="approval-comment" maxlength="500" placeholder="审批意见（可选）" aria-label="审批意见">
                <button type="button" class="action-button reject" data-decision="reject" data-approval-id="${safeId}">拒绝</button>
                <button type="button" class="action-button approve" data-decision="approve" data-approval-id="${safeId}">批准并发送</button>
            </div>` : `<div class="decision-note">${decisionMeta || '<small>该审批已处理</small>'}</div>`}
        </article>`;
    }).join('');
}

function syncLatestInterventionApproval(approval) {
    const latest = state.latestIntervention;
    if (!approval?.id || !latest || latest.approval?.id !== approval.id) return;
    renderCreatedIntervention({
        ...latest,
        message: approval.message || latest.message,
        approval: { ...latest.approval, ...approval }
    });
}

function renderNotificationList(payload) {
    const container = document.getElementById('notification-list');
    const notifications = unwrapItems(payload, 'notifications');
    if (!notifications.length) {
        container.className = 'notification-list empty-state';
        container.textContent = '尚无已发送通知。拒绝的请求不会出现在这里。';
        return;
    }
    container.className = 'notification-list';
    container.innerHTML = notifications.map(item => `<article class="notification-card">
        <div><span class="delivery-dot" aria-hidden="true"></span><strong>已发送 · ${escapeHtml(item?.channel || 'IN_APP')}</strong></div>
        <p>${escapeHtml(item?.content || '')}</p>
        <small>${escapeHtml(item?.learnerId || '—')} · ${escapeHtml(item?.courseId || '—')}</small>
        <small>${escapeHtml(formatDate(item?.sentAt))} · ${escapeHtml(item?.sentBy || '系统')}</small>
    </article>`).join('');
}

function unwrapItems(payload, key) {
    if (Array.isArray(payload)) return payload;
    if (Array.isArray(payload?.[key])) return payload[key];
    if (Array.isArray(payload?.items)) return payload.items;
    if (Array.isArray(payload?.content)) return payload.content;
    return [];
}

function normalisePostModelChecks(checks) {
    if (Array.isArray(checks)) {
        return checks.map(check => typeof check === 'object'
            ? [check.name || check.check, check.status || check.result].filter(Boolean).join(' ')
            : String(check)).filter(Boolean);
    }
    if (checks && typeof checks === 'object') {
        return Object.entries(checks).map(([name, result]) => {
            const value = result && typeof result === 'object' ? result.status || result.result : result;
            return `${name} ${value ?? ''}`.trim();
        });
    }
    return checks ? [String(checks)] : [];
}

function approvalStatusClass(status) {
    if (status === 'PENDING_APPROVAL') return 'pending';
    if (['APPROVED', 'EXECUTED'].includes(status)) return 'executed';
    if (status === 'REJECTED') return 'rejected';
    return '';
}

function riskClass(level) {
    return ['high', 'medium', 'low'].includes(String(level).toLowerCase()) ? String(level).toLowerCase() : 'high';
}

function formatDate(value) {
    if (!value) return '—';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN');
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
bootstrapAuthentication();

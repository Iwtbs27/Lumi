'use strict';

const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const state = {runs: [], run: null, step: null, runId: null, stepNumber: null, tab: 'decision', filter: 'all', image: 'current', attempt: 0, status: null, online: true, runLoad: 0, stepLoad: 0, pollBusy: false, lastSync: null};
const actionLabels = {tap: '点击', swipe: '滑动', type: '输入文本', type_text: '输入文本', open_app: '打开应用', launch_app: '打开应用', press_key: '按键', key: '按键', back: '返回', wait: '等待', finish: '完成任务', update_plan: '更新计划', read_memory: '读取记忆', search_memory: '检索记忆', long_press: '长按', ask_user: '请求接管'};
const statusLabels = {completed: '已完成', success: '成功', succeeded: '成功', confirmed_success: '已观察到成功', confirmed_failure: '已观察到失败', failed: '失败', error: '错误', blocked: '已阻止', running: '运行中', cancelled: '已停止', canceled: '已停止', pending: '等待处理', active: '已启用', candidate: '候选', disabled: '已停用', unverified: '待验证', user_confirmed: '人工确认', observed_once: '一次验证', observed_repeatedly: '多次验证', injected: '注入', retrieved: '检索命中', selected: '声明采用', applied: '执行采用', outcome: '结果反馈'};
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const json = value => typeof value === 'string' ? value : JSON.stringify(value, null, 2);
const present = value => value !== null && value !== undefined && value !== '';
const array = value => Array.isArray(value) ? value : [];
const label = value => statusLabels[value] || value || '未记录';
const actionName = value => typeof value === 'object' && value !== null ? value.action_type || value.action || value.type || value.name || '操作' : value || '未记录动作';
const hasError = step => Boolean(step.error) || ['error', 'failed', 'blocked'].includes(step.execution_status);
const count = value => present(value) && Number.isFinite(Number(value)) ? new Intl.NumberFormat('zh-CN').format(value) : '未记录';
function date(value, timeOnly = false) {
  if (!present(value)) return '时间未记录';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return String(value);
  return new Intl.DateTimeFormat('zh-CN', timeOnly ? {hour:'2-digit',minute:'2-digit',second:'2-digit'} : {month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}).format(parsed);
}
function badge(value) {
  const kind = ['completed','success','succeeded','confirmed_success','active','user_confirmed'].includes(value) ? 'success' : ['error','failed','blocked','confirmed_failure'].includes(value) ? 'error' : ['running','candidate','unverified','needs_review'].includes(value) ? 'warning' : '';
  return `<span class="badge ${kind}">${esc(label(value))}</span>`;
}
function empty(message) { return `<p class="empty-inline">${esc(message)}</p>`; }
function section(title, body, note = '') { return `<section class="detail-section"><div class="section-title"><h3>${esc(title)}</h3>${note ? `<span>${esc(note)}</span>` : ''}</div>${body}</section>`; }
function prose(value, missing = '该项未记录') { return present(value) ? `<div class="prose">${esc(typeof value === 'object' ? json(value) : value)}</div>` : empty(missing); }
function code(value, title = 'JSON') {
  if (!present(value)) return empty('该项未记录');
  return `<div class="code-wrap"><div class="code-tools"><span>${esc(title)}</span><button class="copy-button" data-copy>复制</button></div><pre>${esc(json(value))}</pre></div>`;
}
function disclosure(title, body, open = false, suffix = '') { return `<details class="disclosure"${open ? ' open' : ''}><summary>${esc(title)}${suffix ? `<span class="muted">${esc(suffix)}</span>` : ''}</summary>${body}</details>`; }
function rows(entries) { return `<dl class="data-table">${entries.map(([key,value]) => `<div class="data-row"><dt>${esc(key)}</dt><dd>${present(value) ? esc(typeof value === 'object' ? json(value) : value) : '<span class="muted">未记录</span>'}</dd></div>`).join('')}</dl>`; }
function warnList(warnings) { return array(warnings).map(warning => `<div class="inline-warning">${esc(typeof warning === 'string' ? warning : json(warning))}</div>`).join(''); }
async function api(path, options) {
  const response = await fetch(path, {...options, headers: {'Content-Type':'application/json', ...options?.headers}});
  const data = await response.json();
  if (!response.ok) throw new Error(typeof data.error === 'string' ? data.error : data.message || `请求失败（${response.status}）`);
  return data;
}
function notify(message) {
  const toast = $('#toast'); toast.textContent = message; toast.hidden = false;
  clearTimeout(notify.timer); notify.timer = setTimeout(() => { toast.hidden = true; }, 3600);
}
function serviceNotice(message, error = false) {
  const node = $('#service-notice'); node.textContent = message; node.hidden = !message; node.classList.toggle('error', error);
  // A warning occupies real space, so the desktop workspace continues to fit the viewport.
  if (window.innerWidth > 760) $('.workspace').style.height = `calc(100dvh - ${$('.topbar').offsetHeight + $('.runbar').offsetHeight + node.offsetHeight}px)`;
}
function renderStatus(data) {
  state.status = data;
  const device = data.device || {}, sync = data.sync || {};
  const ready = Boolean(device.available && device.bridge_ready);
  $('#device-dot').className = `status-dot${ready ? ' online' : ''}`;
  const deviceLabel = device.available ? /provider|APK|更新/i.test(device.message || '') ? '手机需更新' : '手机未就绪' : '手机未连接';
  $('#device-status').textContent = ready ? '手机已连接' : deviceLabel;
  $('#device-status').title = device.message || '';
  $('#sync-button').disabled = Boolean(sync.running) || !ready;
  $('#sync-button span').textContent = sync.running ? '正在同步…' : '同步最新任务';
  $('#auto-sync').checked = Boolean(sync.auto);
  $('#auto-sync').disabled = false;
  const result = Array.isArray(sync.last_result) ? sync.last_result[sync.last_result.length - 1] : sync.last_result;
  const resultTime = result?.finished_at || result?.synced_at || result?.timestamp;
  $('#sync-time').textContent = resultTime ? `同步于 ${date(resultTime, true)}` : sync.auto ? `每 ${sync.interval || 10} 秒检查` : '手动同步';
  const error = sync.error;
  serviceNotice(error ? typeof error === 'string' ? error : json(error) : !ready ? device.message || '手机尚未连接。已同步的本地轨迹仍可查看。' : '', Boolean(error));
}
function renderRuns() {
  const select = $('#run-select');
  select.innerHTML = state.runs.length ? state.runs.map(run => `<option value="${esc(run.id)}">${esc(run.instruction || '未记录任务指令')} · ${esc(date(run.created_at))} · ${esc(label(run.status))}</option>`).join('') : '<option value="">还没有任务记录，请连接手机后同步</option>';
  select.disabled = !state.runs.length;
  if (state.runId) select.value = state.runId;
}
function renderRunStats() {
  const meta = state.run?.meta || {}, summary = state.runs.find(run => run.id === state.runId) || {};
  const info = {...summary, ...meta};
  const tokens = info.token_usage;
  const tokenLabel = info.usage_complete === false ? '已记录用量' : 'tokens';
  const tokenTitle = info.usage_complete === false ? '输入 / 输出 tokens；部分请求未保存用量，非完整合计' : '输入 / 输出 tokens';
  $('#run-stats').innerHTML = state.run ? `${badge(info.status)}<span class="stat"><strong>${count(state.run.steps?.length)}</strong> 步</span>${present(info.error_count) ? `<span class="stat"><strong>${count(info.error_count)}</strong> 异常</span>` : ''}${tokens ? `<span class="stat tokens" title="${tokenTitle}"><strong>${count(tokens.input)} / ${count(tokens.output)}</strong> ${tokenLabel}</span>` : ''}` : '';
}
function renderSteps() {
  const steps = array(state.run?.steps);
  $('#step-count').textContent = steps.length;
  $('#error-count').textContent = steps.filter(hasError).length;
  const visible = steps.filter(step => state.filter === 'all' || hasError(step));
  $('#step-list').innerHTML = visible.length ? visible.map(step => {
    const name = actionName(step.action), translated = actionLabels[name];
    return `<button class="step-row${step.number === state.stepNumber ? ' active' : ''}" data-step="${step.number}" aria-current="${step.number === state.stepNumber ? 'step' : 'false'}"><span class="step-number">${String(step.number).padStart(2,'0')}</span><span class="step-text"><span class="step-action"><span>${esc(translated || name)}</span>${hasError(step) ? '<span class="error-dot" title="此步骤有异常"></span>' : ''}</span><span class="step-thought">${esc(step.thought || (step.error ? typeof step.error === 'string' ? step.error : json(step.error) : name))}</span></span></button>`;
  }).join('') : `<div class="empty-state"><h3>${state.filter === 'errors' ? '没有记录到异常' : '还没有执行步骤'}</h3><p>${state.filter === 'errors' ? '可以切回全部，查看任务的完整过程。' : '同步任务后，步骤会显示在这里。'}</p></div>`;
}
function renderImage() {
  const step = state.step;
  if (!step) return;
  const isNext = state.image === 'next';
  const url = isNext ? step.next_screenshot_url : step.screenshot_url;
  $('#observation-title').textContent = `页面观测 · ${String(step.number).padStart(2,'0')}`;
  $('#image-step').textContent = isNext ? '后续页面' : '动作前';
  $('#current-image').classList.toggle('active', !isNext);
  $('#next-image').classList.toggle('active', isNext);
  $('#current-image').setAttribute('aria-pressed', String(!isNext));
  $('#next-image').setAttribute('aria-pressed', String(isNext));
  $('#next-image').disabled = !step.next_screenshot_url;
  $('#zoom-button').disabled = !url;
  $('#observation-caption').textContent = isNext ? '下一步观测仅展示后续页面，不代表当前动作已成功。' : '这是本步决策时的页面，尚未执行当前动作。';
  const stage = $('#screenshot-stage');
  if (url) {
    stage.innerHTML = `<img class="screen-image" src="${esc(url)}" alt="第 ${step.number} 步${isNext ? '之后下一步' : '决策前'}的屏幕截图" tabindex="0" role="button" aria-label="放大屏幕截图">`;
    $('.screen-image').addEventListener('error', () => { stage.innerHTML = '<div class="empty-state"><h3>截图加载失败</h3><p>记录中的截图文件可能尚未同步，请重新同步后查看。</p></div>'; $('#zoom-button').disabled = true; });
  } else stage.innerHTML = '<div class="empty-state"><h3>这一步没有截图</h3><p>记录中未提供该观测的截图文件。右侧仍可查看已记录的请求和动作。</p></div>';
  const summary = state.run?.steps?.find(item => item.number === step.number) || {};
  $('#step-summary').hidden = false;
  $('#step-summary').innerHTML = `<div class="step-summary-top"><strong>${esc(actionLabels[actionName(summary.action)] || actionName(summary.action))}</strong>${badge(summary.execution_status)}</div><p>${esc(summary.thought || '此步骤未记录决策说明')}</p>`;
}
function selectedRequest() { return array(state.step?.requests)[state.attempt]; }
function attemptPicker() {
  const requests = array(state.step?.requests);
  if (!requests.length) return '<p class="helper">此步骤没有记录 LLM 请求，无法还原模型当时收到的完整上下文。</p>';
  return `<div class="attempt-picker"><span>本步 LLM 请求 <span class="muted">${requests.length} 次</span></span><select id="attempt-select" aria-label="选择请求次数">${requests.map((request,index) => `<option value="${index}"${index === state.attempt ? ' selected' : ''}>第 ${request.attempt ?? index + 1} 次请求${index === requests.length - 1 ? ' · 最后一次' : ''}</option>`).join('')}</select></div>`;
}
function renderDecision() {
  const step = state.step, summary = state.run?.steps?.find(item => item.number === step.number) || {};
  const decision = step.decision;
  const thought = decision?.thought || decision?.arguments?.thought || summary.thought;
  let output = section('决策说明', prose(thought, '响应中没有记录 thought。'));
  output += section('模型提出的动作', code(decision, '模型响应'));
  const execution = step.execution;
  output += section('实际执行', `${summary.error ? `<div class="inline-error">${esc(typeof summary.error === 'string' ? summary.error : json(summary.error))}</div>` : ''}${code(execution, '执行记录')}`, label(summary.execution_status));
  output += section('后续观察到的效果', `${present(step.effect) && (!Array.isArray(step.effect) || step.effect.length) ? code(step.effect, '效果记录') : empty('没有记录后续效果；不能仅凭动作执行返回判断任务成功。')}`);
  output += section('请求消耗', rows([['耗时', present(summary.elapsed_ms) ? `${(summary.elapsed_ms / 1000).toFixed(2)} 秒` : null], ['Token 用量', step.usage || summary.token_usage]]));
  return output;
}
function messageBody(message) {
  const text = message.text ?? message.content;
  const content = typeof text === 'string' ? text : present(text) ? json(text) : '';
  const imageList = array(message.images);
  return `${content ? prose(content) : empty('该消息没有文本内容。')}${message.tool_calls ? code(message.tool_calls, '工具调用') : ''}${imageList.length ? `<p class="helper">包含 ${imageList.length} 张图片</p>${imageList.map(image => { const url = typeof image === 'string' ? image : image.url; return url ? `<a href="${esc(url)}" target="_blank" rel="noopener"><img class="request-image" src="${esc(url)}" alt="此请求包含的图片" loading="lazy"></a>` : ''; }).join('')}` : ''}`;
}
function renderContext() {
  const request = selectedRequest();
  let output = attemptPicker();
  if (!request) return output;
  const messages = array(request.messages);
  const blocks = request.blocks || {};
  const imageCount = messages.reduce((total,message) => total + array(message.images).length, 0);
  output += `<div class="request-overview"><span>本次实际请求</span><strong>${messages.length} 条消息</strong><strong>${imageCount} 张图片</strong></div>`;
  output += section('当前输入', `<p class="helper">从这次实际请求中提取；不是根据任务结束后的状态重新拼接。</p>${Object.entries({task:'任务要求', long_term_memory:'长期记忆 · long_term_memory', cur_state:'当前状态 · cur_state', current_step:'当前步骤 · current_step'}).map(([key,title]) => disclosure(title, present(blocks[key]) ? (typeof blocks[key] === 'object' ? code(blocks[key]) : prose(blocks[key])) : empty('这次请求中未识别到该内容块。'), key === 'task')).join('')}`);
  output += section('消息序列', messages.length ? messages.map((message,index) => {
    const name = message.role === 'system' ? 'System prompt' : index === messages.length - 1 && message.role === 'user' ? '当前输入 · user' : `历史消息 · ${message.role || '未知角色'}`;
    return disclosure(`${String(index + 1).padStart(2,'0')}  ${name}`, messageBody(message), false);
  }).join('') : empty('请求没有可解析的消息序列，请查看原始请求。'), `${messages.length} 条消息`);
  output += section('完整请求', disclosure('展开原始请求', code(request.raw, '实际发送的请求')));
  return output;
}
function memoryEntry(item, index) {
  if (typeof item === 'string') return `<article class="memory-entry">${prose(item)}</article>`;
  const title = item.title || item.name || `记忆 ${index + 1}`;
  const type = item.type === 'procedure' ? '方法' : item.type === 'fact' ? '知识' : item.type;
  return `<article class="memory-entry"><div class="memory-entry-heading"><h4>${esc(title)}</h4>${type ? `<span class="badge">${esc(type)}</span>` : ''}</div>${prose(item.summary || item.text || item.content, '此条记录未提供摘要。')}<p class="memory-meta">${esc([item.id || item.memory_id, present(item.revision) ? `版本 ${item.revision}` : null, item.status ? label(item.status) : null, item.evidence_level ? label(item.evidence_level) : null].filter(Boolean).join(' · '))}</p>${disclosure('查看记录字段', code(item))}</article>`;
}
function usageItems(items) {
  return items.length ? items.map((item,index) => disclosure(`${label(item.kind || item.event || item.type || item.phase)}${item.memory_id || item.id ? ` · ${item.memory_id || item.id}` : ` ${index + 1}`}`, code(item), false)).join('') : empty('没有相应记录。');
}
function renderMemory() {
  const memory = state.step.memory || {}, usage = array(memory.usage);
  const request = selectedRequest();
  const injected = array(request?.memory_injected ?? memory.injected);
  const requestMemory = request?.blocks?.long_term_memory;
  const kind = item => item.kind || item.event || item.type || item.phase || '';
  const selections = usage.filter(item => ['selected','applied','memory_use'].includes(kind(item)));
  const outcomes = usage.filter(item => ['outcome','feedback','memory_feedback'].includes(kind(item)));
  let output = warnList(state.run?.warnings) + '<p class="helper">注入只表示模型收到了记忆；声明采用与结果反馈分别展示，不等同于记忆已经验证成功。</p>';
  if (array(memory.errors).length) output += section('Memory 处理异常', memory.errors.map(error => {
    const data = error.data || error;
    return `<div class="inline-error">${data.stage ? `<strong>${esc(data.stage)}</strong><br>` : ''}${esc(data.message || json(data))}</div>`;
  }).join(''));
  output += section('本步注入的记忆', injected.length ? injected.map(memoryEntry).join('') : empty('这一步没有可解析的注入记忆条目。可在下面核对实际请求的 long_term_memory。'), `${injected.length} 条`);
  output += section('实际请求中的记忆块', `${attemptPicker()}${requestMemory ? disclosure('long_term_memory', typeof requestMemory === 'object' ? code(requestMemory) : prose(requestMemory), !injected.length) : empty('所选请求未记录 long_term_memory 内容块。')}`);
  output += section('声明采用与执行采用', usageItems(selections));
  output += section('局部结果反馈', usageItems(outcomes));
  output += section('本步完整使用记录', usage.length ? disclosure(`展开 ${usage.length} 条记录`, code(usage)) : empty('本步没有记录 Memory 使用事件。'));
  if (array(memory.snapshots).length) output += section('整次任务的记忆版本快照', '<p class="helper">用于查阅任务关联的记忆版本，不代表本步采用，也不代表完整内容已注入上下文。</p>' + memory.snapshots.map((item,index) => disclosure(item.title || `快照 ${index+1}`, code(item))).join(''));
  const consolidated = state.run?.memory?.consolidation;
  if (consolidated) output += section('任务结束后的记忆整理', '<p class="helper">这是整次任务的后台整理结果，不属于本步决策时的上下文。</p>' + disclosure('查看整理记录', code(consolidated)));
  return output;
}
function renderState() {
  const step = state.step;
  return section('决策前 · 实际请求中的状态', `${attemptPicker()}<p class="helper">以所选 LLM 请求的 cur_state 为准，反映做出这次决策前模型知道的状态。</p>${selectedRequest()?.blocks?.cur_state != null ? code(selectedRequest().blocks.cur_state, 'cur_state · 请求时') : empty('所选请求未记录 cur_state，无法还原请求时状态。')}`) + section('响应应用后 · 记录的状态', '<p class="helper">应用模型响应后、执行手机动作前保存的 state，可能包含本轮新写入的任务记忆和计划进度。</p>' + code(step.state_after, 'state · 响应应用后')) + section('观测记录', disclosure('页面文字与无障碍树', code(step.tree, '当前页面树')));
}
function renderRaw() {
  return section('步骤原始记录', `${disclosure('全部事件', code(state.step.events), true)}${disclosure('页面观测', code(state.step.observation))}${disclosure('计时数据', code(state.step.timing))}${disclosure('Token 用量', code(state.step.usage))}${disclosure('完整步骤数据', code(state.step))}`) + section('整次任务', `${disclosure('任务元信息', code(state.run?.meta))}${disclosure('历史经历 · episode', code(state.run?.memory?.episode))}${disclosure('全部 Memory 使用记录', code(state.run?.memory?.usage))}${disclosure('记忆快照', code(state.run?.memory?.memories))}`);
}
function renderDetail() {
  if (!state.step) return;
  $('#detail-step').textContent = `第 ${state.step.number} 步`;
  const renderer = {decision:renderDecision, context:renderContext, memory:renderMemory, state:renderState, raw:renderRaw}[state.tab];
  $('#detail-content').innerHTML = warnList(state.step.warnings) + renderer();
  $('#detail-content').setAttribute('aria-labelledby', `tab-${state.tab}`);
}
async function loadRuns({preserve = true, refreshCurrent = false} = {}) {
  const result = await api('/api/runs'); state.runs = array(result.runs);
  const id = preserve && state.runs.some(run => run.id === state.runId) ? state.runId : state.runs[0]?.id;
  renderRuns();
  if (id && (id !== state.runId || refreshCurrent || !state.run)) await loadRun(id, preserve);
  else if (!id) { state.run = null; renderSteps(); renderRunStats(); }
}
async function loadRun(id, preserveStep = false) {
  const ticket = ++state.runLoad; ++state.stepLoad;
  const previousNumber = preserveStep ? state.stepNumber : null;
  state.runId = id; $('#run-select').value = id;
  $('#step-list').innerHTML = '<div class="loading-lines" aria-label="正在加载步骤"><i></i><i></i><i></i><i></i></div>';
  try {
    const result = await api(`/api/runs/${encodeURIComponent(id)}`);
    if (ticket !== state.runLoad) return;
    state.run = result;
    const steps = array(result.steps);
    state.stepNumber = steps.some(step => step.number === previousNumber) ? previousNumber : steps[0]?.number;
    renderRunStats(); renderSteps();
    if (state.stepNumber != null) await loadStep(state.stepNumber);
    else { state.step = null; $('#detail-content').innerHTML = empty('任务尚未记录执行步骤。'); $('#screenshot-stage').innerHTML = '<div class="empty-state"><h3>尚无页面观测</h3><p>任务产生步骤记录后，同步即可查看。</p></div>'; $('#step-summary').hidden = true; }
  } catch (error) { if (ticket === state.runLoad) { $('#step-list').innerHTML = `<div class="empty-state"><h3>任务加载失败</h3><p>${esc(error.message)}</p></div>`; notify(error.message); } }
}
async function loadStep(number) {
  const ticket = ++state.stepLoad;
  state.stepNumber = Number(number); state.image = 'current';
  renderSteps();
  $('#detail-content').innerHTML = '<div class="loading-lines" aria-label="正在加载步骤详情"><i></i><i></i><i></i><i></i></div>';
  try {
    const step = await api(`/api/runs/${encodeURIComponent(state.runId)}/steps/${number}`);
    if (ticket !== state.stepLoad) return;
    state.step = step; state.attempt = Math.max(0,array(step.requests).length - 1);
    renderImage(); renderDetail();
  } catch (error) { if (ticket === state.stepLoad) { state.step = null; $('#detail-content').innerHTML = `<div class="inline-error">${esc(error.message)}。请重新选择步骤或同步任务。</div>`; } }
}
async function pollStatus() {
  if (state.pollBusy) return;
  state.pollBusy = true;
  try {
    const data = await api('/api/status'), wasRunning = state.status?.sync?.running;
    const syncKey = JSON.stringify(data.sync?.last_result ?? null);
    const refresh = (!state.online || wasRunning && !data.sync?.running || state.lastSync !== null && syncKey !== state.lastSync);
    state.online = true; renderStatus(data); state.lastSync = syncKey;
    if (refresh) await loadRuns({refreshCurrent:true});
  } catch (error) {
    state.online = false; $('#device-dot').className = 'status-dot error'; $('#device-status').textContent = '本地服务离线';
    $('#sync-button').disabled = true; $('#auto-sync').disabled = true;
    serviceNotice(`无法连接本地轨迹服务：${error.message}。请确认启动窗口仍在运行，连接恢复后会自动重试。`, true);
  } finally { state.pollBusy = false; }
}
function zoomImage() {
  const image = $('.screen-image'); if (!image) return;
  $('#zoom-image').src = image.src; $('#dialog-title').textContent = `第 ${state.stepNumber} 步 · ${state.image === 'next' ? '下一步观测' : '当前观测'}`;
  $('#image-dialog').showModal();
}

$('#run-select').addEventListener('change', event => loadRun(event.target.value));
$('#step-list').addEventListener('click', event => { const row = event.target.closest('[data-step]'); if (row) loadStep(Number(row.dataset.step)); });
$('#step-list').addEventListener('keydown', event => {
  if (!['ArrowDown','ArrowUp','ArrowLeft','ArrowRight'].includes(event.key)) return;
  const steps = array(state.run?.steps).filter(step => state.filter === 'all' || hasError(step));
  const index = steps.findIndex(step => step.number === state.stepNumber);
  const next = steps[index + (['ArrowDown','ArrowRight'].includes(event.key) ? 1 : -1)];
  if (next) { event.preventDefault(); loadStep(next.number); $(`[data-step="${next.number}"]`)?.focus(); }
});
$$('[data-filter]').forEach(button => button.addEventListener('click', () => {
  state.filter = button.dataset.filter;
  $$('[data-filter]').forEach(item => { item.classList.toggle('active', item === button); item.setAttribute('aria-pressed', String(item === button)); });
  renderSteps();
}));
function activateTab(button) {
  state.tab = button.dataset.tab;
  $$('[data-tab]').forEach(item => { item.classList.toggle('active', item === button); item.setAttribute('aria-selected', String(item === button)); item.tabIndex = item === button ? 0 : -1; });
  renderDetail(); $('#detail-content').scrollTop = 0;
}
$$('[data-tab]').forEach(button => button.addEventListener('click', () => activateTab(button)));
$('.tabs').addEventListener('keydown', event => {
  if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
  const tabs = $$('[data-tab]'), index = tabs.indexOf(document.activeElement);
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length-1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
  event.preventDefault(); activateTab(tabs[next]); tabs[next].focus();
});
$('#current-image').addEventListener('click', () => { state.image = 'current'; renderImage(); });
$('#next-image').addEventListener('click', () => { state.image = 'next'; renderImage(); });
$('#zoom-button').addEventListener('click', zoomImage);
$('#screenshot-stage').addEventListener('click', event => { if (event.target.matches('.screen-image')) zoomImage(); });
$('#screenshot-stage').addEventListener('keydown', event => { if (event.target.matches('.screen-image') && ['Enter',' '].includes(event.key)) { event.preventDefault(); zoomImage(); } });
$('#close-dialog').addEventListener('click', () => $('#image-dialog').close());
$('#image-dialog').addEventListener('click', event => { if (event.target === $('#image-dialog')) $('#image-dialog').close(); });
$('#detail-content').addEventListener('change', event => { if (event.target.id === 'attempt-select') { state.attempt = Number(event.target.value); renderDetail(); } });
$('#detail-content').addEventListener('click', async event => {
  const button = event.target.closest('[data-copy]'); if (!button) return;
  try { await navigator.clipboard.writeText(button.closest('.code-wrap').querySelector('pre').textContent); button.textContent = '已复制'; setTimeout(() => { button.textContent = '复制'; },1500); } catch { notify('浏览器未允许复制，请选中内容后复制。'); }
});
$('#sync-button').addEventListener('click', async () => {
  $('#sync-button').disabled = true;
  try { await api('/api/sync', {method:'POST',body:'{}'}); notify('开始同步手机上的最新任务'); await pollStatus(); }
  catch (error) { notify(error.message); await pollStatus(); }
});
$('#auto-sync').addEventListener('change', async event => {
  const enabled = event.target.checked; event.target.disabled = true;
  try { await api('/api/auto-sync', {method:'POST',body:JSON.stringify({enabled})}); await pollStatus(); notify(enabled ? '已开启自动同步，手机连接时定期检查新记录' : '已关闭自动同步'); }
  catch (error) { event.target.checked = !enabled; notify(error.message); }
  finally { event.target.disabled = !state.online; }
});
window.addEventListener('resize', () => { $('.workspace').style.height = ''; if (window.innerWidth > 760) serviceNotice($('#service-notice').textContent, $('#service-notice').classList.contains('error')); });
async function start() {
  await pollStatus();
  try { await loadRuns(); } catch (error) { $('#step-list').innerHTML = empty('记录暂时无法加载，服务恢复后将自动重试。'); notify(error.message); }
  setInterval(pollStatus, 2500);
}
start();

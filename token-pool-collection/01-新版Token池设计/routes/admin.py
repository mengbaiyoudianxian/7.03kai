"""管理面板 HTML"""
from fastapi import APIRouter
from fastapi.responses import HTMLResponse

router = APIRouter()

PANEL = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Token Pool 管理面板</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font:13px/1.5 system-ui,sans-serif;background:#0d1117;color:#c9d1d9;min-height:100vh}
.nav{background:#161b22;border-bottom:1px solid #30363d;padding:12px 20px;display:flex;align-items:center;gap:16px}
.nav h1{font-size:16px;color:#58a6ff;font-weight:700}
.nav span{color:#8b949e;font-size:12px}
.nav button{margin-left:auto;padding:6px 14px;background:#21262d;border:1px solid #30363d;border-radius:6px;color:#c9d1d9;cursor:pointer;font:12px system-ui}
.nav button:hover{background:#30363d}
.container{padding:20px;max-width:1200px;margin:0 auto}
.row{display:grid;grid-template-columns:1fr 1fr 1fr 1fr;gap:12px;margin-bottom:20px}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:16px}
.card h3{font-size:11px;color:#8b949e;text-transform:uppercase;letter-spacing:.5px;margin-bottom:8px}
.card .val{font-size:24px;font-weight:700;color:#f0f6fc}
.card .sub{font-size:11px;color:#8b949e;margin-top:4px}
.section{background:#161b22;border:1px solid #30363d;border-radius:8px;margin-bottom:16px;overflow:hidden}
.section-header{padding:12px 16px;border-bottom:1px solid #30363d;display:flex;align-items:center;gap:8px}
.section-header h2{font-size:13px;font-weight:600;color:#f0f6fc;flex:1}
.btn{padding:6px 12px;border:1px solid #30363d;background:#21262d;color:#c9d1d9;border-radius:6px;cursor:pointer;font:12px system-ui}
.btn:hover{background:#30363d}
.btn-green{background:#1f6427;border-color:#2ea043;color:#fff}.btn-green:hover{background:#238636}
.btn-red{background:#5d1a1a;border-color:#f85149;color:#f85149}.btn-red:hover{background:#b91c1c}
.btn-blue{background:#1c2d4a;border-color:#388bfd;color:#388bfd}.btn-blue:hover{background:#1f3869}
table{width:100%;border-collapse:collapse;font-size:12px}
th{padding:8px 12px;text-align:left;color:#8b949e;font-weight:500;background:#0d1117;border-bottom:1px solid #30363d}
td{padding:8px 12px;border-bottom:1px solid #21262d;vertical-align:middle}
tr:hover td{background:#161b22}
.badge{display:inline-flex;align-items:center;gap:4px;padding:2px 8px;border-radius:12px;font-size:11px;font-weight:500}
.badge-ok{background:#1f3d1f;color:#3fb950}
.badge-fail{background:#3d1f1f;color:#f85149}
.badge-unknown{background:#2d2d1f;color:#d29922}
.badge-open{background:#3d1a1a;color:#ff7b72}
.form-row{display:grid;grid-template-columns:1fr 1fr;gap:8px;padding:12px 16px}
label{display:flex;flex-direction:column;gap:4px;font-size:12px;color:#8b949e}
input,select{padding:7px 10px;background:#0d1117;border:1px solid #30363d;border-radius:6px;color:#c9d1d9;font:13px system-ui}
input:focus,select:focus{border-color:#58a6ff;outline:none}
.modal{display:none;position:fixed;inset:0;background:rgba(0,0,0,.7);z-index:100;align-items:center;justify-content:center}
.modal.show{display:flex}
.modal-box{background:#161b22;border:1px solid #30363d;border-radius:12px;width:560px;max-width:95vw;max-height:90vh;overflow-y:auto}
.modal-box h3{padding:16px 20px;border-bottom:1px solid #30363d;font-size:14px}
.modal-form{display:grid;grid-template-columns:1fr 1fr;gap:8px;padding:16px 20px}
.modal-form label{display:flex;flex-direction:column;gap:4px;font-size:12px;color:#8b949e}
.modal-form .full{grid-column:1/-1}
.modal-footer{padding:12px 20px;border-top:1px solid #30363d;display:flex;gap:8px;justify-content:flex-end}
.log-box{background:#0d1117;padding:12px;font:11px/1.6 'Cascadia Code','Fira Code',monospace;max-height:320px;overflow-y:auto;white-space:pre}
.toast{position:fixed;bottom:20px;right:20px;padding:10px 20px;border-radius:8px;font-size:13px;z-index:200;transition:opacity .3s;background:#238636;color:#fff}
.toast.err{background:#b91c1c}
.input-group{display:flex;gap:6px}
.input-group input{flex:1}
</style>
</head>
<body>
<div class="nav">
  <h1>⚡ Token Pool</h1>
  <span id="overview-text">加载中...</span>
  <button onclick="openAddModal()">+ 添加 Key</button>
  <button onclick="probeAll()" id="probe-btn">🔍 全量检测</button>
  <button onclick="loadAll()">↺ 刷新</button>
</div>
<div class="container">
  <div class="row" id="stats-row">
    <div class="card"><h3>总 Key 数</h3><div class="val" id="s-total">-</div></div>
    <div class="card"><h3>正常工作</h3><div class="val" id="s-ok" style="color:#3fb950">-</div></div>
    <div class="card"><h3>熔断中</h3><div class="val" id="s-cb" style="color:#f85149">-</div></div>
    <div class="card"><h3>总消耗 Token</h3><div class="val" id="s-tok">-</div><div class="sub" id="s-cost">-</div></div>
  </div>

  <div class="section">
    <div class="section-header">
      <h2>🔑 Key 列表</h2>
      <input id="filter-input" placeholder="搜索 alias/provider/model..." style="width:200px;padding:5px 10px;font-size:12px" oninput="filterKeys()">
    </div>
    <div style="overflow-x:auto">
    <table id="keys-table">
      <thead><tr>
        <th>Alias</th><th>Provider</th><th>Model</th><th>状态</th>
        <th>成功/失败</th><th>延迟</th><th>总Cost</th><th>Priority</th><th>操作</th>
      </tr></thead>
      <tbody id="keys-tbody"></tbody>
    </table>
    </div>
  </div>

  <div class="section">
    <div class="section-header"><h2>📊 调用日志</h2>
      <select id="log-alias" onchange="loadLog()" style="font-size:12px;padding:4px 8px">
        <option value="">全部</option>
      </select>
      <button class="btn" onclick="loadLog()">刷新</button>
    </div>
    <div class="log-box" id="log-box">点击刷新加载</div>
  </div>
</div>

<!-- 添加/编辑 Modal -->
<div class="modal" id="key-modal">
<div class="modal-box">
  <h3 id="modal-title">添加 Key</h3>
  <div class="modal-form">
    <label class="full">Alias（唯一标识）<input id="m-alias" placeholder="openai-main"></label>
    <label>Provider
      <select id="m-provider">
        <option value="openai">openai</option>
        <option value="anthropic">anthropic</option>
        <option value="deepseek">deepseek</option>
        <option value="dashscope">dashscope (阿里云)</option>
        <option value="miclaw">miclaw</option>
        <option value="custom">custom</option>
        <option value="local">local (ollama)</option>
      </select>
    </label>
    <label>Priority (1-10)<input id="m-priority" type="number" min="1" max="10" value="5"></label>
    <label class="full">Base URL<input id="m-url" placeholder="https://api.openai.com/v1"></label>
    <label class="full">API Key（填写后会加密存储）
      <div class="input-group">
        <input id="m-apikey" type="password" placeholder="sk-...">
        <button class="btn" onclick="toggleKeyVis()" id="key-vis-btn">显示</button>
      </div>
    </label>
    <label>Model<input id="m-model" placeholder="gpt-4o"></label>
    <label>Cost/1k tokens($)<input id="m-cost" type="number" step="0.00001" value="0.01"></label>
    <label style="grid-column:1/-1;flex-direction:row;align-items:center;gap:8px">
      <input id="m-enabled" type="checkbox" checked style="width:auto">
      <span>启用</span>
    </label>
  </div>
  <div class="modal-footer">
    <button class="btn" onclick="closeModal()">取消</button>
    <button class="btn btn-green" onclick="saveKey()">保存</button>
  </div>
</div>
</div>

<div class="toast" id="toast" style="display:none"></div>

<script>
const ADMIN_KEY = localStorage.getItem('tp_admin_key') || '';
let _keys = [];
let _editAlias = null;

async function apiFetch(path, opts={}) {
  const headers = {'X-Admin-Key': ADMIN_KEY, 'Content-Type': 'application/json', ...(opts.headers||{})};
  const r = await fetch(path, {...opts, headers});
  if (!r.ok) throw new Error(`${r.status}: ${await r.text()}`);
  return r.json();
}

function toast(msg, err=false) {
  const el = document.getElementById('toast');
  el.textContent = msg; el.className = 'toast' + (err?' err':'');
  el.style.display='block';
  setTimeout(()=>el.style.display='none', 2500);
}

async function loadStats() {
  try {
    const d = await apiFetch('/api/stats');
    document.getElementById('s-total').textContent = d.total_keys;
    document.getElementById('s-ok').textContent = d.working_keys;
    document.getElementById('s-cb').textContent = d.circuit_open;
    document.getElementById('s-tok').textContent = (d.total_tokens_all_time||0).toLocaleString();
    document.getElementById('s-cost').textContent = '$' + (d.total_cost_all_time||0).toFixed(6);
    document.getElementById('overview-text').textContent = `${d.working_keys}/${d.total_keys} 可用`;
  } catch(e) {
    if (!ADMIN_KEY) { promptAdminKey(); return; }
    toast('加载统计失败: '+e.message, true);
  }
}

function promptAdminKey() {
  const k = prompt('请输入管理 Key (TP_ADMIN_KEY):');
  if (k) { localStorage.setItem('tp_admin_key', k); location.reload(); }
}

async function loadKeys() {
  try {
    _keys = await apiFetch('/api/keys');
    renderKeys(_keys);
    // 填充日志alias选项
    const sel = document.getElementById('log-alias');
    const cur = sel.value;
    sel.innerHTML = '<option value="">全部</option>' + _keys.map(k=>`<option value="${k.alias}">${k.alias}</option>`).join('');
    sel.value = cur;
  } catch(e) { toast('加载Keys失败: '+e.message, true); }
}

function filterKeys() {
  const q = document.getElementById('filter-input').value.toLowerCase();
  renderKeys(q ? _keys.filter(k=>(k.alias+k.provider+k.model).toLowerCase().includes(q)) : _keys);
}

function _badge(status, circuit_open) {
  if (circuit_open) return '<span class="badge badge-open">⚡ 熔断</span>';
  if (status==='working') return '<span class="badge badge-ok">✅ 正常</span>';
  if (status==='failed') return '<span class="badge badge-fail">❌ 失败</span>';
  return '<span class="badge badge-unknown">? 未知</span>';
}

function renderKeys(keys) {
  const tbody = document.getElementById('keys-tbody');
  if (!keys.length) { tbody.innerHTML = '<tr><td colspan="9" style="text-align:center;color:#8b949e;padding:20px">暂无数据</td></tr>'; return; }
  tbody.innerHTML = keys.map(k=>`
    <tr>
      <td><b>${k.alias}</b>${k.has_key?'':' <span style="color:#d29922;font-size:10px">⚠ 缺Key</span>'}</td>
      <td>${k.provider}</td>
      <td style="color:#8b949e">${k.model}</td>
      <td>${_badge(k.status, k.circuit_open)}</td>
      <td style="color:#8b949e">${k.success_count}/<span style="color:#f85149">${k.fail_count}</span></td>
      <td>${k.avg_latency_ms ? k.avg_latency_ms.toFixed(0)+'ms' : '-'}</td>
      <td>$${(k.total_cost||0).toFixed(5)}</td>
      <td>${k.priority}</td>
      <td style="white-space:nowrap">
        <button class="btn btn-blue" onclick="probeKey('${k.alias}')" style="padding:3px 8px;font-size:11px">检测</button>
        <button class="btn" onclick="editKey('${k.alias}')" style="padding:3px 8px;font-size:11px">编辑</button>
        ${k.circuit_open?`<button class="btn" onclick="resetCircuit('${k.alias}')" style="padding:3px 8px;font-size:11px;color:#3fb950">恢复</button>`:''}
        <button class="btn btn-red" onclick="deleteKey('${k.alias}')" style="padding:3px 8px;font-size:11px">删</button>
      </td>
    </tr>`).join('');
}

async function loadLog() {
  const alias = document.getElementById('log-alias').value;
  try {
    const data = await apiFetch(`/api/stats/log?alias=${encodeURIComponent(alias)}&limit=80`);
    const box = document.getElementById('log-box');
    if (!data.length) { box.textContent = '暂无记录'; return; }
    box.textContent = data.map(r=>{
      const t = new Date(r.ts*1000).toLocaleTimeString('zh-CN',{hour12:false});
      const ok = r.success ? '✅' : '❌';
      return `[${t}] ${ok} ${r.alias.padEnd(24)} ${r.latency_ms?.toFixed(0).padStart(5)}ms  ${r.tokens}tok  $${(r.cost||0).toFixed(6)}  ${r.error||''}`;
    }).join('\n');
    box.scrollTop = box.scrollHeight;
  } catch(e) { toast('加载日志失败: '+e.message, true); }
}

function openAddModal() {
  _editAlias = null;
  document.getElementById('modal-title').textContent = '添加 Key';
  ['m-alias','m-url','m-apikey','m-model'].forEach(id=>document.getElementById(id).value='');
  document.getElementById('m-priority').value='5';
  document.getElementById('m-cost').value='0.01';
  document.getElementById('m-enabled').checked=true;
  document.getElementById('m-provider').value='openai';
  document.getElementById('key-modal').classList.add('show');
}

function editKey(alias) {
  const k = _keys.find(k=>k.alias===alias); if(!k) return;
  _editAlias = alias;
  document.getElementById('modal-title').textContent = '编辑 Key: '+alias;
  document.getElementById('m-alias').value = k.alias;
  document.getElementById('m-provider').value = k.provider;
  document.getElementById('m-url').value = k.base_url;
  document.getElementById('m-apikey').value = '';  // 不回显
  document.getElementById('m-model').value = k.model;
  document.getElementById('m-cost').value = k.cost_per_1k;
  document.getElementById('m-priority').value = k.priority;
  document.getElementById('m-enabled').checked = k.enabled;
  document.getElementById('key-modal').classList.add('show');
}

function closeModal() { document.getElementById('key-modal').classList.remove('show'); }

function toggleKeyVis() {
  const inp = document.getElementById('m-apikey');
  const btn = document.getElementById('key-vis-btn');
  inp.type = inp.type==='password' ? 'text' : 'password';
  btn.textContent = inp.type==='password' ? '显示' : '隐藏';
}

async function saveKey() {
  const alias = document.getElementById('m-alias').value.trim();
  const apiKey = document.getElementById('m-apikey').value.trim();
  if (!alias) { toast('alias 不能为空', true); return; }
  const body = {
    alias, provider: document.getElementById('m-provider').value,
    base_url: document.getElementById('m-url').value.trim(),
    api_key: apiKey,
    model: document.getElementById('m-model').value.trim(),
    cost_per_1k: parseFloat(document.getElementById('m-cost').value)||0,
    priority: parseInt(document.getElementById('m-priority').value)||5,
    enabled: document.getElementById('m-enabled').checked,
  };
  try {
    if (_editAlias) {
      await apiFetch(`/api/keys/${encodeURIComponent(_editAlias)}`, {method:'PUT', body:JSON.stringify(body)});
      if (apiKey) await apiFetch(`/api/keys/${encodeURIComponent(alias)}/key`, {method:'PATCH', body:JSON.stringify({api_key:apiKey})});
    } else {
      await apiFetch('/api/keys', {method:'POST', body:JSON.stringify(body)});
    }
    closeModal(); toast('保存成功'); loadAll();
  } catch(e) { toast('保存失败: '+e.message, true); }
}

async function deleteKey(alias) {
  if (!confirm(`确定删除 ${alias}？`)) return;
  try { await apiFetch(`/api/keys/${encodeURIComponent(alias)}`, {method:'DELETE'}); toast('已删除'); loadAll(); }
  catch(e) { toast('删除失败: '+e.message, true); }
}

async function probeKey(alias) {
  toast('检测中...');
  try {
    const r = await apiFetch(`/api/keys/${encodeURIComponent(alias)}/probe`, {method:'POST'});
    toast(r.ok ? `✅ ${alias} 正常 ${r.latency_ms.toFixed(0)}ms` : `❌ ${alias}: ${r.error}`, !r.ok);
    loadKeys();
  } catch(e) { toast('检测失败: '+e.message, true); }
}

async function resetCircuit(alias) {
  try {
    await apiFetch(`/api/keys/${encodeURIComponent(alias)}/reset_circuit`, {method:'POST'});
    toast('熔断已重置'); loadKeys();
  } catch(e) { toast('失败: '+e.message, true); }
}

async function probeAll() {
  const btn = document.getElementById('probe-btn');
  btn.textContent='检测中..'; btn.disabled=true;
  toast('全量检测中，请稍等...');
  try {
    await apiFetch('/api/keys/probe_all', {method:'POST'});
    toast('检测完成'); loadAll();
  } catch(e) { toast('失败: '+e.message, true); }
  finally { btn.textContent='🔍 全量检测'; btn.disabled=false; }
}

function loadAll() { loadStats(); loadKeys(); }

// 启动
if (!ADMIN_KEY) { promptAdminKey(); } else { loadAll(); setInterval(loadStats, 30000); }
</script>
</body>
</html>"""

@router.get("/", response_class=HTMLResponse)
@router.get("/admin", response_class=HTMLResponse)
def admin_panel(): return HTMLResponse(PANEL)

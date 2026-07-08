"""Token Pool Admin Panel"""
from fastapi import APIRouter
from fastapi.responses import HTMLResponse

router = APIRouter()

PANEL = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Token Pool</title>
<style>
:root{--bg:#f5f6f8;--surface:#fff;--border:#e8eaef;--border-light:#f0f1f4;--text:#1a1d23;--text-secondary:#6b7080;--text-tertiary:#9ca0ae;--accent:#4f6ef7;--accent-light:#eef1fe;--accent-hover:#3d5ce5;--green:#22c55e;--green-bg:#f0fdf4;--green-text:#15803d;--red:#ef4444;--red-bg:#fef2f2;--red-text:#b91c1c;--amber:#f59e0b;--amber-bg:#fffbeb;--amber-text:#b45309;--radius:12px;--radius-sm:8px;--shadow-sm:0 1px 2px rgba(0,0,0,.04);--shadow:0 1px 3px rgba(0,0,0,.06),0 1px 2px rgba(0,0,0,.04);--shadow-md:0 4px 6px rgba(0,0,0,.04),0 2px 4px rgba(0,0,0,.04);--font:system-ui,-apple-system,sans-serif;--mono:"SF Mono","Cascadia Code",monospace}
*{margin:0;padding:0;box-sizing:border-box}
body{font:14px/1.6 var(--font);background:var(--bg);color:var(--text);min-height:100vh;-webkit-font-smoothing:antialiased}
nav.tp-nav{background:var(--surface);border-bottom:1px solid var(--border);padding:0 32px;height:56px;display:flex;align-items:center;gap:32px;position:sticky;top:0;z-index:50;background:rgba(255,255,255,.88);backdrop-filter:blur(12px)}
nav.tp-nav .logo{font-size:16px;font-weight:700;color:var(--text);letter-spacing:-0.3px;display:flex;align-items:center;gap:8px}
nav.tp-nav .logo svg{width:22px;height:22px}
nav.tp-nav .links{display:flex;gap:4px;flex:1;overflow-x:auto}
nav.tp-nav .links a{padding:8px 16px;border-radius:8px;font-size:13px;font-weight:500;color:var(--text-secondary);cursor:pointer;transition:all .15s;text-decoration:none;white-space:nowrap}
nav.tp-nav .links a:hover{background:var(--bg);color:var(--text)}
nav.tp-nav .links a.active{background:var(--accent-light);color:var(--accent)}
nav.tp-nav .actions{display:flex;gap:8px;align-items:center}
.btn{padding:8px 18px;font-size:13px;font-weight:600;border-radius:8px;cursor:pointer;border:none;background:var(--accent);color:#fff;transition:all .15s;font-family:var(--font);height:38px;display:inline-flex;align-items:center;gap:6px}
.btn:hover{background:var(--accent-hover);transform:translateY(-1px);box-shadow:0 2px 8px rgba(79,110,247,.3)}
.btn:disabled{opacity:.5;transform:none;box-shadow:none}
.btn-outline{padding:8px 16px;font-size:13px;font-weight:500;border-radius:8px;cursor:pointer;background:var(--surface);color:var(--text-secondary);border:1px solid var(--border);font-family:var(--font);height:38px;transition:all .15s}
.btn-outline:hover{background:var(--bg);border-color:var(--accent);color:var(--accent)}
.btn-sm{padding:5px 12px;font-size:12px;border-radius:6px;background:transparent;color:var(--text-secondary);border:1px solid var(--border);cursor:pointer;font-family:var(--font);transition:all .15s;height:30px}
.btn-sm:hover{background:var(--bg);border-color:var(--accent);color:var(--accent)}
.btn-sm.danger{color:var(--red);border-color:transparent}.btn-sm.danger:hover{background:var(--red-bg);border-color:var(--red);color:var(--red)}
main{margin:0 auto;max-width:1280px;padding:32px}
.page{display:none}.page.active{display:block}
.stats-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:32px}
.stat-card{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:20px 24px;transition:all .2s;box-shadow:var(--shadow-sm)}
.stat-card:hover{box-shadow:var(--shadow-md);transform:translateY(-1px)}
.stat-card .label{font-size:12px;font-weight:500;color:var(--text-tertiary);text-transform:uppercase;letter-spacing:.5px;margin-bottom:8px}
.stat-card .value{font-size:32px;font-weight:700;letter-spacing:-0.5px;line-height:1}
.stat-card .sub{font-size:12px;color:var(--text-tertiary);margin-top:6px}
.panel{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);overflow:hidden;box-shadow:var(--shadow-sm);margin-bottom:24px}
.panel-header{padding:16px 24px;border-bottom:1px solid var(--border-light);display:flex;align-items:center;gap:16px}
.panel-header h2{font-size:15px;font-weight:600;flex:1}
.panel-body{padding:0}
table{width:100%;border-collapse:collapse}
th{padding:10px 16px;text-align:left;font-size:11px;font-weight:600;color:var(--text-tertiary);text-transform:uppercase;letter-spacing:.5px;background:var(--bg);border-bottom:1px solid var(--border)}
td{padding:10px 16px;border-bottom:1px solid var(--border-light);font-size:13px;vertical-align:middle}
tr:last-child td{border-bottom:none}tr:hover td{background:#fafbfc}
.status{display:inline-flex;align-items:center;gap:6px;padding:4px 10px;border-radius:20px;font-size:12px;font-weight:500}
.status::before{content:"";width:6px;height:6px;border-radius:50%}
.status-ok{background:var(--green-bg);color:var(--green-text)}.status-ok::before{background:var(--green)}
.status-err{background:var(--red-bg);color:var(--red-text)}.status-err::before{background:var(--red)}
.status-warn{background:var(--amber-bg);color:var(--amber-text)}.status-warn::before{background:var(--amber)}
.status-cb{background:#fef2f2;color:#b91c1c;border:1px solid #fecaca}.status-cb::before{background:var(--red)}
input,select{padding:9px 13px;font-size:13px;border:1px solid var(--border);border-radius:var(--radius-sm);background:var(--surface);color:var(--text);outline:none;font-family:var(--font);transition:border-color .15s;height:38px}
input:focus,select:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(79,110,247,.1)}
input[type=range]{padding:0;height:auto;accent-color:var(--accent)}
input[type=checkbox]{width:auto;height:auto;accent-color:var(--accent)}
.search-input{position:relative;width:240px}.search-input input{padding-left:36px;width:100%}
.search-input svg{position:absolute;left:12px;top:50%;transform:translateY(-50%);color:var(--text-tertiary)}
.empty-state{text-align:center;padding:48px 20px;color:var(--text-tertiary);font-size:13px}
.spinner{display:inline-block;width:16px;height:16px;border:2px solid var(--border);border-top-color:var(--accent);border-radius:50%;animation:spin .6s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
.login-screen{display:flex;align-items:center;justify-content:center;min-height:100vh;background:linear-gradient(135deg,#f5f6f8 0%,#eef1fe 100%)}
.login-card{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:48px 40px;width:400px;max-width:92vw;box-shadow:0 8px 32px rgba(0,0,0,.06);text-align:center}
.login-card h1{font-size:24px;font-weight:700;letter-spacing:-0.5px;margin-bottom:4px}
.login-card p{color:var(--text-tertiary);font-size:14px;margin-bottom:28px}
.login-card input{width:100%;margin-bottom:14px;text-align:center;font-size:14px;height:46px;border-radius:10px}
.login-card button{width:100%;height:46px;font-size:15px;font-weight:600;border-radius:10px}
.login-err{display:none;color:var(--red);font-size:13px;margin-top:12px}
.modal-overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,.3);z-index:100;align-items:center;justify-content:center;padding:24px;backdrop-filter:blur(4px)}
.modal-overlay.show{display:flex}
.modal-box{background:var(--surface);border:1px solid var(--border);border-radius:16px;width:560px;max-width:100%;max-height:90vh;overflow-y:auto;box-shadow:0 20px 60px rgba(0,0,0,.12);animation:modalIn .2s ease}
@keyframes modalIn{from{opacity:0;transform:scale(.96) translateY(8px)}to{opacity:1;transform:scale(1) translateY(0)}}
.modal-header{padding:20px 24px;border-bottom:1px solid var(--border-light);display:flex;align-items:center;justify-content:space-between}
.modal-header h3{font-size:16px;font-weight:600}
.modal-close{background:none;border:none;font-size:20px;color:var(--text-tertiary);cursor:pointer;padding:4px 8px;border-radius:6px}.modal-close:hover{background:var(--bg);color:var(--text)}
.modal-body{padding:20px 24px;display:grid;grid-template-columns:1fr 1fr;gap:16px}
.modal-body .full{grid-column:1/-1}
.modal-body label{display:flex;flex-direction:column;gap:5px;font-size:12px;font-weight:500;color:var(--text-secondary)}
.modal-footer{padding:16px 24px;border-top:1px solid var(--border-light);display:flex;gap:10px;justify-content:flex-end}
.log-box{background:var(--bg);border-radius:var(--radius-sm);padding:16px;font:12px/1.8 var(--mono);max-height:260px;overflow-y:auto;white-space:pre;color:var(--text-secondary)}
.chart-box{background:var(--bg);border-radius:var(--radius-sm);padding:16px;height:160px;display:flex;align-items:flex-end;gap:2px;overflow-x:auto}
.toast-container{position:fixed;bottom:24px;right:24px;z-index:200;display:flex;flex-direction:column;gap:8px}
.toast{padding:12px 20px;border-radius:10px;font-size:13px;animation:slideIn .3s ease;background:var(--surface);border:1px solid var(--border);color:var(--text);box-shadow:0 8px 24px rgba(0,0,0,.1);max-width:400px;font-weight:500}
.toast.error{border-color:#fecaca;background:var(--red-bg);color:var(--red-text)}
@keyframes slideIn{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:translateY(0)}}
@media(max-width:768px){nav.tp-nav{padding:0 16px;gap:16px}nav.tp-nav .links{gap:0}main{padding:20px 16px}.stats-grid{grid-template-columns:1fr 1fr;gap:10px}.stat-card{padding:16px}.stat-card .value{font-size:24px}.modal-body{grid-template-columns:1fr}}
</style>
</head>
<body>
<div class="login-screen" id="login-screen">
  <div class="login-card">
    <h1>Token Pool</h1>
    <p>LLM API Key 管理与调度</p>
    <input id="login-key" type="password" placeholder="管理密钥" autofocus onkeydown="if(event.key==='Enter')doLogin()">
    <button class="btn" onclick="doLogin()" id="btn-login">登录</button>
    <div class="login-err" id="login-err"></div>
  </div>
</div>

<div id="app-main" style="display:none">
<nav class="tp-nav">
  <div class="logo"><svg viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>Token Pool</div>
  <div class="links">
    <a class="active" data-page="overview">概览</a>
    <a data-page="keys">Key 管理</a>
    <a data-page="sold">售出 Key</a>
    <a data-page="free">营销 Key</a>
    <a data-page="users">乌托邦token池</a>
    <a data-page="miclaw">MiClaw</a>
    <a data-page="logs">调用日志</a>
  </div>
  <div class="actions"><button class="btn btn-outline" onclick="logout()">退出</button></div>
</nav>

<main>
<div class="page active" id="page-overview">
  <div class="stats-grid" id="stats-grid"></div>
  <div class="panel"><div class="panel-header"><h2>Key 评分</h2></div><div class="panel-body"><div class="stats-grid" id="scores-grid" style="grid-template-columns:repeat(auto-fill,minmax(240px,1fr));padding:16px;gap:12px"></div></div></div>
</div>

<div class="page" id="page-keys">
  <div class="panel"><div class="panel-header"><h2>Key 列表</h2><div class="search-input"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><input id="filter-keys" placeholder="搜索..." oninput="renderKeys()"></div><button class="btn" onclick="openKeyModal()">+ 添加</button><button class="btn-outline" onclick="probeAll()" id="btn-probe">全量检测</button></div>
  <div class="panel-body"><table><thead><tr><th>Alias</th><th>Provider</th><th>Model</th><th>状态</th><th>成功/失败</th><th>延迟</th><th>费用</th><th>优先级</th><th>操作</th></tr></thead><tbody id="keys-tbody"></tbody></table></div></div>
</div>

<div class="page" id="page-sold">
  <div style="display:flex;align-items:center;gap:16px;margin-bottom:24px"><h1 style="font-size:26px;font-weight:700">售出 Key</h1></div>
  <div class="stats-grid" id="sold-stats"></div>
  <div class="panel" style="margin-bottom:16px"><div class="panel-header"><h2>批量倍率调整</h2></div>
  <div style="padding:16px 24px;display:flex;gap:10px;align-items:center;flex-wrap:wrap">
    <select id="batch-type" style="width:120px"><option value="key">Key倍率</option><option value="model">模型倍率</option></select>
    <input id="batch-target" placeholder="目标(空=全部)" style="width:140px"><input id="batch-model" placeholder="模型名" style="width:140px">
    <input id="batch-mult" type="number" step="0.1" value="1.0" style="width:80px"><button class="btn" onclick="batchMult()">批量应用</button>
  </div></div>
  <div class="panel"><div class="panel-header"><h2>售出 Key 列表</h2><button class="btn" onclick="openSoldModal()">+ 添加</button></div>
  <div class="panel-body"><table><thead><tr><th>用户ID</th><th>Alias</th><th>Key倍率</th><th>模型(倍率)</th><th>余额</th><th>充值</th><th>用量</th><th style="width:100px">操作</th></tr></thead><tbody id="sold-tbody"></tbody></table></div></div>
</div>

<div class="page" id="page-free">
  <div style="display:flex;align-items:center;gap:16px;margin-bottom:24px"><h1 style="font-size:26px;font-weight:700">营销 Key</h1></div>
  <div class="stats-grid" id="free-stats"></div>
  <div class="panel" style="margin-bottom:16px"><div class="panel-header"><h2>AI 指令</h2></div>
  <div style="padding:16px 24px;display:flex;gap:10px"><input id="ai-cmd" placeholder="设置总量100000 每日10000 速率10" style="flex:1" onkeydown="if(event.key==='Enter')runAiCmd()"><button class="btn" onclick="runAiCmd()" id="btn-ai">执行</button></div>
  <div id="ai-result" style="padding:0 24px 16px;font-size:12px;color:var(--text-secondary)"></div></div>
  <div class="panel"><div class="panel-header"><h2>营销 Key 列表</h2><button class="btn-outline" onclick="resetAllFree()">批量重置</button></div>
  <div class="panel-body"><table><thead><tr><th>Code</th><th>设备码</th><th>IP</th><th>总量/已用</th><th>今日/已用</th><th>速率</th><th>状态</th><th style="width:140px">操作</th></tr></thead><tbody id="free-tbody"></tbody></table></div></div>
</div>

<div class="page" id="page-users">
  <div class="panel"><div class="panel-header"><h2>乌托邦token池</h2>
    <span id="users-filter-bar" style="display:flex;gap:4px;margin-right:12px">
      <button class="btn-sm" style="background:var(--accent);color:#fff" onclick="setUserFilter('all')" id="uf-all">全部key</button>
      <button class="btn-sm" onclick="setUserFilter('working')" id="uf-working">可用key</button>
    </span>
    <button class="btn-outline" onclick="probeAllUsers()" id="btn-probe-users">一键检测全部</button>
    <button class="btn-outline" onclick="loadUsers()">刷新</button>
  </div>
  <div class="panel-body"><table><thead><tr><th>设备码</th><th>状态</th><th>Key</th><th>URL</th><th>模型</th><th>操作</th></tr></thead><tbody id="users-tbody"></tbody></table></div></div>
</div>

<div class="page" id="page-miclaw">
  <div class="stats-grid" id="pool-stats"></div>
  <div class="panel"><div class="panel-header"><h2>MiClaw 账号池</h2>
    <button class="btn-outline" onclick="poolLoginAll()" id="btn-login-all">全部登录</button>
    <button class="btn-outline" onclick="poolProbeAll()" id="btn-probe-all">全部检测</button>
    <button class="btn-outline" onclick="loadMiclaw()">刷新</button>
  </div>
  <div class="panel-body"><table><thead><tr><th>手机号</th><th>调试码</th><th>状态</th><th>归属</th><th>Token输出</th><th>速率(RPM/TPM/QPS)</th><th>并发</th><th>每日</th><th>操作</th></tr></thead><tbody id="miclaw-tbody"></tbody></table></div></div>
</div>

<div class="page" id="page-logs">
  <div class="panel"><div class="panel-header"><h2>调用日志</h2><select id="log-alias" onchange="loadLog();loadChart()" style="width:160px"><option value="">全部</option></select><select id="log-hours" onchange="loadChart()" style="width:120px"><option value="6">6h</option><option value="12">12h</option><option value="24" selected>24h</option><option value="48">48h</option></select></div>
  <div class="panel-body"><div class="chart-box" id="chart-box"><span style="color:var(--text-tertiary);align-self:center;margin:auto;font-size:12px">加载中...</span></div><div class="log-box" id="log-box">加载中...</div></div></div>
</div>
</main>
</div>

<div class="modal-overlay" id="key-modal"><div class="modal-box"><div class="modal-header"><h3 id="modal-title">添加 Key</h3><button class="modal-close" onclick="closeKeyModal()">&times;</button></div><div class="modal-body">
<label class="full">Alias<input id="m-alias" placeholder="openai-gpt4o"></label>
<label>Provider<select id="m-provider"><option value="openai">OpenAI</option><option value="anthropic">Anthropic</option><option value="deepseek">DeepSeek</option><option value="dashscope">DashScope</option><option value="miclaw">MiClaw</option><option value="custom">自定义</option><option value="local">本地</option></select></label>
<label>Priority<input id="m-priority" type="number" min="1" max="10" value="5"></label>
<label class="full">Base URL<input id="m-url" placeholder="https://api.openai.com/v1"></label>
<label class="full">API Key<div style="display:flex;gap:0"><input id="m-apikey" type="password" placeholder="sk-..."><button class="btn-sm" onclick="toggleKeyVis()" id="btn-key-vis">显示</button></div></label>
<label>Model<input id="m-model" placeholder="gpt-4o"></label>
<label>$/1k tokens<input id="m-cost" type="number" step="0.00001" value="0.01"></label>
<label class="full" style="flex-direction:row;align-items:center;gap:8px"><input type="checkbox" id="m-enabled" checked> 启用</label>
</div><div class="modal-footer"><button class="btn-sm" onclick="closeKeyModal()">取消</button><button class="btn" onclick="saveKey()" id="btn-save-key">保存</button></div></div></div>

<div class="modal-overlay" id="sold-modal"><div class="modal-box"><div class="modal-header"><h3 id="sold-modal-title">添加售出 Key</h3><button class="modal-close" onclick="closeSoldModal()">&times;</button></div><div class="modal-body">
<label class="full">用户ID<input id="sm-user" placeholder="用户唯一标识"></label>
<label class="full">Alias<input id="sm-alias" placeholder="唯一别名"></label>
<label class="full">API Key<input id="sm-key" type="password" placeholder="sk-..."></label>
<label>Key倍率<input id="sm-km" type="number" step="0.1" value="1.0"></label>
<label>初始余额<input id="sm-bal" type="number" step="0.01" value="0"></label>
<label class="full">模型(name:倍率,逗号分隔)<input id="sm-models" placeholder="gpt-4o:1.0,claude:1.5"></label>
</div><div class="modal-footer"><button class="btn-sm" onclick="closeSoldModal()">取消</button><button class="btn" onclick="saveSoldKey()" id="btn-save-sold">保存</button></div></div></div>

<div class="modal-overlay" id="confirm-modal"><div class="modal-box" style="width:400px"><div class="modal-header"><h3 id="confirm-title">确认</h3></div><div style="padding:20px 24px"><p id="confirm-msg" style="color:var(--text)"></p></div><div class="modal-footer"><button class="btn-sm" onclick="closeConfirm()">取消</button><button class="btn btn-danger" id="confirm-ok" style="background:var(--red)">确认</button></div></div></div>

<div class="toast-container" id="toasts"></div>

<script>
var AK=localStorage.getItem("tp_admin_key")||"",_keys=[],_edit=null;
function $(id){return document.getElementById(id)}

/* ⛔ LOCK: 登录验证 — 禁止修改 ⛔ */
async function doLogin(){var k=$("login-key").value.trim();if(!k)return;var b=$("btn-login");b.disabled=true;b.textContent="验证中...";$("login-err").style.display="none";localStorage.setItem("tp_admin_key",k);AK=k;try{var r=await fetch("/token-pool/api/stats",{headers:{"X-Admin-Key":k}});if(r.ok){$("login-screen").style.display="none";$("app-main").style.display="block";loadOverview();setInterval(function(){if($("page-overview").classList.contains("active"))loadOverview()},30000)}else{localStorage.removeItem("tp_admin_key");AK="";$("login-err").textContent="密钥无效 ("+r.status+")";$("login-err").style.display="block"}}catch(e){localStorage.removeItem("tp_admin_key");AK="";$("login-err").textContent="连接失败: "+e.message;$("login-err").style.display="block"}finally{b.disabled=false;b.textContent="登录"}}

/* ⛔ LOCK: 登出 — 禁止修改 ⛔ */
/* ⛔ END LOCK ⛔ */

function logout(){localStorage.removeItem("tp_admin_key");AK="";$("app-main").style.display="none";$("login-screen").style.display="flex";$("login-key").value=""}
/* ⛔ END LOCK ⛔ */
async function api(p,o){o=o||{};o.headers=Object.assign({"X-Admin-Key":AK,"Content-Type":"application/json"},o.headers||{});var r=await fetch("/token-pool"+p,o);if(!r.ok){var t;try{t=await r.json()}catch(e){t={detail:r.statusText}}throw new Error(t.detail||"HTTP "+r.status)}return r.json().catch(function(){return null})}
function toast(m,e){var d=document.createElement("div");d.className="toast"+(e?" error":"");d.textContent=m;$("toasts").appendChild(d);setTimeout(function(){d.style.opacity="0";d.style.transition="opacity .3s";setTimeout(function(){d.remove()},300)},3000)}
function showConfirm(t,m,cb){$("confirm-title").textContent=t;$("confirm-msg").textContent=m;$("confirm-ok").onclick=function(){closeConfirm();cb()};$("confirm-modal").classList.add("show")}
function closeConfirm(){$("confirm-modal").classList.remove("show")}
function spin(el){el.innerHTML="<div class=\"empty-state\"><span class=\"spinner\"></span></div>"}
function none(el,m){el.innerHTML="<div class=\"empty-state\">"+(m||"no data")+"</div>"}

document.querySelectorAll("nav.tp-nav .links a").forEach(function(a){a.addEventListener("click",function(){document.querySelectorAll("nav.tp-nav .links a").forEach(function(x){x.classList.remove("active")});a.classList.add("active");var p=a.dataset.page;document.querySelectorAll(".page").forEach(function(x){x.classList.remove("active")});$("page-"+p).classList.add("active");if(p==="overview")loadOverview();else if(p==="keys")loadKeys();else if(p==="sold")loadSoldKeys();else if(p==="free")loadFreeKeys();else if(p==="users")loadUsers();else if(p==="miclaw")loadMiclaw();else if(p==="logs"){loadLog();loadChart()}})});

async function loadOverview(){try{var d=await api("/api/stats");$("stats-grid").innerHTML="<div class=\"stat-card\"><div class=\"label\">Key 总数</div><div class=\"value\">"+d.total_keys+"</div></div><div class=\"stat-card\"><div class=\"label\">正常</div><div class=\"value\" style=\"color:var(--green)\">"+d.working_keys+"</div></div><div class=\"stat-card\"><div class=\"label\">熔断中</div><div class=\"value\" style=\"color:var(--red)\">"+d.circuit_open+"</div></div><div class=\"stat-card\"><div class=\"label\">Token 总消耗</div><div class=\"value\">"+(d.total_tokens_all_time||0).toLocaleString()+"</div><div class=\"sub\">$"+(d.total_cost_all_time||0).toFixed(6)+"</div></div>";var m=d.metrics_5m||{},ks=Object.keys(m).sort(function(a,b){return(m[b]&&m[b].success_rate||0)-(m[a]&&m[a].success_rate||0)}).slice(0,6);$("scores-grid").innerHTML=ks.map(function(k){var v=m[k]||{},r=((v.success_rate||0)*100).toFixed(0),c=r>80?"var(--green-text)":r>50?"var(--amber-text)":"var(--red-text)";return"<div class=\"stat-card\"><div class=\"label\">"+k.slice(0,22)+"</div><div class=\"value\" style=\"font-size:24px;color:"+c+"\">"+r+"%</div><div class=\"sub\">"+(v.avg_latency||0).toFixed(0)+"ms | "+(v.rpm||0).toFixed(0)+" rpm</div></div>"}).join("")||"<div class=\"stat-card\"><div class=\"label\">评分</div><div class=\"sub\">暂无数据</div></div>"}catch(e){if(!AK){logout();return}toast(""+e.message,1)}}

async function loadKeys(){var t=$("keys-tbody");spin(t);try{_keys=await api("/api/keys");var s=$("log-alias"),c=s.value;s.innerHTML="<option value=\"\">全部</option>"+_keys.map(function(k){return"<option value=\""+k.alias+"\">"+k.alias+"</option>"}).join("");s.value=c;renderKeys()}catch(e){toast(""+e.message,1);none(t,"加载失败")}}
function renderKeys(){var q=($("filter-keys")&&$("filter-keys").value||"").toLowerCase(),ks=q?_keys.filter(function(k){return(k.alias+k.provider+k.model).toLowerCase().indexOf(q)>=0}):_keys,t=$("keys-tbody");if(!ks.length){none(t,_keys.length?"无匹配":"暂无 Key");return}t.innerHTML=ks.map(function(k){var b=k.circuit_open?"<span class=\"status status-cb\">熔断</span>":k.status==="working"?"<span class=\"status status-ok\">正常</span>":k.status==="failed"?"<span class=\"status status-err\">失败</span>":"<span class=\"status status-warn\">未知</span>",m=k.has_key?"":" <span style=\"color:var(--amber-text);font-size:11px\">缺Key</span>",r=k.circuit_open?"<button type=\"button\" class=\"btn-sm\" style=\"color:var(--green)\" onclick=\"api('/api/keys/'+encodeURIComponent('"+k.alias+"')+'/reset_circuit',{method:'POST'}).then(function(){loadKeys()})\">恢复</button>":"";return"<tr><td><strong>"+k.alias+"</strong>"+m+"</td><td>"+k.provider+"</td><td style=\"color:var(--text-tertiary)\">"+k.model+"</td><td>"+b+"</td><td>"+k.success_count+"/<span style=\"color:var(--red)\">"+k.fail_count+"</span></td><td>"+(k.avg_latency_ms?k.avg_latency_ms.toFixed(0)+"ms":"-")+"</td><td>$"+(k.total_cost||0).toFixed(5)+"</td><td>"+k.priority+"</td><td style=\"white-space:nowrap\">"+r+"<button type=\"button\" class=\"btn-sm\" onclick=\"probeKey('"+k.alias+"',this)\">检测</button><button type=\"button\" class=\"btn-sm\" onclick=\"editKey('"+k.alias+"')\">编辑</button><button type=\"button\" class=\"btn-sm danger\" onclick=\"if(confirm('确认删除?'))api('/api/keys/'+encodeURIComponent('"+k.alias+"'),{method:'DELETE'}).then(function(){loadKeys()})\">删除</button></td></tr>"}).join("")}
async function probeKey(alias,btn){btn.disabled=true;btn.textContent='...';try{var r=await api('/api/keys/'+encodeURIComponent(alias)+'/probe',{method:'POST'});toast(r.ok?'可用 '+r.latency_ms.toFixed(0)+'ms':'不可用: '+r.error,!r.ok);loadKeys()}catch(e){toast(''+e.message,1)}finally{btn.disabled=false;btn.textContent='检测'}}
function openKeyModal(){_edit=null;$("modal-title").textContent="添加 Key";["m-alias","m-url","m-apikey","m-model"].forEach(function(id){$(id).value=""});$("m-priority").value="5";$("m-cost").value="0.01";$("m-enabled").checked=true;$("m-provider").value="openai";$("key-modal").classList.add("show")}
function editKey(alias){var k=_keys.find(function(x){return x.alias===alias});if(!k){toast("Key未加载,请刷新",1);return;}_edit=alias;$("modal-title").textContent="编辑: "+alias;$("m-alias").value=k.alias;$("m-provider").value=k.provider;$("m-url").value=k.base_url;$("m-apikey").value="";$("m-model").value=k.model;$("m-cost").value=k.cost_per_1k;$("m-priority").value=k.priority;$("m-enabled").checked=k.enabled;$("key-modal").classList.add("show")}
function closeKeyModal(){$("key-modal").classList.remove("show")}
function toggleKeyVis(){var i=$("m-apikey"),b=$("btn-key-vis");i.type=i.type==="password"?"text":"password";b.textContent=i.type==="password"?"显示":"隐藏"}
async function saveKey(){var a=$("m-alias").value.trim();if(!a){toast("Alias required",1);return}var body={alias:a,provider:$("m-provider").value,base_url:$("m-url").value.trim(),api_key:$("m-apikey").value.trim(),model:$("m-model").value.trim(),cost_per_1k:parseFloat($("m-cost").value)||0,priority:parseInt($("m-priority").value)||5,enabled:$("m-enabled").checked},btn=$("btn-save-key");btn.disabled=true;btn.textContent="保存中...";try{if(_edit){await api("/api/keys/"+encodeURIComponent(_edit),{method:"PUT",body:JSON.stringify(body)});if(body.api_key)await api("/api/keys/"+encodeURIComponent(a)+"/key",{method:"PATCH",body:JSON.stringify({api_key:body.api_key})})}else{await api("/api/keys",{method:"POST",body:JSON.stringify(body)})}closeKeyModal();toast("已保存");refresh()}catch(e){toast(""+e.message,1)}finally{btn.disabled=false;btn.textContent="保存"}}
async function probeAll(){var b=$("btn-probe");b.disabled=true;b.textContent="...";try{await api("/api/keys/probe_all",{method:"POST"});toast("完成");await loadKeys()}catch(e){toast(""+e.message,1)}finally{b.disabled=false;b.textContent="全量检测"}}

// ── Sold Keys ──
async function loadSoldKeys(){var t=$("sold-tbody");spin(t);try{var d=await api("/api/sold-keys");$("sold-stats").innerHTML="<div class=\"stat-card\"><div class=\"label\">总数</div><div class=\"value\">"+d.length+"</div></div><div class=\"stat-card\"><div class=\"label\">活跃</div><div class=\"value\" style=\"color:var(--green)\">"+d.filter(function(x){return x.status==="active"}).length+"</div></div><div class=\"stat-card\"><div class=\"label\">总余额</div><div class=\"value\">$"+d.reduce(function(s,x){return s+(x.balance||0)},0).toFixed(2)+"</div></div><div class=\"stat-card\"><div class=\"label\">累计充值</div><div class=\"value\">$"+d.reduce(function(s,x){return s+(x.total_recharged||0)},0).toFixed(2)+"</div></div>";if(!d.length){none(t,"暂无");return}t.innerHTML=d.map(function(k){var ms=[];if(k.models)for(var m in k.models)ms.push(m+":"+k.models[m]);var st=k.status==="active"?"<span class=\"status status-ok\">活跃</span>":"<span class=\"status status-err\">禁用</span>";return"<tr><td><strong>"+k.user_id+"</strong></td><td style=\"font-family:var(--mono);font-size:12px\">"+k.key_alias+"</td><td>"+k.key_multiplier.toFixed(1)+"x</td><td style=\"font-size:11px\">"+ms.map(function(m){return"<span style=\"background:var(--accent-light);color:var(--accent);padding:2px 6px;border-radius:4px;font-size:10px;cursor:pointer\" onclick=\"soldDetail('"+k.key_alias+"',this)\">"+m+"</span>"}).join(" ")+"</td><td style=\"color:"+(k.balance>0?"var(--green)":"var(--red)")+"\">$"+k.balance.toFixed(2)+"</td><td>$"+k.total_recharged.toFixed(2)+"</td><td><button class=\"btn-sm\" onclick=\"soldDetail('"+k.key_alias+"',this)\">详情</button></td></tr>"}).join("")}catch(e){toast(""+e.message,1);none(t,"加载失败")}}
async function soldDetail(alias,btn){var row=btn.closest("tr"),next=row.nextElementSibling;if(next&&next.classList.contains("detail-row")){next.remove();return}btn.disabled=true;btn.textContent="...";try{var d=await api("/api/sold-keys/"+alias+"/detail"),html="<tr class=\"detail-row\" style=\"background:var(--bg)\"><td colspan=\"7\" style=\"padding:16px 24px\"><div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px\">";if(d.usage&&d.usage.length){d.usage.forEach(function(u){var rate=6.8;html+="<div style=\"background:var(--surface);border:1px solid var(--border);border-radius:8px;padding:14px\"><div style=\"font-weight:600;font-size:13px;margin-bottom:8px\">"+u.model+"</div><div style=\"display:grid;grid-template-columns:1fr 1fr;gap:8px;font-size:12px\"><div><span style=\"color:var(--text-tertiary)\">今日 Token</span><div>"+u.today_tokens.toLocaleString()+"</div></div><div><span style=\"color:var(--text-tertiary)\">今日费用</span><div>&yen;"+(u.today_cost*rate).toFixed(4)+"</div></div><div><span style=\"color:var(--text-tertiary)\">总 Token</span><div>"+u.total_tokens.toLocaleString()+"</div></div><div><span style=\"color:var(--text-tertiary)\">总费用</span><div>&yen;"+(u.total_cost*rate).toFixed(4)+"</div></div></div></div>"})}else{html+="<span style=\"color:var(--text-tertiary)\">暂无用量数据</span>"}html+="</div></td></tr>";row.insertAdjacentHTML("afterend",html)}catch(e){toast(""+e.message,1)}finally{btn.disabled=false;btn.textContent="详情"}}

async function loadFreeKeys(){var t=$("free-tbody");spin(t);try{var d=await api("/api/free-keys");$("free-stats").innerHTML="<div class=\"stat-card\"><div class=\"label\">总数</div><div class=\"value\">"+d.length+"</div></div><div class=\"stat-card\"><div class=\"label\">活跃</div><div class=\"value\" style=\"color:var(--green)\">"+d.filter(function(x){return x.status==="active"}).length+"</div></div><div class=\"stat-card\"><div class=\"label\">总消耗</div><div class=\"value\">"+d.reduce(function(s,x){return s+(x.used_total||0)},0).toLocaleString()+"</div></div><div class=\"stat-card\"><div class=\"label\">今日消耗</div><div class=\"value\">"+d.reduce(function(s,x){return s+(x.used_today||0)},0).toLocaleString()+"</div></div>";if(!d.length){none(t,"暂无");return}t.innerHTML=d.map(function(k){var up=k.total_limit>0?((k.used_total/k.total_limit)*100).toFixed(0):0,dp=k.daily_limit>0?((k.used_today/k.daily_limit)*100).toFixed(0):0,st=k.status==="active"?"<span class=\"status status-ok\">活跃</span>":"<span class=\"status status-err\">禁用</span>";return"<tr><td style=\"font-family:var(--mono);font-size:12px\">"+k.code+"</td><td style=\"font-size:12px;color:var(--text-tertiary)\">"+(k.device_code||"").slice(0,16)+"</td><td style=\"font-size:12px\">"+k.ip_address+"</td><td><input type=\"number\" value=\""+k.total_limit+"\" style=\"width:120px;height:26px;font-size:11px\"> <span style=\"font-size:11px\">"+k.used_total.toLocaleString()+"</span></td><td><input type=\"number\" value=\""+k.daily_limit+"\" style=\"width:120px;height:26px;font-size:11px\"> <span style=\"font-size:11px\">"+k.used_today.toLocaleString()+"</span></td><td><input type=\"number\" value=\""+k.rpm_limit+"\" style=\"width:55px;height:26px;font-size:11px\"></td><td>"+st+"</td><td><button class=\"btn-sm\">重置</button><button class=\"btn-sm\">"+(k.status==="active"?"禁用":"启用")+"</button></td></tr>"}).join("")}catch(e){toast(""+e.message,1);none(t,"加载失败")}}
async function resetAllFree(){try{var r=await api("/api/free-keys/refresh-all",{method:"POST"});toast("已重置 "+r.refreshed+" 个");loadFreeKeys()}catch(e){toast(""+e.message,1)}}
async function runAiCmd(){var c=$("ai-cmd").value.trim();if(!c)return;var b=$("btn-ai");b.disabled=true;b.textContent="...";try{var r=await api("/api/free-keys/ai-command",{method:"POST",body:JSON.stringify({command:c})});$("ai-result").innerHTML=r.results.map(function(x){return"<div>"+x+"</div>"}).join("");toast("完成");loadFreeKeys()}catch(e){toast(""+e.message,1)}finally{b.disabled=false;b.textContent="执行"}}

// ── Users / Utopia ──
var _userFilter="all";
function setUserFilter(f){_userFilter=f;$("uf-all").style.background=f==="all"?"var(--accent)":"transparent";$("uf-all").style.color=f==="all"?"#fff":"var(--text-secondary)";$("uf-working").style.background=f==="working"?"var(--accent)":"transparent";$("uf-working").style.color=f==="working"?"#fff":"var(--text-secondary)";loadUsers()}
function keyStatusBadge(t){if(t.ok)return'<span class="status status-ok">✔ 可用</span>';if(t.msg)return'<span class="status status-err">✘ '+t.msg.slice(0,30)+'</span>';return''}
async function loadUsers(){var t=$("users-tbody");spin(t);try{var d=await api("/api/shared-keys/legacy/tokens");d=d.tokens||[];var wkd=0;var filtered=_userFilter==="working"?d.filter(function(x){return x.key_test.ok}):d;$("uf-all").textContent="全部key ("+d.length+")";$("uf-working").textContent="可用key";$("uf-working").textContent="可用key ("+wkd+")";if(!filtered.length){none(t,_userFilter==="working"?"暂无可用key":"暂无");return}t.innerHTML=filtered.map(function(u){var kt=u.key_test||{};return'<tr><td title="'+u.code+'" style="font-family:var(--mono);font-size:12px">'+(u.code||"").slice(0,16)+'</td><td>'+keyStatusBadge(kt)+'</td><td style="max-width:200px"><div style="font-family:var(--mono);font-size:11px;word-break:break-all;max-height:32px;overflow:hidden;text-overflow:ellipsis">'+(u.api_key||"-")+'</div><button class="btn-sm" onclick="navigator.clipboard.writeText(this.previousElementSibling.textContent);this.textContent=\'\u5df2\u590d\u5236\';setTimeout(function(){this.textContent=\'\u590d\u5236\'},1500)" style="margin-top:2px;font-size:10px;padding:1px 7px">复制</button></td><td style="max-width:200px"><div style="font-size:11px;word-break:break-all;max-height:28px;overflow:hidden">'+(u.api_base_url||"-")+'</div><button class="btn-sm" onclick="navigator.clipboard.writeText(this.previousElementSibling.textContent);this.textContent=\'\u5df2\u590d\u5236\';setTimeout(function(){this.textContent=\'\u590d\u5236\'},1500)" style="margin-top:2px;font-size:10px;padding:1px 7px">复制</button> <button class="btn-sm" onclick="editUrl(\''+u.code+'\',this)" style="margin-top:2px;font-size:10px;padding:1px 7px;background:var(--amber)">修改</button></td><td><div>'+(u.model_name||"-")+'</div><div id="um-'+u.code+'" style="font-size:10px;color:var(--text-tertiary);margin-top:2px"></div></td><td><button type="button" class="btn-sm" onclick="probeUser(\''+u.code+'\',this)">检测</button></td></tr>'}).join("")}catch(e){toast(""+e.message,1);none(t,"加载失败")}}
async function probeAllUsers(){var b=$("btn-probe-users");if(b.disabled)return;b.disabled=true;b.textContent="准备...";var rows=document.querySelectorAll("#users-tbody tr");var codes=[];rows.forEach(function(r){var td=r.querySelector("td[title]");if(td)codes.push(td.getAttribute("title"));});if(!codes.length){b.disabled=false;b.textContent="一键检测全部";return;}var total=codes.length,done=0,ok=0;function updateProgress(){b.textContent="检测中 "+done+"/"+total;}function finish(){b.disabled=false;b.textContent="一键检测全部("+ok+"可用)";updateUserCounts();}updateProgress();var queue=codes.slice();function next(){if(!queue.length){finish();return;}var c=queue.shift();api("/api/shared-keys/legacy/test-key?code="+encodeURIComponent(c),{method:"POST"}).then(function(r){var kt=r.key_test||{};ok+=kt.ok?1:0;var td=document.querySelector("tr td[title='"+c+"']"),row=td?td.closest("tr"):null,cell=row?row.cells[1]:null;if(cell)cell.innerHTML=keyStatusBadge(kt);}).catch(function(){}).finally(function(){done++;updateProgress();next();});}for(var i=0;i<Math.min(3,codes.length);i++)next();}
async function probeUser(c,b){b.disabled=true;b.textContent="检测中...";try{var r=await api("/api/shared-keys/legacy/test-key?code="+encodeURIComponent(c),{method:"POST"}),kt=r.key_test||{};var el=$("um-"+c);if(el&&kt.models&&kt.models.length){el.textContent="模型: "+kt.models.join(", ")}var row=b.closest("tr"),td=row?row.cells[1]:null;if(td)td.innerHTML=keyStatusBadge(kt);toast(kt.ok?("可用 - "+kt.msg.slice(0,40)):"不可用: "+kt.msg,!!kt.msg);updateUserCounts()}catch(e){toast(""+e.message,1)}finally{b.disabled=false;b.textContent="检测"}}
function editUrl(c,b){var td=b.closest("td");var div=td.querySelector("div");var cur=div.textContent;td.innerHTML='<input id="eu-'+c+'" value="'+cur.replace(/\"/g,"&quot;")+'" style="width:100%;font-size:11px;padding:3px 5px;margin-bottom:4px;background:var(--bg);border:1px solid var(--border);color:var(--text);border-radius:4px"><button class="btn-sm" onclick="saveUrl(\''+c+'\')" style="background:var(--green);font-size:10px;padding:2px 8px">保存</button> <button class="btn-sm" onclick="loadUsers()" style="font-size:10px;padding:2px 8px">取消</button>'}
async function saveUrl(c){var inp=$("eu-"+c);var url=inp.value.trim();if(!url){toast("URL不能为空",1);return}try{await api("/api/shared-keys/"+encodeURIComponent(c)+"/url",{method:"POST",body:JSON.stringify({base_url:url})});toast("已更新");loadUsers()}catch(e){toast(""+e.message,1)}}

// ── MiClaw Pool ──
async function loadPoolStats(){
  try {
    var d=await api("/api/shared-keys/miclaw-pool/stats");
    var pct=d.total>0?(d.logged_in/d.total*100).toFixed(0):0;
    $("pool-stats").innerHTML=
      '<div class="stat-card"><div class="label">总账号</div><div class="value">'+d.total+'</div></div>'+
      '<div class="stat-card"><div class="label">已登录</div><div class="value" style="color:var(--green)">'+d.logged_in+'</div><div class="sub">'+pct+'%</div></div>'+
      '<div class="stat-card"><div class="label">待处理</div><div class="value" style="color:var(--amber-text)">'+d.pending+'</div></div>'+
      '<div class="stat-card"><div class="label">失败</div><div class="value" style="color:var(--red)">'+d.failed+'</div></div>'+
      '<div class="stat-card"><div class="label">今日Token</div><div class="value">'+(d.total_tokens_today||0).toLocaleString()+'</div></div>'+
      '<div class="stat-card"><div class="label">今日调用</div><div class="value">'+(d.total_calls_today||0).toLocaleString()+'</div></div>'+
      '<div class="stat-card"><div class="label">Bridge状态</div><div class="value" style="font-size:18px">'+(d.bridge_ok?'\u2705':'\u274C')+'</div></div>'+
      '<div class="stat-card"><div class="label">成功率</div><div class="value">'+((d.total_success+d.total_fail)>0?(d.total_success/(d.total_success+d.total_fail)*100).toFixed(0):'-')+'%</div></div>';
  }catch(e){}
}
async function poolLoginAll(){
  var b=$("btn-login-all");b.disabled=true;b.textContent="登录中...";
  try{var r=await api("/api/shared-keys/miclaw-pool/login-all",{method:"POST"});toast(r.logged_in+" 登录, "+r.failed+" 失败"+(r.two_factor?" , "+r.two_factor+" 需二步验证":""),r.failed>0);loadMiclaw();loadPoolStats()}catch(e){toast(""+e.message,1)}finally{b.disabled=false;b.textContent="全部登录"}
}
async function poolProbeAll(){
  var b=$("btn-probe-all");b.disabled=true;b.textContent="检测中...";
  try{var r=await api("/api/shared-keys/miclaw-pool/probe-all",{method:"POST"});toast(r.healthy+"/"+r.total+" 健康",r.dead>0);loadMiclaw();loadPoolStats()}catch(e){toast(""+e.message,1)}finally{b.disabled=false;b.textContent="全部检测"}
}
async function accountLogin(id){
  var btn=event.target;btn.disabled=true;btn.textContent="登录中...";
  try{var r=await api("/api/shared-keys/miclaw-accounts/"+id+"/login",{method:"POST"});toast(r.ok?(r.outcome==="two_factor_required"?"需二步验证":"已登录"):r.error,!r.ok);loadMiclaw();loadPoolStats()}catch(e){toast(""+e.message,1)}finally{btn.disabled=false;btn.textContent="登录"}
}
async function accountLogout(id){
  if(!confirm("确认登出此账号?"))return;
  try{await api("/api/shared-keys/miclaw-accounts/"+id+"/logout",{method:"POST"});toast("已登出");loadMiclaw();loadPoolStats()}catch(e){toast(""+e.message,1)}
}

async function loadMiclaw(){var t=$("miclaw-tbody");spin(t);try{var d=await api("/api/shared-keys/miclaw-accounts");if(!d.length){none(t,"暂无");return}t.innerHTML=d.map(function(a){var s=a.login_status==="logged_in"?"<span class=\"status status-ok\">已登录</span>":a.login_status==="active"?"<span class=\"status status-ok\">活跃</span>":a.login_status==="pending"?"<span class=\"status status-warn\">待验证</span>":"<span class=\"status status-err\">"+a.login_status+"</span>";return"<tr><td><strong>"+(a.miclaw_account||a.username)+"</strong>"+(a.has_password?"":" <span style=\"color:var(--amber-text);font-size:10px\">缺密码</span>")+"</td><td style=\"font-family:var(--mono);font-size:11px\">"+(a.device_code||"-")+"</td><td>"+s+"</td><td>"+(a.owner_user_code||"-")+"</td><td>"+(a.tokens_used||0).toLocaleString()+"</td><td><input type=\"number\" value=\""+(a.rpm_limit||50)+"\" style=\"width:55px;height:24px;font-size:10px\" title=\"RPM\">/<input type=\"number\" value=\""+(a.tpm_limit||50000)+"\" style=\"width:65px;height:24px;font-size:10px\" title=\"TPM\">/<input type=\"number\" value=\""+(a.qps_limit||3)+"\" style=\"width:45px;height:24px;font-size:10px\" title=\"QPS\"></td><td><input type=\"number\" value=\""+(a.concurrent_limit||2)+"\" style=\"width:45px;height:24px;font-size:10px\" title=\"并发\"></td><td><input type=\"number\" value=\""+(a.daily_limit||500)+"\" style=\"width:60px;height:24px;font-size:10px\" title=\"每日限额\"></td><td><button class=\"btn-sm\" onclick=\"miclawLogin("+a.id+")\">登录</button> <button class=\"btn-sm\" onclick=\"miclawProbe("+a.id+",this)\">检测</button></td></tr>"}).join("")}catch(e){toast(""+e.message,1);none(t,"加载失败")}}


/* ⛔ LOCK: MiClaw 登录函数 — 禁止修改 ⛔ */
function miclawLogin(id){
  var iframe=$("miclaw-login-iframe");
  iframe.src="/token-pool/api/miclaw/login-page?account_id="+id;
  $("miclaw-login-modal").classList.add("show");
}
function closeMiclawModal(){
  $("miclaw-login-modal").classList.remove("show");
  $("miclaw-login-iframe").src="";
}
/* ⛔ END LOCK ⛔ */
async function miclawProbe(id,btn){btn.disabled=true;btn.textContent="检测中...";try{var r=await api("/api/shared-keys/miclaw-accounts/"+id+"/probe");toast(r.ok?"可用 "+r.latency_ms.toFixed(0)+"ms":"不可用: "+r.error,!r.ok);loadMiclaw()}catch(e){toast(""+e.message,1)}finally{btn.disabled=false;btn.textContent="检测"}}
// ── Logs ──
async function loadLog(){var b=$("log-box"),a=$("log-alias").value;spin(b);try{var d=await api("/api/stats/log?alias="+encodeURIComponent(a)+"&limit=80");if(!d.length){b.textContent="暂无记录";return}b.textContent=d.map(function(r){var al=r.key_alias||r.alias||"",lat=r.latency_ms||0,tok=r.total_tokens||r.tokens||0,cost=r.cost||0,ok=r.success===1||r.success===true,err=r.error_msg||r.error||"";return"["+new Date(r.ts*1000).toLocaleTimeString("zh-CN",{hour12:false})+"] "+(ok?"OK":"FAIL")+" "+al.padEnd(22)+" "+lat.toFixed(0).padStart(5)+"ms  "+(tok+"").padStart(6)+"tok  $"+cost.toFixed(6)+(err?"  "+err:"")}).join("\n");b.scrollTop=b.scrollHeight}catch(e){toast(""+e.message,1)}}
async function loadChart(){var b=$("chart-box"),a=$("log-alias").value,h=$("log-hours").value;try{var d=await api("/api/stats/log/hourly?alias="+encodeURIComponent(a)+"&hours="+h);if(!d.length){b.innerHTML="<span style=\"color:var(--text-tertiary);align-self:center;margin:auto;font-size:12px\">暂无数据</span>";return}var mx=Math.max.apply(null,d.map(function(x){return x.tokens}))||1;b.innerHTML=d.map(function(r){var hr=new Date(r.hour*1000).getHours(),p=(r.tokens/mx*100).toFixed(0),c=r.fail>0&&r.ok===0?"var(--red)":r.ok>0?"var(--green)":"var(--border)";return"<div style=\"flex:1;min-width:14px;display:flex;flex-direction:column;align-items:center;font-size:9px\"><span style=\"color:var(--text-tertiary);margin-bottom:2px\">"+(r.tokens>999?(r.tokens/1000).toFixed(0)+"k":r.tokens)+"</span><div style=\"width:100%;background:"+c+";height:"+Math.max(p,2)+"%;border-radius:2px 2px 0 0\" title=\""+new Date(r.hour*1000).toLocaleString()+": "+r.ok+" OK / "+r.fail+" FAIL\"></div><span style=\"color:var(--text-tertiary);margin-top:2px\">"+hr+"h</span></div>"}).join("")}catch(e){toast(""+e.message,1)}}

$("key-modal").addEventListener("click",function(e){if(e.target===this)closeKeyModal()});
$("sold-modal").addEventListener("click",function(e){if(e.target===this)closeSoldModal()});
$("confirm-modal").addEventListener("click",function(e){if(e.target===this)closeConfirm()});
$("miclaw-login-modal").addEventListener("click",function(e){if(e.target===this)closeMiclawModal()});
document.addEventListener("keydown",function(e){if(e.key==="Escape"){closeKeyModal();closeSoldModal();closeConfirm()}});


/* ⛔ LOCK: MiClaw 登录成功监听 — 禁止修改 ⛔ */
window.addEventListener('message', function(e){
  if (e.data && e.data.type === 'miclaw_login_success') {
    closeMiclawModal();
    toast('登录成功', false);
    loadMiclaw();
  }
});
/* ⛔ END LOCK ⛔ */
function updateUserCounts(){var ok=0,total=0;document.querySelectorAll("#users-tbody .status").forEach(function(s){total++;if(s.classList.contains("status-ok"))ok++;});$("uf-all").textContent="全部key ("+total+")";$("uf-working").textContent="可用key ("+ok+")";}
function refresh(){var t=document.querySelector(".page.active").id.replace("page-","");if(t==="overview")loadOverview();else if(t==="keys")loadKeys();else if(t==="sold")loadSoldKeys();else if(t==="free")loadFreeKeys();else if(t==="users")loadUsers();else if(t==="miclaw"){loadMiclaw();loadPoolStats()}else if(t==="logs"){loadLog();loadChart()}}

document.addEventListener("DOMContentLoaded",function(){(function(){if(AK){$("login-screen").style.display="none";$("app-main").style.display="block";loadOverview();setInterval(function(){if($("page-overview").classList.contains("active"))loadOverview()},30000)}})()})
</script>

<!-- ⛔ LOCK: MiClaw 登录模态框 — 禁止修改 ⛔ -->
<div class="modal-overlay" id="miclaw-login-modal">
  <div class="modal-box" style="width:420px">
    <div class="modal-header">
      <h3>MiClaw 账号登录</h3>
      <button class="modal-close" onclick="closeMiclawModal()">&times;</button>
    </div>
    <div style="padding:0">
      <iframe id="miclaw-login-iframe" src="" style="width:100%;height:420px;border:none;border-radius:0 0 12px 12px" sandbox="allow-scripts allow-same-origin allow-forms allow-popups"></iframe>
    </div>
  </div>
</div>
<!-- ⛔ END LOCK ⛔ -->

</body>
</html>"""

@router.get("/", response_class=HTMLResponse)
@router.get("/admin", response_class=HTMLResponse)
def admin_panel(): return HTMLResponse(PANEL)

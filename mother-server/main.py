"""MBclaw Mother Server — 统一入口 v2.0"""
from __future__ import annotations
import os, asyncio, logging, hashlib, secrets, json, time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Cookie, Request, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from config import cfg

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
log = logging.getLogger("mbclaw")

for w in cfg.validate(): log.info("CONFIG: %s", w)

# 数据目录
Path(cfg.data_dir).mkdir(parents=True, exist_ok=True)
for sub in ("mother", "mother/events", "mother/episodes", "mother/evolution_reports", "mother/archive"):
    Path(cfg.data_dir, sub).mkdir(parents=True, exist_ok=True)

# ── Admin Session ──
ADMIN_DB = Path(cfg.data_dir) / "admin.json"
SESSIONS_DB = Path(cfg.data_dir) / "admin_sessions.json"

def _load(p, default):
    if p.exists():
        try: return json.loads(p.read_text())
        except: pass
    return default

def _save(p, d): p.write_text(json.dumps(d, ensure_ascii=False, indent=2))

def _admin_init():
    if not ADMIN_DB.exists():
        salt = secrets.token_hex(8)
        h = hashlib.sha256(f"{salt}:{cfg.admin_password}".encode()).hexdigest()
        _save(ADMIN_DB, {"username": "admin", "salt": salt, "hash": h, "created_at": int(time.time())})

_admin_init()

def _check_session(sid: str | None) -> bool:
    if not sid: return False
    s = _load(SESSIONS_DB, {})
    item = s.get(sid)
    if not item: return False
    if item.get("expires_at", 0) < time.time():
        del s[sid]; _save(SESSIONS_DB, s); return False
    return True

# ── App ──
@asynccontextmanager
async def lifespan(_app: FastAPI):
    log.info("✅ 数据目录: %s", cfg.data_dir)
    try:
        from mother.evolution.daily import run_forever
        asyncio.create_task(run_forever())
        log.info("✅ 进化循环已启动")
    except Exception as e: log.warning("进化循环: %s", e)
    log.info("🚀 MBclaw Mother Server v2.0 — %s:%s", cfg.host, cfg.port)
    yield
    log.info("MBclaw 关闭")

app = FastAPI(title="MBclaw Mother Server", version="2.0.0", lifespan=lifespan,
              docs_url="/api/docs", redoc_url="/api/redoc")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"], allow_credentials=True)

# ── 路由 ──
from mother.mother_api import router as mother_router
app.include_router(mother_router)

# ── Admin API ──
from pydantic import BaseModel

class LoginReq(BaseModel):
    username: str = "admin"; password: str

@app.post("/admin/api/login")
def api_login(req: LoginReq):
    d = _load(ADMIN_DB, {})
    if req.username != d.get("username", "admin"): raise HTTPException(401, "账号或密码错误")
    h = hashlib.sha256(f"{d['salt']}:{req.password}".encode()).hexdigest()
    if h != d.get("hash"): raise HTTPException(401, "账号或密码错误")
    sid = secrets.token_urlsafe(32)
    s = _load(SESSIONS_DB, {})
    s[sid] = {"created_at": int(time.time()), "expires_at": int(time.time()) + 7*86400}
    _save(SESSIONS_DB, s)
    from fastapi.responses import Response
    resp = JSONResponse({"ok": True})
    resp.set_cookie("mb_admin", sid, max_age=7*86400, httponly=True, samesite="lax")
    return resp

@app.post("/admin/api/logout")
def api_logout(mb_admin: str = Cookie(default=None)):
    s = _load(SESSIONS_DB, {})
    if mb_admin and mb_admin in s: del s[mb_admin]; _save(SESSIONS_DB, s)
    from fastapi.responses import Response
    resp = JSONResponse({"ok": True}); resp.delete_cookie("mb_admin"); return resp

class ChangePwdReq(BaseModel):
    old_password: str; new_password: str

@app.post("/admin/api/change-password")
def api_change_pwd(req: ChangePwdReq, mb_admin: str = Cookie(default=None)):
    if not _check_session(mb_admin): raise HTTPException(401)
    d = _load(ADMIN_DB, {})
    h = hashlib.sha256(f"{d['salt']}:{req.old_password}".encode()).hexdigest()
    if h != d.get("hash"): raise HTTPException(401, "原密码错误")
    if len(req.new_password) < 6: raise HTTPException(400, "新密码至少6位")
    salt = secrets.token_hex(8)
    d.update(salt=salt, hash=hashlib.sha256(f"{salt}:{req.new_password}".encode()).hexdigest())
    _save(ADMIN_DB, d); return {"ok": True}

# ── Health ──
@app.get("/health")
def health():
    from mother.token_pool.client import get_tp_client
    tp = get_tp_client().health() if cfg.token_pool_url else {}
    return {"status": "ok", "version": "2.0.0", "db_ok": Path(cfg.resolved_db_path()).exists() if cfg.db_path else True, "llm_configured": bool(cfg.best_llm_key() or cfg.token_pool_url), "token_pool": tp, "owner": cfg.owner_name}

# ── Admin Panel ──
_PANEL = """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><title>MBclaw</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>*{margin:0;padding:0;box-sizing:border-box}body{font:13px system-ui;background:#0d1117;color:#c9d1d9}
.nav{background:#161b22;padding:12px 20px;border-bottom:1px solid #30363d;display:flex;gap:12px;align-items:center}
.nav h1{font-size:15px;color:#58a6ff;font-weight:700}.nav a{color:#8b949e;text-decoration:none;font-size:12px}.nav a:hover{color:#c9d1d9}
.main{padding:16px;max-width:1000px;margin:0 auto}
.tabs{display:flex;gap:4px;margin-bottom:16px;border-bottom:1px solid #30363d}
.tab{padding:8px 16px;cursor:pointer;color:#8b949e;font-size:13px;border-bottom:2px solid transparent}
.tab.active{color:#58a6ff;border-color:#58a6ff}.panel{display:none}.panel.active{display:block}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:14px;margin-bottom:12px}
.card h3{font-size:12px;color:#8b949e;margin-bottom:8px;text-transform:uppercase}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:10px}
input,textarea,select{width:100%;padding:8px;background:#0d1117;border:1px solid #30363d;border-radius:6px;color:#c9d1d9;font:13px system-ui;margin-bottom:8px;outline:none}
input:focus,textarea:focus{border-color:#58a6ff}
button{padding:7px 14px;border:1px solid #30363d;background:#21262d;color:#c9d1d9;border-radius:6px;cursor:pointer;font:13px system-ui}
button:hover{background:#30363d}.btn-green{background:#1a3d20;border-color:#2ea043;color:#3fb950}
pre{background:#0d1117;padding:10px;border-radius:6px;font:12px monospace;overflow:auto;max-height:400px;color:#8b949e;white-space:pre-wrap}
.badge{display:inline-block;padding:2px 8px;border-radius:10px;font-size:11px}
.ok{background:#1f3d1f;color:#3fb950}.fail{background:#3d1f1f;color:#f85149}.warn{background:#3d2d00;color:#d29922}
.msg-list{max-height:400px;overflow-y:auto;display:flex;flex-direction:column;gap:8px}
.msg{padding:8px 12px;border-radius:8px;max-width:80%;font-size:13px;line-height:1.5}
.msg.user{background:#1f3a5f;align-self:flex-end}.msg.assistant{background:#1f3d1f;align-self:flex-start}
.input-row{display:flex;gap:8px;margin-top:8px}.input-row input{margin-bottom:0}
table{width:100%;border-collapse:collapse;font-size:12px}
th{padding:8px;text-align:left;color:#8b949e;font-weight:500;background:#0d1117;border-bottom:1px solid #30363d}
td{padding:8px;border-bottom:1px solid #21262d}
</style></head><body>
<div class="nav"><h1>🧠 MBclaw Mother</h1>
<a href="#" onclick="switchTab('chat')">对话</a><a href="#" onclick="switchTab('memory')">记忆</a>
<a href="#" onclick="switchTab('goals')">目标</a><a href="#" onclick="switchTab('evolution')">进化</a>
<a href="#" onclick="switchTab('system')">系统</a><a href="/api/docs" target="_blank">API</a>
<span style="margin-left:auto;color:#8b949e;font-size:12px" id="nav-status">连接中...</span>
<a href="#" onclick="logout()" style="color:#f85149">退出</a></div>
<div class="main">
<div class="panel active" id="tab-chat"><div class="card"><h3>与母体对话</h3>
<div class="msg-list" id="msg-list"></div><div class="input-row">
<input id="chat-input" placeholder="输入消息..." onkeydown="if(event.key==='Enter'){sendChat()}">
<button class="btn-green" onclick="sendChat()">发送</button><button onclick="resetCtx()">重置</button></div>
<div style="font-size:11px;color:#8b949e;margin-top:6px" id="ctx-stats"></div></div></div>
<div class="panel" id="tab-memory"><div class="grid2"><div class="card"><h3>记忆搜索</h3>
<div style="display:flex;gap:8px"><input id="mem-q" placeholder="搜索..." style="margin-bottom:0"><button onclick="searchMem()">搜索</button></div>
<pre id="mem-results">—</pre></div><div class="card"><h3>添加知识</h3>
<input id="kn-key" placeholder="键"><textarea id="kn-val" placeholder="值" rows="3"></textarea>
<select id="kn-cat"><option value="fact">事实</option><option value="rule">规则</option><option value="procedure">流程</option></select>
<button onclick="addKnowledge()">保存</button></div></div>
<div class="card"><h3>最近经验 <button onclick="loadExperiences()" style="float:right;font-size:11px">刷新</button></h3><div id="exp-list"><pre>点击刷新</pre></div></div>
<div class="card"><h3>情节记录 <button onclick="loadEpisodes()" style="float:right;font-size:11px">刷新</button></h3><div id="ep-list"><pre>点击刷新</pre></div></div></div>
<div class="panel" id="tab-goals"><div class="grid2"><div class="card"><h3>添加目标</h3>
<input id="goal-title" placeholder="标题"><textarea id="goal-desc" placeholder="描述" rows="2"></textarea>
<input id="goal-priority" type="number" value="5" min="1" max="10"><button class="btn-green" onclick="addGoal()">添加</button></div>
<div class="card"><h3>过滤</h3><select id="goal-status-filter" onchange="loadGoals()"><option value="">全部</option><option value="active">进行中</option><option value="completed">已完成</option></select><button onclick="loadGoals()">刷新</button></div></div>
<div class="card" id="goals-list"><pre>加载中...</pre></div></div>
<div class="panel" id="tab-evolution"><div class="grid2"><div class="card"><h3>进化状态</h3><div id="evo-state"><pre>加载中...</pre></div></div>
<div class="card"><h3>操作</h3><button onclick="triggerEvo()">触发进化</button><pre style="margin-top:8px;font-size:11px" id="evo-msg">—</pre></div></div>
<div class="card"><h3>历史报告 <button onclick="loadEvoReports()" style="float:right;font-size:11px">刷新</button></h3><div id="evo-reports"><pre>点击刷新</pre></div></div>
<div class="card"><h3>事件日志 <button onclick="loadEvents()" style="float:right;font-size:11px">刷新</button></h3><pre id="events-log">点击刷新</pre></div></div>
<div class="panel" id="tab-system"><div class="grid2"><div class="card"><h3>服务状态</h3><pre id="sys-health">加载中...</pre></div>
<div class="card"><h3>Token Pool</h3><pre id="sys-tp">加载中...</pre></div></div>
<div class="card"><h3>修改密码</h3><input id="pwd-old" type="password" placeholder="旧密码">
<input id="pwd-new" type="password" placeholder="新密码"><button onclick="changePwd()">修改</button>
<span id="pwd-msg" style="font-size:12px;color:#3fb950;margin-left:8px"></span></div></div>
</div>
<script>
let _sid=0;const API='';
async function af(p,o={}){const r=await fetch(API+p,{headers:{'Content-Type':'application/json'},...o});if(!r.ok)throw new Error(await r.text());return r.json()}
function toast(m,e){document.getElementById('nav-status').textContent=m;document.getElementById('nav-status').style.color=e?'#f85149':'#3fb950';setTimeout(()=>document.getElementById('nav-status').style.color='#8b949e',3000)}
function switchTab(t){document.querySelectorAll('.panel').forEach(p=>p.classList.remove('active'));document.getElementById('tab-'+t).classList.add('active');if(t==='system')loadSystem();if(t==='memory')loadExperiences();if(t==='goals')loadGoals();if(t==='evolution'){loadEvoState();loadEvoReports()}}
async function sendChat(){const m=document.getElementById('chat-input').value.trim();if(!m)return;document.getElementById('chat-input').value='';addMsg('user',m);addMsg('assistant','思考中...');try{const r=await af('/api/mother/run',{method:'POST',body:JSON.stringify({goal:m,session_id:_sid})});_sid=r.session_id||_sid;document.querySelectorAll('.msg.assistant').forEach((el,i,arr)=>{if(i===arr.length-1)el.textContent=r.reply});if(r.ctx_stats)document.getElementById('ctx-stats').textContent='上下文: '+(r.ctx_stats.total_tokens||0)+'/'+(r.ctx_stats.limit||8000)}catch(e){document.querySelectorAll('.msg.assistant').forEach((el,i,arr)=>{if(i===arr.length-1)el.textContent='错误: '+e.message})}}
function addMsg(r,c){const l=document.getElementById('msg-list'),d=document.createElement('div');d.className='msg '+r;d.textContent=c;l.appendChild(d);l.scrollTop=l.scrollHeight}
async function resetCtx(){await af('/api/mother/reset',{method:'POST'});document.getElementById('msg-list').innerHTML='';_sid=0;toast('已重置')}
async function searchMem(){const q=document.getElementById('mem-q').value;const r=await af('/api/mother/memory/recall?q='+encodeURIComponent(q)+'&n=8');document.getElementById('mem-results').textContent=r.hits.map(h=>'['+h.layer+'|'+h.score.toFixed(2)+'] '+h.content.substring(0,150)).join('\n\n')||'无结果'}
async function addKnowledge(){const k=document.getElementById('kn-key').value.trim(),v=document.getElementById('kn-val').value.trim(),c=document.getElementById('kn-cat').value;if(!k||!v)return;await af('/api/mother/memory/knowledge',{method:'POST',body:JSON.stringify({key:k,value:v,category:c})});document.getElementById('kn-key').value='';document.getElementById('kn-val').value='';toast('已保存')}
async function loadExperiences(){const r=await af('/api/mother/memory/experience?limit=10');document.getElementById('exp-list').innerHTML='<pre>'+r.data.map(e=>'['+e.kind+'] '+e.title+'\n  '+e.content.substring(0,100)).join('\n\n')+'</pre>'}
async function loadEpisodes(){const r=await af('/api/mother/memory/episodes?limit=10');document.getElementById('ep-list').innerHTML='<pre>'+r.data.map(e=>'['+e.status+'] '+e.goal.substring(0,60)+'\n  '+new Date(e.started_at*1000).toLocaleString('zh-CN')).join('\n\n')+'</pre>'}
async function addGoal(){const t=document.getElementById('goal-title').value.trim();if(!t)return;await af('/api/mother/goals',{method:'POST',body:JSON.stringify({title:t,description:document.getElementById('goal-desc').value,priority:parseInt(document.getElementById('goal-priority').value)||5})});document.getElementById('goal-title').value='';document.getElementById('goal-desc').value='';toast('已添加');loadGoals()}
async function loadGoals(){const s=document.getElementById('goal-status-filter').value;const r=await af('/api/mother/goals'+(s?'?status='+s:''));const el=document.getElementById('goals-list');if(!r.goals.length){el.innerHTML='<pre>暂无目标</pre>';return}el.innerHTML='<table><thead><tr><th>标题</th><th>状态</th><th>优先级</th><th>进度</th></tr></thead><tbody>'+r.goals.map(g=>'<tr><td><b>'+g.title+'</b><br><span style="color:#8b949e;font-size:11px">'+g.description.substring(0,60)+'</span></td><td><span class="badge '+(g.status==='completed'?'ok':g.status==='active'?'warn':'fail')+'">'+g.status+'</span></td><td>'+g.priority+'</td><td>'+g.progress+'%</td></tr>').join('')+'</tbody></table>'}
async function loadEvoState(){const r=await af('/api/mother/evolution/state');document.getElementById('evo-state').innerHTML='<pre>'+JSON.stringify(r.state,null,2)+'</pre>'}
async function loadEvoReports(){const r=await af('/api/mother/evolution/state');document.getElementById('evo-reports').innerHTML='<pre>'+r.recent_reports.map(rp=>'['+rp.date+'] 健康分:'+rp.health_score+' - '+rp.summary.substring(0,100)).join('\n')+'</pre>'}
async function triggerEvo(){await af('/api/mother/evolution/trigger',{method:'POST'});document.getElementById('evo-msg').textContent='进化已启动'}
async function loadEvents(){const r=await af('/api/mother/events?limit=30');document.getElementById('events-log').textContent=r.events.slice(-30).reverse().map(e=>'['+e.event_type+'] '+JSON.stringify(e.payload).substring(0,80)).join('\n')}
async function loadSystem(){try{const r=await af('/health');document.getElementById('sys-health').textContent=JSON.stringify(r,null,2)}catch(e){document.getElementById('sys-health').textContent=e.message}try{const r=await af('/api/mother/token_pool/health');document.getElementById('sys-tp').textContent=JSON.stringify(r,null,2)}catch(e){document.getElementById('sys-tp').textContent='未连接'}}
async function changePwd(){const o=document.getElementById('pwd-old').value,n=document.getElementById('pwd-new').value;if(!o||!n)return;try{await af('/admin/api/change-password',{method:'POST',body:JSON.stringify({old_password:o,new_password:n})});document.getElementById('pwd-msg').textContent='成功'}catch(e){document.getElementById('pwd-msg').textContent='失败';document.getElementById('pwd-msg').style.color='#f85149'}}
function logout(){fetch('/admin/api/logout',{method:'POST'}).then(()=>location.href='/admin/login')}
async function init(){try{const h=await af('/health');document.getElementById('nav-status').textContent='v'+h.version+' | '+h.owner}catch(e){document.getElementById('nav-status').textContent='连接失败'}}init();
</script></body></html>"""

_LOGIN = """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><title>MBclaw</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>*{margin:0;padding:0;box-sizing:border-box}body{font:14px system-ui;background:#0d1117;color:#c9d1d9;display:flex;align-items:center;justify-content:center;min-height:100vh}
.card{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:32px 28px;width:360px}
h1{font-size:20px;color:#f0f6fc;margin-bottom:4px}p.sub{color:#8b949e;font-size:13px;margin-bottom:24px}
label{display:block;margin-bottom:14px}label span{display:block;font-size:12px;color:#8b949e;margin-bottom:4px}
input{width:100%;padding:10px 12px;background:#0d1117;border:1px solid #30363d;border-radius:6px;color:#c9d1d9;font:inherit;outline:none}
input:focus{border-color:#58a6ff}button{width:100%;padding:10px;border:none;border-radius:6px;background:#238636;color:#fff;font:inherit;font-weight:600;cursor:pointer}
button:hover{background:#2ea043}.err{margin-top:12px;color:#f85149;font-size:13px}
</style></head><body><div class="card"><h1>MBclaw Admin</h1><p class="sub">管理员登录</p>
<form onsubmit="doLogin(event)"><label><span>账号</span><input type="text" id="u" required></label>
<label><span>密码</span><input type="password" id="p" required></label>
<button type="submit">登录</button></form><div id="err" class="err"></div></div>
<script>async function doLogin(e){e.preventDefault();
var r=await fetch('/admin/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:document.getElementById('u').value,password:document.getElementById('p').value})});
if(r.ok){window.location.href='/admin'}else{var d=await r.json();document.getElementById('err').textContent=d.detail||'失败'}}</script></body></html>"""

@app.get("/", response_class=HTMLResponse)
@app.get("/admin", response_class=HTMLResponse)
@app.get("/admin/", response_class=HTMLResponse)
def admin_panel(mb_admin: str = Cookie(default=None)):
    if not _check_session(mb_admin): return RedirectResponse("/admin/login", status_code=302)
    return HTMLResponse(_PANEL)

@app.get("/admin/login", response_class=HTMLResponse)
def login_page(): return HTMLResponse(_LOGIN)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=cfg.host, port=cfg.port, reload=False)

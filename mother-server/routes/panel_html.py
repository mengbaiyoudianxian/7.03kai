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
async function sendChat(){const m=document.getElementById('chat-input').value.trim();if(!m)return;document.getElementById('chat-input').value='';addMsg('user',m);addMsg('assistant','思考中...');try{const r=await af('/gateway/web/chat',{method:'POST',body:JSON.stringify({goal:m,session_id:_sid})});_sid=r.session_id||_sid;document.querySelectorAll('.msg.assistant').forEach((el,i,arr)=>{if(i===arr.length-1)el.textContent=r.reply})}catch(e){document.querySelectorAll('.msg.assistant').forEach((el,i,arr)=>{if(i===arr.length-1)el.textContent='错误: '+e.message})}}
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

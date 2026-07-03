# 新版admin HTML — 用户列表+公告+反馈+共建
NEW_ADMIN_HTML = r'''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>MBclaw Admin v5.2</title>
<style>
:root{--bg:#0d1117;--s:#161b22;--b:#30363d;--t:#c9d1d9;--m:#8b949e;--a:#58a6ff;--g:#3fb950;--r:#f85149;--o:#d2991d;--purple:#a371f7}
*{margin:0;padding:0;box-sizing:border-box}body{font:13px system-ui,sans-serif;background:var(--bg);color:var(--t);display:flex;min-height:100vh}
nav{width:200px;background:var(--s);border-right:1px solid var(--b);padding:16px 0;position:fixed;top:0;left:0;bottom:0;overflow-y:auto}
nav h2{padding:0 16px 16px;font-size:14px;display:flex;align-items:center;gap:8px}
nav a{display:flex;align-items:center;gap:8px;padding:8px 16px;color:var(--m);text-decoration:none;font-size:12.5px;border-left:2px solid transparent;cursor:pointer}
nav a:hover,nav a.active{color:var(--t);background:rgba(88,166,255,.06);border-left-color:var(--a)}
main{margin-left:200px;flex:1;padding:24px}
h1{font-size:18px;margin-bottom:4px}h1+p{color:var(--m);font-size:12px;margin-bottom:20px}
.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:20px}
.card{background:var(--s);border:1px solid var(--b);border-radius:8px;padding:16px}
.card .l{font-size:11px;color:var(--m);text-transform:uppercase;letter-spacing:.5px}
.card .v{font-size:26px;font-weight:700;margin:4px 0;font-family:monospace}
table{width:100%;border-collapse:collapse;font-size:12px}
th{text-align:left;padding:8px 12px;color:var(--m);font-weight:600;border-bottom:2px solid var(--b);font-size:11px}
td{padding:8px 12px;border-bottom:1px solid var(--b)}tr:hover{background:rgba(255,255,255,.02)}
.badge{display:inline-block;padding:2px 8px;border-radius:8px;font-size:10px;font-weight:600}
.badge-ok{background:rgba(63,185,80,.15);color:var(--g)}.badge-err{background:rgba(248,81,73,.15);color:var(--r)}
.badge-warn{background:rgba(210,153,29,.15);color:var(--o)}.badge-purple{background:rgba(163,113,247,.15);color:var(--purple)}
.mono{font-family:monospace;font-size:12px}.page{display:none}.page.active{display:block}
input,textarea{padding:8px;background:var(--bg);border:1px solid var(--b);color:var(--t);border-radius:4px;width:100%;font:inherit}
button{padding:8px 16px;background:var(--a);color:#000;border:none;border-radius:4px;font-weight:600;cursor:pointer;font:inherit}
button.red{background:var(--r)}button.green{background:var(--g)}button.purple{background:var(--purple)}
.row{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:20px}
.feed-card{background:var(--s);border:1px solid var(--b);border-radius:8px;padding:14px;margin-bottom:10px}
.feed-card .title{font-weight:600;font-size:14px;margin-bottom:4px}
.feed-card .meta{color:var(--m);font-size:11px}
.feed-card .body{margin-top:8px;font-size:12px;color:var(--t);line-height:1.6}
.vote-btn{display:inline-flex;align-items:center;gap:4px;padding:4px 12px;background:transparent;border:1px solid var(--b);border-radius:12px;color:var(--m);cursor:pointer;font-size:11px;transition:all 0.2s}
.vote-btn:hover{border-color:var(--a);color:var(--a)}.vote-btn.voted{border-color:var(--g);color:var(--g)}
@media(max-width:768px){nav{display:none}main{margin-left:0}.grid{grid-template-columns:1fr 1fr}.row{grid-template-columns:1fr}}
</style>
</head>
<body>
<nav>
<h2>MBclaw Admin</h2>
<a data-p="仪表盘" class="active">仪表盘</a>
<a data-p="设备">设备</a>

<a data-p="公告">公告</a>
<a data-p="反馈">Bug反馈</a>
<a data-p="共建">共建计划</a>
<a data-p="版本">版本</a>
</nav>
<main>
<div class="page active" id="p-仪表盘"><h1>仪表盘</h1><p id="dash-time">数据加载中 (6台在线)</p><div class="grid" id="dash-stats"></div><div class="row"><div class="card"><h3 style="margin-bottom:12px">在线设备</h3><div id="dash-online"></div></div><div class="card"><h3 style="margin-bottom:12px">服务器</h3><div id="dash-metrics"></div></div></div></div>
<div class="page" id="p-设备"><h1>设备</h1><p id="user-count">-</p><div style="overflow-x:auto"><table><thead><tr><th>调试码</th><th>QQ</th><th>型号</th><th>版本</th><th>Root</th><th>无障碍</th><th>权限</th><th>密钥</th><th>心跳</th><th>IP</th></tr></thead><tbody id="user-table"></tbody></table></div></div>
<div class="page" id="p-调试"><h1>远程调试</h1><p id="debug-count">-</p><div style="overflow-x:auto"><table><thead><tr><th>调试码</th><th>设备ID</th><th>型号</th><th>Root</th><th>无障碍</th><th>权限</th><th>心跳</th></tr></thead><tbody id="debug-table"></tbody></table></div></div>
<div class="page" id="p-公告"><h1>公告</h1><div class="card" style="margin-bottom:16px"><h3 style="margin-bottom:8px">新建公告</h3><input id="notice-title" placeholder="Title" style="margin-bottom:8px"><textarea id="notice-content" placeholder="Content" rows="3" style="margin-bottom:8px"></textarea><button onclick="createNotice()">发布</button></div><div id="notice-list"></div></div>
<div class="page" id="p-反馈"><h1>Bug反馈</h1><div id="bug-list"></div></div>
<div class="page" id="p-共建"><h1>共建计划</h1><div id="feature-list"></div></div>
<div class="page" id="p-版本"><h1>版本</h1><div class="card"><div id="version-info"></div><form id="ver-form" style="margin-top:12px;display:grid;gap:8px"><input id="ver-latest" placeholder="版本号"><textarea id="ver-changelog" placeholder="更新日志" rows="2"></textarea><button type="submit">保存</button></form></div></div>
</main>
<script>
async function A(p,o){o=o||{};var h=o.headers||{};h["Content-Type"]="application/json";var r=await fetch(p,{...o,headers:h});return r.json()}
function T(ts){var d=new Date(ts);return d.toLocaleString()}

// Dashboard
async function LD(){
  try{
    var o=await A("/admin/api/overview");
    document.getElementById("dash-time").textContent="已更新: "+new Date().toLocaleString();
    document.getElementById("dash-stats").innerHTML=
      '<div class="card"><div class="l">在线设备</div><div class="v">'+(o.online_devices||0)+'</div></div>'+
      '<div class="card"><div class="l">历史设备</div><div class="v">'+(o.total_devices_ever||0)+'</div></div>'+
      '<div class="card"><div class="l">总请求</div><div class="v">'+(o.total_requests||0)+'</div></div>'+
      '<div class="card"><div class="l">错误</div><div class="v">'+(o.errors||0)+'</div></div>';
    var ol=(o.online_list||[]).map(function(d){
      return '<tr><td><span class="mono">'+d.code+'</span></td><td>'+(d.qq||'-')+'</td><td>'+d.model+'</td><td>'+d.version+'</td><td>'+d.ip+'</td><td>'+d.seconds_ago+'秒前</td></tr>'
    }).join('');
    document.getElementById("dash-online").innerHTML='<table><thead><tr><th>调试码</th><th>QQ</th><th>型号</th><th>版本</th><th>IP</th><th>最后心跳</th></tr></thead><tbody>'+ol+'</tbody></table>';
    document.getElementById("dash-metrics").innerHTML='<div class="mono">运行'+Math.floor((o.uptime||0)/3600)+'h | 请求'+o.total_requests+' | 错误'+o.errors+'</div>';
  }catch(e){}
}

// Devices
async function LU(){
  try{
    var uu=await A("/admin/api/users?limit=200");
    document.getElementById("user-count").textContent="Total: "+(uu.total||0)+" devices";
    var h=(uu.users||[]).map(function(d){
      var p=d.permissions||d;
      var keys=d.keys||{};
      var kstr=Object.keys(keys).map(function(k){return k}).join(', ')||'-';
      return '<tr>'+
        '<td><span class="mono">'+(d.code||d.user_id||'-')+'</span></td>'+
        '<td>'+(d.qq||'-')+'</td>'+
        '<td>'+(d.model||'-')+'</td>'+
        '<td><span class="badge '+(d.version?'badge-purple':'badge-warn')+'">'+(d.version||'?')+'</span></td>'+
        '<td><span class="badge '+(d.root?'badge-ok':'badge-err')+'">'+(d.root?'ROOT':'NO')+'</span></td>'+
        '<td><span class="badge '+(d.accessibility?'badge-ok':'badge-warn')+'">'+(d.accessibility?'ON':'OFF')+'</span></td>'+
        '<td>'+(d.perms_granted||0)+'/'+(d.perms_total||0)+'</td>'+
        '<td><span style="font-size:10px;color:var(--m)">'+kstr+'</span></td>'+
        '<td><span class="badge '+(d.online?'badge-ok':'badge-warn')+'">'+(d.online?'ONLINE':(d.last_seen||'').slice(0,16))+'</span></td>'+
        '<td><span class="mono" style="font-size:10px">'+(d.ip||'-')+'</span></td>'+
      '</tr>'
    }).join('');
    document.getElementById("user-table").innerHTML=h||'<tr><td colspan=10>暂无设备</td></tr>'
  }catch(e){}
}

// Debug
async function LDbg(){
  try{
    var d=await A("/admin/client/debug/devices");
    document.getElementById("debug-count").textContent="Online: "+(d||[]).length;
    var h=(d||[]).map(function(dv){
      var p=dv.permissions||{};
      return '<tr><td><span class="mono">'+(dv.code||'-')+'</span></td><td><span class="mono" style="font-size:10px">'+(dv.device_id||'').slice(0,16)+'</span></td><td>'+dv.model+'</td><td><span class="badge '+(p.root?"badge-ok":"badge-err")+'">'+(p.root?"ROOT":"-")+'</span></td><td><span class="badge '+(p.accessibility?"badge-ok":"badge-warn")+'">'+(p.accessibility?"ON":"OFF")+'</span></td><td>'+(p.granted||0)+'/'+(p.total||0)+'</td><td>'+(dv.last_seen||'').slice(0,16)+'</td></tr>'
    }).join('');
    document.getElementById("debug-table").innerHTML=h||'<tr><td colspan=7>暂无设备</td></tr>'
  }catch(e){}
}

// Notices
async function LN(){
  try{
    var n=await A("/admin/api/notices");
    document.getElementById("notice-list").innerHTML=(n.notices||[]).map(function(i){
      return '<div class="feed-card"><div class="title">'+i.title+'</div><div class="meta">'+T(i.ts*1000)+' | '+(i.archived?'ARCHIVED':'ACTIVE')+'</div><div class="body">'+i.content+'</div><button class="vote-btn" onclick="archiveNotice(\''+i.id+'\')">归档</button></div>'
    }).join('')||'No notices'
  }catch(e){}
}
async function createNotice(){
  var t=document.getElementById("notice-title").value.trim();
  var c=document.getElementById("notice-content").value.trim();
  if(!t||!c)return alert("Title and content required");
  await A("/admin/api/notices",{method:"POST",body:JSON.stringify({title:t,content:c})});
  document.getElementById("notice-title").value='';
  document.getElementById("notice-content").value='';
  LN()
}
async function archiveNotice(id){await A("/admin/api/notices/"+id+"/archive",{method:"POST"});LN()}

// Bugs
async function LB(){
  try{
    var b=await A("/admin/api/bugs");
    document.getElementById("bug-list").innerHTML=(b.bugs||[]).map(function(i){
      return '<div class="feed-card"><div class="title">'+i.title+' <span class="badge badge-purple">'+i.votes+' votes</span></div><div class="meta">'+T(i.ts*1000)+' | '+i.status+' | '+ (i.ip||'')+'</div><div class="body">'+i.content+'</div></div>'
    }).join('')||'No bug reports'
  }catch(e){}
}

// Features
async function LF(){
  try{
    var f=await A("/admin/api/features");
    document.getElementById("feature-list").innerHTML=(f.features||[]).map(function(i){
      return '<div class="feed-card"><div class="title">'+i.title+' <span class="badge badge-ok">'+i.votes+' votes</span></div><div class="meta">'+T(i.ts*1000)+' | '+i.status+'</div><div class="body">'+i.content+'</div></div>'
    }).join('')||'No feature requests'
  }catch(e){}
}

// Version
async function LV(){
  try{
    var v=await A("/admin/client/version");
    document.getElementById("version-info").innerHTML='<span class="mono">当前: '+(v.current||"-")+' | 最新: '+(v.latest||"-")+'</span><span class="badge '+(v.has_update?"badge-warn":"badge-ok")+'" style="margin-left:8px">'+(v.has_update?"有更新":"最新")+'</span><div style="margin-top:4px;font-size:12px;color:var(--m)">'+(v.changelog||"")+'</div>';
    document.getElementById("ver-latest").value=v.latest||"";
    document.getElementById("ver-changelog").value=v.changelog||""
  }catch(e){}
}
document.getElementById("ver-form").onsubmit=async function(e){e.preventDefault();var l=document.getElementById("ver-latest").value.trim();var c=document.getElementById("ver-changelog").value.trim();await A("/admin/client/version/set?latest="+encodeURIComponent(l)+"&notes="+encodeURIComponent(c));alert("已更新");LV()};

// Nav
document.querySelectorAll("nav a").forEach(function(a){a.onclick=function(e){e.preventDefault();document.querySelectorAll("nav a").forEach(function(x){x.classList.remove("active")});a.classList.add("active");document.querySelectorAll(".page").forEach(function(p){p.classList.remove("active")});document.getElementById("p-"+a.dataset.p).classList.add("active");var fns={dashboard:LD,users:LU,debug:LDbg,notices:LN,bugs:LB,features:LF,version:LV};if(fns[a.dataset.p])fns[a.dataset.p]()}});
try{LD()}catch(e){};setTimeout(function(){try{LU()}catch(e){};try{LDbg()}catch(e){}},500)
</script>
</body>
</html>'''

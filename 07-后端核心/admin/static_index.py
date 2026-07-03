HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>MBclaw Admin v4.8</title>
<style>
:root{--bg:#0d1117;--s:#161b22;--b:#30363d;--t:#c9d1d9;--m:#8b949e;--a:#58a6ff;--g:#3fb950;--r:#f85149;--o:#d2991d}
*{margin:0;padding:0;box-sizing:border-box}
body{font:13px system-ui,sans-serif;background:var(--bg);color:var(--t);display:flex;min-height:100vh}
nav{width:200px;background:var(--s);border-right:1px solid var(--b);padding:16px 0;position:fixed;top:0;left:0;bottom:0;overflow-y:auto}
nav h2{padding:0 16px 16px;font-size:14px;display:flex;align-items:center;gap:8px}
nav a{display:flex;align-items:center;gap:8px;padding:8px 16px;color:var(--m);text-decoration:none;font-size:12.5px;border-left:2px solid transparent}
nav a:hover,nav a.active{color:var(--t);background:rgba(88,166,255,.06);border-left-color:var(--a)}
main{margin-left:200px;flex:1;padding:24px}
h1{font-size:18px;margin-bottom:4px}
h1+p{color:var(--m);font-size:12px;margin-bottom:20px}
.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:20px}
.card{background:var(--s);border:1px solid var(--b);border-radius:8px;padding:16px}
.card .l{font-size:11px;color:var(--m);text-transform:uppercase;letter-spacing:.5px}
.card .v{font-size:26px;font-weight:700;margin:4px 0;font-family:monospace}
.row{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:20px}
table{width:100%;border-collapse:collapse;font-size:12px}
th{text-align:left;padding:8px 12px;color:var(--m);font-weight:600;border-bottom:2px solid var(--b);font-size:11px}
td{padding:8px 12px;border-bottom:1px solid var(--b)}
tr:hover{background:rgba(255,255,255,.02)}
.badge{display:inline-block;padding:2px 8px;border-radius:8px;font-size:10px;font-weight:600}
.badge-ok{background:rgba(63,185,80,.15);color:var(--g)}
.badge-err{background:rgba(248,81,73,.15);color:var(--r)}
.badge-warn{background:rgba(210,153,29,.15);color:var(--o)}
.bar{height:6px;background:var(--b);border-radius:3px;overflow:hidden;margin-top:4px}
.bar-fill{height:100%;border-radius:3px}.bar-ok{background:var(--g)}.bar-warn{background:var(--o)}
.mono{font-family:monospace;font-size:12px}
.page{display:none}.page.active{display:block}
input,textarea{padding:8px;background:var(--bg);border:1px solid var(--b);color:var(--t);border-radius:4px;width:100%;font:inherit}
button{padding:8px 16px;background:var(--a);color:#000;border:none;border-radius:4px;font-weight:600;cursor:pointer;font:inherit}
@media(max-width:768px){nav{display:none}main{margin-left:0}.grid{grid-template-columns:1fr 1fr}.row{grid-template-columns:1fr}}
</style>
</head>
<body>
<nav><h2>MBclaw <span style="background:var(--a);color:#000;padding:2px 8px;border-radius:4px;font-size:10px;font-weight:700">ADMIN</span></h2>
<a href="#" class="active" data-p="dashboard">Dashboard</a>
<a href="#" data-p="users">Users</a>
<a href="#" data-p="debug">Debug</a>
<a href="#" data-p="version">Version</a></nav>
<main>
<div class="page active" id="p-dashboard"><h1>Dashboard</h1><p id="dash-time">Loading...</p><div class="grid" id="dash-stats"></div><div class="row"><div class="card"><h3 style="margin-bottom:12px;font-size:13px">Server Metrics</h3><div id="metrics"></div></div><div class="card"><h3 style="margin-bottom:12px;font-size:13px">Downloads</h3><div id="downloads"></div></div></div></div>
<div class="page" id="p-users"><h1>Devices</h1><p id="user-count">-</p><div style="overflow-x:auto"><table><thead><tr><th>User</th><th>Device</th><th>ID</th><th>Root</th><th>A11y</th><th>Perms</th><th>Version</th><th>LastSeen</th></tr></thead><tbody id="user-table"></tbody></table></div></div>
<div class="page" id="p-debug"><h1>Remote Debug</h1><p id="debug-count">-</p><div style="overflow-x:auto"><table><thead><tr><th>Code</th><th>DeviceID</th><th>Model</th><th>Root</th><th>A11y</th><th>Perms</th><th>Heartbeat</th></tr></thead><tbody id="debug-table"></tbody></table></div></div>
<div class="page" id="p-version"><h1>Version</h1><div class="card"><div id="version-info"></div><form id="ver-form" style="margin-top:12px;display:grid;gap:8px"><input id="ver-latest" placeholder="Version"><textarea id="ver-changelog" placeholder="Changelog" rows="2"></textarea><button type="submit">Save</button></form></div></div>
</main>
<script>
async function A(p,o){o=o||{};var h=o.headers||{};h["Content-Type"]="application/json";var r=await fetch(p,{...o,headers:h});return r.json()}
async function LD(){try{var s=await A("/api/admin/stats");var m=await A("/api/admin/metrics");var d=await A("/api/admin/downloads");var dd=await A("/admin/client/debug/devices");document.getElementById("dash-time").textContent="Updated: "+new Date().toLocaleString();var online=(dd||[]).length;document.getElementById("dash-stats").innerHTML='<div class="card"><div class="l">Online Devices</div><div class="v">'+online+'</div></div><div class="card"><div class="l">Total Users</div><div class="v">'+(s.unique_users||0)+'</div></div><div class="card"><div class="l">Downloads</div><div class="v">'+(s.total_downloads||0)+'</div></div><div class="card"><div class="l">Today</div><div class="v">'+(s.today_downloads||0)+'</div></div>';var t="";if(m){t+='Disk '+m.disk_pct+'%<div class="bar"><div class="bar-fill bar-ok" style="width:'+m.disk_pct+'%"></div></div>';t+='Memory '+m.mem_pct+'%<div class="bar"><div class="bar-fill bar-warn" style="width:'+m.mem_pct+'%"></div></div>';t+='<div class="mono">Uptime '+Math.floor((m.uptime_seconds||0)/3600)+'h | DB '+m.db_size_mb+'MB</div>'}document.getElementById("metrics").innerHTML=t||"No data";var n="";if(d)Object.keys(d).forEach(function(k){var v=d[k];n+='<div style="margin-bottom:6px"><span class="mono">'+(v.total||0)+'</span> total | <span class="mono">'+(v.today||0)+'</span> today<br><span style="font-size:10px;color:var(--m)">'+k+'</span></div>'});document.getElementById("downloads").innerHTML=n||"No data"}catch(e){}}
async function LU(){try{var dd=await A("/admin/client/debug/devices");var uu=await A("/api/admin/users?limit=100");var dl=await A("/api/admin/downloads");var totalDl=0;if(dl)Object.keys(dl).forEach(function(k){totalDl+=(dl[k].total||0)});document.getElementById("user-count").textContent="Online: "+(dd||[]).length+" | Users: "+(uu.total||0)+" | Downloads: "+totalDl;var h="";(dd||[]).forEach(function(d){var p=d.permissions||{};h+='<tr><td><span class="mono">'+(d.user_id||"-")+'</span></td><td>'+(d.model||"-")+'<br><span style="font-size:10px;color:var(--m)">'+(d.brand||"")+'</span></td><td><span class="mono" style="font-size:10px">'+(d.device_id||"").slice(0,16)+'</span></td><td><span class="badge '+(p.root?"badge-ok":"badge-err")+'">'+(p.root?"ROOT":"-")+'</span></td><td><span class="badge '+(p.accessibility?"badge-ok":"badge-warn")+'">'+(p.accessibility?"ON":"OFF")+'</span></td><td>'+(p.granted||0)+'/'+(p.total||0)+'</td><td>'+(d.version||"-")+'</td><td>'+(d.last_seen||"").slice(0,16)+'</td></tr>'});document.getElementById("user-table").innerHTML=h||'<tr><td colspan=8>No online devices</td></tr>'}catch(e){}}
async function LDbg(){try{var d=await A("/admin/client/debug/devices");document.getElementById("debug-count").textContent="Online: "+(d||[]).length;var h="";(d||[]).forEach(function(dv){h+='<tr><td><span class="mono">'+(dv.code||"-")+'</span></td><td><span class="mono" style="font-size:10px">'+(dv.device_id||"").slice(0,16)+'</span></td><td>'+(dv.model||"-")+'</td><td><span class="badge '+((dv.permissions||{}).root?"badge-ok":"badge-err")+'">'+((dv.permissions||{}).root?"ROOT":"-")+'</span></td><td><span class="badge '+((dv.permissions||{}).accessibility?"badge-ok":"badge-warn")+'">'+((dv.permissions||{}).accessibility?"ON":"OFF")+'</span></td><td>'+((dv.permissions||{}).granted||0)+'/'+((dv.permissions||{}).total||0)+'</td><td>'+(dv.last_seen||"").slice(0,16)+'</td></tr>'});document.getElementById("debug-table").innerHTML=h||'<tr><td colspan=7>No devices</td></tr>'}catch(e){}}
async function LV(){try{var v=await A("/admin/client/version");document.getElementById("version-info").innerHTML='<span class="mono">Current: '+(v.current||"-")+' | Latest: '+(v.latest||"-")+'</span><span class="badge '+(v.has_update?"badge-warn":"badge-ok")+'" style="margin-left:8px">'+(v.has_update?"UPDATE":"LATEST")+'</span><div style="margin-top:4px;font-size:12px;color:var(--m)">'+(v.changelog||"")+'</div>';document.getElementById("ver-latest").value=v.latest||"";document.getElementById("ver-changelog").value=v.changelog||""}catch(e){}}
document.querySelectorAll("nav a").forEach(function(a){a.onclick=function(e){e.preventDefault();document.querySelectorAll("nav a").forEach(function(x){x.classList.remove("active")});a.classList.add("active");document.querySelectorAll(".page").forEach(function(p){p.classList.remove("active")});document.getElementById("p-"+a.dataset.p).classList.add("active");if(a.dataset.p==="dashboard")LD();else if(a.dataset.p==="users")LU();else if(a.dataset.p==="debug")LDbg();else LV()}});
document.getElementById("ver-form").onsubmit=async function(e){e.preventDefault();var l=document.getElementById("ver-latest").value.trim();var c=document.getElementById("ver-changelog").value.trim();await A("/admin/client/version/set?latest="+encodeURIComponent(l)+"&notes="+encodeURIComponent(c));alert("Updated");LV()};
LD();
</script>
</body>
</html>
"""


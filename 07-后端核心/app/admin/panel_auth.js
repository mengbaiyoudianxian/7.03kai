
// ======== 登录认证层 ========
function checkAuth(){
  api('/admin/api/overview').then(function(){
    document.getElementById('auth-overlay').style.display='none';
    document.getElementById('app-main').style.display='flex';
    loadDash();
  }).catch(function(e){
    if(e===401){ showLogin(); }
  });
}

function showLogin(){
  document.getElementById('auth-overlay').style.display='flex';
  document.getElementById('app-main').style.display='none';
  document.getElementById('login-error').style.display='none';
}

function doLogin(){
  var pwd=document.getElementById('login-pwd').value;
  if(!pwd){ document.getElementById('login-error').textContent='请输入密码'; document.getElementById('login-error').style.display='block'; return; }
  document.getElementById('login-btn').disabled=true;
  document.getElementById('login-btn').textContent='登录中...';
  fetch('/admin/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'mengbai',password:pwd})})
  .then(function(r){ if(!r.ok) throw r.status; return r.json(); })
  .then(function(){
    document.getElementById('login-error').style.display='none';
    document.getElementById('auth-overlay').style.display='none';
    document.getElementById('app-main').style.display='flex';
    loadDash();
  })
  .catch(function(e){
    document.getElementById('login-error').textContent=e===401?'密码错误':'服务器错误: '+e;
    document.getElementById('login-error').style.display='block';
    document.getElementById('login-btn').disabled=false;
    document.getElementById('login-btn').textContent='登录';
  });
}

function doLogout(){
  fetch('/admin/api/logout',{method:'POST'}).then(function(){
    showLogin();
    document.getElementById('login-pwd').value='';
  }).catch(function(){ showLogin(); });
}

// 回车登录
document.addEventListener('DOMContentLoaded',function(){
  document.getElementById('login-pwd').addEventListener('keydown',function(e){ if(e.key==='Enter') doLogin(); });
  checkAuth();
});

"""P2-7/P2-8: MiClaw 登录 API + 登录页"""
from __future__ import annotations
import logging, httpx
from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
from pool.registry import get_registry

router = APIRouter(prefix="/api/miclaw", tags=["miclaw_login"])
log = logging.getLogger(__name__)

BRIDGE = "http://121.199.57.195:8765"

_sessions: dict[str, dict] = {}


class LoginReq(BaseModel):
    username: str
    password: str


class VerifyReq(BaseModel):
    session_id: str
    code: str


LOGIN_PAGE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MiClaw 登录</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font:14px system-ui,sans-serif;background:#0d1117;color:#c9d1d9;display:flex;align-items:center;justify-content:center;min-height:100vh}
.box{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:32px;width:360px}
.box h2{font-size:18px;color:#58a6ff;margin-bottom:20px;text-align:center}
label{display:block;font-size:12px;color:#8b949e;margin-bottom:4px;margin-top:12px}
input{width:100%;padding:8px 12px;background:#0d1117;border:1px solid #30363d;border-radius:6px;color:#c9d1d9;font:14px system-ui}
input:focus{border-color:#58a6ff;outline:none}
button{width:100%;padding:10px;margin-top:16px;background:#238636;border:none;border-radius:6px;color:#fff;font:14px system-ui;cursor:pointer}
button:hover{background:#2ea043}
button:disabled{background:#30363d;cursor:not-allowed}
.msg{margin-top:12px;font-size:12px;text-align:center;min-height:18px}
.msg.ok{color:#3fb950}.msg.err{color:#f85149}
#step2{display:none}
</style>
</head>
<body>
<div class="box">
<h2>🔐 MiClaw 账号登录</h2>
<div id="step1">
  <label>小米账号</label><input id="username" placeholder="手机号/邮箱">
  <label>密码</label><input id="password" type="password" placeholder="小米账号密码">
  <button onclick="doLogin()" id="login-btn">获取验证码</button>
  <div class="msg" id="msg1"></div>
</div>
<div id="step2">
  <label>验证码</label><input id="code" placeholder="6位短信验证码">
  <button onclick="doVerify()" id="verify-btn">验证登录</button>
  <div class="msg" id="msg2"></div>
</div>
</div>
<script>
let sid = '';
function msg(el, text, ok) { el.textContent = text; el.className = 'msg ' + (ok ? 'ok' : 'err'); }
async function doLogin() {
  const u = document.getElementById('username').value.trim();
  const p = document.getElementById('password').value.trim();
  if (!u || !p) return msg(document.getElementById('msg1'), '请填写账号和密码', false);
  document.getElementById('login-btn').disabled = true;
  try {
    const r = await fetch('api/miclaw/login', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:p})});
    const d = await r.json();
    if (r.ok) {
      sid = d.session_id;
      msg(document.getElementById('msg1'), '验证码已发送', true);
      document.getElementById('step1').style.display = 'none';
      document.getElementById('step2').style.display = 'block';
    } else {
      msg(document.getElementById('msg1'), d.detail || '登录失败', false);
    }
  } catch(e) { msg(document.getElementById('msg1'), '网络错误: '+e.message, false); }
  document.getElementById('login-btn').disabled = false;
}
async function doVerify() {
  const code = document.getElementById('code').value.trim();
  if (!code || code.length < 4) return msg(document.getElementById('msg2'), '请输入验证码', false);
  document.getElementById('verify-btn').disabled = true;
  try {
    const r = await fetch('api/miclaw/verify', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({session_id:sid,code})});
    const d = await r.json();
    if (r.ok) {
      msg(document.getElementById('msg2'), d.message || '登录成功！', true);
      try { parent.postMessage({type:'miclaw_login_success', account_id: new URLSearchParams(location.search).get('account_id')}, '*'); } catch(e) {}
      setTimeout(() => { try { window.close(); } catch(e) {} }, 2000);
    } else {
      msg(document.getElementById('msg2'), d.detail || '验证失败', false);
    }
  } catch(e) { msg(document.getElementById('msg2'), '网络错误: '+e.message, false); }
  document.getElementById('verify-btn').disabled = false;
}
</script>
</body>
</html>"""


@router.get("/login-page", response_class=HTMLResponse)
def login_page():
    """P2-8: MiClaw 登录页"""
    return HTMLResponse(LOGIN_PAGE)


@router.post("/login")
async def miclaw_login(body: LoginReq):
    """第一步：提交小米账号密码，触发验证码"""
    try:
        async with httpx.AsyncClient(timeout=30) as c:
            r = await c.post(f"{BRIDGE}/api/login",
                             json={"username": body.username, "password": body.password})
            data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
    except Exception as e:
        raise HTTPException(502, f"Bridge 不可达: {e}")

    if not r.is_success:
        raise HTTPException(400, data.get("error", "登录失败"))
    sid = data.get("session_id", "")
    if not sid:
        raise HTTPException(500, "Bridge 未返回 session_id")
    _sessions[sid] = {"username": body.username, "password": body.password, "ts": __import__("time").time()}
    # 注册/更新账号记录
    reg = get_registry()
    reg.add_miclaw_account(body.username, body.password)
    return {"ok": True, "session_id": sid, "message": "验证码已发送，请查收手机"}


@router.post("/verify")
async def miclaw_verify(body: VerifyReq):
    """第二步：提交验证码，完成登录"""
    sess = _sessions.pop(body.session_id, None)
    if not sess:
        raise HTTPException(404, "会话已过期，请重新登录")

    try:
        async with httpx.AsyncClient(timeout=30) as c:
            r = await c.post(f"{BRIDGE}/api/verify",
                             json={"session_id": body.session_id, "code": body.code})
            data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
    except Exception as e:
        raise HTTPException(502, f"Bridge 不可达: {e}")

    if not r.is_success:
        raise HTTPException(400, data.get("error", "验证失败"))

    cookie = r.headers.get("set-cookie", "") or data.get("cookie", "")
    session_token = data.get("session_token", "")

    # 更新账号登录状态 + 加密存储 Cookie
    reg = get_registry()
    accts = reg.list_miclaw_accounts()
    for a in accts:
        if a["username"] == sess["username"]:
            reg.update_miclaw_session(a["id"], cookie=cookie, session_token=session_token, login_status="logged_in")
            return {"ok": True, "account_id": a["id"], "message": "登录成功"}

    raise HTTPException(500, "账号记录丢失")


@router.get("/status/{account_id}")
def login_status(account_id: int):
    """查询指定账号的登录状态"""
    reg = get_registry()
    for a in reg.list_miclaw_accounts():
        if a["id"] == account_id:
            return {"account_id": account_id, "username": a["username"], "login_status": a["login_status"]}
    raise HTTPException(404, "账号不存在")

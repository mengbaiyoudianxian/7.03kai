"""P5-5: 管理员登录 + Session"""
from __future__ import annotations
import hashlib, secrets, time
from fastapi import APIRouter, Cookie, HTTPException
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
from config import cfg

router = APIRouter()

_sessions: dict[str, float] = {}

def _check_session(token: str | None) -> bool:
    return token is not None and token in _sessions and time.time() - _sessions[token] < 86400

LOGIN_HTML = """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><title>MBclaw</title>
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

@router.get("/login", response_class=HTMLResponse)
def login_page(): return HTMLResponse(LOGIN_HTML)

@router.post("/api/login")
def do_login(body: dict):
    h = hashlib.sha256((body.get("password","")+cfg.admin_password).encode()).hexdigest()
    if body.get("username") != "admin" or not secrets.compare_digest(body.get("password",""), cfg.admin_password):
        raise HTTPException(401, "账号或密码错误")
    token = secrets.token_hex(32)
    _sessions[token] = time.time()
    resp = JSONResponse({"ok": True})
    resp.set_cookie("mb_admin", token, httponly=True, max_age=86400)
    return resp

@router.post("/api/logout")
def do_logout(mb_admin: str = Cookie(default=None)):
    _sessions.pop(mb_admin, None)
    return {"ok": True}

@router.post("/api/change-password")
def change_pwd(body: dict):
    raise HTTPException(501, "修改密码功能未实现")

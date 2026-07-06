"""MBclaw FastAPI 入口 — 集成管理面板"""
import os
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi import Cookie
from starlette.responses import FileResponse

from app.api import router as api_router
from app.db import init_db
from app.admin.router import router as admin_router, record_user_call, _check_session
from app.admin.extra import router as admin_extra_router
from app.admin.upload import router as admin_upload_router
from app.admin.version_api import router as version_router
from app.admin.bridge_manager import router as bridge_router
from app.admin.debug_api_v2 import router as debug_router
from app.admin.admin_api import router as admin_api_router
ADMIN_HTML = open("/opt/mbclaw/app/admin/panel_one.html").read() + "<!-- TS: " + str(__import__("time").time()) + " -->"


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_db()
    yield


app = FastAPI(title="MBclaw Server", version="0.4.0", lifespan=lifespan)

app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

# ── 用户调用追踪中间件 ──
@app.middleware("http")
async def track_users(request: Request, call_next):
    # 追踪所有客户端 API 调用
    p = request.url.path
    is_client_api = (
        p.startswith("/bridge/miclaw/") or
        p.startswith("/admin/client/") or
        p.startswith("/client/") or
        p.startswith("/v1/") or
        p.startswith("/api/")
    )
    response = await call_next(request)
    if is_client_api and not p.startswith("/admin/client/version"):
        try:
            from app.admin.router import record_user_call, record_request
            uid = (
                request.headers.get("X-User-Id") or
                request.headers.get("X-Client-Id") or
                "anonymous"
            )
            ip = request.client.host if request.client else ""
            record_user_call(uid, ip)
            # 统计请求量
            provider = "miclaw-bridge" if "bridge" in p else "api"
            record_request(provider, error=(response.status_code >= 400))
        except Exception as e:
            print(f"track_users err: {e}")
    return response

app.include_router(api_router)
app.include_router(admin_router)
app.include_router(admin_extra_router)
app.include_router(admin_upload_router)
app.include_router(bridge_router)
app.include_router(version_router)
app.include_router(debug_router)
app.include_router(admin_api_router)

@app.get("/hotfix/latest.json")
def hotfix_latest():
    return FileResponse("/var/lib/mbclaw/hotfix/latest.json")

@app.get("/hotfix/{filename}")
def hotfix_file(filename: str):
    path = f"/var/lib/mbclaw/hotfix/{filename}"
    if os.path.exists(path):
        return FileResponse(path)
    raise HTTPException(404)



@app.get("/", response_class=HTMLResponse)
def index():
    return ADMIN_HTML





@app.get("/admin/panel.js", response_class=HTMLResponse)
def panel_js():
    return FileResponse("/opt/mbclaw/app/admin/panel.js", media_type="application/javascript")
@app.get("/admin2/panel.js", response_class=HTMLResponse)
def panel_js(): return FileResponse("/opt/mbclaw/app/admin/panel.js", media_type="application/javascript")
@app.get("/admin2/panel_auth.js", response_class=HTMLResponse)
def panel_auth_js(): return FileResponse("/opt/mbclaw/app/admin/panel_auth.js", media_type="application/javascript")


@app.get("/admin2", response_class=HTMLResponse)
@app.get("/admin2/", response_class=HTMLResponse)
def admin2_panel():
    return HTMLResponse(content=open("/opt/mbclaw/app/admin/panel_one.html").read() + "<!-- TS: " + str(__import__("time").time()) + " -->", media_type="text/html")
@app.get("/admin", response_class=HTMLResponse)
@app.get("/admin/", response_class=HTMLResponse)
def admin_panel(mb_admin: str = Cookie(default=None)):
    if not _check_session(mb_admin):
        return RedirectResponse("/admin/login", status_code=302)
    return HTMLResponse(content=open("/opt/mbclaw/app/admin/panel_one.html").read() + "<!-- TS: " + str(__import__("time").time()) + " -->", media_type="text/html")

@app.get("/admin/login", response_class=HTMLResponse)
def admin_login_page():
    return HTMLResponse("""<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>MBclaw Admin</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}body{font:14px system-ui,sans-serif;background:#0d1117;color:#c9d1d9;display:flex;align-items:center;justify-content:center;min-height:100vh}
.card{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:32px 28px;width:100%;max-width:360px}
h1{font-size:22px;margin-bottom:4px;color:#f0f6fc}p.sub{color:#8b949e;font-size:13px;margin-bottom:24px}
label{display:block;margin-bottom:14px}label span{display:block;font-size:12px;color:#8b949e;margin-bottom:4px}
input{width:100%;padding:10px 12px;background:#0d1117;border:1px solid #30363d;border-radius:6px;color:#c9d1d9;font:inherit;outline:none}
input:focus{border-color:#58a6ff}button{width:100%;padding:10px;border:none;border-radius:6px;background:#238636;color:#fff;font:inherit;font-weight:600;cursor:pointer}
button:hover{background:#2ea043}.err{margin-top:12px;color:#f85149;font-size:13px}
</style></head>
<body><div class="card">
<h1>MBclaw Admin</h1><p class="sub">输入管理密码登录</p>
<form onsubmit="doLogin(event)">
<label><span>账号</span><input type="text" id="user" autocomplete="username" required></label>
<label><span>密码</span><input type="password" id="pwd" autocomplete="current-password" required></label>
<button type="submit">登录</button>
</form><div id="err" class="err"></div></div>
<script>
async function doLogin(e){e.preventDefault();var u=document.getElementById('user').value.trim();var p=document.getElementById('pwd').value;
try{var r=await fetch('/admin/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:p})});if(r.ok){window.location.href='/admin'}else{var d=await r.json();document.getElementById('err').textContent=d.detail||'密码错误'}}catch(ex){document.getElementById('err').textContent='网络错误'}}
</script></body></html>""")

@app.get("/bridge/miclaw/login/{app_id}", response_class=HTMLResponse)
def miclaw_login_page(app_id: str):
        return FileResponse("/opt/mbclaw/app/admin/miclaw_login.html")


@app.get("/health")
def health():
    db_ok = os.path.exists(os.getenv("MBCLAW_DB_PATH", "data/mbclaw.db"))
    return {"db_ok": db_ok, "version": "0.4.0", "service": "MBclaw"}

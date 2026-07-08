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
from app.gateway.adapters.wechat import WechatAdapter
ADMIN_HTML = open("/opt/mbclaw/admin-panel/app/admin/panel_one.html").read() + "<!-- TS: " + str(__import__("time").time()) + " -->"


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_db()
    # 启动微信 Bot
    try:
        from app.gateway import get_registry
        from app.gateway.adapters.wechat_api import WeixinAPI
        reg = get_registry()
        wx = WechatAdapter()
        async def on_wx_msg(sm):
            from app.gateway_agent import handle_gateway_agent
            return handle_gateway_agent(sm.content, sm.user_id)
        wx.set_on_message(on_wx_msg)
        reg.register('wechat', wx)
        import asyncio
        asyncio.ensure_future(wx.start())
        print('[main] WeChat adapter started')
    except Exception as e:
        print(f'[main] WeChat adapter start failed: {e}')
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
    return FileResponse("/opt/mbclaw/admin-panel/app/admin/panel.js", media_type="application/javascript")
@app.get("/admin2/panel.js", response_class=HTMLResponse)
def panel_js(): return FileResponse("/opt/mbclaw/admin-panel/app/admin/panel.js", media_type="application/javascript")
@app.get("/admin2/panel_auth.js", response_class=HTMLResponse)
def panel_auth_js(): return FileResponse("/opt/mbclaw/admin-panel/app/admin/panel_auth.js", media_type="application/javascript")


@app.get("/admin2", response_class=HTMLResponse)
@app.get("/admin2/", response_class=HTMLResponse)
def admin2_panel():
    return HTMLResponse(content=open("/opt/mbclaw/admin-panel/app/admin/panel_one.html").read() + "<!-- TS: " + str(__import__("time").time()) + " -->", media_type="text/html")
@app.get("/admin", response_class=HTMLResponse)
@app.get("/admin/", response_class=HTMLResponse)
def admin_panel():
    return HTMLResponse(content=open("/opt/mbclaw/admin-panel/app/admin/panel_one.html").read() + "<!-- TS: " + str(__import__("time").time()) + " -->", media_type="text/html")

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
<label><span>账号</span><input type="text" id="user" value="mengbai" autocomplete="username" required></label>
<label><span>密码</span><input type="password" id="pwd" autocomplete="current-password" required></label>
<button type="submit">登录</button>
</form><div id="err" class="err"></div></div>
<script>
async function doLogin(e){e.preventDefault();var u=document.getElementById('user').value.trim();var p=document.getElementById('pwd').value;
try{var r=await fetch('/admin/api/login',{method:'POST',credentials:'include',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:p})});if(r.ok){window.location.href='/admin2'}else{var d=await r.json();document.getElementById('err').textContent=d.detail||'密码错误'}}catch(ex){document.getElementById('err').textContent='网络错误'}}
</script></body></html>""")

@app.get("/bridge/miclaw/login/{app_id}", response_class=HTMLResponse)
def miclaw_login_page(app_id: str):
        return FileResponse("/opt/mbclaw/admin-panel/app/admin/miclaw_login.html")


@app.get("/health")
def health():
    db_ok = os.path.exists(os.getenv("MBCLAW_DB_PATH", "data/mbclaw.db"))
    return {"db_ok": db_ok, "version": "0.4.0", "service": "MBclaw"}

@app.get("/admin/api/server-status")
def api_server_status():
    import json
    HOSTNAMES = {
        "母体机": "iZj6c6xhvpez8w1hk9pefuZ",
        "工具池": "iZbp14z7xg0itzgqgf1uc3Z",
        "跳板机": "iZj6camnt3ocwjveip3f7rZ",
        "备用站": "iZ0jl0q0zxij3hfnwjfbekZ",
        "母体": "iZ0jl3aqsblqwrkyxt46tvZ",
        "云电脑": "",
        "小米手机": "shouji",
    }
    ROLES = {
        "母体机": "后端API + 管理面板 + Claude Code",
        "工具池": "MiClaw Bridge :8765 + Token池 + APK下载站",
        "跳板机": "SSH跳板中转 (香港)",
        "备用站": "旧下载站/备用文件服务",
        "母体": "母体记忆系统 + k2自进化",
        "云电脑": "APK编译 + QQ Bot (无影云)",
        "小米手机": "主力调试机 · 小爪远程控制中心 :19876",
    }
    try:
        data = json.load(open("/var/lib/mbclaw/server_status.json"))
        servers = {k:v for k,v in data.items() if k != "updated"}
        for name, hn in HOSTNAMES.items():
            if name in servers:
                if hn:
                    servers[name]["hostname"] = hn
                if name in ROLES:
                    servers[name]["role"] = ROLES[name]
        return {"servers": servers, "updated": data.get("updated",0)}
    except:
        return {"servers": {"母体机": {"status":"loading"}}}

# ── 微信扫码登录 ──
@app.get("/gateway/wechat/link")
async def wechat_link():
    from app.gateway.adapters.wechat_api import WeixinAPI
    api = WeixinAPI()
    qr = api.get_qrcode()
    qr_code = qr.get("qrcode", "")
    link = f"https://liteapp.weixin.qq.com/q/7GiQu1?qrcode={qr_code}&bot_type=3"
    html = f"""<!DOCTYPE html><html><head><meta charset="UTF-8"><title>微信一键登录</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{{font:14px system-ui,sans-serif;background:#f5f5f5;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}}.card{{background:#fff;border-radius:12px;padding:24px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.1);max-width:420px}}a.btn{{display:inline-block;margin:12px 0;padding:12px 28px;background:#07c160;color:#fff;border-radius:6px;text-decoration:none;font-weight:600;font-size:15px}}.status{{color:#888;margin-top:12px;font-size:13px}}</style></head><body>
<div class="card"><h2>📱 微信Bot登录</h2><p>点击下方按钮在微信中打开并确认授权</p>
<a class="btn" href="{link}" target="_blank">在微信中打开登录</a>
<p style="font-size:12px;color:#aaa;word-break:break-all">{link}</p>
<p class="status" id="s">等待授权...</p></div>
<script>var pt=setInterval(function(){{fetch("/gateway/wechat/poll?qrcode="+encodeURIComponent("{qr_code}")).then(r=>r.json()).then(d=>{{var s=document.getElementById("s");if(d.status==="scanned")s.textContent="📱 已扫码，请确认";if(d.status==="confirmed"&&d.account_id){{clearInterval(pt);s.textContent="正在登录...";fetch("/gateway/wechat/login",{{method:"POST",headers:{{"Content-Type":"application/json"}},body:JSON.stringify({{qrcode:"{qr_code}",bot_id:d.account_id}})}}).then(r=>r.json()).then(d=>{{if(d.ok)s.textContent="✅ 登录成功！账号: "+d.account_id+"，10秒后生效";else s.textContent="❌ "+(d.error||"失败")}})}}}}).catch(()=>{{}})}},3000)</script></body></html>"""
    return HTMLResponse(html)

@app.get("/gateway/wechat/qr", response_class=HTMLResponse)
async def wechat_qr_page():
    from app.gateway.adapters.wechat_api import WeixinAPI
    import qrcode as _qr
    from qrcode.image.svg import SvgPathImage
    api = WeixinAPI()
    try:
        qr = api.get_qrcode()
        qr_url = qr.get("qrcode_img_content", "")
        qr_code = qr.get("qrcode", "")
        qr_gen = _qr.QRCode(border=2, box_size=10)
        qr_gen.add_data(qr_url)
        qr_gen.make(fit=True)
        svg = qr_gen.make_image(image_factory=SvgPathImage).to_string().decode()
        svg = svg.replace('width="37mm"', 'width="256"').replace('height="37mm"', 'height="256"')
    except Exception as e:
        return HTMLResponse(f"<h1>获取二维码失败</h1><p>{e}</p>")
    return HTMLResponse(f"""<!DOCTYPE html>
<html><head><meta charset="UTF-8"><title>微信扫码登录</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{{font:14px system-ui,sans-serif;background:#f5f5f5;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}}.card{{background:#fff;border-radius:12px;padding:24px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.1);max-width:400px}}.card svg{{max-width:280px;height:auto}}.btn{{display:inline-block;margin:12px 4px;padding:10px 24px;border:none;border-radius:6px;cursor:pointer;font:inherit;font-weight:600}}.btn-login{{background:#07c160;color:#fff}}.btn-refresh{{background:#eee;color:#333}}.status{{color:#888;font-size:12px;margin-top:8px}}</style></head><body><div class="card">
<h2>📱 微信扫码登录</h2><p>用手机微信扫描下方二维码，扫码后自动登录</p>
{svg}
<p class="status" id="pollStatus">等待扫码...</p>
<button class="btn btn-login" id="loginBtn" style="display:none" onclick="startLogin()">开始登录</button>
<button class="btn btn-refresh" onclick="location.reload()">刷新二维码</button>
<div id="result" style="margin-top:12px"></div></div>
<script>
async function startLogin(){{document.getElementById("status").textContent="正在等待扫码确认...";document.getElementById("result").innerHTML="";try{{let r=await fetch("/gateway/wechat/login",{{method:"POST",headers:{{"Content-Type":"application/json"}},body:JSON.stringify({{qrcode:"{qr_code}"}})}});let d=await r.json();if(d.ok){{document.getElementById("status").textContent="✅ 登录成功！";document.getElementById("result").innerHTML="<b>账号: "+d.account_id+"</b><br>重启后生效"}}else{{document.getElementById("status").textContent="❌ "+(d.error||"失败")}}}}catch(e){{document.getElementById("status").textContent="❌ "+e}}}}
</script></body></html>""")

@app.get("/gateway/wechat/poll")
async def wechat_poll(qrcode: str = ""):
    from app.gateway.adapters.wechat_api import WeixinAPI
    api = WeixinAPI()
    resp = api.poll_qr_status(qrcode)
    return {"status": resp.get("status", "wait"), "account_id": resp.get("ilink_bot_id", "")}

@app.post("/gateway/wechat/login")
async def wechat_login(body: dict):
    from app.gateway.adapters.wechat_auth import login_with_qr
    import asyncio
    loop = asyncio.get_event_loop()
    result = await loop.run_in_executor(None, login_with_qr)
    if result:
        return {"ok": True, "account_id": result["account_id"]}
    return {"ok": False, "error": "登录超时"}

@app.get("/gateway/wechat/accounts")
def wechat_accounts():
    from app.gateway.adapters.wechat_auth import load_accounts
    return {"accounts": [{"account_id": a.get("account_id", ""), "user_id": a.get("userId", ""), "base_url": a.get("baseUrl", "")} for a in load_accounts()]}

@app.post('/gateway/web/chat')
async def gateway_chat(body: dict):
    msg = body.get('message', body.get('content', ''))
    code = body.get('code', body.get('channel_user', 'anon'))
    if not msg:
        return {'reply': '请说点什么'}
    from app.gateway_agent import handle_gateway_agent
    reply = handle_gateway_agent(msg, code)
    return {'reply': reply}

"""MBclaw Mother Server — 统一入口 v2.0

P5-5: HTML/登录逻辑已拆到 routes/panel_html.py + routes/auth_admin.py
"""
from __future__ import annotations
import asyncio, logging
from pathlib import Path
from contextlib import asynccontextmanager

from fastapi import FastAPI, Cookie, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, RedirectResponse

from config import cfg

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
log = logging.getLogger("mbclaw")

for w in cfg.validate(): log.info("CONFIG: %s", w)

Path(cfg.data_dir).mkdir(parents=True, exist_ok=True)


async def _start_gateway():
    """启动所有渠道适配器"""
    from gateway import register
    from gateway.adapters.qqbot import QQBotAdapter
    from gateway.adapters.wechat import WechatAdapter
    from gateway.agent import on_channel_message

    adapters = [QQBotAdapter(), WechatAdapter()]
    for a in adapters:
        a.set_on_message(on_channel_message)
        register(a)
        await a.start()
    print(f"[gateway] {len(adapters)} adapters started")


@asynccontextmanager
async def lifespan(app: FastAPI):
    from mother.evolution.daily import run_forever
    asyncio.ensure_future(run_forever())
    await _start_gateway()
    yield


app = FastAPI(title="MBclaw Mother", version="2.0.0", lifespan=lifespan)

app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

from mother.mother_api import router as mother_router
app.include_router(mother_router)

from routes.auth_admin import router as auth_router, _check_session
from routes.panel_html import _PANEL as PANEL

app.include_router(auth_router, prefix="/admin")


@app.get("/", response_class=HTMLResponse)
@app.get("/admin", response_class=HTMLResponse)
@app.get("/admin/", response_class=HTMLResponse)
def admin_panel(mb_admin: str = Cookie(default=None)):
    if not _check_session(mb_admin):
        return RedirectResponse("/admin/login", status_code=302)
    return HTMLResponse(PANEL)


@app.get("/health")
def health():
    return {"ok": True, "version": "2.0.0", "owner": cfg.owner_name}


@app.post("/gateway/wechat/login")
async def wechat_login():
    """W4: 触发微信扫码登录（后台执行）"""
    import asyncio
    from gateway.adapters.wechat_auth import login_with_qr
    loop = asyncio.get_event_loop()
    result = await loop.run_in_executor(None, login_with_qr)
    if result:
        return {"ok": True, "account_id": result["account_id"]}
    return {"ok": False, "error": "登录失败或超时"}


@app.get("/gateway/wechat/accounts")
def wechat_accounts():
    """列出已登录的微信账号"""
    from gateway.adapters.wechat_auth import load_accounts
    return {"accounts": [{"account_id": a.get("account_id", ""),
                          "user_id": a.get("userId", ""),
                          "base_url": a.get("baseUrl", "")} for a in load_accounts()]}


@app.get("/gateway/wechat/qr", response_class=HTMLResponse)
async def wechat_qr_page():
    """微信扫码登录页面"""
    from gateway.adapters.wechat_api import WeixinAPI
    api = WeixinAPI()
    try:
        qr = api.get_qrcode()
        qr_url = qr.get("qrcode_img_content", "")
    except Exception as e:
        return HTMLResponse(f"<h1>获取二维码失败</h1><p>{e}</p>")
    return HTMLResponse(f"""<!DOCTYPE html>
<html><head><meta charset="UTF-8"><title>微信扫码登录 MBclaw</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{{font:14px system-ui,sans-serif;background:#f5f5f5;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}}
.card{{background:#fff;border-radius:12px;padding:24px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.1);max-width:360px}}
img{{max-width:256px;border:1px solid #eee;border-radius:8px}}
.btn{{display:inline-block;margin:12px 4px;padding:10px 24px;border:none;border-radius:6px;cursor:pointer;font:inherit;font-weight:600}}
.btn-login{{background:#07c160;color:#fff}}
.btn-refresh{{background:#eee;color:#333}}
.status{{color:#888;font-size:12px;margin-top:8px}}
</style></head><body><div class="card">
<h2>📱 微信扫码登录</h2>
<p>用手机微信扫描下方二维码</p>
<img src="{qr_url}" alt="QR Code" id="qr">
<p class="status" id="status">等待扫码...</p>
<button class="btn btn-login" onclick="startLogin()">开始登录</button>
<button class="btn btn-refresh" onclick="location.reload()">刷新二维码</button>
<div id="result" style="margin-top:12px"></div>
</div>
<script>
async function startLogin() {{
    document.getElementById("status").textContent = "正在等待扫码确认...";
    document.getElementById("result").innerHTML = "";
    try {{
        let r = await fetch("/gateway/wechat/login", {{method:"POST"}});
        let d = await r.json();
        if (d.ok) {{
            document.getElementById("status").textContent = "✅ 登录成功！";
            document.getElementById("result").innerHTML = "<b>账号: " + d.account_id + "</b><br>重启后生效";
        }} else {{
            document.getElementById("status").textContent = "❌ " + (d.error || "失败");
        }}
    }} catch(e) {{
        document.getElementById("status").textContent = "❌ " + e;
    }}
}}
</script></body></html>""")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=cfg.host, port=cfg.port, reload=False)

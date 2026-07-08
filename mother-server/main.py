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
from pydantic import BaseModel, Field

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
    from mother.idle_scheduler import run_forever as idle_run
    asyncio.ensure_future(run_forever())
    asyncio.ensure_future(idle_run())
    await _start_gateway()
    yield


app = FastAPI(title="MBclaw Mother", version="2.0.0", lifespan=lifespan)

app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

from mother.mother_api import router as mother_router
app.include_router(mother_router)

from mother.admin_panel import router as admin_router
app.include_router(admin_router)


@app.get("/")
def root():
    return RedirectResponse("/admin", status_code=302)


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


from routes.wechat_qr import router as wechat_qr_router
app.include_router(wechat_qr_router)


# ── 统一网关: Web + CLI 渠道 ──

class GatewayChatReq(BaseModel):
    goal: str = Field(min_length=1, max_length=2000)
    session_id: int = 0

@app.post("/gateway/web/chat")
async def gateway_web_chat(req: GatewayChatReq):
    """WebUI 走统一网关 → 母体"""
    from gateway.normalize import normalize_web
    from gateway.agent import handle_message
    msg = normalize_web(req.goal, user_code="admin")
    msg.session_id = str(req.session_id % 100000)
    reply = await handle_message(msg)
    return {"reply": reply, "session_id": req.session_id}


@app.post("/gateway/cli/chat")
async def gateway_cli_chat(req: GatewayChatReq):
    """CLI 走统一网关 → 母体"""
    from gateway.normalize import normalize_cli
    from gateway.agent import handle_message
    msg = normalize_cli(req.goal)
    msg.session_id = str(req.session_id % 100000)
    reply = await handle_message(msg)
    return {"reply": reply, "session_id": req.session_id}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=cfg.host, port=cfg.port, reload=False)

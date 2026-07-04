"""MBclaw Mother Server — 统一入口 v2.0

P5-5: HTML/登录逻辑已拆到 routes/panel_html.py + routes/auth_admin.py
"""
from __future__ import annotations
import asyncio, logging
from pathlib import Path
from contextlib import asynccontextmanager

from fastapi import FastAPI, Cookie
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, RedirectResponse

from config import cfg

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
log = logging.getLogger("mbclaw")

for w in cfg.validate(): log.info("CONFIG: %s", w)

Path(cfg.data_dir).mkdir(parents=True, exist_ok=True)


@asynccontextmanager
async def lifespan(app: FastAPI):
    from mother.evolution.daily import run_forever
    asyncio.ensure_future(run_forever())
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


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=cfg.host, port=cfg.port, reload=False)

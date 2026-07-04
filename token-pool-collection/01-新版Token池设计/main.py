"""Token Pool — 统一 LLM Key 管理与代理服务

端口: 8100（默认）
Admin: http://host:8100/admin
Proxy: POST http://host:8100/v1/chat/completions
"""
from __future__ import annotations
import asyncio, logging
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")

@asynccontextmanager
async def lifespan(_app):
    from pool.health import run_forever
    task = asyncio.create_task(run_forever())
    yield
    task.cancel()

app = FastAPI(title="MBclaw Token Pool", version="1.0.0", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

from routes.proxy     import router as proxy_router
from routes.keys      import router as keys_router
from routes.stats     import router as stats_router
from routes.heartbeat import router as hb_router
from routes.admin     import router as admin_router
from routes.auth      import router as auth_router

app.include_router(proxy_router)
app.include_router(keys_router)
app.include_router(stats_router)
app.include_router(hb_router)
app.include_router(admin_router)
app.include_router(auth_router)

@app.get("/health")
def health():
    from pool.registry import get_registry
    from pool.circuit import get_cb
    keys = get_registry().all(enabled_only=True)
    cb = get_cb()
    working = [k for k in keys if k.status == "working" and not cb.is_open(k.alias)]
    return {"ok": True, "total": len(keys), "working": len(working), "version": "1.0.0"}

if __name__ == "__main__":
    import uvicorn
    from config import cfg
    uvicorn.run("main:app", host=cfg.HOST, port=cfg.PORT, reload=False)

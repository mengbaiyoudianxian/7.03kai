"""Token Pool — 统一 LLM Key 管理与代理服务

端口: 8100（默认）
Admin: http://host:8100/admin
Proxy: POST http://host:8100/v1/chat/completions
"""
import asyncio, logging
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")

app = FastAPI(title="MBclaw Token Pool", version="1.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

@app.on_event("startup")
async def startup():
    from pool.health import run_forever
    from pool.miclaw_pool import start_health_monitor
    asyncio.ensure_future(run_forever())
    asyncio.ensure_future(start_health_monitor())

from routes.proxy      import router as proxy_router
from routes.keys       import router as keys_router
from routes.stats      import router as stats_router
from routes.heartbeat  import router as hb_router
from routes.admin      import router as admin_router
from routes.auth       import router as auth_router
from routes.user_stats  import router as user_stats_router
from routes.miclaw_login import router as miclaw_login_router
from routes.free_keys import router as free_keys_router
from routes.sold_keys import router as sold_keys_router

app.include_router(proxy_router)
app.include_router(keys_router)
app.include_router(stats_router)
app.include_router(hb_router)
app.include_router(admin_router)
app.include_router(auth_router)
app.include_router(user_stats_router)
app.include_router(miclaw_login_router)
app.include_router(free_keys_router)
app.include_router(sold_keys_router)

@app.get("/health")
def health():
    from pool.registry import get_registry
    from pool.ratelimit import get_limiter
    keys = get_registry().all(enabled_only=True)
    rl = get_limiter()
    working = [k for k in keys if k.status == "working" and not rl.is_on_cooldown(k.alias, "", "")]
    return {"ok": True, "total": len(keys), "working": len(working), "version": "1.0.0"}

if __name__ == "__main__":
    import uvicorn
    from config import cfg
    uvicorn.run("main:app", host=cfg.HOST, port=cfg.PORT, reload=False)

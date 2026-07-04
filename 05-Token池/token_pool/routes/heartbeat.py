"""用户设备心跳 — 上报本机Key供母体使用"""
from __future__ import annotations
import json, time, logging
from pathlib import Path
from fastapi import APIRouter, Request
from pydantic import BaseModel
from pool.registry import ProviderKey, get_registry
from config import cfg

router = APIRouter(tags=["heartbeat"])
log = logging.getLogger(__name__)
HB_DIR = Path(cfg.DATA_DIR) / "heartbeat"
HB_DIR.mkdir(parents=True, exist_ok=True)

class HeartbeatReq(BaseModel):
    code: str                  # 设备码
    api_key: str = ""
    base_url: str = ""
    model: str = "gpt-3.5-turbo"
    provider: str = "openai"
    qq: str = ""
    yesterday_usage: int = 0   # 昨日Token消耗（P1-1：供配额计算用）

@router.post("/api/heartbeat")
async def heartbeat(req: HeartbeatReq, request: Request):
    ip = request.client.host if request.client else "unknown"
    hb = req.model_dump()
    hb["ip"] = ip; hb["ts"] = time.time()
    (HB_DIR / f"{req.code.replace('/', '_')}.json").write_text(
        json.dumps(hb, ensure_ascii=False))

    # 自动注册到Key池 + 用户共享Key表（P1-1：同步昨日用量）
    if req.api_key and req.base_url:
        alias = f"hb-{req.code[:16]}"
        reg = get_registry()
        existing = reg.get(alias)
        pk = ProviderKey(
            id=existing.id if existing else 0,
            alias=alias, provider=req.provider,
            base_url=req.base_url, api_key=req.api_key,
            model=req.model, cost_per_1k=0.0, priority=2, enabled=True)
        reg.upsert(pk)
        # P1-1: 同步到 user_shared_keys，上报昨日消耗
        reg.upsert_shared_key(req.code, req.api_key, req.base_url,
                              model=req.model, provider=req.provider,
                              yesterday_usage=req.yesterday_usage)
        log.info("心跳注册: %s (%s) yesterday=%d", alias, req.code, req.yesterday_usage)
        return {"has_command": False, "registered": True}

    return {"has_command": False, "registered": False}

@router.get("/api/heartbeat/devices")
async def devices():
    result = {}
    for f in HB_DIR.glob("*.json"):
        try:
            d = json.loads(f.read_text())
            code = d.get("code","")
            d["online"] = (time.time() - d.get("ts",0)) < 120
            result[code] = d
        except: pass
    return result

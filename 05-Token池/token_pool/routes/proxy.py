"""OpenAI 兼容代理 /v1/chat/completions"""
from __future__ import annotations
import json, time, secrets, logging
from fastapi import APIRouter, Request, HTTPException, Header
from fastapi.responses import JSONResponse, StreamingResponse
import httpx
from pool.caller import call_with_fallback
from pool.scheduler import pick_all_ranked
from config import cfg

router = APIRouter(tags=["proxy"])
log = logging.getLogger(__name__)

def _auth(authorization: str = ""):
    if not cfg.PROXY_KEY: return  # 未设置=不验证
    if authorization != f"Bearer {cfg.PROXY_KEY}":
        raise HTTPException(401, "Invalid proxy key")

@router.post("/v1/chat/completions")
async def chat_completions(request: Request, authorization: str = Header(default="")):
    _auth(authorization)
    try:
        payload = await request.json()
    except Exception:
        raise HTTPException(400, "Invalid JSON")

    # 判断任务类型
    model_hint = (payload.get("model") or "").lower()
    task = "code" if any(k in model_hint for k in ("claude","opus")) else \
           "cheap" if any(k in model_hint for k in ("mini","haiku","deepseek","cheap")) else "chat"

    # Stream 模式：直接转发到第一个可用Key
    if payload.get("stream"):
        candidates = pick_all_ranked(task)
        if not candidates:
            raise HTTPException(503, "No available keys")
        pk = candidates[0]
        return await _stream_proxy(pk, payload)

    try:
        resp, alias = await call_with_fallback(payload, task)
        resp["_pool_alias"] = alias  # 调试信息
        return JSONResponse(resp)
    except RuntimeError as e:
        raise HTTPException(503, str(e))

async def _stream_proxy(pk, payload):
    from pool.registry import get_registry
    from pool.circuit import get_cb
    from pool.metrics import get_hub
    import time as _t

    if pk.provider == "anthropic":
        url = f"{pk.base_url}/messages"
        headers = {"x-api-key": pk.api_key, "anthropic-version": "2023-06-01", "content-type": "application/json"}
    else:
        url = f"{pk.base_url}/chat/completions"
        headers = {"Authorization": f"Bearer {pk.api_key}", "Content-Type": "application/json"}

    async def gen():
        start = _t.time()
        try:
            async with httpx.AsyncClient(timeout=120) as c:
                async with c.stream("POST", url, headers=headers, json={**payload,"model":pk.model}) as r:
                    async for chunk in r.aiter_bytes():
                        yield chunk
            get_cb().on_success(pk.alias)
            get_hub().record(pk.alias, (_t.time()-start)*1000, 0, 0, True)
            get_registry().update_stat(pk.alias, "working", (_t.time()-start)*1000, 0, 0, True)
        except Exception as e:
            get_cb().on_failure(pk.alias)
            get_hub().record(pk.alias, (_t.time()-start)*1000, 0, 0, False)
            yield b"data: {\"error\": \"" + str(e).encode()[:100] + b"\"}\n\n"

    return StreamingResponse(gen(), media_type="text/event-stream")

@router.get("/v1/models")
async def list_models(authorization: str = Header(default="")):
    _auth(authorization)
    from pool.registry import get_registry
    keys = get_registry().all(enabled_only=True)
    models = list({pk.model for pk in keys if pk.api_key or pk.provider in ("local","miclaw")})
    return {"object": "list", "data": [{"id": m, "object": "model"} for m in sorted(models)]}

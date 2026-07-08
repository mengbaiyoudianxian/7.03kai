"""OpenAI 兼容代理 /v1/chat/completions"""
from __future__ import annotations
import logging
from fastapi import APIRouter, Request, HTTPException, Header
from fastapi.responses import JSONResponse, StreamingResponse
import httpx
from pool.caller import call_with_fallback
from pool.scheduler import pick_all
from config import cfg

router = APIRouter(tags=["proxy"])
log = logging.getLogger(__name__)

def _auth(authorization: str = ""):
    if not cfg.PROXY_KEY: return  # 未设置=不验证
    if authorization != f"Bearer {cfg.PROXY_KEY}":
        raise HTTPException(401, "Invalid proxy key")

def _check_quota(user_code: str = ""):
    """P1-4: 用户配额检查。borrowed_today >= max_borrowable → 429"""
    if not user_code:
        return  # 未指定用户=管理员，不限配额
    from pool.registry import get_registry
    keys = get_registry().get_user_daily_stats(user_code)
    for k in keys:
        if k.get("max_borrowable", 0) > 0 and k.get("borrowed_today", 0) >= k["max_borrowable"]:
            raise HTTPException(429, f"配额耗尽: {k['borrowed_today']}/{k['max_borrowable']} tokens (昨日的 {k['allowed_ratio']*100:.0f}%)")

def _check_user_rl(user_code: str = ""):
    """P1-5: 每用户 RPM/RPD/TPD 限流"""
    if not user_code:
        return
    from pool.user_ratelimit import get_user_limiter
    ok, retry = get_user_limiter().check(user_code)
    if not ok:
        raise HTTPException(429, f"用户限流, {retry:.0f}s 后重试")

def _record_usage(user_code: str = "", tokens: int = 0):
    """P1-4+P1-5: 成功后记录限流消耗 + 递增 borrowed_today"""
    if not user_code:
        return
    from pool.user_ratelimit import get_user_limiter
    from pool.registry import get_registry
    get_user_limiter().record(user_code, tokens)
    get_registry().increment_borrowed(user_code, tokens)

@router.post("/v1/chat/completions")
async def chat_completions(request: Request, authorization: str = Header(default=""), x_user_code: str = Header(default="")):
    _auth(authorization)
    _check_quota(x_user_code)
    _check_user_rl(x_user_code)
    try:
        payload = await request.json()
    except Exception:
        raise HTTPException(400, "Invalid JSON")

    # 判断任务类型
    model_hint = (payload.get("model") or "").lower()
    task = "code" if any(k in model_hint for k in ("claude","opus")) else \
           "cheap" if any(k in model_hint for k in ("mini","haiku","deepseek","cheap")) else "chat"

    # P2-10: Stream 模式 — 故障转移，逐个尝试候选Key
    if payload.get("stream"):
        candidates = pick_all(task)
        if not candidates:
            raise HTTPException(503, "No available keys")
        last_err = ""
        for pk in candidates[:3]:  # 最多试3个
            try:
                return await _stream_proxy(pk, payload)
            except HTTPException:
                raise
            except Exception as e:
                last_err = str(e)
                log.warning("Stream failover: %s → %s", pk.alias, e)
                continue
        raise HTTPException(503, f"All stream keys failed: {last_err}")

    try:
        model_req = (payload.get("model") or "").strip()
        resp, alias = await call_with_fallback(payload, task, require_model=model_req)
        resp["_pool_alias"] = alias
        _record_usage(x_user_code, resp.get("usage", {}).get("total_tokens", 0))
        return JSONResponse(resp)
    except RuntimeError as e:
        raise HTTPException(503, str(e))

async def _stream_proxy(pk, payload):
    from pool.registry import get_registry
    from pool.metrics import get_hub
    from pool.ratelimit import get_limiter
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
            rl = get_limiter(); rl.clear_cooldown(pk.alias, pk.provider, pk.model)
            get_hub().record(pk.alias, (_t.time()-start)*1000, 0, 0, True)
            get_registry().update_stat(pk.alias, "working", (_t.time()-start)*1000, 0, 0, True)
        except Exception as e:
            rl = get_limiter(); rl.set_cooldown(pk.alias, pk.provider, pk.model, status_code=429)
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

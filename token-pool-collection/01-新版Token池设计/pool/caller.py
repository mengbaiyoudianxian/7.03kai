"""统一调用器 — 处理OpenAI/Anthropic差异，自动故障转移"""
from __future__ import annotations
import asyncio, time, logging
import httpx
from pool.registry import ProviderKey, get_registry
from pool.circuit import get_cb
from pool.metrics import get_hub
from pool.scheduler import pick_all_ranked

log = logging.getLogger(__name__)

async def _call_openai_compat(pk: ProviderKey, payload: dict, timeout=120) -> dict:
    async with httpx.AsyncClient(timeout=timeout) as c:
        r = await c.post(f"{pk.base_url}/chat/completions",
            headers={"Authorization": f"Bearer {pk.api_key}", "Content-Type": "application/json"},
            json={**payload, "model": payload.get("model") or pk.model})
        r.raise_for_status()
        return r.json()

async def _call_anthropic(pk: ProviderKey, payload: dict, timeout=120) -> dict:
    """Anthropic API → 转换为 OpenAI 格式响应"""
    msgs = payload.get("messages", [])
    sys_msg = next((m["content"] for m in msgs if m["role"] == "system"), "")
    user_msgs = [m for m in msgs if m["role"] != "system"]
    body = {
        "model": payload.get("model") or pk.model,
        "max_tokens": payload.get("max_tokens", 1024),
        "messages": user_msgs,
    }
    if sys_msg: body["system"] = sys_msg
    if payload.get("temperature") is not None: body["temperature"] = payload["temperature"]
    if payload.get("stream"): body["stream"] = True
    async with httpx.AsyncClient(timeout=timeout) as c:
        r = await c.post(f"{pk.base_url}/messages",
            headers={"x-api-key": pk.api_key, "anthropic-version": "2023-06-01", "content-type": "application/json"},
            json=body)
        r.raise_for_status()
        data = r.json()
    # 转换回 OpenAI 格式
    content = data.get("content", [{}])[0].get("text", "")
    return {"id": data.get("id",""), "object": "chat.completion", "model": data.get("model",""),
            "choices": [{"index":0,"message":{"role":"assistant","content":content},"finish_reason":"stop"}],
            "usage": {"prompt_tokens": data.get("usage",{}).get("input_tokens",0),
                      "completion_tokens": data.get("usage",{}).get("output_tokens",0)}}

async def call_with_fallback(payload: dict, task: str = "chat", budget: float = 0.0, max_retries: int = 3) -> tuple[dict, str]:
    """自动故障转移调用，返回 (response_dict, alias_used)"""
    cb = get_cb(); hub = get_hub(); reg = get_registry()
    candidates = pick_all_ranked(task, budget)
    if not candidates:
        raise RuntimeError("token pool 中没有可用的 Key（全部熔断或未配置）")

    last_err = ""
    for pk in candidates[:max_retries]:
        start = time.time()
        try:
            if pk.provider == "anthropic":
                resp = await _call_anthropic(pk, payload)
            else:
                resp = await _call_openai_compat(pk, payload)
            latency = (time.time()-start)*1000
            tokens = resp.get("usage",{}).get("total_tokens", 0)
            cost = tokens / 1000 * pk.cost_per_1k
            cb.on_success(pk.alias)
            hub.record(pk.alias, latency, tokens, cost, True)
            reg.update_stat(pk.alias, "working", latency, tokens, cost, True)
            log.info("✅ %s (%.0fms, %dt)", pk.alias, latency, tokens)
            return resp, pk.alias
        except Exception as e:
            latency = (time.time()-start)*1000
            last_err = str(e)[:200]
            cb.on_failure(pk.alias)
            hub.record(pk.alias, latency, 0, 0, False)
            reg.update_stat(pk.alias, "failed", latency, 0, 0, False, last_err)
            log.warning("❌ %s: %s", pk.alias, last_err[:80])

    raise RuntimeError(f"所有 {min(max_retries,len(candidates))} 个候选Key均失败，最后错误: {last_err}")

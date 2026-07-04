"""后台健康检测 — 定期探活所有Key"""
from __future__ import annotations
import asyncio, time, logging
import httpx
from pool.registry import ProviderKey, get_registry
from pool.circuit import get_cb
from pool.metrics import get_hub
from config import cfg

log = logging.getLogger(__name__)

ANTHROPIC_TEST_HEADERS = {"x-api-key": "", "anthropic-version": "2023-06-01", "content-type": "application/json"}

async def probe_key(pk: ProviderKey, timeout: int = 10) -> tuple[bool, float, str]:
    """探测单个Key是否可用。返回 (ok, latency_ms, error)"""
    if not pk.api_key and pk.provider not in ("local",):
        return False, 0.0, "no api_key"
    start = time.time()
    try:
        async with httpx.AsyncClient(timeout=timeout) as c:
            if pk.provider == "anthropic":
                hdrs = {**ANTHROPIC_TEST_HEADERS, "x-api-key": pk.api_key}
                r = await c.post(f"{pk.base_url}/messages",
                    headers=hdrs,
                    json={"model": pk.model, "max_tokens": 1,
                          "messages": [{"role": "user", "content": "hi"}]})
            elif pk.provider == "local":
                r = await c.get(f"{pk.base_url}/models")
            else:
                r = await c.post(f"{pk.base_url}/chat/completions",
                    headers={"Authorization": f"Bearer {pk.api_key}", "Content-Type": "application/json"},
                    json={"model": pk.model, "messages": [{"role":"user","content":"hi"}], "max_tokens": 1})
        latency = (time.time() - start) * 1000
        ok = r.status_code in (200, 400)  # 400=模型存在但参数问题，也算通
        err = "" if ok else f"HTTP {r.status_code}: {r.text[:100]}"
        return ok, latency, err
    except Exception as e:
        return False, (time.time()-start)*1000, str(e)[:150]

async def check_all():
    """检测所有启用Key"""
    reg = get_registry(); cb = get_cb(); hub = get_hub()
    keys = reg.all(enabled_only=True)
    log.info("健康检测开始，共 %d 个Key", len(keys))
    for pk in keys:
        ok, latency, err = await probe_key(pk, cfg.HEALTH_TIMEOUT)
        status = "working" if ok else "failed"
        reg.update_stat(pk.alias, status, latency, 0, 0, ok, err)
        hub.record(pk.alias, latency, 0, 0, ok)
        if ok: cb.on_success(pk.alias)
        else: cb.on_failure(pk.alias)
        log.info("  [%s] %s %s %.0fms %s", pk.alias, "✅" if ok else "❌", status, latency, err[:60] if err else "")

async def run_forever():
    """后台无限循环"""
    while True:
        try: await check_all()
        except Exception as e: log.error("健康检测异常: %s", e)
        await asyncio.sleep(cfg.HEALTH_INTERVAL)

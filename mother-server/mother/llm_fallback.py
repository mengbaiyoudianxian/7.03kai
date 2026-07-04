"""LLM 直连回退 — 当 Token Pool 不可用时"""
from __future__ import annotations
import json, logging, httpx
from config import cfg

log = logging.getLogger(__name__)

def direct_chat(messages: list[dict], max_tokens: int = 2000, temperature: float = 0.7) -> str:
    api_key = cfg.best_llm_key()
    base_url = cfg.llm_base_url.rstrip("/")
    if not api_key: return "[LLM 未配置]"
    try:
        r = httpx.post(f"{base_url}/chat/completions",
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            json={"model": cfg.llm_model, "messages": messages,
                  "max_tokens": max_tokens, "temperature": temperature}, timeout=90)
        r.raise_for_status()
        return r.json()["choices"][0]["message"]["content"]
    except Exception as e:
        log.error("直接LLM调用失败: %s", e)
        return f"[LLM错误: {e}]"

"""Token Pool 客户端 — 母体通过此模块调用 Token Pool 服务"""
from __future__ import annotations
import logging, httpx
from config import cfg

log = logging.getLogger(__name__)

class TokenPoolClient:
    def __init__(self):
        self.base = cfg.token_pool_url.rstrip("/")
        self.proxy_key = cfg.token_pool_proxy_key

    def _headers(self) -> dict:
        h = {"Content-Type": "application/json"}
        if self.proxy_key: h["Authorization"] = f"Bearer {self.proxy_key}"
        return h

    def chat(self, messages: list[dict], model: str = "", task: str = "chat",
             max_tokens: int = 2000, temperature: float = 0.7) -> str:
        if not self.base:
            from mother.llm_fallback import direct_chat
            return direct_chat(messages, max_tokens=max_tokens, temperature=temperature)
        payload = {"model": model or cfg.llm_model, "messages": messages,
                   "max_tokens": max_tokens, "temperature": temperature}
        try:
            r = httpx.post(f"{self.base}/v1/chat/completions",
                          headers=self._headers(), json=payload, timeout=120)
            r.raise_for_status()
            return r.json()["choices"][0]["message"]["content"]
        except Exception as e:
            log.warning("TokenPool 失败，降级: %s", e)
            from mother.llm_fallback import direct_chat
            return direct_chat(messages, max_tokens=max_tokens, temperature=temperature)

    def health(self) -> dict:
        try:
            r = httpx.get(f"{self.base}/health", timeout=5)
            return r.json()
        except: return {"ok": False, "error": "unreachable"}

    def models(self) -> list[str]:
        try:
            r = httpx.get(f"{self.base}/v1/models", headers=self._headers(), timeout=5)
            return [m["id"] for m in r.json().get("data", [])]
        except: return []

_client: TokenPoolClient | None = None

def get_tp_client() -> TokenPoolClient:
    global _client
    if _client is None: _client = TokenPoolClient()
    return _client

def llm_chat(messages: list[dict], task: str = "chat",
             max_tokens: int = 2000, temperature: float = 0.7) -> str:
    return get_tp_client().chat(messages, task=task, max_tokens=max_tokens, temperature=temperature)

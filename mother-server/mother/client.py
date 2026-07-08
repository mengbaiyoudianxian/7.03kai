"""Token Pool 客户端 — 母体唯一 LLM 入口，不存Key不降级，全部走 Token Pool 调度

P5-1a: 删 llm_fallback 降级，删自己选 model 逻辑，传 task 让 scheduler 选 Key。
"""
from __future__ import annotations
import logging, httpx, json
from config import cfg

log = logging.getLogger(__name__)


class TokenPoolClient:
    def __init__(self):
        if not cfg.token_pool_url:
            raise RuntimeError("TOKEN_POOL_URL 未配置，母体必须接入 Token Pool")
        self.base = cfg.token_pool_url.rstrip("/")
        self.proxy_key = cfg.token_pool_proxy_key

    def _headers(self, user_code: str = "") -> dict:
        h = {"Content-Type": "application/json"}
        if self.proxy_key:
            h["Authorization"] = f"Bearer {self.proxy_key}"
        if user_code:
            h["X-User-Code"] = user_code
        return h

    def chat(self, messages: list[dict], task: str = "chat",
             max_tokens: int = 2000, temperature: float = 0.7,
             user_code: str = "") -> dict:
        """调用 Token Pool /v1/chat/completions，返回 {content, model, alias, tokens}"""
        payload = {"messages": messages, "max_tokens": max_tokens, "temperature": temperature}
        try:
            r = httpx.post(f"{self.base}/v1/chat/completions",
                           headers=self._headers(user_code), json=payload, timeout=120)
            r.raise_for_status()
            body = r.json()
            choice = body.get("choices", [{}])[0]
            return {
                "content": choice.get("message", {}).get("content", ""),
                "model": body.get("model", ""),
                "alias": body.get("_pool_alias", ""),
                "tokens": body.get("usage", {}).get("total_tokens", 0),
            }
        except Exception as e:
            log.error("TokenPool 调用失败: %s", e)
            raise RuntimeError(f"TokenPool 不可用: {e}")

    def chat_with_tools(self, messages: list[dict], tools: list[dict],
                        task: str = "chat", max_tokens: int = 2000,
                        model: str = "", user_code: str = "") -> dict:
        """支持 Function Calling 的调用。返回 {content, tool_calls, model, alias, tokens}"""
        payload = {"messages": messages, "tools": tools, "max_tokens": max_tokens}
        if model:
            payload["model"] = model
        try:
            r = httpx.post(f"{self.base}/v1/chat/completions",
                           headers=self._headers(user_code), json=payload, timeout=120)
            r.raise_for_status()
            body = r.json()
            choice = body.get("choices", [{}])[0]
            msg = choice.get("message", {})
            return {
                "content": msg.get("content") or "",
                "tool_calls": msg.get("tool_calls", []),
                "model": body.get("model", ""),
                "alias": body.get("_pool_alias", ""),
                "tokens": body.get("usage", {}).get("total_tokens", 0),
            }
        except Exception as e:
            log.error("TokenPool tool_call 失败: %s", e)
            raise RuntimeError(f"TokenPool 不可用: {e}")

    def health(self) -> dict:
        try:
            r = httpx.get(f"{self.base}/health", timeout=5)
            return r.json()
        except Exception:
            return {"ok": False, "error": "unreachable"}

    def models(self) -> list[str]:
        try:
            r = httpx.get(f"{self.base}/v1/models", headers=self._headers(), timeout=5)
            return [m["id"] for m in r.json().get("data", [])]
        except Exception:
            return []


_client: TokenPoolClient | None = None


def get_tp_client() -> TokenPoolClient:
    global _client
    if _client is None:
        _client = TokenPoolClient()
    return _client


def llm_chat(messages: list[dict], task: str = "chat",
             max_tokens: int = 2000, temperature: float = 0.7) -> str:
    """便捷函数：返回纯文本 content（兼容旧调用方）"""
    result = get_tp_client().chat(messages, task=task, max_tokens=max_tokens, temperature=temperature)
    return result["content"]

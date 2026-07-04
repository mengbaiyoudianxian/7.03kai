"""P2-3: Anthropic → OpenAI 格式转换 Provider"""
from __future__ import annotations
import time, logging
import httpx
from .base import BaseProvider, ProviderResult

log = logging.getLogger(__name__)

# Anthropic 模型名 → OpenAI 标准名映射
ANTHROPIC_TO_OPENAI = {
    "claude-sonnet-4-6": "claude-sonnet-4-6",
    "claude-opus-4-6": "claude-opus-4-6",
    "claude-haiku-4-6": "claude-haiku-4-6",
}


class AnthropicProvider(BaseProvider):
    """Anthropic Messages API → OpenAI chat/completions 格式自动转换"""

    def __init__(self, base_url: str, api_key: str, model: str, timeout: float = 120):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout

    def _to_anthropic(self, payload: dict) -> dict:
        """OpenAI messages → Anthropic messages"""
        messages = payload.get("messages", [])
        system = None
        anthropic_msgs = []
        for m in messages:
            role = m.get("role", "user")
            if role == "system":
                system = m.get("content", "")
            elif role == "assistant":
                anthropic_msgs.append({"role": "assistant", "content": m.get("content", "")})
            else:
                content = m.get("content", "")
                if isinstance(content, list):
                    parts = []
                    for part in content:
                        if part.get("type") == "text":
                            parts.append({"type": "text", "text": part.get("text", "")})
                        elif part.get("type") == "image_url":
                            src = part.get("image_url", {}).get("url", "")
                            if src.startswith("data:"):
                                media_type, b64 = src.split(",", 1) if "," in src else ("image/jpeg", src.split(";base64,")[-1] if ";base64," in src else "")
                                parts.append({"type": "image", "source": {"type": "base64", "media_type": media_type.replace("data:", "").split(";")[0], "data": b64}})
                    content = parts or content
                anthropic_msgs.append({"role": "user", "content": content})
        body = {"model": self.model, "messages": anthropic_msgs, "max_tokens": payload.get("max_tokens", 4096)}
        if system:
            body["system"] = system
        if payload.get("temperature") is not None:
            body["temperature"] = payload["temperature"]
        if payload.get("stream"):
            body["stream"] = True
        return body

    def _from_anthropic(self, data: dict) -> dict:
        """Anthropic response → OpenAI 格式"""
        content = ""
        for block in data.get("content", []):
            if block.get("type") == "text":
                content += block.get("text", "")
        usage = data.get("usage", {})
        return {
            "id": data.get("id", ""),
            "object": "chat.completion",
            "model": data.get("model", self.model),
            "choices": [{"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": data.get("stop_reason", "stop")}],
            "usage": {"prompt_tokens": usage.get("input_tokens", 0), "completion_tokens": usage.get("output_tokens", 0), "total_tokens": usage.get("input_tokens", 0) + usage.get("output_tokens", 0)},
        }

    async def chat(self, payload: dict, headers: dict | None = None) -> ProviderResult:
        url = f"{self.base_url}/messages"
        hdrs = {"x-api-key": self.api_key, "anthropic-version": "2023-06-01", "content-type": "application/json"}
        if headers:
            hdrs.update(headers)
        body = self._to_anthropic(payload)
        start = time.time()
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as c:
                r = await c.post(url, json=body, headers=hdrs)
                data = r.json()
                latency = (time.time() - start) * 1000
                if r.is_success:
                    openai_resp = self._from_anthropic(data)
                    tokens = openai_resp["usage"]["total_tokens"]
                    return ProviderResult(ok=True, status_code=r.status_code, body=openai_resp,
                                          latency_ms=latency, tokens_used=tokens)
                err = data.get("error", {}).get("message", str(r.status_code))
                return ProviderResult(ok=False, status_code=r.status_code, error=err, latency_ms=latency)
        except Exception as e:
            return ProviderResult(ok=False, error=str(e), latency_ms=(time.time()-start)*1000)

    async def health_check(self) -> bool:
        try:
            async with httpx.AsyncClient(timeout=10) as c:
                r = await c.post(f"{self.base_url}/messages",
                                 headers={"x-api-key": self.api_key, "anthropic-version": "2023-06-01", "content-type": "application/json"},
                                 json={"model": self.model, "messages": [{"role": "user", "content": "ping"}], "max_tokens": 1})
                return r.is_success
        except Exception:
            return False

    def supports_vision(self) -> bool:
        return "sonnet" in self.model or "opus" in self.model

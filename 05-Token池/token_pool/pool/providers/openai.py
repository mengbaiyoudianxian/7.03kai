"""P2-2: OpenAI 兼容 Provider"""
from __future__ import annotations
import time, logging
import httpx
from .base import BaseProvider, ProviderResult

log = logging.getLogger(__name__)


class OpenAIProvider(BaseProvider):
    """标准 OpenAI API 兼容 Provider（覆盖 OpenAI/DeepSeek/Qwen/GLM 等）"""

    def __init__(self, base_url: str, api_key: str, model: str, timeout: float = 120):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout

    async def chat(self, payload: dict, headers: dict | None = None) -> ProviderResult:
        url = f"{self.base_url}/chat/completions"
        hdrs = {"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"}
        if headers:
            hdrs.update(headers)
        body = {**payload, "model": self.model}
        start = time.time()
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as c:
                r = await c.post(url, json=body, headers=hdrs)
                data = r.json()
                latency = (time.time() - start) * 1000
                tokens = data.get("usage", {}).get("total_tokens", 0)
                return ProviderResult(ok=r.is_success, status_code=r.status_code,
                                      body=data, latency_ms=latency, tokens_used=tokens,
                                      error="" if r.is_success else data.get("error", {}).get("message", str(r.status_code)))
        except Exception as e:
            return ProviderResult(ok=False, error=str(e), latency_ms=(time.time()-start)*1000)

    async def health_check(self) -> bool:
        try:
            async with httpx.AsyncClient(timeout=10) as c:
                r = await c.get(f"{self.base_url}/models",
                                headers={"Authorization": f"Bearer {self.api_key}"})
                return r.is_success
        except Exception:
            return False

    def supports_vision(self) -> bool:
        return "gpt-4" in self.model or "vision" in self.model.lower()

    def supports_tools(self) -> bool:
        return True

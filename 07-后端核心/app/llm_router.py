"""
LLM Router — MBOS 唯一模型入口
所有模块通过这里调用 LLM，不再各自管理 key
"""
import os, httpx, time

class LLMRouter:
    def __init__(self):
        self.provider = os.getenv("MBOS_LLM_PROVIDER", "tokenpool")
        self.api_key = os.getenv("MBOS_LLM_KEY", "")
        self.base_url = os.getenv("MBOS_LLM_BASE_URL", "")
        self.model = os.getenv("MBOS_LLM_MODEL", "")

    def call(self, messages: list, max_tokens=2000) -> str:
        # Mode 1: mock
        if self.provider == "mock":
            return f"[MOCK] {messages[-1]['content'][:100]}"

        # Mode 2: explicit env key
        if self.provider != "tokenpool" and self.api_key:
            return self._call_api(self.base_url, self.api_key, self.model, messages, max_tokens)

        # Mode 3: token pool (default)
        return self._call_via_tokenpool(messages, max_tokens)

    def _call_via_tokenpool(self, messages, max_tokens) -> str:
        try:
            from app.token_pool import get_pool
            pool = get_pool()
            inst = [k for k in pool.keys if k.provider == "miclaw-bridge" and k.status == "working"]
            key = inst[0] if inst else pool.pick()
            if not key:
                return f"[MBOS] 收到: {messages[-1]['content'][:100]} (无可用Key)"
            return self._call_api(key.base_url, key.api_key, key.model, messages, max_tokens)
        except Exception as e:
            return f"[MBOS] Token池错误: {e}"

    def _call_api(self, base_url, api_key, model, messages, max_tokens) -> str:
        try:
            url = base_url.rstrip("/") + "/chat/completions"
            r = httpx.post(url,
                headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
                json={"model": model, "messages": messages, "max_tokens": max_tokens, "temperature": 0.7},
                timeout=60)
            if r.status_code == 200:
                return r.json()["choices"][0]["message"]["content"]
            return f"LLM HTTP {r.status_code}: {r.text[:100]}"
        except Exception as e:
            return f"LLM error: {e}"

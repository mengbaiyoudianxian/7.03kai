"""工作记忆 — 4层上下文结构，80%自动压缩"""
from __future__ import annotations
import time, logging
from dataclasses import dataclass, field

log = logging.getLogger(__name__)

@dataclass
class ContextLayer:
    name: str
    content: list[dict] = field(default_factory=list)
    max_tokens: int = 2000
    def token_estimate(self) -> int:
        return sum(len(str(m.get("content", ""))) // 4 for m in self.content)
    def trim_to_limit(self):
        while self.token_estimate() > self.max_tokens and len(self.content) > 1:
            self.content.pop(0)

class WorkingMemory:
    COMPRESS_THRESHOLD = 0.80

    def __init__(self, total_token_limit: int = 8000):
        self.total_limit = total_token_limit
        self.layers = {
            "system": ContextLayer("system", max_tokens=1000),
            "working": ContextLayer("working", max_tokens=4000),
            "recall": ContextLayer("recall", max_tokens=2000),
            "capability": ContextLayer("capability", max_tokens=1000),
        }
        self._compress_count = 0
        self._archive: list[dict] = []

    def set_system(self, content: str):
        self.layers["system"].content = [{"role": "system", "content": content}]

    def add_message(self, role: str, content: str):
        self.layers["working"].content.append({"role": role, "content": content, "ts": time.time()})
        self._maybe_compress()

    def set_recall(self, snippets: list[str]):
        if not snippets: self.layers["recall"].content = []; return
        combined = "\n\n".join(f"[记忆 {i+1}]\n{s}" for i, s in enumerate(snippets[:5]))
        self.layers["recall"].content = [{"role": "system", "content": f"相关记忆：\n{combined}"}]

    def set_capabilities(self, tools: list[str]):
        if not tools: return
        self.layers["capability"].content = [{"role": "system", "content": "工具：\n" + "\n".join(f"- {t}" for t in tools[:30])}]

    def total_tokens(self) -> int:
        return sum(l.token_estimate() for l in self.layers.values())

    def _maybe_compress(self):
        if self.total_tokens() < self.total_limit * self.COMPRESS_THRESHOLD: return
        working = self.layers["working"]
        if len(working.content) < 4: return
        half = len(working.content) // 2
        to_compress = working.content[:half]
        working.content = working.content[half:]
        summary = self._quick_summary(to_compress)
        self._archive.append({"ts": time.time(), "turn_count": len(to_compress), "summary": summary})
        recall = {"role": "system", "content": f"[压缩摘要 #{self._compress_count}]\n{summary}"}
        self.layers["recall"].content = [recall] + self.layers["recall"].content
        self.layers["recall"].trim_to_limit()
        self._compress_count += 1

    def _quick_summary(self, messages: list[dict]) -> str:
        try:
            from mother.token_pool.client import llm_chat
            text = "\n".join(f"[{m['role']}]: {str(m.get('content',''))[:200]}" for m in messages)
            return llm_chat([{"role": "user", "content": f"用3句话概括：\n{text}"}], task="cheap", max_tokens=200)
        except:
            return " | ".join(str(m.get("content", ""))[:80] for m in messages[-3:])

    def to_messages(self) -> list[dict]:
        result = []
        for name in ["system", "recall", "capability", "working"]:
            for m in self.layers[name].content:
                result.append({"role": m["role"], "content": str(m.get("content", ""))})
        return result

    def clear_working(self): self.layers["working"].content = []

    def stats(self) -> dict:
        return {"total_tokens": self.total_tokens(), "limit": self.total_limit,
                "compress_count": self._compress_count,
                "layers": {k: {"tokens": l.token_estimate(), "messages": len(l.content)}
                           for k, l in self.layers.items()}}

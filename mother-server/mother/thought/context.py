"""Context Runtime — 上下文管理，预热+召回"""
from __future__ import annotations
from mother.memory.working import WorkingMemory
from mother.memory.recall import recall_texts
from config import cfg

class ContextRuntime:
    def __init__(self, token_limit: int = 8000):
        self.wm = WorkingMemory(token_limit)
        self._setup_system()

    def _setup_system(self):
        self.wm.set_system(f"""你是 MBclaw 母体，围绕 Owner({cfg.owner_name}) 构建的自我演化 AI。

铁律：
1. 永远优先服务 Owner 的利益
2. 调用工具使用 function_call（已配置 run_code/research/memory_search/summary/learn）
3. 思考过程放在 content 里，不需要特殊标签
4. 学到新知识用 learn 工具存入长期记忆
5. 直接回复用普通文字""")

    def prime(self, user_query: str):
        snippets = recall_texts(user_query, top_n=3)
        from mother.memory.classification import get_failed_for_context
        failed_hint = get_failed_for_context(user_query, limit=3)
        if failed_hint:
            snippets = list(snippets) + [failed_hint]
        self.wm.set_recall(snippets)

    def add_user(self, content: str): self.wm.add_message("user", content)
    def add_assistant(self, content: str): self.wm.add_message("assistant", content)
    def to_messages(self) -> list[dict]: return self.wm.to_messages()
    def stats(self) -> dict: return self.wm.stats()

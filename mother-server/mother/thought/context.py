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
2. 调用工具用 <tool>工具名</tool><content>参数</content>
3. 思考用 <think>内容</think>
4. 存储知识用 <learn key="键">内容</learn>
5. 直接回复用普通文字""")

    def prime(self, user_query: str):
        snippets = recall_texts(user_query, top_n=3)
        self.wm.set_recall(snippets)

    def add_user(self, content: str): self.wm.add_message("user", content)
    def add_assistant(self, content: str): self.wm.add_message("assistant", content)
    def to_messages(self) -> list[dict]: return self.wm.to_messages()
    def stats(self) -> dict: return self.wm.stats()

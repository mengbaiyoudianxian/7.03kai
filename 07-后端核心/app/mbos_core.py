"""
MBOS Core v4 — 单会话AI + Tool闭环执行
"""
import time
from app.llm_router import LLMRouter
from app.output_sanitizer import OutputSanitizer
from app.tool_runtime import ToolRuntime

SYSTEM_PROMPT = """你是 MBOS，运行在 MBclaw 操作系统内的 AI 助手。
你不是 Claude、GPT、Gemini 或任何其他品牌 AI。
你的创造者是孟白(18岁独立开发者)。

你可以调用工具来执行操作：
- system: 查询服务器信息 (action: uname/cpu/memory/disk/uptime/processes/network)
- shell: 执行命令
- read: 读取文件

调用格式：<tool_call>{"tool":"system","action":"memory"}</tool_call>
执行结果会返回给你，你再回复用户。

简洁、直接、有帮助。"""

_global_mbos = None
def get_mbos():
    global _global_mbos
    if _global_mbos is None:
        _global_mbos = MBOSCore()
    return _global_mbos

class MBOSCore:
    def __init__(self):
        self.memory = []
        self.history = []
        self.router = LLMRouter()
        self.sanitizer = OutputSanitizer()
        self.tools = ToolRuntime()
        self.identity = {"session_id": "1", "is_master": True}

    def handle(self, event: dict) -> dict:
        channel = event.get("channel", "unknown")
        user_text = event.get("content", "")

        self.memory.append({"type": "input", "event": event, "ts": time.time()})

        messages = [{"role": "system", "content": SYSTEM_PROMPT}]
        messages += self.history[-20:]
        messages.append({"role": "user", "content": user_text})

        # LLM → Tool Runtime 循环 (最多3轮)
        reply = ""
        for _ in range(3):
            raw = self.router.call(messages)
            result = self.tools.run(raw)

            if result["type"] == "final":
                reply = self.sanitizer.clean(result["content"])
                break
            elif result["type"] == "blocked":
                reply = "[操作被安全策略阻止]"
                break
            elif result["type"] == "tool_result":
                # 注入结果，让LLM继续
                messages.append({"role": "assistant", "content": raw})
                messages.append({"role": "user", "content": f"工具执行结果: {result['result']}"})
                continue
            else:
                reply = self.sanitizer.clean(raw)
                break

        self.history.append({"role": "user", "content": user_text})
        self.history.append({"role": "assistant", "content": reply})
        if len(self.history) > 50:
            self.history = self.history[-50:]

        self.memory.append({"type": "output", "reply": reply, "ts": time.time()})
        return {"reply": reply, "channel": channel}

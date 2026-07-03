from abc import ABC, abstractmethod

class Worker(ABC):
    """Pure execution unit - stateless, no model choice, no strategy"""
    name: str = "base"

    @abstractmethod
    def run(self, task, context=None):
        pass

class LLMWorker(Worker):
    name = "llm"

    def __init__(self, llm_client=None):
        self._llm = llm_client

    def run(self, task, context=None):
        if self._llm is None:
            from app.llm import LLMClient
            self._llm = LLMClient()
        prompt = task.get("input", task.get("prompt", str(task)))
        try:
            import httpx, json
            resp = httpx.post(
                f"{self._llm.base_url}/chat/completions",
                headers={"Content-Type": "application/json",
                         **({"Authorization": f"Bearer {self._llm.api_key}"} if self._llm.api_key else {})},
                json={"model": self._llm.model, "messages": [
                    {"role": "user", "content": prompt}
                ], "temperature": 0.3, "max_tokens": 2000},
                timeout=120
            )
            resp.raise_for_status()
            return {"output": resp.json()["choices"][0]["message"]["content"], "model": self._llm.model}
        except Exception as e:
            return {"output": "", "error": str(e)}

class ToolWorker(Worker):
    name = "tool"

    def run(self, task, context=None):
        tool_name = task.get("tool", task.get("type", "unknown"))
        try:
            from app.tools import execute as exec_tool, get_tool
            result = exec_tool(None, tool_name, task.get("input", task.get("params", "")))
            return {"output": str(result), "tool": tool_name}
        except Exception as e:
            return {"output": "", "error": str(e), "tool": tool_name}

class MockWorker(Worker):
    """测试用 - 无LLM依赖"""
    name = "mock"

    def run(self, task, context=None):
        return {"output": f"[MOCK] executed: {task.get('type', task.get('id', '?'))}", "mock": True}

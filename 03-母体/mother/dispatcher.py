from .worker import LLMWorker, ToolWorker, MockWorker

class Dispatcher:
    """Task → Worker mapping. 执行桥."""

    def __init__(self):
        self._workers = {
            "llm": LLMWorker(),
            "tool": ToolWorker(),
            "mock": MockWorker(),
        }

    def select_worker(self, task):
        ttype = task.get("type", task.get("id", ""))
        if ttype in ("execute", "review", "analyze", "code", "search", "compress"):
            return self._workers.get("llm", self._workers["mock"])
        if ttype == "tool":
            return self._workers["tool"]
        return self._workers["mock"]

    def run(self, plan, context=None):
        """plan: topo_sort后的任务列表"""
        results = {}
        for task in plan:
            worker = self.select_worker(task)
            try:
                result = worker.run(task, context)
                results[task["id"]] = result
            except Exception as e:
                results[task["id"]] = {"error": str(e)}
        return results

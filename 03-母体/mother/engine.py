from .dag import DagCompiler
from .policy import PolicyEngine
from .dispatcher import Dispatcher
from .event_model import create_event
from .event_log import append_event

class ExecutionEngine:
    """Orchestrator only - DAG compile → policy validate → dispatch → log result"""

    def __init__(self):
        self.compiler = DagCompiler()
        self.policy = PolicyEngine()
        self.dispatcher = Dispatcher()
        # Token Pool fallback: 检查Token池是否有可用Key
        import os
        has_key = bool(os.environ.get("MBCLAW_LLM_API_KEY"))
        if not has_key:
            try:
                from app.token_pool import get_pool
                best = get_pool().get_best_for_llm()
                if best:
                    os.environ["MBCLAW_LLM_BASE_URL"] = best[0]
                    os.environ["MBCLAW_LLM_API_KEY"] = best[1]
                    os.environ["MBCLAW_LLM_MODEL"] = best[2]
                    has_key = True
            except: pass
        if not has_key:
            from .worker import MockWorker
            self.dispatcher._workers["llm"] = MockWorker()

    def execute(self, goal, context=None, trace_id=None):
        # 1. log intent
        append_event("intent.received", "user", {"goal": str(goal)[:200]})

        # 2. compile DAG
        dag = self.compiler.compile(goal, context)

        # 3. policy gate
        if not self.policy.validate(dag):
            risk = self.policy.risk_score(dag)
            append_event("execution.rejected", "policy",
                         {"goal": str(goal)[:200], "risk_score": risk})
            return {"status": "rejected", "risk_score": risk, "reason": "policy_blocked"}

        # 4. topo sort
        plan = self.compiler.topo_sort(dag)

        # 5. dispatch
        results = self.dispatcher.run(plan, context)

        # 6. log result
        append_event("execution.complete", "engine",
                     {"goal": str(goal)[:200], "tasks": len(plan),
                      "results": {k: str(v)[:200] for k, v in results.items()}})

        return {
            "status": "completed",
            "dag_size": len(dag["nodes"]),
            "tasks_executed": len(plan),
            "results": results,
        }

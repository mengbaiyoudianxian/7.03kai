"""Thought Runtime — Observe→Think→Plan→Act→Reflect→Learn 主循环

P5-2a: 删 <tool>/<think>/<learn> 正则解析，改用 OpenAI Function Calling 原生格式。
"""
from __future__ import annotations
import time, logging, json
from mother.thought.context import ContextRuntime
from mother.thought.reflect import reflect_and_learn
from mother.memory.episode import Episode
from mother.memory import knowledge
from mother.event_log import append_event
from config import cfg

log = logging.getLogger(__name__)

TOOLS = [
    {"type": "function", "function": {
        "name": "run_code",
        "description": "编写并执行代码片段",
        "parameters": {"type": "object", "properties": {
            "language": {"type": "string", "description": "编程语言 python/bash"},
            "code": {"type": "string", "description": "要执行的代码"},
            "goal": {"type": "string", "description": "这段代码要达成什么目的"},
        }, "required": ["language", "code"]},
    }},
    {"type": "function", "function": {
        "name": "research",
        "description": "搜索/研究某个主题",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string", "description": "搜索查询"},
            "depth": {"type": "string", "enum": ["quick", "deep"]},
        }, "required": ["query"]},
    }},
    {"type": "function", "function": {
        "name": "memory_search",
        "description": "搜索母体的历史记忆和经验",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string", "description": "搜索词"},
            "top_n": {"type": "integer", "description": "返回条数"},
        }, "required": ["query"]},
    }},
    {"type": "function", "function": {
        "name": "summary",
        "description": "总结/压缩长文本",
        "parameters": {"type": "object", "properties": {
            "text": {"type": "string", "description": "要总结的文本"},
        }, "required": ["text"]},
    }},
    {"type": "function", "function": {
        "name": "learn",
        "description": "将新知识存入长期记忆",
        "parameters": {"type": "object", "properties": {
            "key": {"type": "string", "description": "知识键名"},
            "value": {"type": "string", "description": "知识内容"},
            "category": {"type": "string", "enum": ["fact", "procedure", "rule"]},
        }, "required": ["key", "value"]},
    }},
    {"type": "function", "function": {
        "name": "shell",
        "description": "在服务器上执行 shell 命令，控制服务器",
        "parameters": {"type": "object", "properties": {
            "command": {"type": "string", "description": "要执行的 shell 命令"},
        }, "required": ["command"]},
    }},
]

TOOL_MAP = {
    "run_code":       ("run_code",       "code"),
    "research":       ("run_research",   "research"),
    "memory_search":  ("run_memory_search", "research"),
    "summary":        ("run_summary",    "cheap"),
    "learn":          ("_learn",         "cheap"),
    "shell":          ("run_shell",      "cheap"),
}


class ThoughtRuntime:
    def __init__(self):
        self.ctx = ContextRuntime()

    def run(self, goal: str, session_id: int = 0, max_turns: int = None) -> dict:
        max_turns = max_turns or cfg.max_iterations
        episode = Episode(goal, session_id)
        append_event("intent.received", "user", {"goal": goal[:200]})

        self.ctx.prime(goal); self.ctx.add_user(goal)
        episode.add_step("observe", goal)

        tool_calls_log = []; final_reply = ""; error_count = 0

        for turn in range(max_turns):
            try:
                from mother.token_pool.client import get_tp_client
                tp = get_tp_client()
                result = tp.chat_with_tools(
                    self.ctx.to_messages(), tools=TOOLS,
                    task="chat", model="deepseek-chat", max_tokens=2000)
                # P5-4: 成功后记录经验
                _record_call_result("success", result.get("alias",""), result.get("model",""),
                                   result.get("tokens",0), task="chat")
            except Exception as e:
                error_count += 1
                _record_call_result("failure", "", "", 0, task="chat", error=str(e)[:200])
                if error_count >= 3:
                    final_reply = f"LLM调用失败: {e}"
                    episode.add_step("error", str(e))
                    break
                time.sleep(1); continue

            # P5-2a: 用原生 tool_calls，不再正则解析
            tool_calls = result.get("tool_calls", [])
            content = result.get("content", "")

            if not tool_calls:
                final_reply = content; break

            self.ctx.add_assistant(json.dumps({"content": content, "tool_calls": tool_calls}, ensure_ascii=False) if content else "")
            results = []
            for tc in tool_calls:
                fn = tc.get("function", {})
                tool_name = fn.get("name", "")
                try:
                    args = json.loads(fn.get("arguments", "{}"))
                except Exception:
                    args = {}
                # 执行工具
                tool_result = self._execute_tool(tool_name, args)
                tool_calls_log.append({"tool": tool_name, "args": args, "result": tool_result[:200]})
                episode.add_step("act", f"[{tool_name}] {str(args)[:100]}", tool_result[:200])
                results.append(f"[{tool_name}] 结果:\n{tool_result[:800]}")
                append_event("task.done", "engine", {"tool": tool_name, "turn": turn})

            self.ctx.add_user("\n\n".join(results))

        if not final_reply:
            final_reply = f"已完成 {len(tool_calls_log)} 次工具调用。"

        self.ctx.add_assistant(final_reply); episode.add_step("respond", final_reply[:300])

        try:
            import threading
            threading.Thread(target=reflect_and_learn,
                args=(goal, final_reply, episode.steps, episode.id), daemon=True).start()
        except Exception:
            pass

        episode.complete(final_reply)
        append_event("execution.complete", "engine",
                     {"goal": goal[:200], "turns": turn + 1, "tool_calls": len(tool_calls_log)})

        return {"reply": final_reply, "episode_id": episode.id, "turns": turn + 1,
                "tool_calls": tool_calls_log, "ctx_stats": self.ctx.stats()}

    def _execute_tool(self, tool_name: str, args: dict) -> str:
        """P5-2c: 真正执行工具，而非假返回"""
        try:
            if tool_name == "learn":
                key = args.get("key", "").strip()
                val = args.get("value", "").strip()
                cat = args.get("category", "fact")
                if key and val:
                    knowledge.set(key, val, category=cat, source="thought_runtime")
                    return f"已学习: {key} = {val[:100]}"
                return "learn 参数不完整"

            if tool_name in ("run_code", "research", "memory_search", "summary", "shell"):
                from mother import workers
                import inspect
                worker_func = getattr(workers, TOOL_MAP[tool_name][0])
                valid_params = set(inspect.signature(worker_func).parameters)
                kwargs = {k: v for k, v in args.items() if k in valid_params}
                result = worker_func(**kwargs)
                return str(result) if result else "执行完成（无输出）"

            return f"未知工具: {tool_name}"
        except Exception as e:
            return f"工具执行失败: {e}"

    def reset(self):
        self.ctx = ContextRuntime()


def _record_call_result(status: str, alias: str, model: str, tokens: int, task: str, error: str = ""):
    """P5-4: 记录每次 LLM 调用结果到经验记忆"""
    try:
        from mother.memory import experience
        if status == "success":
            experience.add("success", f"模型 {alias or model} 在 {task} 任务上可用",
                          f"{model} 响应正常，{tokens} tokens", [alias, model, task])
        else:
            experience.add("failure", f"模型调用失败({task})",
                          f"错误: {error[:200]}", [task, error[:50]])
    except Exception:
        pass

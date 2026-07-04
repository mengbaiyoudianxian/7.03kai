"""Thought Runtime — Observe→Think→Plan→Act→Reflect→Learn 主循环"""
from __future__ import annotations
import re, time, logging
from mother.thought.context import ContextRuntime
from mother.thought.reflect import reflect_and_learn
from mother.memory.episode import Episode
from mother.event_log import append_event
from config import cfg

log = logging.getLogger(__name__)
TOOL_RE = re.compile(r'<tool>(.*?)</tool>\s*<content>(.*?)</content>', re.DOTALL)
THINK_RE = re.compile(r'<think>(.*?)</think>', re.DOTALL)
LEARN_RE = re.compile(r'<learn\s+key="([^"]+)">(.*?)</learn>', re.DOTALL)

class ThoughtRuntime:
    def __init__(self):
        self.ctx = ContextRuntime()

    def run(self, goal: str, session_id: int = 0, max_turns: int = None) -> dict:
        max_turns = max_turns or cfg.max_iterations
        episode = Episode(goal, session_id)
        append_event("intent.received", "user", {"goal": goal[:200]})

        self.ctx.prime(goal); self.ctx.add_user(goal)
        episode.add_step("observe", goal)

        tool_calls = []; final_reply = ""; error_count = 0

        for turn in range(max_turns):
            try:
                from mother.token_pool.client import llm_chat
                raw = llm_chat(self.ctx.to_messages(), task="chat", max_tokens=2000)
            except Exception as e:
                error_count += 1
                if error_count >= 3: final_reply = f"LLM调用失败: {e}"; episode.add_step("error", str(e)); break
                time.sleep(1); continue

            thinks = THINK_RE.findall(raw)
            if thinks: episode.add_step("think", thinks[0][:300])

            for key, val in LEARN_RE.findall(raw):
                from mother.memory import knowledge
                knowledge.set(key.strip(), val.strip(), source=f"episode:{episode.id}")
                episode.add_step("learn", f"{key}: {val[:100]}")

            tool_matches = TOOL_RE.findall(raw)
            if not tool_matches:
                final_reply = THINK_RE.sub("", LEARN_RE.sub("", raw)).strip(); break

            self.ctx.add_assistant(raw)
            results = []
            for tool_name, content in tool_matches:
                tool_name, content = tool_name.strip(), content.strip()
                # 简化的工具执行（可扩展）
                result = f"[{tool_name}] 已执行: {content[:100]}"
                tool_calls.append({"tool": tool_name, "content": content[:200], "result": result[:300]})
                episode.add_step("act", f"[{tool_name}] {content[:100]}", result[:200])
                results.append(f"[{tool_name}] 结果:\n{result[:800]}")
                append_event("task.done", "engine", {"tool": tool_name, "turn": turn})

            self.ctx.add_user("\n\n".join(results))

        if not final_reply: final_reply = f"已完成 {len(tool_calls)} 次工具调用。"
        self.ctx.add_assistant(final_reply); episode.add_step("respond", final_reply[:300])

        # 异步反思
        try:
            import threading
            threading.Thread(target=reflect_and_learn, args=(goal, final_reply, episode.steps, episode.id), daemon=True).start()
        except: pass

        episode.complete(final_reply)
        append_event("execution.complete", "engine", {"goal": goal[:200], "turns": turn+1, "tool_calls": len(tool_calls)})

        return {"reply": final_reply, "episode_id": episode.id, "turns": turn + 1,
                "tool_calls": tool_calls, "ctx_stats": self.ctx.stats()}

    def reset(self): self.ctx = ContextRuntime()

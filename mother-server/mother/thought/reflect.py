"""反思与学习 — Reflect + Learn 阶段"""
from __future__ import annotations
import json, logging
from mother.memory import experience, knowledge

log = logging.getLogger(__name__)

_PROMPT = """分析以下任务，输出 JSON：
{"success": true/false, "key_lesson": "核心教训≤100字",
 "experiences": [{"kind":"success|failure|lesson","title":"≤60字","content":"≤300字","keywords":["tag"]}],
 "knowledge_updates": [{"key":"键","value":"值","category":"fact|procedure|rule","confidence":0.0-1.0}]}
任务: {goal}
结果: {outcome}
步骤: {steps}"""

def reflect_and_learn(goal: str, outcome: str, steps: list[dict], episode_id: str = "") -> dict:
    try:
        from mother.token_pool.client import llm_chat
        steps_text = "\n".join(f"- [{s['type']}] {s['content'][:100]}" for s in steps[-10:])
        raw = llm_chat([{"role": "user", "content": _PROMPT.format(goal=goal[:200], outcome=outcome[:300], steps=steps_text[:800])}], task="cheap", max_tokens=800)
        clean = raw.strip()
        if clean.startswith("```"): clean = clean.split("```")[1].lstrip("json").strip()
        data = json.loads(clean)
        for exp in data.get("experiences", [])[:3]:
            experience.add(exp.get("kind","lesson"), exp.get("title",""), exp.get("content",""), exp.get("keywords",[]), episode_id)
        for ku in data.get("knowledge_updates", [])[:5]:
            knowledge.set(ku.get("key",""), ku.get("value",""), ku.get("category","fact"), f"episode:{episode_id}", confidence=float(ku.get("confidence",1.0)))
        log.info("反思: %d经验, %d知识", len(data.get("experiences",[])), len(data.get("knowledge_updates",[])))
        return data
    except Exception as e:
        log.warning("反思失败: %s", e)
        return {"success": True, "key_lesson": str(outcome)[:100], "experiences": [], "knowledge_updates": []}

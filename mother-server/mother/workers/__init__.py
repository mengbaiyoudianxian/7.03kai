"""Workers — 专业化子任务执行器"""
from __future__ import annotations
import logging
from mother.token_pool.client import llm_chat
from mother.memory import experience, knowledge
from mother.memory.recall import recall

log = logging.getLogger(__name__)

def run_code(task: str, lang: str = "Python", existing: str = "") -> dict:
    try:
        code = llm_chat([{"role": "user", "content": f"写{lang}代码: {task}\n已有代码: {existing[:1000] or '(无)'}"}], task="code", max_tokens=4000)
        return {"ok": True, "code": code, "lang": lang}
    except Exception as e: return {"ok": False, "error": str(e)}

def run_research(topic: str, background: str = "") -> dict:
    try:
        report = llm_chat([{"role": "user", "content": f"研究{topic}，输出结构化报告。背景: {background[:500] or '(无)'}"}], max_tokens=2000)
        knowledge.set(key=f"research:{topic[:60]}", value=report[:2000], category="research", confidence=0.8)
        return {"ok": True, "report": report, "topic": topic}
    except Exception as e: return {"ok": False, "error": str(e)}

def run_memory_search(query: str) -> dict:
    results = recall(query, top_n=10)
    return {"ok": True, "results": results, "count": len(results)}

def run_summary(text: str, style: str = "bullet", max_words: int = 300) -> dict:
    styles = {"bullet": "用5条以内要点列表", "paragraph": f"用{max_words}字段落", "tldr": "用一句话(50字内)"}
    try:
        summary = llm_chat([{"role": "user", "content": f"请{styles.get(style, styles['bullet'])}总结:\n{text[:3000]}"}], task="cheap", max_tokens=600)
        return {"ok": True, "summary": summary, "original_len": len(text)}
    except Exception as e: return {"ok": False, "error": str(e)}
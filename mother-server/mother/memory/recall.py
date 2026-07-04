"""记忆召回 — 统一跨层搜索"""
from __future__ import annotations
from mother.memory import experience, knowledge

def recall(query: str, top_n: int = 5) -> list[dict]:
    results = []
    for e in experience.search(query, limit=top_n):
        results.append({"layer": "experience", "score": e.get("score", 0.5),
                        "content": f"[经验/{e['kind']}] {e['title']}: {e['content'][:300]}", "id": e["id"]})
    for k in knowledge.search(query, limit=top_n):
        results.append({"layer": "knowledge", "score": k.get("confidence", 0.7),
                        "content": f"[知识/{k['category']}] {k['key']}: {str(k['value'])[:300]}", "id": k["id"]})
    results.sort(key=lambda x: x["score"], reverse=True)
    return results[:top_n]

def recall_texts(query: str, top_n: int = 5) -> list[str]:
    return [r["content"] for r in recall(query, top_n)]

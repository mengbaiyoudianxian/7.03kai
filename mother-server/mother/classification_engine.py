"""P0-1: LLM classification engine"""
import json, logging, time, re
from mother.memory.classification import add_node, get_tree, update_node
from mother.memory.episode import Episode
from mother.memory import knowledge

log = logging.getLogger(__name__)

_PROMPT = """You are a classifier. Output ONLY a JSON object:
{{"categories":[{{"name":"","summary":"","keywords":[]}}],"failed_approaches":[{{"approach":"","why_failed":"","lesson":""}}],"new_knowledge":[{{"key":"","value":"","category":"fact|rule|procedure"}}]}}
Dialogue: {content}"""


def _extract_json(raw):
    s = raw.strip()
    if s.startswith("```"):
        parts = s.split("```")
        s = parts[1] if len(parts) >= 2 else s
        s = s[4:] if s.startswith("json") else s
        s = s.strip()
    for fn in [
        lambda x: json.loads(x),
        lambda x: json.loads(x[x.find("{"):x.rfind("}")+1]),
        lambda x: json.loads(re.search(r"\{.+(?=\})", x, re.DOTALL).group()),
    ]:
        try:
            return fn(s)
        except Exception:
            continue
    raise ValueError(raw[:200])


def classify_episode(episode_id):
    ep = Episode.load(episode_id)
    if not ep or not ep.steps:
        return None
    steps = []
    for s in ep.steps[-20:]:
        t = s.get("type", "")
        c = str(s.get("content", ""))[:200]
        steps.append("[" + t + "] " + c)
    text = "Goal: " + (ep.goal or "")[:300] + "\nOutcome: " + (ep.outcome or "")[:300] + "\nSteps:\n" + "\n".join(steps)
    raw = ""
    try:
        from mother.token_pool.client import llm_chat
        raw = llm_chat([{"role":"user","content":_PROMPT.format(content=text[:4000])}], task="cheap", max_tokens=1500)
        data = _extract_json(raw)
    except Exception as e:
        log.warning("classify failed: %s raw=%.200s", e, raw)
        return None

    result = {"nodes_created": 0, "knowledge_added": 0}
    existing = {n["category_name"]: n for n in get_tree()}
    for c in data.get("categories", [])[:5]:
        name = c.get("name", "").strip()
        if name and name not in existing:
            add_node(parent_id=None, level=c.get("level", 1), category_name=name,
                     summary=c.get("summary", "")[:200], keywords=c.get("keywords", [])[:10],
                     source_episodes=[episode_id])
            result["nodes_created"] += 1

    for fa in data.get("failed_approaches", [])[:5]:
        combined = (fa.get("approach","") + " " + (ep.goal or "")).lower()
        best_node, best_score = None, 0
        for node in get_tree():
            score = 2 if node.get("category_name","").lower() in combined else 0
            kws = node.get("keywords", [])
            if isinstance(kws, str):
                try:
                    kws = json.loads(kws)
                except Exception:
                    kws = []
            score += sum(1 for kw in kws if kw.lower() in combined)
            if score > best_score:
                best_score, best_node = score, node
        if best_node:
            fas = best_node.get("failed_approaches", [])
            if isinstance(fas, str):
                try:
                    fas = json.loads(fas)
                except Exception:
                    fas = []
            fas.append({"approach": fa.get("approach","")[:200], "why_failed": fa.get("why_failed","")[:200],
                        "lesson": fa.get("lesson","")[:200], "episode_id": episode_id, "ts": time.time()})
            update_node(best_node["id"], failed_approaches=fas)

    for nk in data.get("new_knowledge", [])[:5]:
        k, v = nk.get("key","").strip(), nk.get("value","").strip()
        if k and v:
            knowledge.set(k, v, category=nk.get("category","fact"), source="classify:"+episode_id, confidence=0.7)
            result["knowledge_added"] += 1

    log.info("classify done: ep=%s +%d %d", episode_id, result["nodes_created"], result["knowledge_added"])
    return result

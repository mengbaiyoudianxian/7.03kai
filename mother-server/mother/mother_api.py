"""母体完整 REST API — 所有端点"""
from __future__ import annotations
import asyncio, logging
from fastapi import APIRouter, HTTPException, Query, BackgroundTasks
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/mother", tags=["mother"])
log = logging.getLogger(__name__)

_runtime = None
def _get_runtime():
    global _runtime
    if _runtime is None:
        from mother.thought.runtime import ThoughtRuntime
        _runtime = ThoughtRuntime()
    return _runtime

# ── 状态 ──
@router.get("/status")
def status():
    from mother.evolution.daily import get_state as evo_state
    from mother.owner import load_profile
    from mother.memory import experience, knowledge
    from mother.event_log import read_events
    try:
        return {"ok": True, "evolution": evo_state(),
                "owner": load_profile().get("name"),
                "memory": {"experience": experience.count(), "knowledge": len(knowledge.list_all(limit=9999)), "events": len(read_events(9999))},
                "context": _get_runtime().ctx.stats() if _runtime else {}}
    except Exception as e: return {"ok": False, "error": str(e)}

# ── 核心对话 ──
class RunReq(BaseModel):
    goal: str = Field(min_length=1, max_length=2000)
    session_id: int = 0
    max_turns: int = 0
    reset_context: bool = False

@router.post("/run")
def run(req: RunReq):
    rt = _get_runtime()
    if req.reset_context: rt.reset()
    try: return rt.run(req.goal, req.session_id, req.max_turns or None)
    except Exception as e:
        log.error("mother.run: %s", e); raise HTTPException(500, str(e))

@router.post("/reset")
def reset_context():
    _get_runtime().reset(); return {"ok": True}

# ── Memory ──
@router.get("/memory/recall")
def memory_recall(q: str = Query(""), n: int = Query(5)):
    from mother.memory.recall import recall
    return {"hits": recall(q, top_n=n)}

@router.get("/memory/experience")
def memory_experience(limit: int = 20):
    from mother.memory import experience
    return {"data": experience.list_recent(limit)}

@router.get("/memory/knowledge")
def memory_knowledge(category: str = "", q: str = "", limit: int = 50):
    from mother.memory import knowledge
    if q: return {"data": knowledge.search(q, limit)}
    return {"data": knowledge.list_all(category, limit)}

@router.post("/memory/knowledge")
def memory_knowledge_add(body: dict):
    from mother.memory import knowledge
    knowledge.set(body.get("key",""), body.get("value",""), body.get("category","fact"), "manual", confidence=body.get("confidence",1.0))
    return {"ok": True}

@router.get("/memory/episodes")
def memory_episodes(limit: int = 20):
    from mother.memory.episode import Episode
    return {"data": Episode.recent(limit)}

# ── Events ──
@router.get("/events")
def events(limit: int = 100):
    from mother.event_log import read_events
    return {"events": read_events(limit)}

# ── Evolution ──
@router.get("/evolution/state")
def evolution_state():
    from mother.evolution.daily import get_state, list_reports
    return {"state": get_state(), "recent_reports": list_reports(5)}

@router.get("/evolution/report/{date}")
def evolution_report(date: str):
    from mother.evolution.daily import get_report
    return get_report(date) or {}

@router.post("/evolution/trigger")
async def evolution_trigger(bg: BackgroundTasks):
    from mother.evolution.daily import run_evolution
    bg.add_task(run_evolution); return {"ok": True}

# ── Owner ──
@router.get("/owner/profile")
def owner_profile():
    from mother.owner import load_profile
    return load_profile()

@router.patch("/owner/profile")
def owner_profile_update(body: dict):
    from mother.owner import update_profile
    k = body.get("key",""); v = body.get("value")
    if not k: raise HTTPException(400, "key required")
    return update_profile(k, v)

# ── Goals ──
class GoalReq(BaseModel):
    title: str; description: str = ""; parent_id: str = ""
    priority: int = 5; tags: list = []

@router.get("/goals")
def goals_list(status: str = ""):
    from mother.owner import list_goals
    return {"goals": list_goals(status)}

@router.get("/goals/graph")
def goals_graph():
    from mother.owner import get_graph
    return get_graph()

@router.post("/goals")
def goals_add(req: GoalReq):
    from mother.owner import add_goal
    gid = add_goal(req.title, req.description, req.parent_id, req.priority, req.tags)
    return {"ok": True, "id": gid}

@router.patch("/goals/{gid}")
def goals_update(gid: str, body: dict):
    from mother.owner import update_goal
    if not update_goal(gid, **body): raise HTTPException(404, "not found")
    return {"ok": True}

@router.post("/goals/{gid}/complete")
def goals_complete(gid: str, body: dict = {}):
    from mother.owner import complete_goal
    complete_goal(gid, body.get("outcome","")); return {"ok": True}

@router.post("/goals/{gid}/milestone")
def goals_milestone(gid: str, body: dict):
    from mother.owner import add_milestone
    if not add_milestone(gid, body.get("title","")): raise HTTPException(404)
    return {"ok": True}

# ── Workers ──
class WorkerReq(BaseModel):
    worker: str; action: str = ""; params: dict = {}

@router.post("/worker/run")
def worker_run(req: WorkerReq):
    from mother import workers
    try:
        if req.worker == "code": return workers.run_code(**req.params)
        if req.worker == "research": return workers.run_research(**req.params)
        if req.worker == "memory_search": return workers.run_memory_search(**req.params)
        if req.worker == "summary": return workers.run_summary(**req.params)
        raise HTTPException(400, f"未知 worker: {req.worker}")
    except HTTPException: raise
    except Exception as e: raise HTTPException(500, str(e))

# ── Classification ──
@router.get("/classification/tree")
def classification_tree(root_id: int = None):
    from mother.memory.classification import get_tree
    return {"tree": get_tree(root_id)}

@router.get("/classification/failed")
def classification_failed(limit: int = 20):
    from mother.memory.classification import get_failed_approaches
    return {"failed": get_failed_approaches(limit)}

@router.post("/classification/classify/{episode_id}")
def classification_trigger(episode_id: str, bg: BackgroundTasks):
    from mother.classification_engine import classify_episode
    bg.add_task(classify_episode, episode_id)
    return {"ok": True}

@router.get("/classification/stats")
def classification_stats():
    from mother.memory.classification import count as node_count
    from mother.memory.experience import count as exp_count
    return {"classification_nodes": node_count(), "experiences": exp_count()}

# ── Token Pool ──
@router.get("/token_pool/health")
def tp_health():
    from mother.token_pool.client import get_tp_client
    return get_tp_client().health()

@router.get("/token_pool/models")
def tp_models():
    from mother.token_pool.client import get_tp_client
    return {"models": get_tp_client().models()}

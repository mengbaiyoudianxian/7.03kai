"""Owner Runtime — Owner 档案与目标图谱"""
from __future__ import annotations
import json, time, uuid
from pathlib import Path
from config import cfg

_PROFILE = Path(cfg.data_dir) / "mother" / "owner_profile.json"

def load_profile() -> dict:
    if _PROFILE.exists():
        try: return json.loads(_PROFILE.read_text())
        except: pass
    d = {"name": cfg.owner_name, "id": cfg.owner_id, "preferences": {"language": "zh-CN", "reply_style": "concise"}, "devices": {}, "services": {}, "updated_at": time.time()}
    _PROFILE.write_text(json.dumps(d, ensure_ascii=False, indent=2))
    return d

def save_profile(p: dict): p["updated_at"] = time.time(); _PROFILE.write_text(json.dumps(p, ensure_ascii=False, indent=2))

def update_profile(key: str, value):
    p = load_profile(); keys = key.split("."); d = p
    for k in keys[:-1]: d = d.setdefault(k, {})
    d[keys[-1]] = value; save_profile(p); return p

_GOALS = Path(cfg.data_dir) / "mother" / "goal_graph.json"

def _load_goals() -> dict:
    if _GOALS.exists():
        try: return json.loads(_GOALS.read_text())
        except: pass
    return {"goals": {}, "relations": []}

def _save_goals(g: dict): _GOALS.write_text(json.dumps(g, ensure_ascii=False, indent=2))

def add_goal(title: str, description: str = "", parent_id: str = "", priority: int = 5, tags: list = None) -> str:
    g = _load_goals(); gid = str(uuid.uuid4())[:8]
    g["goals"][gid] = {"id": gid, "title": title, "description": description, "status": "active", "priority": priority, "tags": tags or [], "parent_id": parent_id, "progress": 0, "created_at": time.time(), "updated_at": time.time(), "milestones": [], "notes": []}
    if parent_id: g["relations"].append({"from": parent_id, "to": gid, "type": "sub_goal"})
    _save_goals(g); return gid

def update_goal(gid: str, **kwargs) -> bool:
    g = _load_goals()
    if gid not in g["goals"]: return False
    g["goals"][gid].update(kwargs); g["goals"][gid]["updated_at"] = time.time(); _save_goals(g); return True

def complete_goal(gid: str, outcome: str = ""): update_goal(gid, status="completed", progress=100)

def list_goals(status: str = "") -> list[dict]:
    g = _load_goals(); goals = list(g["goals"].values())
    if status: goals = [gl for gl in goals if gl["status"] == status]
    return sorted(goals, key=lambda x: (-x["priority"], -x["updated_at"]))

def get_goal(gid: str) -> dict | None: return _load_goals()["goals"].get(gid)
def get_graph() -> dict: return _load_goals()

def add_milestone(gid: str, title: str) -> bool:
    g = _load_goals()
    if gid not in g["goals"]: return False
    g["goals"][gid]["milestones"].append({"title": title, "done": False, "ts": time.time()})
    g["goals"][gid]["updated_at"] = time.time(); _save_goals(g); return True
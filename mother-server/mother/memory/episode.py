"""情节记忆 — P5-3d: JSON→mbclaw.db mother_episodes 表"""
from __future__ import annotations
import json, time, uuid
from .registry import get_db

class Episode:
    def __init__(self, goal: str, session_id: int = 0):
        self.id = str(uuid.uuid4())[:8]; self.goal = goal; self.session_id = session_id
        self.started_at = time.time(); self.ended_at: float = 0
        self.status = "running"; self.steps: list[dict] = []
        self.outcome = ""; self.tokens_used = 0; self.cost = 0.0
    def add_step(self, step_type: str, content: str, result: str = ""):
        self.steps.append({"ts": time.time(), "type": step_type, "content": content[:500], "result": result[:500]})
    def complete(self, outcome: str, tokens: int = 0, cost: float = 0.0):
        self.ended_at = time.time(); self.status = "completed"
        self.outcome = outcome; self.tokens_used = tokens; self.cost = cost; self._save()
    def fail(self, reason: str):
        self.ended_at = time.time(); self.status = "failed"; self.outcome = reason; self._save()
    def _save(self):
        conn = get_db()
        conn.execute("INSERT OR REPLACE INTO mother_episodes(id,goal,session_id,status,steps,outcome,tokens_used,cost,started_at,ended_at) VALUES(?,?,?,?,?,?,?,?,?,?)", (self.id, self.goal, self.session_id, self.status, json.dumps(self.steps, ensure_ascii=False), self.outcome, self.tokens_used, self.cost, self.started_at, self.ended_at))
        conn.commit()
    @classmethod
    def load(cls, eid: str):
        conn = get_db(); row = conn.execute("SELECT * FROM mother_episodes WHERE id=?", (eid,)).fetchone()
        if not row: return None
        d = dict(row); d["steps"] = json.loads(d.get("steps", "[]"))
        ep = cls.__new__(cls); ep.__dict__.update(d); return ep
    @classmethod
    def recent(cls, limit: int = 20) -> list[dict]:
        conn = get_db()
        return [dict(r) for r in conn.execute("SELECT * FROM mother_episodes ORDER BY started_at DESC LIMIT ?", (limit,)).fetchall()]

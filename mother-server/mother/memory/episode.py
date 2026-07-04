"""情节记忆 — 单次任务完整记录"""
from __future__ import annotations
import json, time, uuid
from pathlib import Path
from config import cfg

_DIR = Path(cfg.data_dir) / "mother" / "episodes"
_DIR.mkdir(parents=True, exist_ok=True)

class Episode:
    def __init__(self, goal: str, session_id: int = 0):
        self.id = str(uuid.uuid4())[:8]
        self.goal = goal; self.session_id = session_id
        self.started_at = time.time(); self.ended_at: float = 0
        self.status = "running"; self.steps: list[dict] = []
        self.outcome = ""; self.tokens_used = 0; self.cost = 0.0

    def add_step(self, step_type: str, content: str, result: str = ""):
        self.steps.append({"ts": time.time(), "type": step_type, "content": content[:500], "result": result[:500]})

    def complete(self, outcome: str, tokens: int = 0, cost: float = 0.0):
        self.ended_at = time.time(); self.status = "completed"
        self.outcome = outcome; self.tokens_used = tokens; self.cost = cost
        self._save()

    def fail(self, reason: str):
        self.ended_at = time.time(); self.status = "failed"; self.outcome = reason; self._save()

    def _save(self):
        (_DIR / f"{self.id}.json").write_text(json.dumps(self.__dict__, ensure_ascii=False, indent=2))

    @classmethod
    def load(cls, eid: str) -> "Episode | None":
        p = _DIR / f"{eid}.json"
        if not p.exists(): return None
        d = json.loads(p.read_text()); ep = cls.__new__(cls); ep.__dict__.update(d); return ep

    @classmethod
    def recent(cls, limit: int = 20) -> list[dict]:
        files = sorted(_DIR.glob("*.json"), key=lambda f: f.stat().st_mtime, reverse=True)[:limit]
        return [json.loads(f.read_text()) for f in files]

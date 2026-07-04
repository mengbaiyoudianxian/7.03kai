"""事件日志 — P5-3e: JSON→mbclaw.db mother_events 表"""
from __future__ import annotations
import json, time
from mother.memory.registry import get_db

def append_event(event_type: str, source: str = "", payload: dict | None = None):
    conn = get_db()
    conn.execute("INSERT INTO mother_events(event_type,source,payload,ts) VALUES(?,?,?,?)", (event_type, source, json.dumps(payload or {}, ensure_ascii=False), time.time()))
    conn.commit()

def read_events(limit: int = 100) -> list[dict]:
    conn = get_db()
    rows = conn.execute("SELECT * FROM mother_events ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
    result = [dict(r) for r in reversed(rows)]
    for r in result:
        try: r["payload"] = json.loads(r.get("payload", "{}"))
        except: r["payload"] = {}
    return result

"""经验记忆 — P5-3b: 迁移到 mbclaw.db mother_experiences 表"""
from __future__ import annotations
import json, time
from .registry import get_db


def add(kind: str, title: str, content: str, keywords: list[str] | None = None, source_ep: str = "") -> int:
    conn = get_db()
    cur = conn.execute(
        "INSERT INTO mother_experiences(kind,title,content,keywords,source_ep,created_at) VALUES(?,?,?,?,?,?)",
        (kind, title, content, json.dumps(keywords or [], ensure_ascii=False), source_ep, time.time()))
    conn.commit()
    return cur.lastrowid

def search(query: str, limit: int = 10) -> list[dict]:
    conn = get_db()
    rows = conn.execute(
        "SELECT * FROM mother_experiences WHERE content LIKE ? ORDER BY score DESC, created_at DESC LIMIT ?",
        (f"%{query}%", limit)).fetchall()
    return [dict(r) for r in rows]

def use(exp_id: int):
    conn = get_db()
    conn.execute("UPDATE mother_experiences SET use_count=use_count+1, last_used=? WHERE id=?", (time.time(), exp_id))
    conn.commit()

def list_recent(limit: int = 20) -> list[dict]:
    conn = get_db()
    rows = conn.execute("SELECT * FROM mother_experiences ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()
    return [dict(r) for r in rows]

def count() -> int:
    conn = get_db()
    return conn.execute("SELECT COUNT(*) FROM mother_experiences").fetchone()[0]

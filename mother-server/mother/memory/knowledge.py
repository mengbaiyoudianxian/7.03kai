"""知识记忆 — P5-3c: 迁移到 mbclaw.db mother_knowledge 表"""
from __future__ import annotations
import time
from .registry import get_db


def set(key: str, value: str, category: str = "fact", source: str = "", confidence: float = 1.0):
    conn = get_db()
    now = time.time()
    row = conn.execute("SELECT id FROM mother_knowledge WHERE key=?", (key,)).fetchone()
    if row:
        conn.execute("UPDATE mother_knowledge SET value=?,category=?,source=?,confidence=?,updated_at=? WHERE id=?", (value, category, source, confidence, now, row["id"]))
    else:
        conn.execute("INSERT INTO mother_knowledge(key,value,category,source,confidence,created_at,updated_at) VALUES(?,?,?,?,?,?,?)", (key, value, category, source, confidence, now, now))
    conn.commit()

def get(key: str) -> dict | None:
    conn = get_db()
    row = conn.execute("SELECT * FROM mother_knowledge WHERE key=?", (key,)).fetchone()
    return dict(row) if row else None

def search(query: str, limit: int = 20) -> list[dict]:
    conn = get_db()
    rows = conn.execute("SELECT * FROM mother_knowledge WHERE key LIKE ? OR value LIKE ? ORDER BY updated_at DESC LIMIT ?", (f"%{query}%", f"%{query}%", limit)).fetchall()
    return [dict(r) for r in rows]

def list_all(category: str = "", limit: int = 100) -> list[dict]:
    conn = get_db()
    if category:
        rows = conn.execute("SELECT * FROM mother_knowledge WHERE category=? ORDER BY updated_at DESC LIMIT ?", (category, limit)).fetchall()
    else:
        rows = conn.execute("SELECT * FROM mother_knowledge ORDER BY updated_at DESC LIMIT ?", (limit,)).fetchall()
    return [dict(r) for r in rows]

"""知识记忆 — 结构化知识图谱"""
from __future__ import annotations
import json, time, sqlite3
from pathlib import Path
from config import cfg

_DB = Path(cfg.data_dir) / "mother" / "knowledge.db"
_DB.parent.mkdir(parents=True, exist_ok=True)
_conn = sqlite3.connect(str(_DB), check_same_thread=False)
_conn.row_factory = sqlite3.Row
_conn.executescript("""
CREATE TABLE IF NOT EXISTS nodes (id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT UNIQUE, category TEXT DEFAULT 'fact', value TEXT, source TEXT DEFAULT '', tags TEXT DEFAULT '[]', created_at REAL, updated_at REAL, confidence REAL DEFAULT 1.0);
CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(key, value, content='nodes', content_rowid='id', tokenize='unicode61');
CREATE TRIGGER IF NOT EXISTS nodes_ai AFTER INSERT ON nodes BEGIN INSERT INTO nodes_fts(rowid, key, value) VALUES(new.id, new.key, new.value); END;
CREATE TABLE IF NOT EXISTS relations (id INTEGER PRIMARY KEY AUTOINCREMENT, from_key TEXT, rel TEXT, to_key TEXT, UNIQUE(from_key, rel, to_key));
""")
_conn.commit()

def set(key: str, value: str, category: str = "fact", source: str = "", tags: list = None, confidence: float = 1.0):
    now = time.time()
    _conn.execute("INSERT INTO nodes(key,category,value,source,tags,created_at,updated_at,confidence) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at, confidence=excluded.confidence",
        (key, category, str(value)[:5000], source, json.dumps(tags or []), now, now, confidence)); _conn.commit()

def get(key: str) -> dict | None:
    row = _conn.execute("SELECT * FROM nodes WHERE key=?", (key,)).fetchone(); return dict(row) if row else None

def search(query: str, limit: int = 10) -> list[dict]:
    try:
        rows = _conn.execute("SELECT n.* FROM nodes_fts JOIN nodes n ON n.id=nodes_fts.rowid WHERE nodes_fts MATCH ? ORDER BY rank LIMIT ?", (f'"{query}"', limit)).fetchall()
    except:
        rows = _conn.execute("SELECT * FROM nodes WHERE key LIKE ? OR value LIKE ? LIMIT ?", (f"%{query}%", f"%{query}%", limit)).fetchall()
    return [dict(r) for r in rows]

def list_all(category: str = "", limit: int = 50) -> list[dict]:
    q = "SELECT * FROM nodes" + (f" WHERE category=?" if category else "") + " ORDER BY updated_at DESC LIMIT ?"
    args = (category, limit) if category else (limit,)
    return [dict(r) for r in _conn.execute(q, args).fetchall()]

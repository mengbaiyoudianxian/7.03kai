"""经验记忆 — FTS全文搜索"""
from __future__ import annotations
import json, time, sqlite3
from pathlib import Path
from config import cfg

_DB = Path(cfg.data_dir) / "mother" / "experience.db"
_DB.parent.mkdir(parents=True, exist_ok=True)
_conn = sqlite3.connect(str(_DB), check_same_thread=False)
_conn.row_factory = sqlite3.Row
_conn.executescript("""
CREATE TABLE IF NOT EXISTS experiences (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT DEFAULT 'lesson', title TEXT, content TEXT, keywords TEXT DEFAULT '[]', source_ep TEXT DEFAULT '', created_at REAL, last_used REAL, use_count INTEGER DEFAULT 0, score REAL DEFAULT 0.5);
CREATE VIRTUAL TABLE IF NOT EXISTS exp_fts USING fts5(title, content, content='experiences', content_rowid='id', tokenize='unicode61');
CREATE TRIGGER IF NOT EXISTS exp_ai AFTER INSERT ON experiences BEGIN INSERT INTO exp_fts(rowid, title, content) VALUES(new.id, new.title, new.content); END;
""")
_conn.commit()

def add(kind: str, title: str, content: str, keywords: list[str], source_ep: str = "") -> int:
    cur = _conn.execute("INSERT INTO experiences(kind,title,content,keywords,source_ep,created_at) VALUES(?,?,?,?,?,?)",
        (kind, title[:200], content[:2000], json.dumps(keywords), source_ep, time.time()))
    _conn.commit(); return cur.lastrowid

def search(query: str, limit: int = 5) -> list[dict]:
    try:
        rows = _conn.execute("SELECT e.* FROM exp_fts JOIN experiences e ON e.id=exp_fts.rowid WHERE exp_fts MATCH ? ORDER BY rank LIMIT ?", (f'"{query}"', limit)).fetchall()
    except:
        rows = _conn.execute("SELECT * FROM experiences WHERE title LIKE ? OR content LIKE ? ORDER BY score DESC LIMIT ?", (f"%{query}%", f"%{query}%", limit)).fetchall()
    return [dict(r) for r in rows]

def use(exp_id: int):
    _conn.execute("UPDATE experiences SET use_count=use_count+1, last_used=?, score=MIN(1.0,score+0.05) WHERE id=?", (time.time(), exp_id)); _conn.commit()

def list_recent(limit: int = 20) -> list[dict]:
    return [dict(r) for r in _conn.execute("SELECT * FROM experiences ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()]

def count() -> int:
    return _conn.execute("SELECT COUNT(*) FROM experiences").fetchone()[0]

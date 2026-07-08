"""P5-3a: 母体统一 DB 连接 — 所有记忆模块共用 mbclaw.db"""
from __future__ import annotations
import sqlite3, threading
from config import cfg

_conn: sqlite3.Connection | None = None
_lock = threading.Lock()


def get_db() -> sqlite3.Connection:
    global _conn
    if _conn is None:
        path = cfg.resolved_db_path()
        _conn = sqlite3.connect(path, check_same_thread=False)
        _conn.row_factory = sqlite3.Row
        _init_tables(_conn)
    return _conn


def _init_tables(conn: sqlite3.Connection):
    conn.executescript("""
    CREATE TABLE IF NOT EXISTS mother_experiences (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        kind TEXT DEFAULT 'lesson',
        title TEXT,
        content TEXT,
        keywords TEXT DEFAULT '[]',
        source_ep TEXT DEFAULT '',
        created_at REAL,
        last_used REAL,
        use_count INTEGER DEFAULT 0,
        score REAL DEFAULT 0.5
    );
    CREATE TABLE IF NOT EXISTS mother_knowledge (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        key TEXT UNIQUE,
        value TEXT,
        category TEXT DEFAULT 'fact',
        source TEXT DEFAULT '',
        confidence REAL DEFAULT 1.0,
        created_at REAL,
        updated_at REAL
    );
    CREATE TABLE IF NOT EXISTS mother_episodes (
        id TEXT PRIMARY KEY,
        goal TEXT,
        session_id INTEGER DEFAULT 0,
        status TEXT DEFAULT 'running',
        steps TEXT DEFAULT '[]',
        outcome TEXT DEFAULT '',
        tokens_used INTEGER DEFAULT 0,
        cost REAL DEFAULT 0.0,
        started_at REAL,
        ended_at REAL
    );
    CREATE TABLE IF NOT EXISTS mother_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        event_type TEXT,
        source TEXT DEFAULT '',
        payload TEXT DEFAULT '{}',
        ts REAL
    );
    CREATE TABLE IF NOT EXISTS mother_classification_nodes (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        parent_id INTEGER REFERENCES mother_classification_nodes(id),
        level INTEGER DEFAULT 1,
        category_name TEXT,
        summary TEXT DEFAULT '',
        summary_detailed TEXT DEFAULT '',
        failed_approaches TEXT DEFAULT '[]',
        keywords TEXT DEFAULT '[]',
        source_episodes TEXT DEFAULT '[]',
        use_count INTEGER DEFAULT 0,
        created_at REAL,
        updated_at REAL
    );
    """)
    conn.commit()

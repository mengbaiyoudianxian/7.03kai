"""Key注册表 — SQLite持久化，线程安全"""
from __future__ import annotations
import sqlite3, json, time, threading
from pathlib import Path
from dataclasses import dataclass, field, asdict
from config import cfg

@dataclass
class ProviderKey:
    id: int = 0
    alias: str = ""          # 别名，如 "openai-main"
    provider: str = ""       # openai / anthropic / deepseek / custom / miclaw
    base_url: str = ""       # https://api.openai.com/v1
    api_key: str = ""        # sk-...
    model: str = ""          # gpt-4o / claude-sonnet-4-6
    cost_per_1k: float = 0.01
    priority: int = 5        # 1-10，越高越优先
    enabled: bool = True
    # 运行时字段（不存入主表）
    status: str = "unknown"  # working / failed / circuit_open
    success_count: int = 0
    fail_count: int = 0
    total_tokens: int = 0
    total_cost: float = 0.0
    avg_latency_ms: float = 0.0
    last_checked: float = 0
    last_error: str = ""

BUILTIN = [
    ProviderKey(alias="openai-gpt4o",       provider="openai",    base_url="https://api.openai.com/v1",          model="gpt-4o",              cost_per_1k=0.005,  priority=9),
    ProviderKey(alias="openai-gpt4o-mini",  provider="openai",    base_url="https://api.openai.com/v1",          model="gpt-4o-mini",         cost_per_1k=0.00015,priority=7),
    ProviderKey(alias="openai-gpt41",       provider="openai",    base_url="https://api.openai.com/v1",          model="gpt-4.1",             cost_per_1k=0.002,  priority=8),
    ProviderKey(alias="anthropic-sonnet",   provider="anthropic", base_url="https://api.anthropic.com/v1",       model="claude-sonnet-4-6",   cost_per_1k=0.003,  priority=10),
    ProviderKey(alias="anthropic-haiku",    provider="anthropic", base_url="https://api.anthropic.com/v1",       model="claude-haiku-4-5-20251001", cost_per_1k=0.00025, priority=6),
    ProviderKey(alias="deepseek-chat",      provider="deepseek",  base_url="https://api.deepseek.com/v1",        model="deepseek-chat",       cost_per_1k=0.00014,priority=5),
    ProviderKey(alias="deepseek-reasoner",  provider="deepseek",  base_url="https://api.deepseek.com/v1",        model="deepseek-reasoner",   cost_per_1k=0.00055,priority=4),
    ProviderKey(alias="qwen-plus",          provider="dashscope", base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", model="qwen-plus", cost_per_1k=0.0004, priority=4),
    ProviderKey(alias="miclaw-bridge",      provider="miclaw",    base_url="http://100.126.55.0:8765/v1",        model="miclaw",              cost_per_1k=0.0,    priority=3),
    ProviderKey(alias="local-ollama",       provider="local",     base_url="http://localhost:11434/v1",           model="llama3",              cost_per_1k=0.0,    priority=1),
]

class Registry:
    def __init__(self):
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(cfg.db_path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._init_db()
        self._seed()

    def _init_db(self):
        self._conn.executescript("""
        CREATE TABLE IF NOT EXISTS keys (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            alias       TEXT UNIQUE NOT NULL,
            provider    TEXT NOT NULL,
            base_url    TEXT NOT NULL,
            api_key     TEXT NOT NULL DEFAULT '',
            model       TEXT NOT NULL,
            cost_per_1k REAL NOT NULL DEFAULT 0.01,
            priority    INTEGER NOT NULL DEFAULT 5,
            enabled     INTEGER NOT NULL DEFAULT 1,
            created_at  REAL NOT NULL DEFAULT (unixepoch('now'))
        );
        CREATE TABLE IF NOT EXISTS key_stats (
            alias         TEXT PRIMARY KEY,
            status        TEXT NOT NULL DEFAULT 'unknown',
            success_count INTEGER NOT NULL DEFAULT 0,
            fail_count    INTEGER NOT NULL DEFAULT 0,
            total_tokens  INTEGER NOT NULL DEFAULT 0,
            total_cost    REAL NOT NULL DEFAULT 0.0,
            avg_latency   REAL NOT NULL DEFAULT 0.0,
            last_checked  REAL NOT NULL DEFAULT 0,
            last_error    TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS call_log (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            alias       TEXT NOT NULL,
            ts          REAL NOT NULL,
            latency_ms  REAL NOT NULL,
            tokens      INTEGER NOT NULL DEFAULT 0,
            cost        REAL NOT NULL DEFAULT 0,
            success     INTEGER NOT NULL,
            error       TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS idx_call_log_alias ON call_log(alias);
        CREATE INDEX IF NOT EXISTS idx_call_log_ts    ON call_log(ts);
        """)
        self._conn.commit()

    def _seed(self):
        for pk in BUILTIN:
            try:
                self._conn.execute(
                    "INSERT OR IGNORE INTO keys(alias,provider,base_url,api_key,model,cost_per_1k,priority) VALUES(?,?,?,?,?,?,?)",
                    (pk.alias, pk.provider, pk.base_url, pk.api_key, pk.model, pk.cost_per_1k, pk.priority))
            except: pass
        self._conn.commit()

    def _row_to_key(self, row) -> ProviderKey:
        pk = ProviderKey(
            id=row["id"], alias=row["alias"], provider=row["provider"],
            base_url=row["base_url"], api_key=row["api_key"],
            model=row["model"], cost_per_1k=row["cost_per_1k"],
            priority=row["priority"], enabled=bool(row["enabled"]),
        )
        stat = self._conn.execute("SELECT * FROM key_stats WHERE alias=?", (pk.alias,)).fetchone()
        if stat:
            pk.status = stat["status"]; pk.success_count = stat["success_count"]
            pk.fail_count = stat["fail_count"]; pk.total_tokens = stat["total_tokens"]
            pk.total_cost = stat["total_cost"]; pk.avg_latency_ms = stat["avg_latency"]
            pk.last_checked = stat["last_checked"]; pk.last_error = stat["last_error"]
        return pk

    def all(self, enabled_only=False) -> list[ProviderKey]:
        with self._lock:
            q = "SELECT * FROM keys" + (" WHERE enabled=1" if enabled_only else "") + " ORDER BY priority DESC"
            rows = self._conn.execute(q).fetchall()
            return [self._row_to_key(r) for r in rows]

    def get(self, alias: str) -> ProviderKey | None:
        with self._lock:
            row = self._conn.execute("SELECT * FROM keys WHERE alias=?", (alias,)).fetchone()
            return self._row_to_key(row) if row else None

    def upsert(self, pk: ProviderKey) -> ProviderKey:
        with self._lock:
            if pk.id:
                self._conn.execute(
                    "UPDATE keys SET alias=?,provider=?,base_url=?,api_key=?,model=?,cost_per_1k=?,priority=?,enabled=? WHERE id=?",
                    (pk.alias,pk.provider,pk.base_url,pk.api_key,pk.model,pk.cost_per_1k,pk.priority,int(pk.enabled),pk.id))
            else:
                self._conn.execute(
                    "INSERT OR REPLACE INTO keys(alias,provider,base_url,api_key,model,cost_per_1k,priority,enabled) VALUES(?,?,?,?,?,?,?,?)",
                    (pk.alias,pk.provider,pk.base_url,pk.api_key,pk.model,pk.cost_per_1k,pk.priority,int(pk.enabled)))
            self._conn.commit()
            row = self._conn.execute("SELECT * FROM keys WHERE alias=?", (pk.alias,)).fetchone()
            return self._row_to_key(row)

    def delete(self, alias: str):
        with self._lock:
            self._conn.execute("DELETE FROM keys WHERE alias=?", (alias,))
            self._conn.execute("DELETE FROM key_stats WHERE alias=?", (alias,))
            self._conn.commit()

    def update_stat(self, alias: str, status: str, latency_ms: float, tokens: int, cost: float, success: bool, error: str = ""):
        with self._lock:
            now = time.time()
            self._conn.execute("""
                INSERT INTO key_stats(alias,status,success_count,fail_count,total_tokens,total_cost,avg_latency,last_checked,last_error)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(alias) DO UPDATE SET
                    status=excluded.status,
                    success_count = success_count + excluded.success_count,
                    fail_count    = fail_count    + excluded.fail_count,
                    total_tokens  = total_tokens  + excluded.total_tokens,
                    total_cost    = total_cost    + excluded.total_cost,
                    avg_latency   = (avg_latency * (success_count+fail_count) + excluded.avg_latency)
                                    / MAX(1, success_count+fail_count+1),
                    last_checked  = excluded.last_checked,
                    last_error    = excluded.last_error
            """, (alias, status, 1 if success else 0, 0 if success else 1,
                  tokens, cost, latency_ms, now, error))
            self._conn.execute(
                "INSERT INTO call_log(alias,ts,latency_ms,tokens,cost,success,error) VALUES(?,?,?,?,?,?,?)",
                (alias, now, latency_ms, tokens, cost, 1 if success else 0, error[:200]))
            self._conn.commit()

    def call_log(self, alias: str = "", limit: int = 100) -> list[dict]:
        with self._lock:
            if alias:
                rows = self._conn.execute(
                    "SELECT * FROM call_log WHERE alias=? ORDER BY ts DESC LIMIT ?", (alias, limit)).fetchall()
            else:
                rows = self._conn.execute(
                    "SELECT * FROM call_log ORDER BY ts DESC LIMIT ?", (limit,)).fetchall()
            return [dict(r) for r in rows]

    def set_key_value(self, alias: str, api_key: str):
        with self._lock:
            self._conn.execute("UPDATE keys SET api_key=? WHERE alias=?", (api_key, alias))
            self._conn.commit()

_registry: Registry | None = None
def get_registry() -> Registry:
    global _registry
    if _registry is None: _registry = Registry()
    return _registry

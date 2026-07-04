"""Key注册表 — SQLite持久化，线程安全。P0：加密+多用户"""
from __future__ import annotations
import sqlite3, secrets, time, threading
from dataclasses import dataclass
from config import cfg
from pool.encryption import encrypt, decrypt, mask_key


@dataclass
class ProviderKey:
    id: int = 0
    alias: str = ""
    provider: str = ""       # openai/anthropic/deepseek/dashscope/gemini/openrouter/miclaw/local/user-shared/custom
    base_url: str = ""
    api_key: str = ""        # 明文（仅内存中），数据库存密文
    model: str = ""
    cost_per_1k: float = 0.01
    priority: int = 5
    enabled: bool = True
    # 速率限制
    rpm_limit: int | None = None
    rpd_limit: int | None = None
    tpm_limit: int | None = None
    tpd_limit: int | None = None
    # 运行时字段
    status: str = "unknown"
    success_count: int = 0
    fail_count: int = 0
    total_tokens: int = 0
    total_cost: float = 0.0
    avg_latency_ms: float = 0.0
    last_checked: float = 0
    last_error: str = ""


@dataclass
class User:
    id: int = 0
    username: str = ""
    password_hash: str = ""
    token: str = ""
    share_ratio: float = 0.0
    quota_limit: int = 0
    balance: float = 0.0
    role: str = "user"
    enabled: bool = True
    created_at: float = 0


BUILTIN = [
    ProviderKey(alias="openai-gpt4o",       provider="openai",    base_url="https://api.openai.com/v1",          model="gpt-4o",              cost_per_1k=0.005,  priority=9),
    ProviderKey(alias="openai-gpt4o-mini",  provider="openai",    base_url="https://api.openai.com/v1",          model="gpt-4o-mini",         cost_per_1k=0.00015,priority=7),
    ProviderKey(alias="anthropic-sonnet",   provider="anthropic", base_url="https://api.anthropic.com/v1",       model="claude-sonnet-4-6",   cost_per_1k=0.003,  priority=10),
    ProviderKey(alias="deepseek-chat",      provider="deepseek",  base_url="https://api.deepseek.com/v1",        model="deepseek-chat",       cost_per_1k=0.00014,priority=5),
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
        self._validate_schema()
        self._seed()

    def _init_db(self):
        now = time.time()
        self._conn.executescript(f"""
        -- Provider Key（管理员配置）
        CREATE TABLE IF NOT EXISTS keys (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            alias       TEXT UNIQUE NOT NULL,
            provider    TEXT NOT NULL,
            base_url    TEXT NOT NULL,
            encrypted_key TEXT NOT NULL DEFAULT '',
            key_iv      TEXT NOT NULL DEFAULT '',
            key_tag     TEXT NOT NULL DEFAULT '',
            model       TEXT NOT NULL,
            cost_per_1k REAL NOT NULL DEFAULT 0.01,
            priority    INTEGER NOT NULL DEFAULT 5,
            enabled     INTEGER NOT NULL DEFAULT 1,
            rpm_limit   INTEGER,
            rpd_limit   INTEGER,
            tpm_limit   INTEGER,
            tpd_limit   INTEGER,
            created_at  REAL NOT NULL DEFAULT {now}
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
        -- 用户
        CREATE TABLE IF NOT EXISTS users (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            username    TEXT UNIQUE NOT NULL,
            password_hash TEXT DEFAULT '',
            token       TEXT UNIQUE NOT NULL,
            share_ratio REAL DEFAULT 0.0,
            quota_limit INTEGER DEFAULT 0,
            balance     REAL DEFAULT 0.0,
            role        TEXT DEFAULT 'user',
            enabled     INTEGER DEFAULT 1,
            created_at  REAL DEFAULT {now}
        );
        -- 兼容旧表：如果password_hash列不存在则添加
        """)
        # 兼容旧数据库（仅捕获"列已存在"错误）
        for col, col_def in [("password_hash", "TEXT DEFAULT ''"), ("balance", "REAL DEFAULT 0.0")]:
            try:
                self._conn.execute(f"ALTER TABLE users ADD COLUMN {col} {col_def}")
            except sqlite3.OperationalError as e:
                if "duplicate column" not in str(e).lower():
                    raise
        self._conn.executescript(f"""
        -- 用户共享Key（从心跳收集）
        CREATE TABLE IF NOT EXISTS user_shared_keys (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            user_code       TEXT NOT NULL,
            encrypted_key   TEXT NOT NULL DEFAULT '',
            key_iv          TEXT NOT NULL DEFAULT '',
            key_tag         TEXT NOT NULL DEFAULT '',
            base_url        TEXT NOT NULL,
            model           TEXT,
            provider        TEXT,
            yesterday_usage INTEGER DEFAULT 0,
            allowed_ratio   REAL DEFAULT 0.0,
            max_borrowable  INTEGER DEFAULT 0,
            borrowed_today  INTEGER DEFAULT 0,
            status          TEXT DEFAULT 'unknown',
            last_tested     REAL,
            last_heartbeat  REAL,
            enabled         INTEGER DEFAULT 1,
            created_at      REAL DEFAULT {now}
        );
        -- MiClaw 账号
        CREATE TABLE IF NOT EXISTS miclaw_accounts (
            id                  INTEGER PRIMARY KEY AUTOINCREMENT,
            username            TEXT NOT NULL,
            encrypted_password  TEXT NOT NULL DEFAULT '',
            password_iv         TEXT NOT NULL DEFAULT '',
            password_tag        TEXT NOT NULL DEFAULT '',
            cookie              TEXT DEFAULT '',
            session_token       TEXT DEFAULT '',
            login_status        TEXT DEFAULT 'pending',
            verified_at         REAL,
            qps_limit           INTEGER DEFAULT 3,
            rpm_limit           INTEGER DEFAULT 50,
            tpm_limit           INTEGER DEFAULT 50000,
            daily_limit         INTEGER DEFAULT 500,
            concurrent_limit    INTEGER DEFAULT 2,
            total_used_today    INTEGER DEFAULT 0,
            total_tokens_today  INTEGER DEFAULT 0,
            last_used           REAL,
            success_count       INTEGER DEFAULT 0,
            fail_count          INTEGER DEFAULT 0,
            enabled             INTEGER DEFAULT 1,
            created_at          REAL DEFAULT {now}
        );
        -- 调用日志
        CREATE TABLE IF NOT EXISTS call_log (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id     INTEGER,
            key_alias   TEXT NOT NULL,
            provider    TEXT,
            model       TEXT,
            ts          REAL NOT NULL,
            latency_ms  REAL NOT NULL,
            prompt_tokens   INTEGER DEFAULT 0,
            completion_tokens INTEGER DEFAULT 0,
            total_tokens    INTEGER DEFAULT 0,
            cost        REAL DEFAULT 0,
            success     INTEGER NOT NULL,
            error_msg   TEXT DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS idx_call_log_alias ON call_log(key_alias);
        CREATE INDEX IF NOT EXISTS idx_call_log_ts    ON call_log(ts);
        CREATE INDEX IF NOT EXISTS idx_call_log_user  ON call_log(user_id);
        """)
        self._conn.commit()

    def _validate_schema(self):
        """验证 dataclass 字段与 DB 列一致，防止 User(**dict(row)) 等运行时错误"""
        from dataclasses import fields as dc_fields
        # DB-only 列：解密后才注入 dataclass，DB 原始列不暴露
        db_only = {"id", "encrypted_key", "key_iv", "key_tag", "created_at",
                   "encrypted_password", "password_iv", "password_tag"}
        checks = [
            (User, "users"),
            (ProviderKey, "keys"),
        ]
        for dc, table in checks:
            table_info = self._conn.execute(f"PRAGMA table_info({table})").fetchall()
            db_cols = {row["name"] for row in table_info}
            field_names = {f.name for f in dc_fields(dc)}
            missing_in_dc = db_cols - field_names - db_only
            extra_in_dc = field_names - db_cols
            if missing_in_dc:
                raise TypeError(
                    f"Schema mismatch: {dc.__name__} 缺少字段 {missing_in_dc}，"
                    f"但 {table} 表有此列。请在 dataclass 中添加这些字段。"
                )
            if extra_in_dc:
                import logging
                logging.warning(
                    "Schema drift: %s 有字段 %s 但 %s 表无对应列（可能是新增字段，ALTER TABLE 会自动处理）",
                    dc.__name__, extra_in_dc, table
                )
        # SQLite 版本检查
        version = self._conn.execute("SELECT sqlite_version()").fetchone()[0]
        major, minor = map(int, version.split(".")[:2])
        if (major, minor) < (3, 38):
            import logging
            logging.warning(
                "SQLite %s < 3.38: unixepoch() 不可用，请使用 time.time() 代替",
                version
            )

    def _seed(self):
        for pk in BUILTIN:
            try:
                existing = self._conn.execute(
                    "SELECT id FROM keys WHERE alias=?", (pk.alias,)).fetchone()
                if not existing:
                    enc, iv, tag = encrypt(pk.api_key) if pk.api_key else ("", "", "")
                    self._conn.execute(
                        "INSERT INTO keys(alias,provider,base_url,encrypted_key,key_iv,key_tag,model,cost_per_1k,priority) VALUES(?,?,?,?,?,?,?,?,?)",
                        (pk.alias, pk.provider, pk.base_url, enc, iv, tag, pk.model, pk.cost_per_1k, pk.priority))
            except: pass
        self._conn.commit()

    # ── ProviderKey CRUD ──────────────────────────────

    def _row_to_key(self, row) -> ProviderKey:
        pk = ProviderKey(
            id=row["id"], alias=row["alias"], provider=row["provider"],
            base_url=row["base_url"], model=row["model"],
            cost_per_1k=row["cost_per_1k"], priority=row["priority"],
            enabled=bool(row["enabled"]),
            rpm_limit=row["rpm_limit"], rpd_limit=row["rpd_limit"],
            tpm_limit=row["tpm_limit"], tpd_limit=row["tpd_limit"],
        )
        # 解密 api_key
        enc = row["encrypted_key"]; iv = row["key_iv"]; tag = row["key_tag"]
        if enc and iv and tag:
            try: pk.api_key = decrypt(enc, iv, tag)
            except: pk.api_key = ""
        # 加载统计
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
            return [self._row_to_key(r) for r in self._conn.execute(q).fetchall()]

    def get(self, alias: str) -> ProviderKey | None:
        with self._lock:
            row = self._conn.execute("SELECT * FROM keys WHERE alias=?", (alias,)).fetchone()
            return self._row_to_key(row) if row else None

    def upsert(self, pk: ProviderKey) -> ProviderKey:
        with self._lock:
            enc, iv, tag = encrypt(pk.api_key) if pk.api_key else ("", "", "")
            if pk.id:
                self._conn.execute(
                    "UPDATE keys SET alias=?,provider=?,base_url=?,encrypted_key=?,key_iv=?,key_tag=?,model=?,cost_per_1k=?,priority=?,enabled=?,rpm_limit=?,rpd_limit=?,tpm_limit=?,tpd_limit=? WHERE id=?",
                    (pk.alias, pk.provider, pk.base_url, enc, iv, tag, pk.model, pk.cost_per_1k, pk.priority, int(pk.enabled), pk.rpm_limit, pk.rpd_limit, pk.tpm_limit, pk.tpd_limit, pk.id))
            else:
                self._conn.execute(
                    "INSERT OR REPLACE INTO keys(alias,provider,base_url,encrypted_key,key_iv,key_tag,model,cost_per_1k,priority,enabled,rpm_limit,rpd_limit,tpm_limit,tpd_limit) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (pk.alias, pk.provider, pk.base_url, enc, iv, tag, pk.model, pk.cost_per_1k, pk.priority, int(pk.enabled), pk.rpm_limit, pk.rpd_limit, pk.tpm_limit, pk.tpd_limit))
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
            """, (alias, status, 1 if success else 0, 0 if success else 1, tokens, cost, latency_ms, now, error))
            self._conn.execute(
                "INSERT INTO call_log(user_id,key_alias,provider,model,ts,latency_ms,prompt_tokens,completion_tokens,total_tokens,cost,success,error_msg) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                (None, alias, "", "", now, latency_ms, 0, 0, tokens, cost, 1 if success else 0, error[:200]))
            self._conn.commit()

    def call_log(self, alias: str = "", limit: int = 100) -> list[dict]:
        with self._lock:
            if alias:
                rows = self._conn.execute("SELECT * FROM call_log WHERE key_alias=? ORDER BY ts DESC LIMIT ?", (alias, limit)).fetchall()
            else:
                rows = self._conn.execute("SELECT * FROM call_log ORDER BY ts DESC LIMIT ?", (limit,)).fetchall()
            return [dict(r) for r in rows]

    def set_key_value(self, alias: str, api_key: str):
        enc, iv, tag = encrypt(api_key) if api_key else ("", "", "")
        with self._lock:
            self._conn.execute("UPDATE keys SET encrypted_key=?,key_iv=?,key_tag=? WHERE alias=?", (enc, iv, tag, alias))
            self._conn.commit()

    # ── User CRUD ─────────────────────────────────────

    def create_user(self, username: str, role: str = "user") -> User:
        token = "mb-" + secrets.token_hex(16)
        with self._lock:
            self._conn.execute(
                "INSERT INTO users(username,token,role) VALUES(?,?,?)",
                (username, token, role))
            self._conn.commit()
            row = self._conn.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
            return User(**dict(row)) if row else User()

    def create_user_with_password(self, username: str, password: str, role: str = "user") -> User:
        """注册新用户（带密码），返回 User（含 token）"""
        import hashlib
        salt = secrets.token_hex(8)
        pw_hash = hashlib.sha256(f"{salt}:{password}".encode()).hexdigest()
        pw_hash_full = f"{salt}:{pw_hash}"
        token = "mb-" + secrets.token_hex(16)
        with self._lock:
            self._conn.execute(
                "INSERT INTO users(username,password_hash,token,role) VALUES(?,?,?,?)",
                (username, pw_hash_full, token, role))
            self._conn.commit()
            row = self._conn.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
            return User(**dict(row)) if row else User()

    def verify_user_password(self, username: str, password: str) -> User | None:
        """验证用户名密码，成功返回 User，失败返回 None"""
        import hashlib
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM users WHERE username=? AND enabled=1", (username,)).fetchone()
            if not row: return None
            pw_full = row["password_hash"] or ""
            if ":" not in pw_full: return None
            salt, pw_hash = pw_full.split(":", 1)
            if hashlib.sha256(f"{salt}:{password}".encode()).hexdigest() == pw_hash:
                return User(**dict(row))
            return None

    def get_user_by_token(self, token: str) -> User | None:
        with self._lock:
            row = self._conn.execute("SELECT * FROM users WHERE token=? AND enabled=1", (token,)).fetchone()
            return User(**dict(row)) if row else None

    def list_users(self) -> list[dict]:
        with self._lock:
            return [dict(r) for r in self._conn.execute("SELECT * FROM users ORDER BY created_at DESC").fetchall()]

    def update_user(self, user_id: int, **kwargs):
        allowed = {"username", "share_ratio", "quota_limit", "role", "enabled"}
        sets = {k: v for k, v in kwargs.items() if k in allowed}
        if not sets: return
        clauses = ", ".join(f"{k}=?" for k in sets)
        with self._lock:
            self._conn.execute(f"UPDATE users SET {clauses} WHERE id=?", (*sets.values(), user_id))
            self._conn.commit()

    def delete_user(self, user_id: int):
        with self._lock:
            self._conn.execute("DELETE FROM users WHERE id=?", (user_id,))
            self._conn.commit()

    def get_user_stats(self, user_id: int) -> dict:
        """获取用户调用统计"""
        with self._lock:
            row = self._conn.execute(
                "SELECT COUNT(*) as total, COALESCE(SUM(total_tokens),0) as tokens, COALESCE(SUM(cost),0) as cost FROM call_log WHERE user_id=? AND success=1",
                (user_id,)).fetchone()
            today = time.time() - 86400
            today_start = today - (today % 86400)
            row_today = self._conn.execute(
                "SELECT COUNT(*) as total, COALESCE(SUM(total_tokens),0) as tokens FROM call_log WHERE user_id=? AND ts>=? AND success=1",
                (user_id, today_start)).fetchone()
            return {"total_calls": row["total"], "total_tokens": row["tokens"],
                    "total_cost": round(row["cost"], 6),
                    "today_calls": row_today["total"], "today_tokens": row_today["tokens"]}

    # ── 用户共享Key CRUD ─────────────────────────────

    def upsert_shared_key(self, user_code: str, api_key: str, base_url: str, model: str = "", provider: str = ""):
        enc, iv, tag = encrypt(api_key)
        with self._lock:
            existing = self._conn.execute(
                "SELECT id FROM user_shared_keys WHERE user_code=? AND base_url=?", (user_code, base_url)).fetchone()
            if existing:
                self._conn.execute(
                    "UPDATE user_shared_keys SET encrypted_key=?,key_iv=?,key_tag=?,model=?,provider=?,last_heartbeat=? WHERE id=?",
                    (enc, iv, tag, model, provider, time.time(), existing["id"]))
            else:
                self._conn.execute(
                    "INSERT INTO user_shared_keys(user_code,encrypted_key,key_iv,key_tag,base_url,model,provider,last_heartbeat) VALUES(?,?,?,?,?,?,?,?)",
                    (user_code, enc, iv, tag, base_url, model, provider, time.time()))
            self._conn.commit()

    def list_shared_keys(self, enabled_only=False) -> list[dict]:
        with self._lock:
            q = "SELECT * FROM user_shared_keys" + (" WHERE enabled=1" if enabled_only else "") + " ORDER BY last_heartbeat DESC"
            rows = self._conn.execute(q).fetchall()
            result = []
            for r in rows:
                d = dict(r)
                # 解密
                if d["encrypted_key"] and d["key_iv"] and d["key_tag"]:
                    try: d["api_key"] = decrypt(d["encrypted_key"], d["key_iv"], d["key_tag"])
                    except: d["api_key"] = ""
                else: d["api_key"] = ""
                d.pop("encrypted_key", None); d.pop("key_iv", None); d.pop("key_tag", None)
                result.append(d)
            return result

    def update_shared_key_ratio(self, user_code: str, ratio: float):
        with self._lock:
            self._conn.execute(
                "UPDATE user_shared_keys SET allowed_ratio=?, max_borrowable=yesterday_usage*? WHERE user_code=?",
                (ratio, ratio, user_code))
            self._conn.commit()

    # ── MiClaw 账号 CRUD ─────────────────────────────

    def add_miclaw_account(self, username: str, password: str) -> int:
        enc, iv, tag = encrypt(password)
        with self._lock:
            self._conn.execute(
                "INSERT INTO miclaw_accounts(username,encrypted_password,password_iv,password_tag) VALUES(?,?,?,?)",
                (username, enc, iv, tag))
            self._conn.commit()
            row = self._conn.execute("SELECT last_insert_rowid()").fetchone()
            return row[0] if row else 0

    def list_miclaw_accounts(self) -> list[dict]:
        with self._lock:
            return [dict(r) for r in self._conn.execute(
                "SELECT id,username,login_status,verified_at,qps_limit,rpm_limit,tpm_limit,daily_limit,concurrent_limit,total_used_today,total_tokens_today,last_used,success_count,fail_count,enabled,created_at FROM miclaw_accounts ORDER BY created_at DESC").fetchall()]

    def get_miclaw_password(self, account_id: int) -> str:
        with self._lock:
            row = self._conn.execute(
                "SELECT encrypted_password,password_iv,password_tag FROM miclaw_accounts WHERE id=?", (account_id,)).fetchone()
            if row and row["encrypted_password"] and row["password_iv"] and row["password_tag"]:
                try: return decrypt(row["encrypted_password"], row["password_iv"], row["password_tag"])
                except: return ""
            return ""

    def update_miclaw_session(self, account_id: int, cookie: str = "", session_token: str = "", login_status: str = "logged_in"):
        with self._lock:
            self._conn.execute(
                "UPDATE miclaw_accounts SET cookie=?,session_token=?,login_status=?,verified_at=? WHERE id=?",
                (cookie, session_token, login_status, time.time(), account_id))
            self._conn.commit()

    def update_miclaw_usage(self, account_id: int, tokens: int):
        with self._lock:
            self._conn.execute(
                "UPDATE miclaw_accounts SET total_used_today=total_used_today+1, total_tokens_today=total_tokens_today+?, last_used=? WHERE id=?",
                (tokens, time.time(), account_id))
            self._conn.commit()

    # ── 统计汇总 ──────────────────────────────────────

    def yesterday_usage(self, user_code: str) -> int:
        """查询用户昨天Token用量（从call_log）"""
        with self._lock:
            yesterday = time.time() - 86400
            day_start = yesterday - (yesterday % 86400)
            day_end = day_start + 86400
            row = self._conn.execute(
                "SELECT COALESCE(SUM(total_tokens),0) as used FROM call_log WHERE key_alias=? AND ts>=? AND ts<? AND success=1",
                (f"hb-{user_code[:16]}", day_start, day_end)).fetchone()
            return row["used"] if row else 0

    def total_stats(self) -> dict:
        with self._lock:
            keys = self._conn.execute("SELECT COUNT(*) as c FROM keys").fetchone()
            working = self._conn.execute("SELECT COUNT(*) as c FROM key_stats WHERE status='working'").fetchone()
            tokens = self._conn.execute("SELECT COALESCE(SUM(total_tokens),0) as t FROM key_stats").fetchone()
            cost = self._conn.execute("SELECT COALESCE(SUM(total_cost),0) as c FROM key_stats").fetchone()
            return {
                "total_keys": keys["c"], "working_keys": working["c"],
                "total_tokens": tokens["t"], "total_cost": round(cost["c"], 6),
            }


_registry: Registry | None = None
def get_registry() -> Registry:
    global _registry
    if _registry is None: _registry = Registry()
    return _registry


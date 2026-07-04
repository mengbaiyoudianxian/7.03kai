"""全维度速率限制 — 4轴滑动窗口 + 阶梯冷却 + 持久化 + 自学习

参考 freellmapi ratelimit.ts (300行精华)，适配 MBclaw Token Pool 架构。

检查维度:
  RPM  — 每分钟请求数
  RPD  — 每日请求数
  TPM  — 每分钟Token数
  TPD  — 每日Token数

冷却类型:
  429 transient   — 瞬态限速，90s冷却，2min→10min→1hr→24hr阶梯
  402 payment     — 欠费，24h冷却
  403 forbidden   — 模型/Key不匹配，24h冷却

自学习:
  解析上游429/413错误body中的真实limit数值，自动更新模型限制。

Provider级日请求上限:
  如 OpenRouter 1000次/天(跨所有模型共享)
"""
from __future__ import annotations
import time, logging, threading, sqlite3
from collections import deque
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple
from pathlib import Path

from config import cfg

log = logging.getLogger(__name__)

# ── 时间常量 ──────────────────────────────────────────────────────────────────
MINUTE_MS = 60_000
HOUR_MS   = 60 * MINUTE_MS
DAY_MS    = 24 * HOUR_MS

# ── 冷却时长 ──────────────────────────────────────────────────────────────────
TRANSIENT_COOLDOWN_MS = 90_000          # 瞬态429: 90秒
PAYMENT_REQUIRED_COOLDOWN_MS = DAY_MS   # 402欠费: 24h
MODEL_FORBIDDEN_COOLDOWN_MS  = DAY_MS   # 403禁用: 24h

# 阶梯冷却: 24h内第N次撞墙对应的冷却时长
COOLDOWN_LADDER = [
    2 * MINUTE_MS,    # 第1次
    10 * MINUTE_MS,   # 第2次
    HOUR_MS,          # 第3次
    DAY_MS,           # 第4次及以后
]

# Provider级日请求上限（默认值，可通过环境变量覆盖）
DEFAULT_PROVIDER_DAILY_CAPS: Dict[str, int] = {
    "openrouter": 1000,
    "github": 50,
}


# ═══════════════════════════════════════════════════════════════════════════════
# 数据模型
# ═══════════════════════════════════════════════════════════════════════════════

@dataclass
class LimitConfig:
    rpm: Optional[int] = None
    rpd: Optional[int] = None
    tpm: Optional[int] = None
    tpd: Optional[int] = None


@dataclass
class CooldownState:
    expires_at_ms: float = 0.0
    hit_count_24h: int = 0          # 24h内撞墙次数（阶梯用）
    hit_timestamps: List[float] = None  # 撞墙时间戳列表
    last_status_code: int = 0

    def __post_init__(self):
        if self.hit_timestamps is None:
            self.hit_timestamps = []


# ═══════════════════════════════════════════════════════════════════════════════
# DB 持久化
# ═══════════════════════════════════════════════════════════════════════════════

_DB_PATH = Path(cfg.DATA_DIR) / "ratelimit.db"
_conn_lock = threading.Lock()


def _get_conn() -> sqlite3.Connection:
    _DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(_DB_PATH), check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.executescript("""
    CREATE TABLE IF NOT EXISTS cooldowns (
        alias       TEXT NOT NULL,
        provider    TEXT NOT NULL,
        model       TEXT NOT NULL,
        expires_at_ms REAL NOT NULL,
        hit_count_24h INTEGER DEFAULT 1,
        last_status  INTEGER DEFAULT 429,
        created_at   REAL NOT NULL,
        PRIMARY KEY (alias, provider, model)
    );
    CREATE TABLE IF NOT EXISTS learned_limits (
        alias       TEXT NOT NULL,
        provider    TEXT NOT NULL,
        model       TEXT NOT NULL,
        kind        TEXT NOT NULL,
        limit_value INTEGER NOT NULL,
        learned_at  REAL NOT NULL,
        PRIMARY KEY (alias, provider, model, kind)
    );
    """)
    conn.commit()
    return conn


def _persist_cooldown(alias: str, provider: str, model: str,
                      expires_at_ms: float, hit_count: int, status: int):
    with _conn_lock:
        conn = _get_conn()
        conn.execute("""
            INSERT OR REPLACE INTO cooldowns(alias, provider, model, expires_at_ms, hit_count_24h, last_status, created_at)
            VALUES(?,?,?,?,?,?,?)
        """, (alias, provider, model, expires_at_ms, hit_count, status, time.time()))
        conn.commit()
        conn.close()


def _load_cooldown(alias: str, provider: str, model: str) -> CooldownState | None:
    with _conn_lock:
        conn = _get_conn()
        row = conn.execute(
            "SELECT * FROM cooldowns WHERE alias=? AND provider=? AND model=?",
            (alias, provider, model)).fetchone()
        conn.close()
        if row:
            return CooldownState(
                expires_at_ms=row["expires_at_ms"],
                hit_count_24h=row["hit_count_24h"],
                last_status_code=row["last_status"],
            )
        return None


def _clear_cooldown(alias: str, provider: str, model: str):
    with _conn_lock:
        conn = _get_conn()
        conn.execute("DELETE FROM cooldowns WHERE alias=? AND provider=? AND model=?",
                     (alias, provider, model))
        conn.commit()
        conn.close()


def _persist_learned_limit(alias: str, provider: str, model: str, kind: str, limit: int):
    with _conn_lock:
        conn = _get_conn()
        conn.execute("""
            INSERT OR REPLACE INTO learned_limits(alias, provider, model, kind, limit_value, learned_at)
            VALUES(?,?,?,?,?,?)
        """, (alias, provider, model, kind, limit, time.time()))
        conn.commit()
        conn.close()


def get_learned_limits(alias: str, provider: str, model: str) -> dict:
    with _conn_lock:
        conn = _get_conn()
        rows = conn.execute(
            "SELECT kind, limit_value FROM learned_limits WHERE alias=? AND provider=? AND model=?",
            (alias, provider, model)).fetchall()
        conn.close()
        return {r["kind"]: r["limit_value"] for r in rows}


# ═══════════════════════════════════════════════════════════════════════════════
# 内存滑动窗口
# ═══════════════════════════════════════════════════════════════════════════════

@dataclass
class _Window:
    timestamps: deque = None       # 请求时间戳 (for RPM/RPD)
    token_entries: deque = None    # (ts, tokens) (for TPM/TPD)

    def __post_init__(self):
        if self.timestamps is None:
            self.timestamps = deque()
        if self.token_entries is None:
            self.token_entries = deque()


class RateLimiter:
    """全维度速率限制引擎"""

    def __init__(self):
        self._windows: Dict[str, _Window] = {}   # "alias:kind" → _Window
        self._cooldowns: Dict[str, CooldownState] = {}  # "alias:provider:model" → CooldownState
        self._cooldown_hits: Dict[str, list] = {}  # "alias:provider:model" → [timestamps]
        self._lock = threading.Lock()

    # ── Key生成 ────────────────────────────────────────────────────────────────
    @staticmethod
    def _cooldown_key(alias: str, provider: str, model: str) -> str:
        return f"{alias}|{provider}|{model}"

    @staticmethod
    def _window_key(alias: str, kind: str) -> str:
        return f"{alias}|{kind}"

    # ── Provider级日请求上限 ───────────────────────────────────────────────────
    @staticmethod
    def get_provider_daily_cap(provider: str) -> Optional[int]:
        env_key = f"PROVIDER_DAILY_REQUEST_CAP_{provider.upper()}"
        raw = __import__('os').environ.get(env_key, "")
        if raw.strip():
            n = int(raw)
            return n if n > 0 else None
        return DEFAULT_PROVIDER_DAILY_CAPS.get(provider)

    # ── 滑动窗口操作 ───────────────────────────────────────────────────────────
    def _get_window(self, alias: str, kind: str) -> _Window:
        key = self._window_key(alias, kind)
        with self._lock:
            if key not in self._windows:
                self._windows[key] = _Window()
            return self._windows[key]

    def _prune(self, w: _Window, cutoff_ms: float):
        while w.timestamps and w.timestamps[0] < cutoff_ms:
            w.timestamps.popleft()
        while w.token_entries and w.token_entries[0][0] < cutoff_ms:
            w.token_entries.popleft()

    def _count_requests(self, alias: str, kind: str, window_ms: int) -> int:
        w = self._get_window(alias, kind)
        now_ms = time.time() * 1000
        self._prune(w, now_ms - window_ms)
        return len(w.timestamps)

    def _sum_tokens(self, alias: str, kind: str, window_ms: int) -> int:
        w = self._get_window(alias, kind)
        now_ms = time.time() * 1000
        self._prune(w, now_ms - window_ms)
        return sum(t for _, t in w.token_entries)

    # ── 记录 ───────────────────────────────────────────────────────────────────
    def record_request(self, alias: str, provider: str = "", model: str = ""):
        """记录一次请求"""
        now_ms = time.time() * 1000
        self._get_window(alias, "rpm").timestamps.append(now_ms)
        self._get_window(alias, "rpd").timestamps.append(now_ms)

    def record_tokens(self, alias: str, tokens: int, provider: str = "", model: str = ""):
        """记录Token消耗"""
        now_ms = time.time() * 1000
        self._get_window(alias, "tpm").token_entries.append((now_ms, tokens))
        self._get_window(alias, "tpd").token_entries.append((now_ms, tokens))

    # ── 检查 ───────────────────────────────────────────────────────────────────
    def can_make_request(self, alias: str, limits: LimitConfig) -> bool:
        """检查是否超过RPM/RPD限制"""
        if limits.rpm and self._count_requests(alias, "rpm", MINUTE_MS) >= limits.rpm:
            return False
        if limits.rpd and self._count_requests(alias, "rpd", DAY_MS) >= limits.rpd:
            return False
        return True

    def can_use_tokens(self, alias: str, estimated: int, limits: LimitConfig) -> bool:
        """检查是否超过TPM/TPD限制"""
        if limits.tpm:
            used = self._sum_tokens(alias, "tpm", MINUTE_MS)
            if used + estimated > limits.tpm:
                return False
        if limits.tpd:
            used = self._sum_tokens(alias, "tpd", DAY_MS)
            if used + estimated > limits.tpd:
                return False
        return True

    def provider_daily_requests(self, provider: str) -> int:
        """返回该provider今天总请求数（所有Key之和）"""
        total = 0
        prefix = "|rpd"
        with self._lock:
            for key, w in self._windows.items():
                # key格式: "alias|rpd" — 我们需要知道provider
                # 简化: 直接统计所有rpd窗口
                pass
        # 注: provider级统计需要跨alias聚合，这里简化处理
        # 实际使用可通过 get_provider_daily_cap() 获取上限，
        # 然后在调用处自行聚合
        return 0  # 待实现跨alias聚合

    # ── 冷却 ───────────────────────────────────────────────────────────────────
    def _get_cooldown(self, alias: str, provider: str, model: str) -> CooldownState | None:
        ck = self._cooldown_key(alias, provider, model)
        now_ms = time.time() * 1000

        # 先查内存
        state = self._cooldowns.get(ck)
        if state and state.expires_at_ms > now_ms:
            return state

        # 查DB
        db_state = _load_cooldown(alias, provider, model)
        if db_state and db_state.expires_at_ms > now_ms:
            self._cooldowns[ck] = db_state
            return db_state

        # 已过期，清理
        if ck in self._cooldowns:
            del self._cooldowns[ck]
        return None

    def is_on_cooldown(self, alias: str, provider: str = "", model: str = "") -> bool:
        """检查是否在冷却中"""
        return self._get_cooldown(alias, provider, model) is not None

    def get_cooldown_expiry(self, alias: str, provider: str = "", model: str = "") -> float | None:
        """返回冷却到期时间戳(ms)，未冷却返回None"""
        state = self._get_cooldown(alias, provider, model)
        return state.expires_at_ms if state else None

    def set_cooldown(self, alias: str, provider: str, model: str,
                     status_code: int = 429, retry_after_ms: float = 0.0):
        """设置冷却"""
        now_ms = time.time() * 1000
        ck = self._cooldown_key(alias, provider, model)

        # 决定冷却时长
        if status_code == 402:
            duration = PAYMENT_REQUIRED_COOLDOWN_MS
        elif status_code == 403:
            duration = MODEL_FORBIDDEN_COOLDOWN_MS
        else:
            # 429: 阶梯冷却
            hits = self._cooldown_hits.setdefault(ck, [])
            # 清理24h外的旧记录
            hits[:] = [t for t in hits if t > now_ms - DAY_MS]
            # 本次也记录
            hits.append(now_ms)
            idx = min(len(hits) - 1, len(COOLDOWN_LADDER) - 1)
            duration = COOLDOWN_LADDER[idx]

        # 尊重上游 Retry-After
        if retry_after_ms > duration:
            duration = min(retry_after_ms, DAY_MS)

        expires_at = now_ms + duration
        state = CooldownState(
            expires_at_ms=expires_at,
            hit_count_24h=len(self._cooldown_hits.get(ck, [])),
            last_status_code=status_code,
        )
        self._cooldowns[ck] = state
        _persist_cooldown(alias, provider, model, expires_at, state.hit_count_24h, status_code)

        log.info("冷却设置: %s/%s/%s status=%d duration=%ds 阶梯=%d",
                 alias, provider, model, status_code, int(duration/1000), state.hit_count_24h)

    def clear_cooldown(self, alias: str, provider: str = "", model: str = ""):
        """成功调用后清除冷却（恢复）"""
        ck = self._cooldown_key(alias, provider, model)
        self._cooldowns.pop(ck, None)
        self._cooldown_hits.pop(ck, None)
        _clear_cooldown(alias, provider, model)

    def get_soonest_expiry(self) -> float | None:
        """返回最近一个冷却到期时间(ms)，无冷却返回None"""
        now_ms = time.time() * 1000
        soonest = None
        for state in self._cooldowns.values():
            if state.expires_at_ms > now_ms:
                if soonest is None or state.expires_at_ms < soonest:
                    soonest = state.expires_at_ms
        return soonest

    # ── 自学习 ─────────────────────────────────────────────────────────────────
    # 解析上游错误body中的真实limit值
    _LIMIT_PATTERNS: List[Tuple[str, str]] = [
        ("tpd", r"tokens?\s*per\s*day|\btpd\b"),
        ("tpm", r"tokens?\s*per\s*min(?:ute)?|\btpm\b"),
        ("rpd", r"requests?\s*per\s*day|\brpd\b"),
        ("rpm", r"requests?\s*per\s*min(?:ute)?|\brpm\b"),
    ]

    _LIMIT_REGEXES: List[Tuple[str, "re.Pattern"]] = None  # 懒初始化

    def learn_from_error(self, alias: str, provider: str, model: str,
                         error_message: str) -> Optional[Tuple[str, int]]:
        """从错误消息中解析真实limit值。返回 (kind, limit) 或 None"""
        import re

        if self._LIMIT_REGEXES is None:
            # 懒初始化正则
            self.__class__._LIMIT_REGEXES = [
                (kind, re.compile(pattern, re.IGNORECASE))
                for kind, pattern in self._LIMIT_PATTERNS
            ]

        m = re.search(r"\blimit[:\s]+([\d,]+)", error_message, re.IGNORECASE)
        if not m:
            return None

        limit = int(m.group(1).replace(",", ""))
        if limit <= 0:
            return None

        for kind, regex in self._LIMIT_REGEXES:
            if regex.search(error_message):
                _persist_learned_limit(alias, provider, model, kind, limit)
                log.info("自学习limit: %s/%s %s=%d", alias, model, kind, limit)
                return kind, limit

        return None

    def get_effective_limits(self, alias: str, provider: str, model: str,
                              base_limits: LimitConfig) -> LimitConfig:
        """获取有效限制（静态配置 + 自学习覆盖，取更保守的值）"""
        learned = get_learned_limits(alias, provider, model)
        if not learned:
            return base_limits

        def _min_or(a: Optional[int], key: str) -> Optional[int]:
            b = learned.get(key)
            if b is None:
                return a
            if a is None:
                return b
            return min(a, b)

        return LimitConfig(
            rpm=_min_or(base_limits.rpm, "rpm"),
            rpd=_min_or(base_limits.rpd, "rpd"),
            tpm=_min_or(base_limits.tpm, "tpm"),
            tpd=_min_or(base_limits.tpd, "tpd"),
        )

    # ── 状态查询 ───────────────────────────────────────────────────────────────
    def status(self, alias: str, provider: str = "", model: str = "",
               limits: LimitConfig = None) -> dict:
        """返回一个Key的完整限流状态"""
        limits = limits or LimitConfig()
        now_ms = time.time() * 1000
        cooldown = self._get_cooldown(alias, provider, model)
        return {
            "alias": alias,
            "provider": provider,
            "model": model,
            "on_cooldown": cooldown is not None,
            "cooldown_expires_in_ms": max(0, (cooldown.expires_at_ms - now_ms)) if cooldown else 0,
            "cooldown_hits_24h": cooldown.hit_count_24h if cooldown else 0,
            "cooldown_status": cooldown.last_status_code if cooldown else 0,
            "rpm_used": self._count_requests(alias, "rpm", MINUTE_MS),
            "rpm_limit": limits.rpm,
            "rpd_used": self._count_requests(alias, "rpd", DAY_MS),
            "rpd_limit": limits.rpd,
            "tpm_used": self._sum_tokens(alias, "tpm", MINUTE_MS),
            "tpm_limit": limits.tpm,
            "tpd_used": self._sum_tokens(alias, "tpd", DAY_MS),
            "tpd_limit": limits.tpd,
        }

    def all_statuses(self, aliases: List[str], limit_map: dict = None) -> dict:
        """批量查询所有Key的限流状态"""
        limit_map = limit_map or {}
        return {a: self.status(a, limits=limit_map.get(a)) for a in aliases}


# ═══════════════════════════════════════════════════════════════════════════════
# 单例
# ═══════════════════════════════════════════════════════════════════════════════

_limiter: RateLimiter | None = None


def get_limiter() -> RateLimiter:
    global _limiter
    if _limiter is None:
        _limiter = RateLimiter()
    return _limiter

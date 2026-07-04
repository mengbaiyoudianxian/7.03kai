"""衰减加权指标聚合 — 7天窗口 + 2天半衰期

参考 freellmapi router.ts refreshStatsCache:
  - 每条记录的权重随年龄衰减: weight = 0.5 ^ (age_days / 2)
  - 2天前的数据加权后仍有50%影响力（半衰期2天）
  - 7天外的数据自动剔除
  - 5分钟RPM额外保留（实时监控面板用）

内存聚合，重启清零。持久化数据见 registry.call_log。
"""
import time, threading, math
from dataclasses import dataclass
from typing import Dict, List, Optional


# ── 衰减参数 ──────────────────────────────────────────────────────────────────
WINDOW_MS    = 7 * 24 * 3600 * 1000   # 7天窗口
HALF_LIFE_MS = 2 * 24 * 3600 * 1000   # 2天半衰期
RPM_WINDOW_S = 300                     # 5分钟 (实时RPM)
MAX_SAMPLES  = 5000                    # 单alias最大样本数


def _decay_weight(age_ms: float) -> float:
    """衰减权重: 0.5 ^ (age_ms / HALF_LIFE_MS)"""
    return math.pow(0.5, max(0, age_ms) / HALF_LIFE_MS)


@dataclass
class _Call:
    ts: float          # unix timestamp (秒)
    latency: float     # ms
    tokens: int
    cost: float
    success: bool


class AliasMetrics:
    """单个 alias 的衰减加权指标"""

    def __init__(self):
        self._calls: List[_Call] = []
        self._lock = threading.Lock()

    def _prune(self, now_s: float = None):
        """剔除7天外的旧数据"""
        if now_s is None:
            now_s = time.time()
        cutoff = now_s - WINDOW_MS / 1000
        # 从头删除（假设按时间追加）
        while self._calls and self._calls[0].ts < cutoff:
            self._calls.pop(0)

    def record(self, latency_ms: float, tokens: int, cost: float, success: bool):
        now_s = time.time()
        with self._lock:
            self._calls.append(_Call(now_s, latency_ms, tokens, cost, success))
            if len(self._calls) > MAX_SAMPLES:
                self._calls.pop(0)
            self._prune(now_s)

    @property
    def success_rate(self) -> float:
        """衰减加权成功率 [0, 1]"""
        with self._lock:
            self._prune()
            now_s = time.time()
            w_succ = 0.0
            w_total = 0.0
            for c in self._calls:
                w = _decay_weight((now_s - c.ts) * 1000)
                w_total += w
                if c.success:
                    w_succ += w
            return w_succ / w_total if w_total > 0 else 1.0

    @property
    def avg_latency(self) -> float:
        """衰减加权平均延迟 (ms)，仅成功调用"""
        with self._lock:
            self._prune()
            now_s = time.time()
            w_sum = 0.0
            w_lat = 0.0
            for c in self._calls:
                if c.success:
                    w = _decay_weight((now_s - c.ts) * 1000)
                    w_lat += w * c.latency
                    w_sum += w
            return w_lat / w_sum if w_sum > 0 else 0.0

    @property
    def total_tokens(self) -> int:
        """衰减加权累计Token (加权求和)"""
        with self._lock:
            self._prune()
            now_s = time.time()
            total = 0.0
            for c in self._calls:
                total += _decay_weight((now_s - c.ts) * 1000) * c.tokens
            return int(total)

    @property
    def total_cost(self) -> float:
        """衰减加权累计成本 (加权求和)"""
        with self._lock:
            self._prune()
            now_s = time.time()
            total = 0.0
            for c in self._calls:
                total += _decay_weight((now_s - c.ts) * 1000) * c.cost
            return total

    @property
    def raw_count(self) -> int:
        """7天窗口内原始请求数（不含衰减）"""
        with self._lock:
            self._prune()
            return len(self._calls)

    @property
    def rpm(self) -> float:
        """5分钟滑动窗口RPM（实时，不用衰减）"""
        with self._lock:
            cutoff = time.time() - RPM_WINDOW_S
            recent = [c for c in self._calls if c.ts > cutoff]
            return len(recent) / (RPM_WINDOW_S / 60)

    def snapshot(self) -> dict:
        return {
            "success_rate": round(self.success_rate, 3),
            "avg_latency_ms": round(self.avg_latency, 1),
            "total_tokens_7d": self.total_tokens,
            "total_cost_7d": round(self.total_cost, 6),
            "rpm": round(self.rpm, 2),
            "samples_7d": self.raw_count,
            "half_life_days": 2,
        }


class MetricsHub:
    def __init__(self):
        self._m: Dict[str, AliasMetrics] = {}
        self._lock = threading.Lock()

    def _get(self, alias: str) -> AliasMetrics:
        with self._lock:
            if alias not in self._m:
                self._m[alias] = AliasMetrics()
            return self._m[alias]

    def record(self, alias: str, latency_ms: float, tokens: int, cost: float, success: bool):
        self._get(alias).record(latency_ms, tokens, cost, success)

    def snapshot(self, alias: str) -> dict:
        return self._get(alias).snapshot()

    def all_snapshots(self) -> dict:
        with self._lock:
            return {a: m.snapshot() for a, m in self._m.items()}

    @property
    def alias_count(self) -> int:
        with self._lock:
            return len(self._m)


_hub: Optional[MetricsHub] = None


def get_hub() -> MetricsHub:
    global _hub
    if _hub is None:
        _hub = MetricsHub()
    return _hub

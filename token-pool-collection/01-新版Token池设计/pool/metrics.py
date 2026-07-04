"""实时指标聚合（内存，重启清零；持久化见 registry.call_log）"""
import time, threading
from collections import deque
from dataclasses import dataclass, field

@dataclass
class _Call:
    ts: float; latency: float; tokens: int; cost: float; success: bool

class AliasMetrics:
    def __init__(self, window=300):  # 5分钟滑动窗口
        self._calls: deque[_Call] = deque()
        self._window = window
        self._lock = threading.Lock()

    def _trim(self):
        cutoff = time.time() - self._window
        while self._calls and self._calls[0].ts < cutoff:
            self._calls.popleft()

    def record(self, latency_ms, tokens, cost, success):
        with self._lock:
            self._calls.append(_Call(time.time(), latency_ms, tokens, cost, success))
            self._trim()

    @property
    def success_rate(self) -> float:
        with self._lock:
            self._trim()
            if not self._calls: return 1.0
            return sum(1 for c in self._calls if c.success) / len(self._calls)

    @property
    def avg_latency(self) -> float:
        with self._lock:
            self._trim()
            s = [c for c in self._calls if c.success]
            return sum(c.latency for c in s) / len(s) if s else 0.0

    @property
    def total_tokens(self) -> int:
        with self._lock: self._trim(); return sum(c.tokens for c in self._calls)

    @property
    def total_cost(self) -> float:
        with self._lock: self._trim(); return sum(c.cost for c in self._calls)

    @property
    def rpm(self) -> float:  # requests per minute
        with self._lock:
            self._trim()
            return len(self._calls) / (self._window / 60)

    def snapshot(self) -> dict:
        return {"success_rate": round(self.success_rate, 3),
                "avg_latency_ms": round(self.avg_latency, 1),
                "tokens_5m": self.total_tokens,
                "cost_5m": round(self.total_cost, 6),
                "rpm": round(self.rpm, 2)}

class MetricsHub:
    def __init__(self):
        self._m: dict[str, AliasMetrics] = {}
        self._lock = threading.Lock()

    def _get(self, alias) -> AliasMetrics:
        with self._lock:
            if alias not in self._m: self._m[alias] = AliasMetrics()
            return self._m[alias]

    def record(self, alias, latency_ms, tokens, cost, success):
        self._get(alias).record(latency_ms, tokens, cost, success)

    def snapshot(self, alias) -> dict:
        return self._get(alias).snapshot()

    def all_snapshots(self) -> dict:
        with self._lock: aliases = list(self._m.keys())
        return {a: self._get(a).snapshot() for a in aliases}

_hub: MetricsHub | None = None
def get_hub() -> MetricsHub:
    global _hub
    if _hub is None: _hub = MetricsHub()
    return _hub

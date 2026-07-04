"""熔断器 — 每个 alias 独立熔断状态"""
import time, threading
from dataclasses import dataclass, field

@dataclass
class _State:
    failures: int = 0
    last_fail: float = 0.0
    open: bool = False

class CircuitBreaker:
    def __init__(self, threshold=3, cooldown=60):
        self.threshold = threshold
        self.cooldown = cooldown
        self._states: dict[str, _State] = {}
        self._lock = threading.Lock()

    def allow(self, alias: str) -> bool:
        with self._lock:
            s = self._states.get(alias)
            if not s: return True
            if not s.open: return True
            if time.time() - s.last_fail > self.cooldown:
                s.open = False; s.failures = 0; return True
            return False

    def on_success(self, alias: str):
        with self._lock:
            self._states.pop(alias, None)

    def on_failure(self, alias: str):
        with self._lock:
            s = self._states.setdefault(alias, _State())
            s.failures += 1; s.last_fail = time.time()
            if s.failures >= self.threshold: s.open = True

    def is_open(self, alias: str) -> bool:
        s = self._states.get(alias)
        return bool(s and s.open)

    def status_all(self) -> dict:
        now = time.time()
        return {a: {"open": s.open, "failures": s.failures,
                    "cooldown_remaining": max(0, self.cooldown-(now-s.last_fail))}
                for a, s in self._states.items()}

    def reset(self, alias: str):
        with self._lock: self._states.pop(alias, None)

_cb: CircuitBreaker | None = None
def get_cb() -> CircuitBreaker:
    global _cb
    if _cb is None:
        from config import cfg
        _cb = CircuitBreaker(cfg.CB_THRESHOLD, cfg.CB_COOLDOWN)
    return _cb

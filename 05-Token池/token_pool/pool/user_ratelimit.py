"""P1-5: 每用户限流 — RPM/RPD/TPD 滑动窗口 + token桶

参考 freellmapi middleware/rateLimit.ts，适配 MBclaw Token Pool。
"""
from __future__ import annotations
import time, threading
from collections import deque, defaultdict
from config import cfg

MINUTE = 60
DAY = 86400


class UserRateLimiter:
    """每用户限流器。内存滑动窗口，线程安全。"""

    def __init__(self, default_rpm: int = 60, default_rpd: int = 1000, default_tpd: int = 1_000_000):
        self._lock = threading.Lock()
        self._rpm: dict[str, deque[float]] = defaultdict(deque)
        self._rpd: dict[str, deque[float]] = defaultdict(deque)
        self._tpd: dict[str, deque[tuple[float, int]]] = defaultdict(deque)
        self.default_rpm = default_rpm
        self.default_rpd = default_rpd
        self.default_tpd = default_tpd

    def _purge(self, dq: deque, window: float) -> None:
        now = time.time()
        while dq and now - dq[0] > window:
            dq.popleft()

    def _purge_tpd(self, dq: deque[tuple[float, int]], window: float) -> None:
        now = time.time()
        while dq and now - dq[0][0] > window:
            dq.popleft()

    def check(self, user_code: str, tokens: int = 0) -> tuple[bool, float]:
        """检查是否允许请求。返回 (allowed, retry_after_seconds)。"""
        with self._lock:
            now = time.time()
            rpm_dq = self._rpm[user_code]
            rpd_dq = self._rpd[user_code]
            tpd_dq = self._tpd[user_code]

            self._purge(rpm_dq, MINUTE)
            self._purge(rpd_dq, DAY)
            self._purge_tpd(tpd_dq, DAY)

            if len(rpm_dq) >= self.default_rpm:
                return False, max(0.0, MINUTE - (now - rpm_dq[0]))
            if len(rpd_dq) >= self.default_rpd:
                return False, max(0.0, DAY - (now - rpd_dq[0]))
            if tokens > 0:
                tpd_sum = sum(t for _, t in tpd_dq)
                if tpd_sum + tokens > self.default_tpd:
                    return False, max(0.0, DAY - (now - tpd_dq[0][0]))
            return True, 0.0

    def record(self, user_code: str, tokens: int = 0) -> None:
        """记录一次请求消耗。"""
        with self._lock:
            now = time.time()
            self._rpm[user_code].append(now)
            self._rpd[user_code].append(now)
            if tokens > 0:
                self._tpd[user_code].append((now, tokens))


_limiter: UserRateLimiter | None = None


def get_user_limiter() -> UserRateLimiter:
    global _limiter
    if _limiter is None:
        _limiter = UserRateLimiter(
            default_rpm=getattr(cfg, "USER_RPM", 60),
            default_rpd=getattr(cfg, "USER_RPD", 1000),
            default_tpd=getattr(cfg, "USER_TPD", 1_000_000),
        )
    return _limiter

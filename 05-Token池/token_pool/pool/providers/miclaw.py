"""P2-4: MiClaw Provider — 免费代理融合为 Token Pool 的一个 Provider

HTTP 调 Rust bridge :8765，OpenAI 兼容。多账号轮询 + Cookie/Session 认证。
"""
from __future__ import annotations
import time, logging, threading
import httpx
from .base import BaseProvider, ProviderResult
from pool.registry import get_registry

log = logging.getLogger(__name__)

# Bridge 地址（默认，可通过环境变量覆盖）
DEFAULT_BRIDGE_URL = "http://121.199.57.195:8765/v1"


class MiClawProvider(BaseProvider):
    """单个 MiClaw 账号的 Provider。chat() 通过 bridge HTTP 转发。"""

    def __init__(self, account_id: int, username: str, bridge_url: str = DEFAULT_BRIDGE_URL, timeout: float = 120):
        self.account_id = account_id
        self.username = username
        self.bridge_url = bridge_url.rstrip("/")
        self.timeout = timeout

    async def chat(self, payload: dict, headers: dict | None = None) -> ProviderResult:
        """调用 bridge /chat/completions，附带 Cookie/Session 认证"""
        reg = get_registry()
        url = f"{self.bridge_url}/chat/completions"
        hdrs = {"Content-Type": "application/json"}
        cookie = reg.get_miclaw_cookie(self.account_id)
        if cookie:
            hdrs["Cookie"] = cookie
        body = {**payload}  # bridge 自己决定 model
        start = time.time()
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as c:
                r = await c.post(url, json=body, headers=hdrs)
                data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
                latency = (time.time() - start) * 1000
                tokens = data.get("usage", {}).get("total_tokens", 0) if isinstance(data, dict) else 0
                ok = r.is_success
                if ok:
                    reg.update_miclaw_usage(self.account_id, tokens)
                return ProviderResult(ok=ok, status_code=r.status_code, body=data if isinstance(data, dict) else None,
                                      latency_ms=latency, tokens_used=tokens,
                                      error="" if ok else str(data))
        except Exception as e:
            return ProviderResult(ok=False, error=str(e), latency_ms=(time.time() - start) * 1000)

    async def health_check(self) -> bool:
        try:
            async with httpx.AsyncClient(timeout=10) as c:
                r = await c.get(f"{self.bridge_url}/models")
                return r.is_success
        except Exception:
            return False


class MiClawAccountManager:
    """多账号管理器 + P2-9: 70/30/10 速率分层"""

    def __init__(self):
        self._lock = threading.Lock()
        self._idx: dict[str, int] = {}

    def get_provider(self, caller_user_code: str = "", model: str = "miclaw") -> MiClawProvider | None:
        """按优先级选账号：主人独占 → 白名单借用 → 跳过"""
        reg = get_registry()
        accts = reg.list_miclaw_accounts()
        candidates = []
        for a in accts:
            if not a.get("enabled") or a.get("login_status") != "logged_in":
                continue
            owner = a.get("owner_user_code", "")
            wl = a.get("borrower_whitelist", "")
            wl_set = set(w.strip() for w in wl.split(",") if w.strip()) if wl else set()

            if caller_user_code and caller_user_code == owner:
                # 主人：全量 owner_ratio 份额
                owner_pct = a.get("owner_ratio", 0.7)
                cap = int((a.get("daily_limit", 500) or 500) * owner_pct)
                used = a.get("total_used_today", 0)
                if used < cap:
                    candidates.insert(0, (a, cap - used))  # 插队到最前
            elif caller_user_code and caller_user_code in wl_set:
                # 白名单借用者：共享池份额
                shared_pct = a.get("shared_ratio", 0.2)
                cap = int((a.get("daily_limit", 500) or 500) * shared_pct)
                used_shared = int(a.get("total_used_today", 0) * (1 - a.get("owner_ratio", 0.7)))
                if used_shared < cap:
                    candidates.append((a, cap - used_shared))
            elif not caller_user_code:
                # 无 caller_user_code → 管理员/系统调用，任意账号均可
                candidates.append((a, 999999))
        if not candidates:
            return None
        # 选剩余额度最大的
        candidates.sort(key=lambda x: x[1], reverse=True)
        a = candidates[0][0]
        return MiClawProvider(account_id=a["id"], username=a["username"])

    def list_providers(self) -> list[MiClawProvider]:
        reg = get_registry()
        return [MiClawProvider(account_id=a["id"], username=a["username"])
                for a in reg.list_miclaw_accounts()
                if a.get("enabled") and a.get("login_status") == "logged_in"]


_miclaw_mgr: MiClawAccountManager | None = None


def get_miclaw_manager() -> MiClawAccountManager:
    global _miclaw_mgr
    if _miclaw_mgr is None:
        _miclaw_mgr = MiClawAccountManager()
    return _miclaw_mgr

"""MiClaw Account Pool — 高并发账号池管理器

职责:
1. Bridge admin session 管理 (登录/缓存/刷新)
2. 账号并发登录编排 (控制并发数, 不把 Bridge 打炸)
3. 后台健康监控 + 自动重登 (死号检测, session过期重登)
4. 池子聚合统计 (总容量/活跃/死号/token消耗)

Bridge API (from Rust source server.rs):
  POST /api/admin/login          → admin 登录, 获取 mb_session cookie
  POST /api/auth/login           → Xiaomi 账号登录 (需 admin session)
  POST /api/auth/two-factor/send → 发送二步验证码
  POST /api/auth/two-factor/verify → 验证二步验证码
  GET  /api/auth/status          → 检查当前认证状态
  POST /api/auth/refresh         → 刷新 session
  POST /api/auth/logout          → 登出
  GET  /api/usage                → 用量统计
"""
from __future__ import annotations
import asyncio, logging, time, threading
import httpx
from pool.registry import get_registry

log = logging.getLogger("miclaw_pool")

BRIDGE_URL = "http://121.199.57.195:8765"
BRIDGE_ADMIN_PASSWORD = "20070520@han"
LOGIN_CONCURRENCY = 3       # 同时最多登录 N 个账号
HEALTH_INTERVAL = 300       # 健康检查间隔(秒), 5分钟
SESSION_STALE = 600         # session 超过此时间认为过期(秒), 10分钟

# ═══════════════════════════════════════════════════════════
# Bridge Admin Session
# ═══════════════════════════════════════════════════════════
_admin_session: dict = {"cookie": None, "expires": 0}
_admin_lock = threading.Lock()


def _bridge_admin_cookie() -> str | None:
    """获取 Bridge admin session cookie, 自动登录/刷新"""
    with _admin_lock:
        now = time.time()
        if _admin_session["cookie"] and now < _admin_session["expires"] - 60:
            return _admin_session["cookie"]
        try:
            r = httpx.post(
                f"{BRIDGE_URL}/api/admin/login",
                json={"password": BRIDGE_ADMIN_PASSWORD},
                timeout=15,
            )
            if r.status_code == 200:
                cookie = r.headers.get("set-cookie", "")
                if cookie:
                    _admin_session["cookie"] = cookie.split(";")[0]
                    _admin_session["expires"] = now + 3600
                    log.info("Bridge admin logged in, cookie obtained")
                    return _admin_session["cookie"]
                # Some responses return cookie in body
                data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
                if data.get("token"):
                    _admin_session["cookie"] = f"mb_session={data['token']}"
                    _admin_session["expires"] = now + 3600
                    return _admin_session["cookie"]
            log.warning("Bridge admin login failed: %s %s", r.status_code, r.text[:200])
        except Exception as e:
            log.error("Bridge admin login error: %s", e)
        return None


def _bridge_req(method: str, path: str, json_data=None, timeout=30) -> httpx.Response:
    """发送带 admin session 的 Bridge API 请求"""
    cookie = _bridge_admin_cookie()
    headers = {"Content-Type": "application/json"}
    if cookie:
        headers["Cookie"] = cookie
    if method == "POST":
        return httpx.post(f"{BRIDGE_URL}{path}", json=json_data, headers=headers, timeout=timeout)
    return httpx.get(f"{BRIDGE_URL}{path}", headers=headers, timeout=timeout)


# ═══════════════════════════════════════════════════════════
# Account Operations
# ═══════════════════════════════════════════════════════════

def login_account(account_id: int, password: str) -> dict:
    """登录单个 MiClaw 账号 (第一步: 提交账号密码)

    返回: {"ok": bool, "outcome": "authenticated"|"two_factor_required"|"captcha_required", ...}
    """
    reg = get_registry()
    accts = reg.list_miclaw_accounts()
    username = None
    for a in accts:
        if a["id"] == account_id:
            username = a["username"]
            break
    if not username:
        return {"ok": False, "error": "账号不存在"}

    try:
        r = _bridge_req("POST", "/api/auth/login", json_data={"account": username, "password": password})
        if r.status_code == 200:
            data = r.json()
            outcome = data.get("outcome", "authenticated")
            if outcome == "authenticated":
                # 获取 cookie 并存储
                cookie = r.headers.get("set-cookie", "")
                reg.update_miclaw_session(account_id, cookie=cookie, session_token="", login_status="logged_in")
                return {"ok": True, "outcome": "authenticated", "nick": data.get("nick", "")}
            elif outcome == "two_factor_required":
                reg._conn.execute("UPDATE miclaw_accounts SET login_status='pending_2fa' WHERE id=?", (account_id,))
                reg._conn.commit()
                return {"ok": True, "outcome": "two_factor_required", "options": data.get("options", [4, 8])}
            elif outcome == "captcha_required":
                return {"ok": True, "outcome": "captcha_required",
                        "captcha_url": data.get("captcha_url", ""),
                        "captcha_token": data.get("captcha_token", "")}
            else:
                return {"ok": False, "error": f"未知outcome: {outcome}"}
        else:
            data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
            err = data.get("error", {}).get("message", str(r.status_code))
            reg._conn.execute("UPDATE miclaw_accounts SET login_status='failed' WHERE id=?", (account_id,))
            reg._conn.commit()
            return {"ok": False, "error": err}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def send_2fa_ticket(flag: int) -> dict:
    """发送二步验证码 flag: 4=短信, 8=邮箱"""
    try:
        r = _bridge_req("POST", "/api/auth/two-factor/send", json_data={"flag": flag})
        if r.status_code == 200:
            return {"ok": True, "message": "验证码已发送"}
        return {"ok": False, "error": r.text[:200]}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def verify_2fa(account_id: int, flag: int, ticket: str) -> dict:
    """验证二步验证码"""
    try:
        r = _bridge_req("POST", "/api/auth/two-factor/verify",
                        json_data={"flag": flag, "ticket": ticket})
        if r.status_code == 200:
            cookie = r.headers.get("set-cookie", "")
            reg = get_registry()
            reg.update_miclaw_session(account_id, cookie=cookie, session_token="", login_status="logged_in")
            return {"ok": True, "message": "验证成功"}
        data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
        err = data.get("error", {}).get("message", "验证失败")
        return {"ok": False, "error": err}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def check_account_health(account_id: int) -> dict:
    """检查单个账号的 session 是否有效"""
    try:
        r = _bridge_req("GET", "/api/auth/status")
        if r.status_code == 200:
            data = r.json()
            if data.get("authenticated"):
                # Session 有效, 刷新
                refresh_r = _bridge_req("POST", "/api/auth/refresh")
                reg = get_registry()
                if refresh_r.status_code == 200:
                    cookie = refresh_r.headers.get("set-cookie", "")
                    reg.update_miclaw_session(account_id, cookie=cookie, login_status="logged_in")
                return {"ok": True, "healthy": True}
            else:
                return {"ok": True, "healthy": False, "reason": "not authenticated"}
        else:
            return {"ok": False, "healthy": False, "reason": f"HTTP {r.status_code}"}
    except Exception as e:
        return {"ok": False, "healthy": False, "reason": str(e)}


def logout_account(account_id: int):
    """登出单个账号"""
    try:
        _bridge_req("POST", "/api/auth/logout")
        reg = get_registry()
        reg._conn.execute("UPDATE miclaw_accounts SET login_status='pending', cookie='', session_token='' WHERE id=?", (account_id,))
        reg._conn.commit()
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": str(e)}


# ═══════════════════════════════════════════════════════════
# Pool Operations
# ═══════════════════════════════════════════════════════════

def login_all_pending(max_concurrent: int = LOGIN_CONCURRENCY) -> dict:
    """批量登录所有 pending/failed 状态的账号 (串行, 逐个提交账号密码)

    返回: {"total": N, "logged_in": N, "failed": N, "two_factor": N, "results": [...]}
    """
    reg = get_registry()
    accts = reg.list_miclaw_accounts()
    results = []
    logged, failed, tfa = 0, 0, 0

    for a in accts:
        if a["login_status"] in ("logged_in", "active"):
            continue  # 已登录的跳过
        pw = reg.get_miclaw_password(a["id"])
        if not pw:
            results.append({"id": a["id"], "username": a["username"], "ok": False, "error": "无密码"})
            failed += 1
            continue

        r = login_account(a["id"], pw)
        results.append({"id": a["id"], "username": a["username"], **r})
        if r["ok"]:
            if r.get("outcome") == "authenticated":
                logged += 1
            elif r.get("outcome") == "two_factor_required":
                tfa += 1
            else:
                logged += 1
        else:
            failed += 1

    return {"total": len(accts), "logged_in": logged, "failed": failed, "two_factor": tfa, "results": results}


def probe_all_accounts() -> dict:
    """探测全部账号健康状态

    返回: {"total": N, "healthy": N, "dead": N, "results": [...]}
    """
    reg = get_registry()
    accts = reg.list_miclaw_accounts()
    results = []
    healthy, dead = 0, 0

    for a in accts:
        if a["login_status"] not in ("logged_in", "active"):
            results.append({"id": a["id"], "username": a["username"], "healthy": False, "reason": f"status={a['login_status']}"})
            dead += 1
            continue
        r = check_account_health(a["id"])
        results.append({"id": a["id"], "username": a["username"], **r})
        if r.get("healthy"):
            healthy += 1
        else:
            dead += 1
            # 标记失败
            err = r.get("reason", "unknown")
            reg._conn.execute("UPDATE miclaw_accounts SET login_status='failed' WHERE id=?", (a["id"],))
            reg._conn.commit()

    return {"total": len(accts), "healthy": healthy, "dead": dead, "results": results}


def get_pool_stats() -> dict:
    """获取池子聚合统计"""
    reg = get_registry()
    accts = reg.list_miclaw_accounts()
    total = len(accts)
    logged = sum(1 for a in accts if a["login_status"] in ("logged_in", "active"))
    pending = sum(1 for a in accts if a["login_status"] == "pending")
    failed = sum(1 for a in accts if a["login_status"] == "failed")
    total_tokens = sum(a.get("total_tokens_today", 0) or 0 for a in accts)
    total_used_today = sum(a.get("total_used_today", 0) or 0 for a in accts)
    total_success = sum(a.get("success_count", 0) or 0 for a in accts)
    total_fail = sum(a.get("fail_count", 0) or 0 for a in accts)

    return {
        "total": total,
        "logged_in": logged,
        "pending": pending,
        "failed": failed,
        "total_tokens_today": total_tokens,
        "total_calls_today": total_used_today,
        "total_success": total_success,
        "total_fail": total_fail,
        "bridge_url": BRIDGE_URL,
        "bridge_ok": _bridge_admin_cookie() is not None,
    }


# ═══════════════════════════════════════════════════════════
# Background Health Monitor
# ═══════════════════════════════════════════════════════════

_health_task: asyncio.Task | None = None


async def health_check_loop(interval: int = HEALTH_INTERVAL):
    """后台健康监控: 每 interval 秒检查所有已登录账号, 死号尝试重登"""
    while True:
        await asyncio.sleep(interval)
        try:
            reg = get_registry()
            accts = reg.list_miclaw_accounts()
            logged = [a for a in accts if a["login_status"] in ("logged_in", "active")]
            if not logged:
                continue

            log.info("Health check: %d logged-in accounts", len(logged))
            for a in logged:
                r = check_account_health(a["id"])
                if not r.get("healthy"):
                    log.warning("Account %s unhealthy: %s, attempting re-login", a["username"], r.get("reason", "?"))
                    pw = reg.get_miclaw_password(a["id"])
                    if pw:
                        login_r = login_account(a["id"], pw)
                        if login_r["ok"] and login_r.get("outcome") == "authenticated":
                            log.info("Account %s re-logged in successfully", a["username"])
                        else:
                            log.error("Account %s re-login failed: %s", a["username"], login_r.get("error", "?"))
        except Exception as e:
            log.error("Health check error: %s", e)


async def start_health_monitor():
    """启动后台健康监控"""
    global _health_task
    if _health_task is None or _health_task.done():
        _health_task = asyncio.ensure_future(health_check_loop())
        log.info("MiClaw pool health monitor started (interval=%ds)", HEALTH_INTERVAL)

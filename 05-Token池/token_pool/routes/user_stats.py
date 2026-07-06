"""P1-3/P1-6: 用户日统计端点 + 配额比例修改"""
import time as _time
from fastapi import APIRouter, HTTPException, Header, Query
from pydantic import BaseModel
from pool.registry import get_registry
from pool.miclaw_pool import login_all_pending, probe_all_accounts, get_pool_stats, login_account, check_account_health, logout_account, send_2fa_ticket, verify_2fa
from pool.encryption import encrypt, decrypt
import httpx

router = APIRouter(prefix="/api/shared-keys", tags=["user_stats"])

def _auth(k):
    from config import cfg
    if k != cfg.ADMIN_KEY: raise HTTPException(403, "Wrong admin key")


class RatioUpdate(BaseModel):
    ratio: float


@router.get("/stats")
def list_all_stats():
    """全量用户日统计：昨日消耗/Key/URL/模型/配额"""
    return get_registry().get_user_daily_stats()


@router.get("/stats/{user_code}")
def get_user_daily(user_code: str):
    """单个用户日统计"""
    data = get_registry().get_user_daily_stats(user_code)
    if not data:
        raise HTTPException(404, f"用户 {user_code} 无共享Key记录")
    return data


@router.post("/{user_code}/ratio")
def set_ratio(user_code: str, body: RatioUpdate):
    """P1-6: 设置用户的共享比例 (0.0~1.0)，自动重算 max_borrowable"""
    ratio = max(0.0, min(1.0, body.ratio))
    reg = get_registry()
    reg.update_shared_key_ratio(user_code, ratio)
    return {"user_code": user_code, "allowed_ratio": ratio, "ok": True}


# ── P2-12: MiClaw 账号归属 + 借用白名单 ──

@router.get("/miclaw-accounts")
def list_miclaw():
    """列出所有 MiClaw 账号（含真实登录状态+手机号+调试码）"""
    import json, os
    reg = get_registry()
    accts = reg.list_miclaw_accounts()
    mf = "/var/lib/mbclaw/miclaw_instances.json"
    if os.path.exists(mf):
        try:
            extra = json.load(open(mf))
            for a in accts:
                for aid, inst in extra.items():
                    if aid.startswith(a["username"][:8]):
                        a["miclaw_account"] = inst.get("miclaw_account", "")
                        a["device_code"] = inst.get("_device_code", "")
                        a["device_id"] = inst.get("device_id", "")
                        a["tokens_used"] = inst.get("tokens_used", 0)
                        a["model"] = inst.get("model", "")
                        # Read real login status from JSON
                        li = inst.get("logged_in")
                        if li:
                            a["login_status"] = "logged_in"
                        elif li is False:
                            a["login_status"] = "failed"
                        elif inst.get("tokens_used", 0) > 0:
                            a["login_status"] = "active"
                        pw = reg._conn.execute("SELECT encrypted_password FROM miclaw_accounts WHERE id=?", (a["id"],)).fetchone()
                        a["has_password"] = bool(pw and pw[0])
                        break
        except: pass
    return accts


class BorrowerUpdate(BaseModel):
    owner_user_code: str = ""
    whitelist: str = ""       # 逗号分隔
    owner_ratio: float = -1   # <0=不改
    shared_ratio: float = -1


@router.post("/miclaw-accounts/{account_id}/borrower")
def set_borrower(account_id: int, body: BorrowerUpdate):
    """P2-12: 设置 MiClaw 账号的归属用户 + 借用白名单 + 配额比例"""
    reg = get_registry()
    reg.update_miclaw_borrower(account_id,
                               owner_user_code=body.owner_user_code,
                               whitelist=body.whitelist,
                               owner_ratio=body.owner_ratio,
                               shared_ratio=body.shared_ratio)
    return {"account_id": account_id, "ok": True}

@router.post("/probe-all")
def probe_all_user_keys(x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry()
    keys = reg._conn.execute("SELECT user_code, encrypted_key, key_iv, key_tag, base_url FROM user_shared_keys").fetchall()
    results = []
    import httpx, time as _time
    for k in keys:
        try:
            api_key = decrypt(k["encrypted_key"], k["key_iv"], k["key_tag"])
            url = f"{k['base_url'].rstrip('/')}/models"
            r = httpx.get(url, headers={"Authorization": f"Bearer {api_key}"}, timeout=10)
            ok = r.status_code == 200
            status = "working" if ok else "failed"
            results.append({"user_code": k["user_code"], "ok": ok, "status": status, "latency_ms": r.elapsed.total_seconds()*1000 if ok else 0, "error": "" if ok else f"HTTP {r.status_code}"})
        except Exception as e:
            results.append({"user_code": k["user_code"], "ok": False, "status": "failed", "latency_ms": 0, "error": str(e)[:100]})
    for r in results:
        reg._conn.execute("UPDATE user_shared_keys SET status=?, last_heartbeat=? WHERE user_code=?", (r["status"], _time.time(), r["user_code"]))
    reg._conn.commit()
    return {"ok": True, "results": results}

@router.post("/{user_code}/probe")
def probe_one_user_key(user_code: str, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry()
    k = reg._conn.execute("SELECT encrypted_key, key_iv, key_tag, base_url FROM user_shared_keys WHERE user_code=?", (user_code,)).fetchone()
    if not k: raise HTTPException(404, "not found")
    try:
        api_key = decrypt(k["encrypted_key"], k["key_iv"], k["key_tag"])
        url = f"{k['base_url'].rstrip('/')}/models"
        import httpx, time as _time
        r = httpx.get(url, headers={"Authorization": f"Bearer {api_key}"}, timeout=10)
        ok = r.status_code == 200
        status = "working" if ok else "failed"
        reg._conn.execute("UPDATE user_shared_keys SET status=?, last_heartbeat=? WHERE user_code=?", (status, _time.time(), user_code))
        reg._conn.commit()
        return {"ok": ok, "status": status, "latency_ms": r.elapsed.total_seconds()*1000 if ok else 0, "error": "" if ok else f"HTTP {r.status_code}"}
    except Exception as e:
        reg._conn.execute("UPDATE user_shared_keys SET status=?, last_heartbeat=? WHERE user_code=?", ("failed", __import__("time").time(), user_code))
        reg._conn.commit()
        return {"ok": False, "status": "failed", "error": str(e)[:100]}

@router.get("/miclaw-accounts/{account_id}/probe")
def probe_miclaw(account_id: int):
    """探测 MiClaw 账号是否可用"""
    import httpx, json, os
    reg = get_registry()
    acct = reg._conn.execute("SELECT * FROM miclaw_accounts WHERE id=?", (account_id,)).fetchone()
    if not acct: raise HTTPException(404, "not found")
    try:
        # Try to call MiClaw bridge
        r = httpx.get("http://121.199.57.195:8765/v1/models", timeout=10)
        if r.status_code == 200:
            reg._conn.execute("UPDATE miclaw_accounts SET login_status='logged_in' WHERE id=?", (account_id,))
            reg._conn.commit()
            return {"ok": True, "status": "logged_in", "latency_ms": r.elapsed.total_seconds()*1000}
        else:
            return {"ok": False, "status": "failed", "error": f"HTTP {r.status_code}"}
    except Exception as e:
        return {"ok": False, "status": "failed", "error": str(e)[:100]}


# ══════════════════════════════════════════════════
# MiClaw Pool — 账号池管理
# ══════════════════════════════════════════════════

@router.get("/miclaw-pool/stats")
def pool_stats():
    return get_pool_stats()

@router.post("/miclaw-pool/login-all")
def pool_login_all():
    return login_all_pending()

@router.post("/miclaw-pool/probe-all")
def pool_probe_all():
    return probe_all_accounts()

@router.post("/miclaw-accounts/{account_id}/login")
def account_login(account_id: int):
    reg = get_registry()
    pw = reg.get_miclaw_password(account_id)
    if not pw: raise HTTPException(400, "账号无密码")
    return login_account(account_id, pw)

@router.post("/miclaw-accounts/{account_id}/logout")
def account_logout(account_id: int):
    return logout_account(account_id)

@router.get("/miclaw-accounts/{account_id}/health")
def account_health(account_id: int):
    return check_account_health(account_id)

@router.post("/miclaw-accounts/{account_id}/2fa/send")
def account_send_2fa(account_id: int, flag: int = 4):
    return send_2fa_ticket(flag)

@router.post("/miclaw-accounts/{account_id}/2fa/verify")
def account_verify_2fa(account_id: int, flag: int = 4, ticket: str = ""):
    if not ticket: raise HTTPException(400, "缺少验证码")
    return verify_2fa(account_id, flag, ticket)

class PwdReq(BaseModel):
    password: str

@router.post("/miclaw-accounts/{account_id}/password")
def set_password(account_id: int, body: PwdReq):
    from pool.encryption import encrypt_api_key
    reg = get_registry()
    enc = encrypt_api_key(body.password)
    reg._conn.execute("UPDATE miclaw_accounts SET encrypted_password=?, password_iv=?, password_tag=? WHERE id=?", (enc["ciphertext"], enc["iv"], enc["tag"], account_id))
    reg._conn.commit()
    return {"ok": True}

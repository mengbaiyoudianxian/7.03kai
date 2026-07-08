"""P1-3/P1-6: 用户日统计端点 + 配额比例修改"""
import time as _time
from fastapi import APIRouter, HTTPException, Header, Query
from pydantic import BaseModel
from pool.registry import get_registry
from pool.miclaw_pool import login_all_pending, probe_all_accounts, get_pool_stats, login_account, check_account_health, logout_account, send_2fa_ticket, verify_2fa
from pool.encryption import encrypt, decrypt
from pool.url_guard import validate_url
import httpx

router = APIRouter(prefix="/api/shared-keys", tags=["user_stats"])

def _auth(k):
    from config import cfg
    if k != cfg.ADMIN_KEY: raise HTTPException(403, "Wrong admin key")


class RatioUpdate(BaseModel):
    ratio: float


class UrlUpdate(BaseModel):
    base_url: str


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


@router.post("/{user_code}/url")
def set_url(user_code: str, body: UrlUpdate, x_admin_key: str = Header(default="")):
    """P1-7: 修改共享Key的 base_url"""
    _auth(x_admin_key)
    url = body.base_url.strip()
    if not url:
        raise HTTPException(400, "URL 不能为空")
    ok, msg = validate_url(url)
    if not ok:
        raise HTTPException(400, f"URL 校验失败: {msg}")
    reg = get_registry()
    reg.update_shared_key_url(user_code, url)
    return {"user_code": user_code, "base_url": url, "ok": True}


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

def _test_key_chat(api_key: str, base_url: str, model_name: str = "") -> dict:
    """实际对话检测，返回 {ok, msg, status_code, models, latency_ms}"""
    import httpx
    models = []
    latency_ms = 0
    # 步骤1: /models
    try:
        r = httpx.get(f"{base_url.rstrip('/')}/models",
                       headers={"Authorization": f"Bearer {api_key}"}, timeout=8)
        if r.status_code == 200:
            latency_ms = r.elapsed.total_seconds() * 1000
            try: models = [m["id"] for m in r.json().get("data", [])]
            except: pass
    except Exception:
        pass
    # 步骤2: chat completion
    test_model = model_name or (models[0] if models else "gpt-3.5-turbo")
    try:
        chat_url = base_url.rstrip("/")
        if not chat_url.endswith("/chat/completions"):
            if "/v1" in chat_url:
                chat_url = chat_url.split("/v1")[0] + "/v1/chat/completions"
            else:
                chat_url += "/chat/completions"
        r = httpx.post(chat_url, json={
            "model": test_model, "messages": [{"role": "user", "content": "hi"}],
            "max_tokens": 5,
        }, headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}, timeout=15)
        if r.status_code == 200:
            data = r.json()
            reply = data.get("choices", [{}])[0].get("message", {}).get("content", "")
            return {"ok": True, "msg": f"回复: {reply[:60]}", "status_code": 200, "models": models, "latency_ms": latency_ms}
        else:
            detail = ""
            try: detail = r.json().get("error", {}).get("message", "")[:80]
            except: detail = r.text[:80] if r.text else ""
            return {"ok": False, "msg": f"对话失败 HTTP {r.status_code} {detail}", "status_code": r.status_code, "models": models, "latency_ms": latency_ms}
    except Exception as e:
        return {"ok": False, "msg": str(e)[:80], "status_code": 0, "models": models, "latency_ms": latency_ms}


@router.post("/probe-all")
def probe_all_user_keys(x_admin_key: str = Header(default="")):
    """全部检测—遍历 heartbeat_logs，发实际对话验证。"""
    _auth(x_admin_key)
    import os as _os, json as _json
    hb_dir = "/var/lib/mbclaw/heartbeat_logs"
    results = []
    if not _os.path.isdir(hb_dir):
        return {"ok": True, "results": results}
    for fn in sorted(_os.listdir(hb_dir)):
        try:
            d = _json.load(open(_os.path.join(hb_dir, fn)))
        except Exception:
            continue
        keys = d.get("keys", {})
        api_key = keys.get("api_key", "")
        base_url = keys.get("api_base_url", "")
        if not api_key or not base_url:
            continue
        code = d.get("code", "")
        kt = _test_key_chat(api_key, base_url, keys.get("model_name", ""))
        results.append({"user_code": code, "ok": kt["ok"], "msg": kt.get("msg", ""),
                         "models": kt.get("models", []), "status_code": kt.get("status_code", 0)})
    return {"ok": True, "results": results}


@router.post("/{user_code}/probe")
def probe_one_user_key(user_code: str, x_admin_key: str = Header(default="")):
    """单条检测—从 heartbeat_logs 读 key/url，发实际对话验证。"""
    _auth(x_admin_key)
    d = _read_hb_file(user_code)
    if not d:
        raise HTTPException(404, f"用户 {user_code} 不存在")
    keys = d.get("keys", {})
    api_key = keys.get("api_key", "")
    base_url = keys.get("api_base_url", "")
    if not api_key:
        raise HTTPException(400, "没有 API Key")
    if not base_url:
        raise HTTPException(400, "没有 API URL")
    kt = _test_key_chat(api_key, base_url, keys.get("model_name", ""))
    return {"ok": kt["ok"], "status": "working" if kt["ok"] else "failed",
            "error": kt.get("msg", ""), "models": kt.get("models", [])}

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


# ── Legacy 兼容：旧管理面板 Token池 数据接口 ──

# ── 心跳文件读取辅助 ──
def _read_hb_file(code: str) -> dict | None:
    """从控制面板 heartbeat_logs 读取设备数据，与控制面板同源。"""
    import os as _os, json as _json
    safe = code.replace("/", "_").replace("..", "_")
    path = _os.path.join("/var/lib/mbclaw/heartbeat_logs", f"{safe}.json")
    if not _os.path.exists(path):
        return None
    return _json.load(open(path))


@router.get("/legacy/tokens")
def legacy_tokens(x_admin_key: str = Header(default="")):
    """Token池乌托邦数据源 — 直接读控制面板 heartbeat_logs，与控制面板同源。"""
    _auth(x_admin_key)
    import os as _os, json as _json, time as _time
    hb_dir = "/var/lib/mbclaw/heartbeat_logs"
    tokens = []
    if not _os.path.isdir(hb_dir):
        return {"tokens": tokens}
    entries = []
    for fn in _os.listdir(hb_dir):
        path = _os.path.join(hb_dir, fn)
        try:
            d = _json.load(open(path))
        except Exception:
            continue
        keys = d.get("keys", {})
        if not keys.get("api_key"):
            continue
        entries.append((_os.path.getmtime(path), d))
    entries.sort(key=lambda x: x[0], reverse=True)
    for _mtime, d in entries:
        keys = d.get("keys", {})
        tokens.append({
            "code": d.get("code", ""),
            "qq": keys.get("qq", ""),
            "model": "",
            "brand": "",
            "api_key": keys.get("api_key", ""),
            "api_base_url": keys.get("api_base_url", ""),
            "model_name": keys.get("model_name", ""),
            "provider": keys.get("provider_id", ""),
            "online": bool(d.get("online", False)),
            "key_test": {"ok": None, "msg": "", "status_code": 0},
        })
    return {"tokens": tokens}


@router.post("/legacy/test-key")
def legacy_test_key(code: str = "", x_admin_key: str = Header(default="")):
    """Key 检测 — 从 heartbeat_logs 读 key/url，先测 /models 再发实际对话验证。"""
    _auth(x_admin_key)
    d = _read_hb_file(code)
    if not d:
        raise HTTPException(404, f"用户 {code} 不存在")
    keys = d.get("keys", {})
    api_key = keys.get("api_key", "")
    base_url = keys.get("api_base_url", "").strip()
    model_name = keys.get("model_name", "")
    if not api_key:
        raise HTTPException(400, "没有 API Key")
    if not base_url:
        raise HTTPException(400, "没有 API URL")
    import httpx as _hx
    models = []
    # 步骤1: 获取模型列表
    try:
        r = _hx.get(f"{base_url.rstrip('/')}/models",
                     headers={"Authorization": f"Bearer {api_key}"}, timeout=10)
        if r.status_code == 200:
            try:
                models = [m["id"] for m in r.json().get("data", [])]
            except: pass
    except Exception:
        pass
    # 步骤2: 实际发送对话验证
    test_model = model_name or (models[0] if models else "gpt-3.5-turbo")
    try:
        # 从 base_url 推断 chat completions 端点
        chat_url = base_url.rstrip('/')
        if not chat_url.endswith('/chat/completions'):
            if '/v1' in chat_url:
                chat_url = chat_url.split('/v1')[0] + '/v1/chat/completions'
            else:
                chat_url += '/chat/completions'
        r = _hx.post(chat_url, json={
            "model": test_model,
            "messages": [{"role": "user", "content": "hi"}],
            "max_tokens": 5,
        }, headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }, timeout=15)
        if r.status_code == 200:
            data = r.json()
            reply = data.get("choices", [{}])[0].get("message", {}).get("content", "")
            return {"ok": True, "key_test": {
                "ok": True, "msg": f"回复: {reply[:80]}",
                "status_code": 200, "models": models,
            }}
        else:
            detail = ""
            try: detail = r.json().get("error", {}).get("message", "")[:80]
            except: detail = r.text[:80] if r.text else ""
            return {"ok": True, "key_test": {
                "ok": False,
                "msg": f"对话失败 HTTP {r.status_code} {detail}",
                "status_code": r.status_code, "models": models,
            }}
    except Exception as e:
        return {"ok": True, "key_test": {
            "ok": False, "msg": f"对话异常: {str(e)[:80]}",
            "status_code": 0, "models": models,
        }}

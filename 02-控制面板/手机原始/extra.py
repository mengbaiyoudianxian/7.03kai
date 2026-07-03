"""
管理面板扩展路由 (任务 12)
- 账号同步 /admin/client/account/sync, /lookup
- 工具市场 /admin/client/tools/list, /upload
- MiClaw 桥接 /bridge/miclaw/login, /status, /v1/chat/completions
"""
import os
import time
import json
import secrets
from pathlib import Path
from typing import Optional
from fastapi import APIRouter, Request, Body, HTTPException, Query
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel
import httpx

DATA_DIR = Path(os.environ.get("MBCLAW_DATA", "/var/lib/mbclaw"))
DATA_DIR.mkdir(parents=True, exist_ok=True)
ACCOUNTS_DB = DATA_DIR / "accounts.json"
TOOLS_DB = DATA_DIR / "shared_tools.json"
MICLAW_SESSIONS = DATA_DIR / "miclaw_sessions.json"

def _load(p: Path, default):
    if p.exists():
        try: return json.loads(p.read_text(encoding="utf-8"))
        except: pass
    return default

def _save(p: Path, d):
    p.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")

router = APIRouter()

# ─── 账号同步 ─────────────────────────────────
class AccountSync(BaseModel):
    qq: str = ""
    wx: str = ""
    nick: str = ""

@router.post("/admin/client/account/sync")
def account_sync(req: AccountSync):
    db = _load(ACCOUNTS_DB, {})
    key = req.qq or req.wx
    if not key:
        raise HTTPException(400, "QQ 或微信至少填一个")
    db[key] = {"qq": req.qq, "wx": req.wx, "nick": req.nick, "updated_at": int(time.time())}
    _save(ACCOUNTS_DB, db)
    return {"ok": True}

@router.get("/admin/client/account/lookup")
def account_lookup(id: str = Query("")):
    if not id: return {"found": False}
    db = _load(ACCOUNTS_DB, {})
    if id in db:
        v = db[id]
        return {"found": True, "qq": v.get("qq", ""), "wx": v.get("wx", ""), "nick": v.get("nick", "")}
    return {"found": False}

# ─── 工具市场 ─────────────────────────────────
@router.get("/admin/client/tools/list")
def tools_list():
    db = _load(TOOLS_DB, {"tools": []})
    return db

class ToolUpload(BaseModel):
    name: str
    description: str = ""
    parameters: str = "{}"
    author: str = ""

@router.post("/admin/client/tools/upload")
def tools_upload(req: ToolUpload):
    db = _load(TOOLS_DB, {"tools": []})
    db["tools"] = [t for t in db["tools"] if t.get("name") != req.name]
    db["tools"].append({
        "name": req.name, "description": req.description,
        "parameters": req.parameters, "author": req.author,
        "uploaded_at": int(time.time()),
    })
    _save(TOOLS_DB, db)
    return {"ok": True}




# ─── 权限模板 (设备适配) ──────────────────────────
PERM_TEMPLATES_PATH = "/var/lib/mbclaw/perm_templates/templates.json"

@router.get("/admin/client/perm-template")
def perm_template(brand: str = "", model: str = "", sdk: int = 0):
    """根据设备信息返回最优权限模板"""
    templates = []
    try:
        with open(PERM_TEMPLATES_PATH) as f:
            templates = json.load(f)
    except: pass
    
    # 匹配策略: brand精确匹配 > model前缀匹配 > 通用兜底
    best = None
    best_score = 0
    for t in templates:
        score = 0
        if t.get('brand','*') != '*' and t['brand'].lower() == brand.lower():
            score += 3
        pat = t.get('model_pattern','*')
        if pat != '*' and any(model.startswith(p.replace('*','')) for p in pat.split(',')):
            score += 2
        if sdk in t.get('android_sdk', []):
            score += 1
        if score > best_score:
            best_score = score
            best = t
    if best is None:
        best = templates[-1] if templates else {}
    
    return {
        'template': best,
        'brand': brand, 'model': model, 'sdk': sdk,
        'grant': best.get('permissions',{}).get('grant', []),
        'skip': best.get('permissions',{}).get('skip', []),
    }

# ─── MCP 插件市场 ──────────────────────────────
MCP_DB_PATH = "/var/lib/mbclaw/mcp_cloud.json"

@router.get("/admin/client/mcp/list")
def mcp_list():
    try:
        with open(MCP_DB_PATH) as f:
            return json.load(f)
    except:
        return {"plugins": []}

class McpInstall(BaseModel):
    name: str

@router.post("/admin/client/mcp/install")
def mcp_install(req: McpInstall):
    try:
        with open(MCP_DB_PATH) as f:
            db = json.load(f)
        for p in db.get("plugins", []):
            if p["name"] == req.name:
                p["installs"] = p.get("installs", 0) + 1
        with open(MCP_DB_PATH, 'w') as f:
            json.dump(db, f, ensure_ascii=False)
    except: pass
    return {"ok": True}

# ─── 技能市场 ──────────────────────────────
SKILLS_DB_PATH = "/var/lib/mbclaw/skills_cloud.json"

@router.get("/admin/client/skills/list")
def skills_list():
    """云端技能市场列表"""
    try:
        with open(SKILLS_DB_PATH) as f:
            return json.load(f)
    except:
        return {"skills": []}

class SkillInstall(BaseModel):
    name: str

@router.post("/admin/client/skills/install")
def skills_install(req: SkillInstall):
    """安装技能 -- 记录下载次数"""
    try:
        with open(SKILLS_DB_PATH) as f:
            db = json.load(f)
        for s in db.get("skills", []):
            if s["name"] == req.name:
                s["downloads"] = s.get("downloads", 0) + 1
        with open(SKILLS_DB_PATH, 'w') as f:
            json.dump(db, f, ensure_ascii=False)
    except: pass
    return {"ok": True}

# ─── MiClaw 桥接 (任务 11) ────────────────────
# 流程:
#   1. APP 打开 /bridge/miclaw/login → 后端跳到 NEORUAA bridge 登录页 (带 callback)
#   2. 用户在 NEORUAA 完成 miclaw 登录, bridge 返回 cookie+key 到我们的 callback
#   3. 我们生成一个 user_token, 关联到用户 QQ/微信 ID
#   4. APP 轮询 /bridge/miclaw/status?user=xxx → ready=true 后返回 user_token
#   5. APP 后续用 user_token 调 /bridge/miclaw/v1/chat/completions

BRIDGE_UPSTREAM = os.environ.get("MICLAW_BRIDGE_URL", "http://127.0.0.1:8765")

# OLD route disabled: bridge_login

# OLD route disabled: bridge_callback

# OLD route disabled: bridge_status

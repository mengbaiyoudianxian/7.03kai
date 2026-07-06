"""免费共享 Key 路由 — 营销吸引用户，仅限借用 MiClaw，有速率和总量限制"""
import json, time, re, hashlib
from fastapi import APIRouter, HTTPException, Header, Query
from pydantic import BaseModel
from pool.registry import get_registry
from config import cfg

router = APIRouter(prefix="/api/free-keys", tags=["free-keys"])

def _auth(k):
    if k != cfg.ADMIN_KEY: raise HTTPException(403, "Wrong admin key")

FREE_DEFAULTS = {
    "total_limit": 50000,      # 默认总量 50000 tokens
    "daily_limit": 5000,       # 每日限额
    "rpm_limit": 5,            # 每分钟请求数
    "miclaw_only": True,       # 只能用 MiClaw
}

# ── 注册 / 获取免费Key（APK调用）──
@router.post("/register")
def register(body: dict):
    """根据设备码+IP注册免费Key，同设备或同IP返回已存在Key"""
    device_code = (body.get("device_code") or "").strip()
    ip = (body.get("ip") or "").strip()
    if not device_code or not ip:
        raise HTTPException(400, "device_code and ip required")
    
    reg = get_registry()
    # 查找已有Key（同设备码或同IP）
    existing = reg._conn.execute(
        "SELECT * FROM free_shared_keys WHERE device_code=? OR ip_address=?",
        (device_code, ip)
    ).fetchone()
    
    if existing:
        return {
            "code": existing["code"],
            "total_limit": existing["total_limit"],
            "used_total": existing["used_total"],
            "used_today": existing["used_today"],
            "rpm_limit": existing["rpm_limit"],
            "status": existing["status"],
            "existing": True,
        }
    
    # 生成新Key code: mb-mf-{device_hash 前8位}
    h = hashlib.md5(f"{device_code}:{ip}".encode()).hexdigest()[:8]
    code = f"mb-mf-{h}"
    
    reg._conn.execute(
        """INSERT INTO free_shared_keys(code, device_code, ip_address, total_limit, daily_limit, rpm_limit, status)
           VALUES(?,?,?,?,?,?,?)""",
        (code, device_code, ip, FREE_DEFAULTS["total_limit"], FREE_DEFAULTS["daily_limit"], FREE_DEFAULTS["rpm_limit"], "active")
    )
    reg._conn.commit()
    
    return {
        "code": code,
        "total_limit": FREE_DEFAULTS["total_limit"],
        "used_total": 0,
        "used_today": 0,
        "rpm_limit": FREE_DEFAULTS["rpm_limit"],
        "status": "active",
        "existing": False,
    }

# ── 管理接口 ──

@router.get("")
def list_free_keys(x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry()
    rows = reg._conn.execute(
        "SELECT * FROM free_shared_keys ORDER BY created_at DESC"
    ).fetchall()
    return [dict(r) for r in rows]

@router.get("/stats")
def free_key_stats(x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry()
    total = reg._conn.execute("SELECT COUNT(*) FROM free_shared_keys").fetchone()[0]
    active = reg._conn.execute("SELECT COUNT(*) FROM free_shared_keys WHERE status='active'").fetchone()[0]
    used_total = reg._conn.execute("SELECT COALESCE(SUM(used_total),0) FROM free_shared_keys").fetchone()[0]
    used_today = reg._conn.execute("SELECT COALESCE(SUM(used_today),0) FROM free_shared_keys").fetchone()[0]
    return {"total": total, "active": active, "used_total": used_total, "used_today": used_today}

@router.post("/{code}/refresh")
def refresh_key(code: str, x_admin_key: str = Header(default="")):
    """刷新单个Key当日用量"""
    _auth(x_admin_key)
    reg = get_registry()
    reg._conn.execute("UPDATE free_shared_keys SET used_today=0 WHERE code=?", (code,))
    reg._conn.commit()
    return {"ok": True}

@router.post("/refresh-all")
def refresh_all_keys(x_admin_key: str = Header(default="")):
    """批量刷新所有Key当日用量"""
    _auth(x_admin_key)
    reg = get_registry()
    reg._conn.execute("UPDATE free_shared_keys SET used_today=0")
    reg._conn.commit()
    n = reg._conn.execute("SELECT COUNT(*) FROM free_shared_keys").fetchone()[0]
    return {"ok": True, "refreshed": n}

@router.post("/{code}/adjust")
def adjust_key(code: str, body: dict, x_admin_key: str = Header(default="")):
    """调整单个Key配额"""
    _auth(x_admin_key)
    reg = get_registry()
    updates = {}
    for k in ["total_limit", "daily_limit", "rpm_limit", "status"]:
        if k in body:
            updates[k] = body[k]
    if not updates:
        raise HTTPException(400, "No fields to update")
    set_clause = ", ".join(f"{k}=?" for k in updates)
    vals = list(updates.values()) + [code]
    reg._conn.execute(f"UPDATE free_shared_keys SET {set_clause} WHERE code=?", vals)
    reg._conn.commit()
    return {"ok": True, "code": code, "updated": updates}

@router.post("/batch-adjust")
def batch_adjust(body: dict, x_admin_key: str = Header(default="")):
    """批量调整所有Key配额"""
    _auth(x_admin_key)
    reg = get_registry()
    updates = {}
    for k in ["total_limit", "daily_limit", "rpm_limit", "status"]:
        if k in body:
            updates[k] = body[k]
    if not updates:
        raise HTTPException(400, "No fields to update")
    set_clause = ", ".join(f"{k}=?" for k in updates)
    vals = list(updates.values())
    reg._conn.execute(f"UPDATE free_shared_keys SET {set_clause}", vals)
    reg._conn.commit()
    n = reg._conn.execute("SELECT COUNT(*) FROM free_shared_keys").fetchone()[0]
    return {"ok": True, "updated_count": n, "fields": list(updates.keys())}

@router.post("/ai-command")
def ai_command(body: dict, x_admin_key: str = Header(default="")):
    """AI指令解析：支持自然语言批量调整免费Key配额
    
    支持的命令格式：
    - 设置 XX 总量/NNNN
    - 调整 XX 配额/NNNN
    - XX 每日限额/NNNN
    - XX 速率/NN rpm
    - 刷新 XX
    - 刷新 全部
    - 禁用 XX
    - 启用 XX
    - 批量设置 总量/NNNN / 每日/NNNN / 速率/NN
    - 默认 总量/NNNN
    """
    _auth(x_admin_key)
    cmd = (body.get("command") or "").strip()
    if not cmd:
        raise HTTPException(400, "command required")
    
    reg = get_registry()
    results = []
    
    # ── Keyword parser ──
    cmd_lower = cmd.lower()
    
    # Extract numbers
    nums = re.findall(r'(\d+)', cmd)
    
    # Default target: all keys
    target_code = None
    all_keys = reg._conn.execute("SELECT code FROM free_shared_keys").fetchall()
    all_codes = [r[0] for r in all_keys]
    
    # Find specific code
    code_match = re.search(r'(mb-mf-[a-f0-9]+)', cmd)
    if code_match:
        target_code = code_match.group(1)
    
    targets = [target_code] if target_code else all_codes
    
    if not targets:
        raise HTTPException(404, "No free keys found")
    
    # ── Parse actions ──
    updates = {}
    
    if any(w in cmd_lower for w in ['总量', 'total', '配额']):
        if nums:
            updates["total_limit"] = max(1000, int(nums[0]))
    
    if any(w in cmd_lower for w in ['每日', 'daily', '日限额']):
        if nums:
            idx = 1 if "total_limit" in updates else 0
            if idx < len(nums):
                updates["daily_limit"] = max(100, int(nums[idx]))
    
    if any(w in cmd_lower for w in ['速率', 'rpm']):
        for n in nums:
            v = int(n)
            if 1 <= v <= 100:
                updates["rpm_limit"] = v
                break
    
    if any(w in cmd_lower for w in ['禁用', 'disable', '停用']):
        updates["status"] = "disabled"
    
    if any(w in cmd_lower for w in ['启用', 'enable', '激活', 'active']):
        updates["status"] = "active"
    
    if any(w in cmd_lower for w in ['刷新', 'refresh', '重置']):
        if target_code:
            reg._conn.execute("UPDATE free_shared_keys SET used_today=0 WHERE code=?", (target_code,))
        else:
            reg._conn.execute("UPDATE free_shared_keys SET used_today=0")
        reg._conn.commit()
        results.append(f"{'已刷新' if target_code else '已批量刷新'} {len(targets)} 个Key的当日用量")
    
    if any(w in cmd_lower for w in ['默认', 'default']):
        if nums:
            FREE_DEFAULTS["total_limit"] = max(1000, int(nums[0]))
        results.append(f"默认总量已更新为 {FREE_DEFAULTS['total_limit']}")
    
    if updates:
        set_clause = ", ".join(f"{k}=?" for k in updates)
        vals = list(updates.values())
        for code in targets:
            reg._conn.execute(f"UPDATE free_shared_keys SET {set_clause} WHERE code=?", vals + [code])
        reg._conn.commit()
        fields_str = ", ".join(f"{k}={v}" for k, v in updates.items())
        results.append(f"已更新 {len(targets)} 个Key: {fields_str}")
    
    if not results:
        results.append("未识别有效指令。支持: 设置/调整/刷新/禁用/启用 + 总量/每日/速率")
    
    return {"ok": True, "affected": len(targets), "results": results}

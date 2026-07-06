"""售出 Key 路由 — 用户Key管理、倍率、余额、用量统计"""
import time
from fastapi import APIRouter, HTTPException, Header, Query
from pydantic import BaseModel
from pool.registry import get_registry
from pool.encryption import encrypt, decrypt
from config import cfg

router = APIRouter(prefix="/api/sold-keys", tags=["sold-keys"])

def _auth(k):
    if k != cfg.ADMIN_KEY: raise HTTPException(403, "Wrong admin key")

# ── 列表 ──
@router.get("")
def list_sold_keys(x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry()
    rows = reg._conn.execute("""
        SELECT sk.*, 
               (SELECT GROUP_CONCAT(model_name || ':' || model_multiplier, ',') 
                FROM sold_key_models WHERE key_alias=sk.key_alias) as models_info
        FROM sold_keys sk ORDER BY sk.created_at DESC
    """).fetchall()
    result = []
    for r in rows:
        d = dict(r)
        # Parse models
        models = {}
        if d.get("models_info"):
            for part in d["models_info"].split(","):
                if ":" in part:
                    name, mul = part.split(":", 1)
                    models[name.strip()] = float(mul)
        d["models"] = models
        
        # Decrypt api_key (show masked)
        if d.get("encrypted_key"):
            try:
                d["api_key"] = decrypt(d["encrypted_key"], d.get("key_iv",""), d.get("key_tag",""))
            except:
                d["api_key"] = ""
        d.pop("encrypted_key", None); d.pop("key_iv", None); d.pop("key_tag", None)
        
        # Usage summary per model
        usage = reg._conn.execute(
            "SELECT model_name, SUM(tokens_used) as tokens, SUM(cost) as cost FROM sold_key_usage WHERE key_alias=? GROUP BY model_name",
            (d["key_alias"],)
        ).fetchall()
        d["usage"] = [{"model": u[0], "tokens": u[1] or 0, "cost": u[2] or 0} for u in usage]
        result.append(d)
    
    # Add per-user aggregation
    return result

@router.get("/stats")
def sold_key_stats(x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry()
    total = reg._conn.execute("SELECT COUNT(*) FROM sold_keys").fetchone()[0]
    active = reg._conn.execute("SELECT COUNT(*) FROM sold_keys WHERE status='active'").fetchone()[0]
    total_balance = reg._conn.execute("SELECT COALESCE(SUM(balance),0) FROM sold_keys").fetchone()[0]
    total_recharged = reg._conn.execute("SELECT COALESCE(SUM(total_recharged),0) FROM sold_keys").fetchone()[0]
    total_tokens = reg._conn.execute("SELECT COALESCE(SUM(tokens_used),0) FROM sold_key_usage").fetchone()[0]
    return {"total": total, "active": active, "balance": total_balance, "recharged": total_recharged, "tokens": total_tokens}

# ── 添加售出Key ──
@router.post("")
def add_sold_key(body: dict, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    alias = body.get("key_alias", "").strip()
    user_id = body.get("user_id", "").strip()
    api_key = body.get("api_key", "").strip()
    if not alias or not user_id:
        raise HTTPException(400, "key_alias and user_id required")
    
    reg = get_registry()
    existing = reg._conn.execute("SELECT id FROM sold_keys WHERE key_alias=?", (alias,)).fetchone()
    if existing:
        raise HTTPException(409, "key_alias exists")
    
    enc, iv, tag = encrypt(api_key) if api_key else ("", "", "")
    key_mult = float(body.get("key_multiplier", 1.0))
    balance = float(body.get("balance", 0))
    
    reg._conn.execute(
        "INSERT INTO sold_keys(user_id, key_alias, encrypted_key, key_iv, key_tag, key_multiplier, balance, total_recharged, status) VALUES(?,?,?,?,?,?,?,?,?)",
        (user_id, alias, enc, iv, tag, key_mult, balance, balance, "active"))
    
    # Add default models
    models = body.get("models", {})
    for model_name, mult in models.items():
        reg._conn.execute(
            "INSERT OR REPLACE INTO sold_key_models(key_alias, model_name, model_multiplier) VALUES(?,?,?)",
            (alias, model_name, float(mult)))
    
    reg._conn.commit()
    return {"ok": True, "key_alias": alias}

# ── 更新 ──
@router.post("/{key_alias}/adjust")
def adjust_sold_key(key_alias: str, body: dict, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry()
    updates = {}
    for k in ["key_multiplier", "balance", "total_recharged", "status", "user_id"]:
        if k in body:
            updates[k] = body[k]
    
    if updates:
        set_clause = ", ".join(f"{k}=?" for k in updates)
        vals = list(updates.values()) + [key_alias]
        reg._conn.execute(f"UPDATE sold_keys SET {set_clause} WHERE key_alias=?", vals)
    
    # Update model multipliers
    if "models" in body:
        for model_name, mult in body["models"].items():
            reg._conn.execute(
                "INSERT OR REPLACE INTO sold_key_models(key_alias, model_name, model_multiplier) VALUES(?,?,?)",
                (key_alias, model_name, float(mult)))
    
    reg._conn.commit()
    return {"ok": True}

# ── 单个Key模型倍率调整 ──
@router.post("/{key_alias}/model-mult")
def set_model_mult(key_alias: str, body: dict, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    model_name = body.get("model", "")
    mult = float(body.get("multiplier", 1.0))
    if not model_name: raise HTTPException(400, "model required")
    reg = get_registry()
    reg._conn.execute(
        "INSERT OR REPLACE INTO sold_key_models(key_alias, model_name, model_multiplier) VALUES(?,?,?)",
        (key_alias, model_name, mult))
    reg._conn.commit()
    return {"ok": True}

# ── 批量倍率调整 ──
@router.post("/batch-mult")
def batch_mult(body: dict, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    mult_type = body.get("type", "key")  # "key" or "model"
    target = body.get("target", "all")  # "all" or specific value
    mult_value = float(body.get("multiplier", 1.0))
    
    reg = get_registry()
    if mult_type == "key":
        if target == "all":
            reg._conn.execute("UPDATE sold_keys SET key_multiplier=?", (mult_value,))
        else:
            reg._conn.execute("UPDATE sold_keys SET key_multiplier=? WHERE user_id=?", (mult_value, target))
    elif mult_type == "model":
        model_name = body.get("model", "")
        if not model_name: raise HTTPException(400, "model required")
        if target == "all":
            reg._conn.execute("UPDATE sold_key_models SET model_multiplier=? WHERE model_name=?", (mult_value, model_name))
        else:
            reg._conn.execute("UPDATE sold_key_models SET model_multiplier=? WHERE key_alias=? AND model_name=?", (mult_value, target, model_name))
    
    n = reg._conn.total_changes
    reg._conn.commit()
    return {"ok": True, "affected": n}

# ── 用量详情 ──
@router.get("/{key_alias}/usage")
def key_usage(key_alias: str, hours: int = Query(24), x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry()
    since = time.time() - hours * 3600
    rows = reg._conn.execute(
        "SELECT model_name, SUM(tokens_used) as tokens, SUM(cost) as cost FROM sold_key_usage WHERE key_alias=? AND ts>=? GROUP BY model_name ORDER BY tokens DESC",
        (key_alias, since)
    ).fetchall()
    return [{"model": r[0], "tokens": r[1] or 0, "cost": r[2] or 0} for r in rows]

# ── 删除 ──
@router.delete("/{key_alias}")
def delete_sold_key(key_alias: str, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry()
    reg._conn.execute("DELETE FROM sold_keys WHERE key_alias=?", (key_alias,))
    reg._conn.execute("DELETE FROM sold_key_models WHERE key_alias=?", (key_alias,))
    reg._conn.execute("DELETE FROM sold_key_usage WHERE key_alias=?", (key_alias,))
    reg._conn.commit()
    return {"ok": True}

@router.get("/{key_alias}/detail")
def sold_key_detail(key_alias: str, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry()
    k = reg._conn.execute("SELECT * FROM sold_keys WHERE key_alias=?", (key_alias,)).fetchone()
    if not k: raise HTTPException(404, "not found")
    now = time.time()
    today_start = now - (now % 86400)
    rows = reg._conn.execute(
        "SELECT model_name, SUM(tokens_used) as total_tokens, SUM(CASE WHEN ts>=? THEN tokens_used ELSE 0 END) as today_tokens, SUM(cost) as total_cost, SUM(CASE WHEN ts>=? THEN cost ELSE 0 END) as today_cost FROM sold_key_usage WHERE key_alias=? GROUP BY model_name",
        (today_start, today_start, key_alias)
    ).fetchall()
    usage = [{"model": r[0], "total_tokens": r[1] or 0, "today_tokens": r[2] or 0, "total_cost": round(r[3] or 0, 6), "today_cost": round(r[4] or 0, 6)} for r in rows]
    models = {}
    mrows = reg._conn.execute("SELECT model_name, model_multiplier FROM sold_key_models WHERE key_alias=?", (key_alias,)).fetchall()
    for mr in mrows: models[mr[0]] = mr[1]
    return {"key_alias": k["key_alias"], "user_id": k["user_id"], "key_multiplier": k["key_multiplier"], "balance": k["balance"], "total_recharged": k["total_recharged"], "status": k["status"], "models": models, "usage": usage}

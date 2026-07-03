"""Key 管理 CRUD"""
from __future__ import annotations
from fastapi import APIRouter, HTTPException, Header
from pydantic import BaseModel
from pool.registry import ProviderKey, get_registry
from pool.circuit import get_cb
from pool.health import probe_key
from config import cfg

router = APIRouter(prefix="/api/keys", tags=["keys"])

def _auth(k): 
    if k != cfg.ADMIN_KEY: raise HTTPException(403, "Wrong admin key")

class KeyIn(BaseModel):
    alias: str
    provider: str
    base_url: str
    api_key: str = ""
    model: str
    cost_per_1k: float = 0.01
    priority: int = 5
    enabled: bool = True

@router.get("")
def list_keys(x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry(); cb = get_cb()
    keys = reg.all()
    result = []
    for pk in keys:
        d = {"id": pk.id, "alias": pk.alias, "provider": pk.provider,
             "base_url": pk.base_url, "model": pk.model,
             "api_key": ("*"*8 + pk.api_key[-4:]) if pk.api_key else "",
             "has_key": bool(pk.api_key),
             "cost_per_1k": pk.cost_per_1k, "priority": pk.priority, "enabled": pk.enabled,
             "status": pk.status, "success_count": pk.success_count, "fail_count": pk.fail_count,
             "total_tokens": pk.total_tokens, "total_cost": round(pk.total_cost, 6),
             "avg_latency_ms": round(pk.avg_latency_ms, 1),
             "last_checked": pk.last_checked, "last_error": pk.last_error,
             "circuit_open": cb.is_open(pk.alias)}
        result.append(d)
    return result

@router.post("")
def add_key(body: KeyIn, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    pk = ProviderKey(**body.model_dump())
    saved = get_registry().upsert(pk)
    return {"ok": True, "id": saved.id, "alias": saved.alias}

@router.put("/{alias}")
def update_key(alias: str, body: KeyIn, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry()
    existing = reg.get(alias)
    if not existing: raise HTTPException(404, f"Key {alias} not found")
    data = body.model_dump()
    data["id"] = existing.id
    pk = ProviderKey(**data)
    reg.upsert(pk)
    return {"ok": True}

@router.delete("/{alias}")
def delete_key(alias: str, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    get_registry().delete(alias)
    return {"ok": True}

@router.patch("/{alias}/key")
def set_api_key(alias: str, body: dict, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    api_key = body.get("api_key", "")
    if not api_key: raise HTTPException(400, "api_key 不能为空")
    get_registry().set_key_value(alias, api_key)
    return {"ok": True}

@router.post("/{alias}/probe")
async def probe(alias: str, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    pk = get_registry().get(alias)
    if not pk: raise HTTPException(404)
    ok, latency, err = await probe_key(pk)
    get_registry().update_stat(alias, "working" if ok else "failed", latency, 0, 0, ok, err)
    cb = get_cb()
    if ok: cb.on_success(alias)
    else: cb.on_failure(alias)
    return {"alias": alias, "ok": ok, "latency_ms": round(latency,1), "error": err}

@router.post("/{alias}/reset_circuit")
def reset_circuit(alias: str, x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    get_cb().reset(alias)
    return {"ok": True}

@router.post("/probe_all")
async def probe_all(x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    from pool.health import check_all
    await check_all()
    return {"ok": True}

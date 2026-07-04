"""统计与监控"""
from fastapi import APIRouter, Header, HTTPException, Query
from pool.registry import get_registry
from pool.ratelimit import get_limiter
from pool.metrics import get_hub
from config import cfg

router = APIRouter(prefix="/api/stats", tags=["stats"])

def _auth(k):
    if k != cfg.ADMIN_KEY: raise HTTPException(403, "Wrong admin key")

@router.get("")
def overview(x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    reg = get_registry(); rl = get_limiter(); hub = get_hub()
    keys = reg.all()
    total_cost = sum(pk.total_cost for pk in keys)
    total_tokens = sum(pk.total_tokens for pk in keys)
    working = sum(1 for pk in keys if pk.status == "working")
    cooldown_count = sum(1 for pk in keys if rl.is_on_cooldown(pk.alias, pk.provider, pk.model))
    cooldown_statuses = {pk.alias: rl.status(pk.alias, pk.provider, pk.model) for pk in keys}
    return {
        "total_keys": len(keys),
        "working_keys": working,
        "failed_keys": sum(1 for pk in keys if pk.status == "failed"),
        "circuit_open": cooldown_count,
        "total_tokens_all_time": total_tokens,
        "total_cost_all_time": round(total_cost, 6),
        "metrics_5m": hub.all_snapshots(),
        "cooldowns": cooldown_statuses,
    }

@router.get("/log")
def call_log(alias: str = Query(""), limit: int = Query(100), x_admin_key: str = Header(default="")):
    _auth(x_admin_key)
    return get_registry().call_log(alias, limit)

@router.get("/log/hourly")
def hourly_agg(alias: str = Query(""), hours: int = Query(24), x_admin_key: str = Header(default="")):
    """P4-1: 按小时聚合 token 消耗，用于图表"""
    _auth(x_admin_key)
    return get_registry().hourly_aggregation(alias, hours)

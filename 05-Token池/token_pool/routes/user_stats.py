"""P1-3: 用户日统计端点"""
from fastapi import APIRouter, HTTPException
from pool.registry import get_registry

router = APIRouter(prefix="/api/shared-keys", tags=["user_stats"])


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

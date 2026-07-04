"""P1-3/P1-6: 用户日统计端点 + 配额比例修改"""
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from pool.registry import get_registry

router = APIRouter(prefix="/api/shared-keys", tags=["user_stats"])


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

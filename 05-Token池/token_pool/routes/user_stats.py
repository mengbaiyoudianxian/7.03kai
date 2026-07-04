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


# ── P2-12: MiClaw 账号归属 + 借用白名单 ──

@router.get("/miclaw-accounts")
def list_miclaw():
    """列出所有 MiClaw 账号（含归属+白名单）"""
    return get_registry().list_miclaw_accounts()


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

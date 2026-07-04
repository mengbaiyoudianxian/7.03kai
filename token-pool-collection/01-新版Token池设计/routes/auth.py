"""用户认证 — 注册/登录/查询自己"""
from __future__ import annotations
from fastapi import APIRouter, HTTPException, Header
from pydantic import BaseModel, Field
from pool.registry import get_registry, User

router = APIRouter(prefix="/api/auth", tags=["auth"])


class RegisterReq(BaseModel):
    username: str = Field(min_length=2, max_length=50)
    password: str = Field(min_length=6, max_length=100)

class LoginReq(BaseModel):
    username: str
    password: str

class UserInfo(BaseModel):
    id: int
    username: str
    token: str
    role: str
    balance: float
    share_ratio: float
    quota_limit: int
    enabled: bool


def _user_to_info(u: User) -> dict:
    return {"id": u.id, "username": u.username, "token": u.token,
            "role": u.role, "balance": getattr(u, 'balance', 0.0),
            "share_ratio": u.share_ratio, "quota_limit": u.quota_limit,
            "enabled": u.enabled}


@router.post("/register")
def register(req: RegisterReq):
    """注册新用户，返回 API Token（仅显示一次）"""
    reg = get_registry()
    try:
        user = reg.create_user_with_password(req.username, req.password)
        stats = reg.get_user_stats(user.id)
        return {"ok": True, "message": "注册成功！请保存你的 API Key",
                "user": _user_to_info(user), "stats": stats}
    except Exception as e:
        if "UNIQUE" in str(e):
            raise HTTPException(409, "用户名已存在")
        raise HTTPException(500, str(e))


@router.post("/login")
def login(req: LoginReq):
    """用户名+密码登录，返回 API Token"""
    reg = get_registry()
    user = reg.verify_user_password(req.username, req.password)
    if not user:
        raise HTTPException(401, "用户名或密码错误")
    stats = reg.get_user_stats(user.id)
    return {"ok": True, "user": _user_to_info(user), "stats": stats}


@router.get("/me")
def me(authorization: str = Header(default="")):
    """用 API Token 查询自己的信息和用量"""
    token = authorization.replace("Bearer ", "").strip()
    if not token:
        raise HTTPException(401, "请提供 Authorization: Bearer <token>")
    reg = get_registry()
    user = reg.get_user_by_token(token)
    if not user:
        raise HTTPException(401, "无效的 API Token")
    stats = reg.get_user_stats(user.id)
    return {"ok": True, "user": _user_to_info(user), "stats": stats}

"""Gateway Agent — 薄转发层

G1: 删 167 行 LLM/regex/agent loop，改为 POST /api/mother/run。
所有渠道消息归一化后走这里，母体统一处理。
"""
from __future__ import annotations
import logging, httpx
from . import StandardMessage, get_adapter

log = logging.getLogger(__name__)
MOTHER_URL = "http://127.0.0.1:8000"


async def handle_message(msg: StandardMessage) -> str:
    """统一入口：所有渠道消息 → 母体 → 回复"""
    try:
        async with httpx.AsyncClient(timeout=120) as c:
            r = await c.post(f"{MOTHER_URL}/api/mother/run", json={
                "goal": f"[{msg.channel}] {msg.content}",
                "session_id": _session_id(msg),
            })
            r.raise_for_status()
            data = r.json()
            reply = data.get("reply", "")
            return _sanitize(reply, msg.channel)
    except Exception as e:
        log.error("母体调用失败: %s", e)
        return f"母体暂时不可用: {e}"


async def on_channel_message(msg: StandardMessage):
    """渠道回调：收消息 → 调母体 → 发回复"""
    reply = await handle_message(msg)
    adapter = get_adapter(msg.channel)
    if adapter:
        target = msg.meta.get("reply_target", msg.user_id)
        await adapter.send(target, reply, msg.meta)


def _session_id(msg: StandardMessage) -> int:
    # 同一渠道同一用户共用 session
    return hash(f"{msg.channel}:{msg.user_id}") % 100000


def _sanitize(text: str, channel: str) -> str:
    """渠道适配：去除不适合该渠道的格式"""
    import re
    if channel == "qq":
        text = re.sub(r'\*\*([^*]+)\*\*', r'\1', text)
        text = re.sub(r'\n{3,}', '\n\n', text)
        return text[:2000]
    return text

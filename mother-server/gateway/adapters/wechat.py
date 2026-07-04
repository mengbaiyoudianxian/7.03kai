"""微信 Bot 适配器 — 企业微信/公众号 Webhook

G3: 从 03-母体迁移，统一走 gateway.agent
"""
from __future__ import annotations
import os
import httpx
from gateway import AdapterBase, StandardMessage, register


class WechatAdapter(AdapterBase):
    name = "wechat"
    _webhook_url: str = ""
    _token: str = ""

    async def start(self) -> None:
        self._token = os.environ.get("WECHAT_TOKEN", "")
        self._webhook_url = os.environ.get("WECHAT_WEBHOOK_URL", "")
        if self._webhook_url:
            print(f"[wechat] webhook configured")

    async def stop(self) -> None:
        pass

    async def send(self, target: str, message: str, meta: dict | None = None) -> bool:
        if not self._webhook_url:
            return False
        try:
            async with httpx.AsyncClient() as c:
                r = await c.post(self._webhook_url, json={
                    "msgtype": "text",
                    "text": {"content": message[:2000]}
                }, timeout=10)
                return r.status_code == 200
        except Exception:
            return False

    async def handle_callback(self, body: dict) -> StandardMessage:
        """微信回调入口（FastAPI endpoint 调用）"""
        return StandardMessage(
            channel="wechat",
            user_id=body.get("FromUserName", ""),
            content=body.get("Content", body.get("text", "")),
            meta={"msg_type": body.get("MsgType", "text"),
                  "reply_target": body.get("FromUserName", "")},
        )

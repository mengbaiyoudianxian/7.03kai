"""W1: 微信 ilink API 客户端 — 纯 HTTP JSON，零 OpenClaw 依赖

逆向自 @tencent-weixin/openclaw-weixin 2.4.6 源码。
协议：标准 HTTP + Bearer Token，无加密。
"""
from __future__ import annotations
import json, logging, time
import httpx

log = logging.getLogger(__name__)

ILINK_BASE = "https://ilinkai.weixin.qq.com"
BOT_TYPE = "3"
QR_POLL_TIMEOUT = 35
API_TIMEOUT = 120


class WeixinAPI:
    """微信 ilink Bot API 客户端"""

    def __init__(self, base_url: str = ILINK_BASE, token: str = ""):
        self.base_url = base_url.rstrip("/")
        self.token = token

    def _headers(self) -> dict:
        h = {"Content-Type": "application/json"}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        return h

    # ── 登录 ──────────────────────────────────────────

    def get_qrcode(self, local_tokens: list[str] | None = None) -> dict:
        """获取登录二维码。返回 {qrcode, qrcode_img_content}"""
        r = httpx.post(
            f"{self.base_url}/ilink/bot/get_bot_qrcode?bot_type={BOT_TYPE}",
            json={"local_token_list": local_tokens or []},
            timeout=API_TIMEOUT)
        r.raise_for_status()
        return r.json()

    def poll_qr_status(self, qrcode: str, verify_code: str = "") -> dict:
        """长轮询二维码扫描状态。返回 {status, bot_token, ilink_bot_id, baseurl, ilink_user_id}"""
        endpoint = f"/ilink/bot/get_qrcode_status?qrcode={qrcode}"
        if verify_code:
            endpoint += f"&verify_code={verify_code}"
        try:
            r = httpx.get(f"{self.base_url}{endpoint}", timeout=QR_POLL_TIMEOUT)
            r.raise_for_status()
            return r.json()
        except httpx.TimeoutException:
            return {"status": "wait"}
        except Exception as e:
            log.warning("poll_qr_status 网络错误: %s", e)
            return {"status": "wait"}

    # ── 收消息 ────────────────────────────────────────

    def get_updates(self, sync_buf: str = "") -> dict:
        """长轮询拉取新消息。返回 {msgs, get_updates_buf, longpolling_timeout_ms}"""
        r = httpx.post(
            f"{self.base_url}/ilink/bot/get_updates",
            headers=self._headers(),
            json={"get_updates_buf": sync_buf},
            timeout=API_TIMEOUT)
        r.raise_for_status()
        return r.json()

    # ── 发消息 ────────────────────────────────────────

    def send_text(self, to_user_id: str, text: str) -> dict:
        """发送文本消息"""
        r = httpx.post(
            f"{self.base_url}/ilink/bot/send_message",
            headers=self._headers(),
            json={"msg": {
                "to_user_id": to_user_id,
                "message_type": 2,
                "item_list": [{"type": 1, "text_item": {"text": text[:2000]}}],
            }},
            timeout=API_TIMEOUT)
        r.raise_for_status()
        return r.json()

    def send_typing(self, ilink_user_id: str, typing_ticket: str, status: int = 1) -> dict:
        """发送正在输入状态。status: 1=typing, 2=cancel"""
        r = httpx.post(
            f"{self.base_url}/ilink/bot/send_typing",
            headers=self._headers(),
            json={"ilink_user_id": ilink_user_id, "typing_ticket": typing_ticket, "status": status},
            timeout=API_TIMEOUT)
        r.raise_for_status()
        return r.json()

    def get_config(self) -> dict:
        """获取 Bot 配置（含 typing_ticket）"""
        r = httpx.post(
            f"{self.base_url}/ilink/bot/get_config",
            headers=self._headers(),
            json={},
            timeout=API_TIMEOUT)
        r.raise_for_status()
        return r.json()

    def notify_start(self) -> dict:
        """通知服务器 channel 启动"""
        r = httpx.post(
            f"{self.base_url}/ilink/bot/notify_start",
            headers=self._headers(),
            json={"base_info": {"bot_agent": "MBclaw/2.0"}},
            timeout=API_TIMEOUT)
        r.raise_for_status()
        return r.json()

    def notify_stop(self) -> dict:
        """通知服务器 channel 停止"""
        r = httpx.post(
            f"{self.base_url}/ilink/bot/notify_stop",
            headers=self._headers(),
            json={"base_info": {"bot_agent": "MBclaw/2.0"}},
            timeout=API_TIMEOUT)
        r.raise_for_status()
        return r.json()

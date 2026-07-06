"""微信 Bot 适配器 — 逆向 @tencent-weixin/openclaw-weixin 协议

W1-W3: 纯 Python 实现，扫码登录 + 长轮询收消息 + 发消息
W4: 自动重连 — session 过期自动重试，60s 退避
"""
from __future__ import annotations
import asyncio, json, logging
from . import AdapterBase
from dataclasses import dataclass, field
import time

@dataclass
class StandardMessage:
    channel: str = "wechat"
    user_id: str = ""
    content: str = ""
    meta: dict = field(default_factory=dict)
    timestamp: float = field(default_factory=time.time)

from .wechat_api import WeixinAPI
from .wechat_auth import load_accounts, login_with_qr, STATE_DIR

log = logging.getLogger(__name__)

RECONNECT_DELAY = 60  # session 失效后重试间隔（秒）


class WechatAdapter(AdapterBase):
    name = "wechat"
    _accounts: list[dict] = []
    _tasks: list = []
    _running: bool = False

    async def start(self) -> None:
        self._accounts = load_accounts()
        if not self._accounts:
            print("[wechat] 未找到已登录账号，请先运行登录")
            return
        self._running = True
        for acct in self._accounts:
            task = asyncio.create_task(self._reconnect_loop(acct))
            self._tasks.append(task)
        print(f"[wechat] {len(self._accounts)} 个账号在线")

    async def stop(self) -> None:
        self._running = False
        for t in self._tasks:
            t.cancel()
        self._tasks.clear()

    async def _reconnect_loop(self, acct: dict):
        """自动重连循环：session 失效 / 网络错误后自动重试"""
        account_id = acct.get("account_id", "unknown")
        while self._running:
            try:
                await self._poll_loop(acct)
            except asyncio.CancelledError:
                break
            except Exception as e:
                log.error("[wechat:%s] poll loop 崩溃: %s", account_id, e)
            if self._running:
                log.warning("[wechat:%s] %ds 后重连...", account_id, RECONNECT_DELAY)
                await asyncio.sleep(RECONNECT_DELAY)

    async def _poll_loop(self, acct: dict):
        """长轮询收消息循环（单次连接生命周期）"""
        account_id = acct.get("account_id", "unknown")
        api = WeixinAPI(base_url=acct.get("base_url", "https://ilinkai.weixin.qq.com"),
                        token=acct.get("token", ""))
        sync_buf_path = STATE_DIR / f"{acct['account_id']}.sync.json"
        sync_buf = json.loads(sync_buf_path.read_text()).get("buf", "") if sync_buf_path.exists() else ""

        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(None, api.notify_start)
            log.info("[wechat:%s] 连接已建立", account_id)
        except Exception as e:
            log.warning("[wechat:%s] notify_start 失败: %s", account_id, e)

        loop = asyncio.get_running_loop()
        consecutive_errors = 0
        while self._running:
            try:
                resp = await loop.run_in_executor(None, api.get_updates, sync_buf)
                ret = resp.get("ret", 0)
                if ret != 0:
                    log.error("[wechat:%s] session 失效 ret=%s，需要重新扫码登录", account_id, ret)
                    return  # 退出到 _reconnect_loop，等待重试
                consecutive_errors = 0
                new_buf = resp.get("get_updates_buf", "")
                if new_buf and new_buf != sync_buf:
                    sync_buf = new_buf
                    sync_buf_path.write_text(json.dumps({"buf": sync_buf}))
                msgs = resp.get("msgs", [])
                for msg in msgs:
                    await self._handle_msg(msg, api)
            except asyncio.CancelledError:
                return
            except Exception as e:
                consecutive_errors += 1
                delay = min(3 * consecutive_errors, 30)
                log.warning("[wechat:%s] poll error (#%d): %s, %ds 后重试",
                            account_id, consecutive_errors, e, delay)
                await asyncio.sleep(delay)

        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(None, api.notify_stop)
        except Exception:
            pass

    async def _handle_msg(self, msg: dict, api: WeixinAPI):
        """处理单条消息 → 转发母体 → 回复"""
        from_user = msg.get("from_user_id", "")
        msg_id = msg.get("message_id", 0)
        text = ""
        for item in msg.get("item_list", []):
            if item.get("type") == 1:  # TEXT
                text = item.get("text_item", {}).get("text", "")
                break
        if not text or not from_user:
            return

        sm = StandardMessage(
            channel="wechat",
            user_id=from_user,
            content=text,
            meta={"account_id": msg.get("to_user_id", ""),
                  "msg_id": msg_id,
                  "reply_target": from_user},
        )
        log.info("[wechat] 收到: %s → %s", from_user, text[:50])

        if self._on_message:
            try:
                reply = await self._on_message(sm)
                if reply:
                    loop = asyncio.get_running_loop()
                    await loop.run_in_executor(None, api.send_text, from_user, reply)
                    log.info("[wechat] 回复: %s → %s", from_user, reply[:50])
            except Exception as e:
                log.error("[wechat] 处理失败: %s", e)
                loop = asyncio.get_running_loop()
                await loop.run_in_executor(None, api.send_text, from_user, f"处理失败: {e}")

    async def send(self, target: str, message: str, meta: dict | None = None) -> bool:
        """主动发消息"""
        loop = asyncio.get_running_loop()
        for acct in self._accounts:
            try:
                api = WeixinAPI(base_url=acct.get("base_url", "https://ilinkai.weixin.qq.com"),
                                token=acct.get("token", ""))
                await loop.run_in_executor(None, api.send_text, target, message)
                return True
            except Exception:
                continue
        return False


def cli_login():
    """CLI 登录入口：python -m gateway.adapters.wechat_auth"""
    result = login_with_qr()
    if result:
        print(f"\n账号已保存: {result['account_id']}")
    else:
        print("\n登录失败")


if __name__ == "__main__":
    cli_login()

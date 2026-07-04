"""微信 Bot 适配器 — 逆向 @tencent-weixin/openclaw-weixin 协议

W1-W3: 纯 Python 实现，扫码登录 + 长轮询收消息 + 发消息
"""
from __future__ import annotations
import asyncio, json, logging
from gateway import AdapterBase, StandardMessage, register
from gateway.adapters.wechat_api import WeixinAPI
from gateway.adapters.wechat_auth import load_accounts, login_with_qr, STATE_DIR

log = logging.getLogger(__name__)


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
            task = asyncio.create_task(self._poll_loop(acct))
            self._tasks.append(task)
        print(f"[wechat] {len(self._accounts)} 个账号在线")

    async def stop(self) -> None:
        self._running = False
        for t in self._tasks:
            t.cancel()
        self._tasks.clear()

    async def _poll_loop(self, acct: dict):
        """长轮询收消息循环"""
        api = WeixinAPI(base_url=acct.get("base_url", "https://ilinkai.weixin.qq.com"),
                        token=acct.get("token", ""))
        sync_buf_path = STATE_DIR / f"{acct['account_id']}.sync.json"
        sync_buf = json.loads(sync_buf_path.read_text()).get("buf", "") if sync_buf_path.exists() else ""

        try:
            api.notify_start()
        except Exception as e:
            log.warning("notify_start: %s", e)

        while self._running:
            try:
                resp = api.get_updates(sync_buf)
                if resp.get("errcode") == -14:
                    log.error("[wechat] session timeout, 需要重新登录")
                    break
                new_buf = resp.get("get_updates_buf", "")
                if new_buf:
                    sync_buf = new_buf
                    sync_buf_path.write_text(json.dumps({"buf": sync_buf}))
                msgs = resp.get("msgs", [])
                for msg in msgs:
                    await self._handle_msg(msg, api)
            except asyncio.CancelledError:
                break
            except Exception as e:
                log.warning("[wechat] poll error: %s", e)
                await asyncio.sleep(3)

        try:
            api.notify_stop()
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
                    api.send_text(from_user, reply)
                    log.info("[wechat] 回复: %s → %s", from_user, reply[:50])
            except Exception as e:
                log.error("[wechat] 处理失败: %s", e)
                api.send_text(from_user, f"处理失败: {e}")

    async def send(self, target: str, message: str, meta: dict | None = None) -> bool:
        """主动发消息"""
        for acct in self._accounts:
            try:
                api = WeixinAPI(base_url=acct.get("base_url", "https://ilinkai.weixin.qq.com"),
                                token=acct.get("token", ""))
                api.send_text(target, message)
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

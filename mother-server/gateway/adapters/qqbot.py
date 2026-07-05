"""QQ Bot 适配器 — QQ官方Bot API v2 WebSocket

G2: 从 03-母体迁移，删硬编码 Secret，统一走 gateway.agent

Intents 说明:
  GUILD_MESSAGES        (1<<9)  = 512         — 频道消息
  GROUP_AND_C2C_EVENT   (1<<25) = 33554432    — C2C私聊消息
  PUBLIC_GUILD_MESSAGES (1<<30) = 1073741824  — 公域频道消息(含 GROUP_AT_MESSAGE_CREATE)
"""
from __future__ import annotations
import asyncio, json, os, time
import httpx
from gateway import AdapterBase, StandardMessage, register

# 正确的 Intents: 私聊 + 群@ + 频道消息
QQ_INTENTS = 512 | 33554432 | 1073741824  # = 1107296768


class QQBotAdapter(AdapterBase):
    name = "qq"
    _app_id: str = ""
    _secret: str = ""
    _token: str = ""
    _token_expires: float = 0
    _ws = None
    _heartbeat_task = None
    _seq: int = 0
    _http: httpx.AsyncClient | None = None

    @property
    def http(self) -> httpx.AsyncClient:
        if self._http is None:
            self._http = httpx.AsyncClient(timeout=httpx.Timeout(15))
        return self._http

    async def _get_access_token(self) -> str:
        if self._token and time.time() < self._token_expires - 60:
            return self._token
        try:
            r = await self.http.post("https://bots.qq.com/app/getAppAccessToken",
                json={"appId": self._app_id, "clientSecret": self._secret})
            data = r.json()
            self._token = data.get("access_token", "")
            self._token_expires = time.time() + int(data.get("expires_in", 7200))
            print(f"[qqbot] token refreshed, expires in {data.get('expires_in')}s")
            return self._token
        except Exception as e:
            print(f"[qqbot] token error: {e}")
            return ""

    async def start(self) -> None:
        self._app_id = os.environ.get("QQ_BOT_APPID", "")
        self._secret = os.environ.get("QQ_BOT_SECRET", "")
        if not self._app_id or not self._secret:
            print("[qqbot] QQ_BOT_APPID/QQ_BOT_SECRET 未配置，跳过")
            return
        self._running = True
        asyncio.create_task(self._reconnect_loop())

    async def _reconnect_loop(self):
        """自动重连循环"""
        import websockets
        while self._running:
            try:
                token = await self._get_access_token()
                if not token:
                    print("[qqbot] token 获取失败，30s后重试")
                    await asyncio.sleep(30)
                    continue
                gw = "wss://api.sgroup.qq.com/websocket/"
                try:
                    r = await self.http.get("https://api.sgroup.qq.com/gateway")
                    gw = r.json().get("url", gw)
                except Exception:
                    pass
                self._ws = await websockets.connect(gw, ping_interval=30)
                hello = json.loads(await self._ws.recv())
                interval = hello.get("d", {}).get("heartbeat_interval", 45000)
                self._seq = hello.get("s", 0)
                await self._ws.send(json.dumps({
                    "op": 2, "d": {"token": f"QQBot {token}", "intents": QQ_INTENTS, "shard": [0, 1]}}))
                async for raw in self._ws:
                    p = json.loads(raw)
                    if p.get("t") == "READY":
                        print(f"[qqbot] ready! session={p['d'].get('session_id','')}")
                        break
                if self._heartbeat_task:
                    self._heartbeat_task.cancel()
                self._heartbeat_task = asyncio.create_task(self._heartbeat_loop(interval))
                await self._listen()
            except Exception as e:
                print(f"[qqbot] 连接断开: {e}, 10s后重连...")
            finally:
                if self._ws:
                    try: await self._ws.close()
                    except Exception: pass
                if self._heartbeat_task:
                    self._heartbeat_task.cancel()
            if self._running:
                await asyncio.sleep(10)

    async def stop(self) -> None:
        if self._heartbeat_task:
            self._heartbeat_task.cancel()
        if self._ws:
            try: await self._ws.close()
            except Exception: pass
        if self._http:
            try: await self._http.aclose()
            except Exception: pass

    async def _heartbeat_loop(self, interval_ms: int):
        while True:
            await asyncio.sleep(interval_ms / 1000)
            try: await self._ws.send(json.dumps({"op": 1, "d": self._seq}))
            except Exception: break

    async def _listen(self):
        async for raw in self._ws:
            try:
                payload = json.loads(raw)
                op = payload.get("op", 0)
                if op == 11: continue
                self._seq = payload.get("s", self._seq)
                if op == 7: print("[qqbot] reconnect"); break
                if op != 0: continue
                t = payload.get("t", ""); d = payload.get("d", {})
                if t in ("C2C_MESSAGE_CREATE", "GROUP_AT_MESSAGE_CREATE"):
                    msg = StandardMessage(
                        channel="qq",
                        user_id=d.get("author", {}).get("id", d.get("author", {}).get("member_openid", "")),
                        content=d.get("content", ""),
                        meta={"message_type": "private" if t == "C2C_MESSAGE_CREATE" else "group",
                              "group_openid": d.get("group_openid", ""),
                              "msg_id": d.get("id", ""),
                              "reply_target": d.get("author", {}).get("id", d.get("author", {}).get("member_openid", "")),
                              "raw_d": d},
                    )
                    if self._on_message:
                        asyncio.create_task(self._process(msg))
            except Exception as e:
                print(f"[qqbot] listen error: {e}")

    async def _process(self, msg: StandardMessage):
        """收消息 → 回调 on_channel_message（agent.py 统一处理回复发送）"""
        try:
            await self._on_message(msg)
        except Exception as e:
            print(f"[qqbot] process error: {e}")

    async def send(self, target: str, message: str, meta: dict | None = None) -> bool:
        token = await self._get_access_token()
        msg_type = (meta or {}).get("message_type", "private")
        try:
            path = f"/v2/groups/{target}/messages" if msg_type == "group" else f"/v2/users/{target}/messages"
            r = await self.http.post(f"https://api.sgroup.qq.com{path}",
                headers={"Authorization": f"QQBot {token}", "Content-Type": "application/json"},
                json={"content": message[:2000], "msg_type": 0})
            ok = r.status_code == 200
            if ok:
                print(f"[qqbot] replied to {msg_type}")
            else:
                print(f"[qqbot] send failed: {r.status_code} {r.text[:200]}")
            return ok
        except Exception as e:
            print(f"[qqbot] send error: {e}")
            return False

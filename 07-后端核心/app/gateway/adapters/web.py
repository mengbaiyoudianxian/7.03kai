from . import AdapterBase

class WebAdapter(AdapterBase):
    name = 'web'
    _messages: list = []       # 待消费消息 (poll模式)
    _replies: dict = {}        # trace_id → reply

    async def start(self) -> None: pass
    async def stop(self) -> None: pass

    async def receive(self, code: str, message: str, ip: str = '') -> str:
        msg = {'channel':'web','code':code,'message':message,'ip':ip}
        if self._on_message:
            return await self._on_message(msg)
        return 'gateway not connected'

    async def send(self, target: str, message: str, meta: dict = None) -> bool:
        self._replies[target] = message
        return True

    async def poll_reply(self, code: str) -> str | None:
        return self._replies.pop(code, None)

import asyncio
from app.agent import StandardMessage, get_mother

class MessageRouter:
    def __init__(self):
        self._agent = None

    def bind_agent(self):
        self._agent = get_mother()

    async def send_to_agent(self, msg: StandardMessage) -> str:
        if self._agent is None: self.bind_agent()
        ok = await self._agent.enqueue(msg)
        if not ok:
            return '系统繁忙，请稍后重试 [queue full]'
        try:
            reply = await self._agent.process_one(msg)
            return reply
        except Exception as e:
            return f'处理失败: {e}'

_router = MessageRouter()
def get_router() -> MessageRouter:
    return _router

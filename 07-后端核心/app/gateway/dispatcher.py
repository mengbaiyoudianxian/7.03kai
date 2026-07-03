# lazy import in bind_registry

from app.agent import StandardMessage

class ResponseDispatcher:
    def __init__(self):
        self._registry = None

    def bind_registry(self):
        from . import get_registry
        self._registry = get_registry()

    def format_for_channel(self, channel: str, text: str) -> str:
        if channel in ('cli', 'web'):
            return text
        if channel == 'qq':
            return text[:2000]
        if channel == 'feishu':
            import json
            return json.dumps({'msg_type': 'text', 'content': {'text': text}}, ensure_ascii=False)
        return text

    async def dispatch(self, msg: StandardMessage, reply: str) -> bool:
        if self._registry is None: self.bind_registry()
        formatted = self.format_for_channel(msg.channel, reply)
        adapter = self._registry.get(msg.channel)
        if adapter:
            return await adapter.send(msg.user_id, formatted, msg.meta)
        return False

_dispatcher = ResponseDispatcher()
def get_dispatcher() -> ResponseDispatcher:
    return _dispatcher

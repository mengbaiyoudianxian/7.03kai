from . import AdapterBase
import asyncio, json

class CliAdapter(AdapterBase):
    name = 'cli'
    _connections: dict = {}  # code → websocket

    async def start(self) -> None: pass
    async def stop(self) -> None:
        for ws in self._connections.values():
            try: await ws.close()
            except: pass

    async def handle_ws(self, websocket, code: str = 'terminal'):
        self._connections[code] = websocket
        try:
            async for raw_text in websocket.iter_text():
                msg = {'channel': 'cli', 'code': code, 'message': raw_text.strip()}
                if self._on_message:
                    reply = await self._on_message(msg)
                    await websocket.send_text(reply)
        except Exception:
            pass
        finally:
            self._connections.pop(code, None)

    async def send(self, target: str, message: str, meta: dict = None) -> bool:
        ws = self._connections.get(target)
        if ws:
            try:
                await ws.send_text(message)
                return True
            except: pass
        return False

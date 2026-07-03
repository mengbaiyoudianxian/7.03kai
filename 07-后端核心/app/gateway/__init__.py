# MBclaw v6 Gateway — 网关注册中心
from .normalize import MessageNormalizer
from .router import MessageRouter
from .dispatcher import ResponseDispatcher

class GatewayRegistry:
    adapters: dict = {}

    def register(self, name: str, adapter):
        self.adapters[name] = adapter

    def unregister(self, name: str):
        self.adapters.pop(name, None)

    def get(self, name: str):
        return self.adapters.get(name)

    def list_channels(self) -> list:
        return list(self.adapters.keys())

    async def start_all(self) -> None:
        for name, adp in self.adapters.items():
            try: await adp.start()
            except Exception as e: print(f'[gateway] {name} start failed: {e}')

    async def stop_all(self) -> None:
        for name, adp in self.adapters.items():
            try: await adp.stop()
            except Exception as e: print(f'[gateway] {name} stop failed: {e}')

_registry = GatewayRegistry()
def get_registry() -> GatewayRegistry:
    return _registry

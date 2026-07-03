from abc import ABC, abstractmethod

class AdapterBase(ABC):
    name: str = ''
    @abstractmethod
    async def start(self) -> None: ...
    @abstractmethod
    async def stop(self) -> None: ...
    @abstractmethod
    async def send(self, target: str, message: str, meta: dict = None) -> bool: ...
    def set_on_message(self, callback) -> None:
        self._on_message = callback

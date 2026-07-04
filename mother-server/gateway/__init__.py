"""Gateway — 多渠道消息入口

G1: 所有渠道统一路由 → 标记 source → POST /api/mother/run
"""
from __future__ import annotations
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
import uuid, time


@dataclass
class StandardMessage:
    trace_id: str = field(default_factory=lambda: str(uuid.uuid4())[:8])
    session_id: str = "global"
    channel: str = "unknown"
    user_id: str = ""
    content: str = ""
    timestamp: float = field(default_factory=time.time)
    meta: dict = field(default_factory=dict)


class AdapterBase(ABC):
    name: str = ""

    @abstractmethod
    async def start(self) -> None: ...
    @abstractmethod
    async def stop(self) -> None: ...
    @abstractmethod
    async def send(self, target: str, message: str, meta: dict | None = None) -> bool: ...

    def set_on_message(self, callback) -> None:
        self._on_message = callback


_registry: dict[str, AdapterBase] = {}


def register(adapter: AdapterBase) -> AdapterBase:
    _registry[adapter.name] = adapter
    return adapter


def get_adapter(name: str) -> AdapterBase | None:
    return _registry.get(name)

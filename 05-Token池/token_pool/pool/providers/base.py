"""P2-1: Provider 抽象基类

参考 freellmapi providers/base.ts，统一所有模型提供方的调用接口。
"""
from __future__ import annotations
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any


@dataclass
class ProviderResult:
    """一次调用的完整结果"""
    ok: bool
    status_code: int = 0
    body: dict[str, Any] | None = None
    latency_ms: float = 0
    tokens_used: int = 0
    error: str = ""


class BaseProvider(ABC):
    """所有 Provider 的基类"""

    @abstractmethod
    async def chat(self, payload: dict, headers: dict | None = None) -> ProviderResult:
        """发送 chat/completions 请求，返回标准化结果"""
        ...

    @abstractmethod
    async def health_check(self) -> bool:
        """探活：返回 True 表示 Provider 可用"""
        ...

    def supports_stream(self) -> bool:
        return True

    def supports_vision(self) -> bool:
        return False

    def supports_tools(self) -> bool:
        return False

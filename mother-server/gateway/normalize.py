"""消息归一化 — 各渠道原始消息 → StandardMessage"""
from __future__ import annotations
from gateway import StandardMessage
import uuid, time


_normalizer = None


def get_normalizer():
    global _normalizer
    if _normalizer is None:
        _normalizer = MessageNormalizer()
    return _normalizer


def normalize_web(content: str, user_code: str = "web") -> StandardMessage:
    return StandardMessage(
        trace_id=str(uuid.uuid4())[:8],
        channel="web",
        user_id=user_code,
        content=content,
        timestamp=time.time(),
        meta={"source": "webui"},
    )


def normalize_cli(content: str) -> StandardMessage:
    return StandardMessage(
        trace_id=str(uuid.uuid4())[:8],
        channel="cli",
        user_id="terminal",
        content=content,
        timestamp=time.time(),
        meta={"source": "cli"},
    )


class MessageNormalizer:
    CHANNELS = ('qq', 'wechat', 'feishu', 'web', 'cli')

    def normalize(self, channel: str, raw: dict) -> StandardMessage:
        method = getattr(self, f'_norm_{channel}', self._norm_default)
        return method(raw)

    def _norm_default(self, raw: dict) -> StandardMessage:
        return StandardMessage(
            trace_id=str(uuid.uuid4())[:8],
            channel=raw.get('channel', 'unknown'),
            user_id=raw.get('user_id', raw.get('code', 'anonymous')),
            content=raw.get('message', raw.get('text', str(raw))),
            timestamp=time.time(),
            meta=raw.get('meta', {}),
        )

    def _norm_qq(self, raw: dict) -> StandardMessage:
        return StandardMessage(
            trace_id=str(uuid.uuid4())[:8],
            channel='qq',
            user_id=raw.get('sender', {}).get('user_id', str(raw.get('user_id', ''))),
            content=raw.get('raw_message', raw.get('message', '')),
            meta={'message_type': raw.get('message_type', 'private'), 'group_id': raw.get('group_id')},
        )

    def _norm_wechat(self, raw: dict) -> StandardMessage:
        return StandardMessage(
            trace_id=str(uuid.uuid4())[:8],
            channel='wechat',
            user_id=raw.get('FromUserName', raw.get('user_id', '')),
            content=raw.get('Content', raw.get('text', '')),
            meta={'msg_type': raw.get('MsgType', 'text')},
        )

    def _norm_feishu(self, raw: dict) -> StandardMessage:
        event = raw.get('event', raw)
        return StandardMessage(
            trace_id=str(uuid.uuid4())[:8],
            channel='feishu',
            user_id=event.get('sender', {}).get('sender_id', {}).get('open_id', ''),
            content=event.get('message', {}).get('content', {}).get('text', ''),
            meta={'chat_id': event.get('message', {}).get('chat_id', '')},
        )

    def _norm_web(self, raw: dict) -> StandardMessage:
        return normalize_web(
            content=raw.get('message', ''),
            user_code=raw.get('code', 'anonymous'),
        )

    def _norm_cli(self, raw: dict) -> StandardMessage:
        return normalize_cli(content=raw.get('message', ''))

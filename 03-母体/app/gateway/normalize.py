from app.agent import StandardMessage
import uuid, time

class MessageNormalizer:
    CHANNELS = ('qq', 'wechat', 'feishu', 'web', 'cli')

    def normalize(self, channel: str, raw: dict) -> StandardMessage:
        method = getattr(self, f'_norm_{channel}', self._norm_default)
        return method(raw)

    def _norm_default(self, raw: dict) -> StandardMessage:
        return StandardMessage(
            trace_id=str(uuid.uuid4()),
            session_id='global',
            channel=raw.get('channel', 'unknown'),
            user_id=raw.get('user_id', raw.get('code', 'anonymous')),
            message=raw.get('message', raw.get('text', str(raw))),
            timestamp=time.time(),
            meta=raw.get('meta', {}),
        )

    def _norm_qq(self, raw: dict) -> StandardMessage:
        return StandardMessage(
            channel='qq',
            user_id=raw.get('sender', {}).get('user_id', str(raw.get('user_id', ''))),
            message=raw.get('raw_message', raw.get('message', '')),
            meta={'message_type': raw.get('message_type', 'private'), 'group_id': raw.get('group_id')},
        )

    def _norm_wechat(self, raw: dict) -> StandardMessage:
        return StandardMessage(
            channel='wechat',
            user_id=raw.get('FromUserName', raw.get('user_id', '')),
            message=raw.get('Content', raw.get('text', '')),
            meta={'msg_type': raw.get('MsgType', 'text')},
        )

    def _norm_feishu(self, raw: dict) -> StandardMessage:
        event = raw.get('event', raw)
        return StandardMessage(
            channel='feishu',
            user_id=event.get('sender', {}).get('sender_id', {}).get('open_id', ''),
            message=event.get('message', {}).get('content', {}).get('text', ''),
            meta={'chat_id': event.get('message', {}).get('chat_id', '')},
        )

    def _norm_web(self, raw: dict) -> StandardMessage:
        return StandardMessage(
            channel='web',
            user_id=raw.get('code', 'anonymous'),
            message=raw.get('message', ''),
            meta={'ip': raw.get('ip', '')},
        )

    def _norm_cli(self, raw: dict) -> StandardMessage:
        return StandardMessage(
            channel='cli',
            user_id=raw.get('code', 'terminal'),
            message=raw.get('message', ''),
        )

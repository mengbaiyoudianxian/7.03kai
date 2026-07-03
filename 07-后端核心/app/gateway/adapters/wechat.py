from . import AdapterBase

class WechatAdapter(AdapterBase):
    name = 'wechat'
    _webhook_url: str = ''
    _token: str = ''

    async def start(self) -> None:
        self._token = __import__('os').environ.get('WECHAT_TOKEN', '')
        self._webhook_url = __import__('os').environ.get('WECHAT_WEBHOOK', '')
        if self._webhook_url:
            print(f'[wechat] webhook configured')

    async def stop(self) -> None: pass

    async def send(self, target: str, message: str, meta: dict = None) -> bool:
        if not self._webhook_url: return False
        try:
            import httpx
            r = await httpx.AsyncClient().post(self._webhook_url, json={
                'msgtype': 'text',
                'text': {'content': message[:2000]}
            }, timeout=10)
            return r.status_code == 200
        except: return False

    async def handle_callback(self, body: dict) -> str | None:
        msg = {
            'channel': 'wechat',
            'FromUserName': body.get('FromUserName', ''),
            'Content': body.get('Content', body.get('text', '')),
            'MsgType': body.get('MsgType', 'text'),
        }
        if self._on_message:
            return await self._on_message(msg)
        return None

from . import AdapterBase

class FeishuAdapter(AdapterBase):
    name = 'feishu'
    _app_id: str = ''
    _app_secret: str = ''

    async def start(self) -> None:
        self._app_id = __import__('os').environ.get('FEISHU_APP_ID', '')
        self._app_secret = __import__('os').environ.get('FEISHU_APP_SECRET', '')
        if self._app_id:
            print(f'[feishu] configured')

    async def stop(self) -> None: pass

    async def send(self, target: str, message: str, meta: dict = None) -> bool:
        if not self._app_id: return False
        try:
            import httpx
            token = await self._get_token()
            chat_id = (meta or {}).get('chat_id', target)
            r = await httpx.AsyncClient().post(
                f'https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id',
                headers={'Authorization': f'Bearer {token}'},
                json={'receive_id': chat_id, 'msg_type': 'text', 'content': '{"text":"' + message[:2000] + '"}'},
                timeout=10)
            return r.status_code == 200
        except: return False

    async def _get_token(self) -> str:
        try:
            import httpx
            r = await httpx.AsyncClient().post(
                'https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal',
                json={'app_id': self._app_id, 'app_secret': self._app_secret}, timeout=10)
            return r.json().get('tenant_access_token', '')
        except: return ''

    async def handle_event(self, body: dict) -> str | None:
        event = body.get('event', body)
        msg = {'channel': 'feishu', 'event': event}
        if self._on_message:
            return await self._on_message(msg)
        return None

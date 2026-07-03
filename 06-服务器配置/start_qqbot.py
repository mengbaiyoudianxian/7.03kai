import asyncio, sys, os
sys.path.insert(0, '/opt/mbclaw')
os.environ.setdefault('QQ_BOT_APPID', '1904147233')
os.environ.setdefault('QQ_BOT_SECRET', '68yb1EF2cy60g8MN')

async def main():
    from app.gateway.adapters.qqbot import QQBotAdapter
    from app.gateway import get_registry, MessageNormalizer
    from app.gateway.router import get_router
    reg = get_registry()
    norm = MessageNormalizer()
    router = get_router()
    async def on_msg(raw):
        msg = norm.normalize(raw.get('channel','qq'), raw)
        return await router.send_to_agent(msg)
    qq = QQBotAdapter()
    qq.set_on_message(on_msg)
    reg.register('qq', qq)
    print('[qqbot] Starting...')
    await qq.start()
    print('[qqbot] Running. Press Ctrl+C to stop.')
    await asyncio.Event().wait()

asyncio.run(main())

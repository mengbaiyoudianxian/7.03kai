"""QQ Bot Bridge — runs on jump server (HK), forwards to API server"""
import asyncio, json, requests, os, time

APPID = "1904147233"
SECRET = "68yb1EF2cy60g8MN"
API_SERVER = "http://47.83.2.188"  # Forward messages here

def get_token():
    r = requests.post("https://bots.qq.com/app/getAppAccessToken",
        json={"appId": APPID, "clientSecret": SECRET}, timeout=10)
    return r.json().get("access_token", "")

async def send_to_mother(msg_text, user_id):
    """Forward QQ message to API server's MotherAgent"""
    try:
        r = requests.post(f"{API_SERVER}/gateway/web/chat",
            json={"code": f"qq-{user_id}", "message": msg_text, "channel": "qq"}, timeout=30)
        if r.status_code == 200:
            return r.json().get("reply", "")
    except Exception as e:
        print(f"[bridge] API error: {e}")
    return "母体暂时无法回复"

async def main():
    import websockets
    token = get_token()
    if not token:
        print("[qqbot] Failed to get token")
        return

    gw = "wss://api.sgroup.qq.com/websocket/"
    print(f"[qqbot] Connecting...")

    while True:
        try:
            async with websockets.connect(gw, ping_interval=30, open_timeout=15) as ws:
                hello = json.loads(await ws.recv())
                print(f"[qqbot] Hello op={hello.get('op')}")

                # Get fresh token for identify
                token = get_token()
                await ws.send(json.dumps({"op":2,"d":{"token":f"QQBot {token}","intents":402653184,"shard":[0,1]}}))

                p = json.loads(await ws.recv())
                if p.get("t") == "READY":
                    print(f"[qqbot] READY! {p['d']['user']['username']}")
                else:
                    print(f"[qqbot] Identify response: {json.dumps(p)[:100]}")

                # Heartbeat
                async def heartbeat():
                    while True:
                        await asyncio.sleep(40)
                        try: await ws.send(json.dumps({"op":1,"d":None}))
                        except: break
                asyncio.create_task(heartbeat())

                # Listen
                async for raw in ws:
                    p = json.loads(raw); op = p.get("op",0)
                    if op == 11: continue
                    if op != 0: continue
                    t = p.get("t",""); d = p.get("d",{})
                    if t == "C2C_MESSAGE_CREATE":
                        uid = d.get("author",{}).get("id","")
                        msg = d.get("content","")
                        print(f"[qqbot] PM from {uid}: {msg[:50]}")
                        reply = await send_to_mother(msg, uid)
                        # Send reply
                        if reply:
                            requests.post(f"https://api.sgroup.qq.com/v2/users/{uid}/messages",
                                headers={"Authorization":f"QQBot {token}","Content-Type":"application/json"},
                                json={"content":reply[:2000],"msg_type":0}, timeout=10)
                    elif t == "GROUP_AT_MESSAGE_CREATE":
                        gid = d.get("group_openid","")
                        msg = d.get("content","")
                        uid = d.get("author",{}).get("member_openid","")
                        print(f"[qqbot] Group @ from {uid}: {msg[:50]}")
                        reply = await send_to_mother(msg, uid)
                        if reply:
                            requests.post(f"https://api.sgroup.qq.com/v2/groups/{gid}/messages",
                                headers={"Authorization":f"QQBot {token}","Content-Type":"application/json"},
                                json={"content":reply[:2000],"msg_type":0,"msg_id":d.get("id")}, timeout=10)

        except Exception as e:
            print(f"[qqbot] Disconnected: {e}, reconnecting in 5s...")
            await asyncio.sleep(5)

if __name__ == "__main__":
    asyncio.run(main())

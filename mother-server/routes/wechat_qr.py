from fastapi import APIRouter
from fastapi.responses import HTMLResponse

router = APIRouter(prefix="/gateway/wechat", tags=["wechat"])


@router.get("/qr", response_class=HTMLResponse)
async def wechat_qr_page():
    from gateway.adapters.wechat_api import WeixinAPI
    import qrcode as _qr
    from qrcode.image.svg import SvgPathImage
    api = WeixinAPI()
    try:
        qr = api.get_qrcode()
        qr_url = qr.get("qrcode_img_content", "")
        qr_gen = _qr.QRCode(border=2, box_size=10)
        qr_gen.add_data(qr_url)
        qr_gen.make(fit=True)
        svg = qr_gen.make_image(image_factory=SvgPathImage).to_string().decode()
        svg = svg.replace('width="37mm"', 'width="256"').replace('height="37mm"', 'height="256"')
    except Exception as e:
        return HTMLResponse(f"<h1>获取二维码失败</h1><p>{e}</p>")
    return HTMLResponse(f"""<!DOCTYPE html>
<html><head><meta charset="UTF-8"><title>微信扫码登录 MBclaw</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{{font:14px system-ui,sans-serif;background:#f5f5f5;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}}
.card{{background:#fff;border-radius:12px;padding:24px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.1);max-width:400px}}
.card svg{{max-width:280px;height:auto}}
.btn{{display:inline-block;margin:12px 4px;padding:10px 24px;border:none;border-radius:6px;cursor:pointer;font:inherit;font-weight:600}}
.btn-login{{background:#07c160;color:#fff}}
.btn-refresh{{background:#eee;color:#333}}
.status{{color:#888;font-size:12px;margin-top:8px}}
</style></head><body><div class="card">
<h2>📱 微信扫码登录</h2>
<p>用手机微信扫描下方二维码</p>
{svg}
<p class="status" id="status">等待扫码...</p>
<button class="btn btn-login" onclick="startLogin()">开始登录</button>
<button class="btn btn-refresh" onclick="location.reload()">刷新二维码</button>
<div id="result" style="margin-top:12px"></div>
</div>
<script>
async function startLogin() {{
    document.getElementById("status").textContent = "正在等待扫码确认...";
    document.getElementById("result").innerHTML = "";
    try {{
        let r = await fetch("/gateway/wechat/login", {{method:"POST"}});
        let d = await r.json();
        if (d.ok) {{
            document.getElementById("status").textContent = "✅ 登录成功！";
            document.getElementById("result").innerHTML = "<b>账号: " + d.account_id + "</b><br>重启后生效";
        }} else {{
            document.getElementById("status").textContent = "❌ " + (d.error || "失败");
        }}
    }} catch(e) {{
        document.getElementById("status").textContent = "❌ " + e;
    }}
}}
</script></body></html>""")


@router.post("/login")
async def wechat_login():
    import asyncio
    from gateway.adapters.wechat_auth import login_with_qr
    loop = asyncio.get_event_loop()
    result = await loop.run_in_executor(None, login_with_qr)
    if result:
        return {"ok": True, "account_id": result["account_id"]}
    return {"ok": False, "error": "登录失败或超时"}


@router.get("/accounts")
def wechat_accounts():
    from gateway.adapters.wechat_auth import load_accounts
    return {"accounts": [{"account_id": a.get("account_id", ""),
                          "user_id": a.get("userId", ""),
                          "base_url": a.get("baseUrl", "")} for a in load_accounts()]}

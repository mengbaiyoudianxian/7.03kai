"""W2: 微信 QR 扫码登录 + Token 持久化

流程：获取二维码 → 终端显示 → 手机扫码 → 验证码 → 确认 → 保存 Token
"""
from __future__ import annotations
import json, time, logging
from pathlib import Path
from .wechat_api import WeixinAPI, ILINK_BASE

log = logging.getLogger(__name__)

STATE_DIR = Path("/var/lib/mbclaw/wechat")
STATE_DIR.mkdir(parents=True, exist_ok=True)

MAX_QR_REFRESH = 3
LOGIN_TIMEOUT = 480  # 8分钟


def login_with_qr() -> dict | None:
    """完整 QR 登录流程，返回 {account_id, token, base_url, user_id} 或 None"""
    api = WeixinAPI(base_url=ILINK_BASE)
    refresh_count = 1

    while refresh_count <= MAX_QR_REFRESH:
        result = _login_round(api)
        if result is not None:
            return result
        refresh_count += 1
        print(f"\n⏳ 二维码失效，刷新中...({refresh_count}/{MAX_QR_REFRESH})")

    print("\n❌ 多次失效，登录终止")
    return None


def _login_round(api: WeixinAPI) -> dict | None:
    """单轮 QR 登录"""
    try:
        qr = api.get_qrcode()
    except Exception as e:
        print(f"获取二维码失败: {e}")
        return None

    qrcode = qr.get("qrcode", "")
    qr_url = qr.get("qrcode_img_content", "")
    if not qrcode or not qr_url:
        print("服务器未返回有效二维码")
        return None

    _display_qr(qr_url)
    print("请用手机微信扫描二维码")

    deadline = time.time() + LOGIN_TIMEOUT
    current_base = ILINK_BASE
    pending_code = ""

    while time.time() < deadline:
        resp = api.poll_qr_status(qrcode, pending_code)
        status = resp.get("status", "wait")

        if status == "wait":
            print(".", end="", flush=True)
            time.sleep(1)
            continue

        if status == "scaned":
            print("\n✅ 已扫码，等待确认...")
            pending_code = ""
            time.sleep(1)
            continue

        if status == "need_verifycode":
            try:
                pending_code = input("\n输入手机微信显示的数字: ").strip()
            except (EOFError, OSError):
                print("\n⚠️ 需要验证码但无人值守模式，跳过此轮")
                return None
            continue

        if status == "scaned_but_redirect":
            host = resp.get("redirect_host", "")
            if host:
                current_base = f"https://{host}"
                api.base_url = current_base
                print(f"\n IDC 重定向 → {host}")
            time.sleep(1)
            continue

        if status == "confirmed":
            token = resp.get("bot_token", "")
            bot_id = resp.get("ilink_bot_id", "")
            base_url = resp.get("baseurl", current_base)
            user_id = resp.get("ilink_user_id", "")
            if not token or not bot_id:
                print("❌ 确认但缺少 token/bot_id")
                return None
            _save_account(bot_id, token, base_url, user_id)
            print(f"\n✅ 微信连接成功！bot_id={bot_id}")
            return {"account_id": bot_id, "token": token, "base_url": base_url, "user_id": user_id}

        if status == "expired":
            print("\n二维码已过期")
            return None

        if status == "verify_code_blocked":
            print("\n⛔ 验证码多次错误")
            return None

        if status == "binded_redirect":
            print("\n✅ 已连接过，无需重复")
            return None

        log.warning("未知状态: %s", status)
        time.sleep(1)

    print("\n⏰ 登录超时")
    return None


def _display_qr(qr_url: str):
    """终端显示二维码"""
    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(qr_url)
        qr.make(fit=True)
        qr.print_ascii(tty=True)
    except ImportError:
        print(f"扫码链接: {qr_url}")


def _save_account(account_id: str, token: str, base_url: str, user_id: str):
    """持久化账号凭证"""
    path = STATE_DIR / f"{account_id}.json"
    data = {"token": token, "baseUrl": base_url, "userId": user_id, "savedAt": time.time()}
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2))
    path.chmod(0o600)
    # 更新索引
    idx_path = STATE_DIR / "accounts.json"
    idx = json.loads(idx_path.read_text()) if idx_path.exists() else []
    if account_id not in idx:
        idx.append(account_id)
        idx_path.write_text(json.dumps(idx))


def load_accounts() -> list[dict]:
    """加载所有已登录账号"""
    idx_path = STATE_DIR / "accounts.json"
    if not idx_path.exists():
        return []
    idx = json.loads(idx_path.read_text())
    accounts = []
    for aid in idx:
        path = STATE_DIR / f"{aid}.json"
        if path.exists():
            d = json.loads(path.read_text())
            d["account_id"] = aid
            accounts.append(d)
    return accounts


def remove_account(account_id: str):
    """移除账号"""
    for f in [f"{account_id}.json", f"{account_id}.sync.json", f"{account_id}.context-tokens.json"]:
        p = STATE_DIR / f
        p.unlink(missing_ok=True)
    idx_path = STATE_DIR / "accounts.json"
    if idx_path.exists():
        idx = json.loads(idx_path.read_text())
        if account_id in idx:
            idx.remove(account_id)
            idx_path.write_text(json.dumps(idx))

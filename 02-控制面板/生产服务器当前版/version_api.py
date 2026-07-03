"""
版本检测接口 — 客户端启动时调用
"""
import os, json
from pathlib import Path
from fastapi import APIRouter, Query

router = APIRouter()

DATA_DIR = Path(os.environ.get("MBCLAW_DATA", "/var/lib/mbclaw"))
DATA_DIR.mkdir(parents=True, exist_ok=True)
VERSION_FILE = DATA_DIR / "version.json"

# 初始化默认配置
if not VERSION_FILE.exists():
    VERSION_FILE.write_text(json.dumps({
        "latest": "4.1-root",
        "min_supported": "3.0-root",
        "download_url": "http://121.199.57.195/mbclaw-root-latest.apk",
        "changelog": "MBclaw 最新版",
        "force_update": False,
    }, ensure_ascii=False, indent=2))


def _ver_tuple(v):
    """简单版本比较: '4.1-root' -> (4, 1)"""
    try:
        base = v.split("-")[0]
        parts = base.split(".")
        return tuple(int(x) for x in parts)
    except: return (0,)


@router.get("/admin/client/version")
def version(current: str = Query("")):
    cfg = json.loads(VERSION_FILE.read_text())
    latest = cfg["latest"]
    has_update = _ver_tuple(current) < _ver_tuple(latest) if current else False
    return {
        "latest": latest,
        "current": current,
        "has_update": has_update,
        "download_url": cfg.get("download_url"),
        "changelog": cfg.get("changelog", ""),
        "force_update": cfg.get("force_update", False),
        "min_supported": cfg.get("min_supported"),
    }


@router.post("/admin/client/version/set")
def set_version(body: dict):
    """管理面板用 - 改最新版本"""
    cfg = json.loads(VERSION_FILE.read_text())
    if "notes" in body and "changelog" not in body:
        body["changelog"] = body.pop("notes")
    cfg.update({k: v for k, v in body.items() if k in {
        "latest", "min_supported", "download_url", "changelog", "force_update"
    }})
    VERSION_FILE.write_text(json.dumps(cfg, ensure_ascii=False, indent=2))
    return {"ok": True, "config": cfg}

# ── Linux 开发环境 ─────────────────────────────────────────

LINUX_ENV = {
    "version": "1.0.0",
    "name": "MBclaw Linux (Alpine 3.22)",
    "size": "3.8MB (base) + ~400MB (dev tools after setup)",
    "download_url": "http://121.199.57.195:8080/mbclaw-linux-env.tar.gz",
    "setup_guide": "下载后自动解压到 /data/local/mbclaw-linux/ ，首次运行 setup.sh 安装开发工具链",
    "tools": ["git", "python3", "openjdk17", "gradle", "gcc", "cmake", "vim", "bash"],
    "root_mode": "chroot",
    "nonroot_mode": "proot",
}


@router.get("/admin/client/linux-env")
def linux_env_info():
    """客户端查询 Linux 开发环境信息"""
    return LINUX_ENV

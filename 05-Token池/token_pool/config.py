"""Token Pool 配置"""
from __future__ import annotations
import os
from pathlib import Path

try:
    from dotenv import load_dotenv
    _env = Path(__file__).parent / ".env"
    if _env.exists(): load_dotenv(_env)
except ImportError: pass

def _e(k, d=""): return os.environ.get(k, d)
def _i(k, d): 
    try: return int(os.environ.get(k, d))
    except: return int(d)

class Config:
    DATA_DIR      = _e("TP_DATA", "/var/lib/token_pool")
    HOST          = _e("TP_HOST", "0.0.0.0")
    PORT          = _i("TP_PORT", 8100)
    ADMIN_KEY     = _e("TP_ADMIN_KEY", "changeme")        # 管理接口鉴权Key
    PROXY_KEY     = _e("TP_PROXY_KEY", "")                 # 调用者鉴权Key（空=不验证）
    # 熔断配置
    CB_THRESHOLD  = _i("TP_CB_THRESHOLD", 3)               # 连续失败N次开启熔断
    CB_COOLDOWN   = _i("TP_CB_COOLDOWN", 60)               # 熔断冷却秒数
    # 健康检测
    HEALTH_INTERVAL = _i("TP_HEALTH_INTERVAL", 300)        # 每N秒自动检测所有Key
    HEALTH_TIMEOUT  = _i("TP_HEALTH_TIMEOUT", 10)          # 单次检测超时
    # MiClaw桥
    MICLAW_API    = _e("MICLAW_API_BASE", "http://100.126.55.0:8765")
    MICLAW_TOKEN  = _e("MICLAW_TOKEN_KEY", "")

    @property
    def db_path(self): return str(Path(self.DATA_DIR) / "pool.db")

cfg = Config()
Path(cfg.DATA_DIR).mkdir(parents=True, exist_ok=True)

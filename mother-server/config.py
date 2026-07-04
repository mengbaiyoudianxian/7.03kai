"""MBclaw Mother Server — 统一配置"""
from __future__ import annotations
import os
from pathlib import Path
from dataclasses import dataclass, field

try:
    from dotenv import load_dotenv
    p = Path(__file__).parent / ".env"
    if p.exists(): load_dotenv(p)
except ImportError: pass

def _e(k, d=""): return os.environ.get(k, d)
def _i(k, d): 
    try: return int(os.environ.get(k, d))
    except: return int(d)

@dataclass(frozen=True)
class Config:
    data_dir: str = field(default_factory=lambda: _e("MBCLAW_DATA", "/var/lib/mbclaw"))
    db_path: str = field(default_factory=lambda: _e("MBCLAW_DB_PATH", ""))
    uploads_dir: str = field(default_factory=lambda: _e("MBCLAW_UPLOADS", ""))
    llm_api_key: str = field(default_factory=lambda: _e("MBCLAW_LLM_API_KEY"))
    llm_base_url: str = field(default_factory=lambda: _e("MBCLAW_LLM_BASE_URL", "https://api.openai.com/v1"))
    llm_model: str = field(default_factory=lambda: _e("MBCLAW_LLM_MODEL", "gpt-4o-mini"))
    llm_mock: bool = field(default_factory=lambda: _e("MBCLAW_LLM_MOCK", "").lower() in ("1","true","yes"))
    token_pool_url: str = field(default_factory=lambda: _e("TOKEN_POOL_URL", ""))
    token_pool_proxy_key: str = field(default_factory=lambda: _e("TOKEN_POOL_PROXY_KEY", ""))
    openai_api_key: str = field(default_factory=lambda: _e("OPENAI_API_KEY"))
    anthropic_api_key: str = field(default_factory=lambda: _e("ANTHROPIC_API_KEY"))
    deepseek_api_key: str = field(default_factory=lambda: _e("DEEPSEEK_API_KEY"))
    qq_bot_appid: str = field(default_factory=lambda: _e("QQ_BOT_APPID"))
    qq_bot_secret: str = field(default_factory=lambda: _e("QQ_BOT_SECRET"))
    miclaw_api_base: str = field(default_factory=lambda: _e("MICLAW_API_BASE", "http://100.126.55.0:8765"))
    owner_name: str = field(default_factory=lambda: _e("OWNER_NAME", "Mengbai"))
    owner_id: str = field(default_factory=lambda: _e("OWNER_ID", "owner"))
    host: str = field(default_factory=lambda: _e("MBCLAW_HOST", "0.0.0.0"))
    port: int = field(default_factory=lambda: _i("MBCLAW_PORT", 8000))
    max_iterations: int = field(default_factory=lambda: _i("MBCLAW_MAX_ITERATIONS", 50))
    admin_password: str = field(default_factory=lambda: _e("MBCLAW_ADMIN_PASSWORD", "admin"))

    def resolved_db_path(self) -> str: return self.db_path or str(Path(self.data_dir) / "mbclaw.db")
    def best_llm_key(self) -> str:
        for k in [self.llm_api_key, self.openai_api_key, self.anthropic_api_key, self.deepseek_api_key]:
            if k: return k
        return ""
    def validate(self) -> list[str]:
        w = []
        if not self.token_pool_url and not self.best_llm_key():
            w.append("WARN: 未配置 Token Pool URL 或 LLM API Key")
        if self.token_pool_url: w.append(f"Token Pool: {self.token_pool_url}")
        return w

cfg = Config()

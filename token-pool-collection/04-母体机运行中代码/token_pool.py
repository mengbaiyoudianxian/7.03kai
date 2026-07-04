"""Token Pool — 用户贡献的Key池，母体自动调用"""
import json, os, time, random
from dataclasses import dataclass, field

HEARTBEAT_DIR = "/var/lib/mbclaw/heartbeat_logs"
POOL_FILE = "/var/lib/mbclaw/token_pool.json"

@dataclass
class PoolKey:
    code: str         # 用户调试码
    api_key: str      # API Key
    base_url: str     # API Base URL
    model: str        # 模型名
    provider: str     # provider_id
    qq: str = ""
    status: str = "unknown"   # working/failed/unknown
    tested_at: float = 0
    error_msg: str = ""
    usage_count: int = 0
    last_used: float = 0

class TokenPool:
    def __init__(self):
        self.keys: list[PoolKey] = []
        self._load()

    def _load(self):
        """从心跳文件加载所有用户Key"""
        self.keys = []
        if not os.path.isdir(HEARTBEAT_DIR): return
        # 先加载 MiClaw 实例 (长期稳定免费) — 只认真token (>20字符，非纯数字)
        miclaw_file = "/var/lib/mbclaw/miclaw_instances.json"
        if os.path.exists(miclaw_file):
            try:
                for aid, inst in json.load(open(miclaw_file)).items():
                    token = inst.get("miclaw_token", "") or inst.get("token", "")
                    if token and (inst.get("logged_in") or inst.get("status") == "ready"):
                        self.keys.append(PoolKey(
                            code=f"miclaw-{aid[:8]}",
                            api_key=token,
                            base_url="http://47.83.2.188/bridge/miclaw/v1",
                            model=inst.get("model", "miclaw"),
                            provider="miclaw-bridge",
                            status="working",
                        ))
            except: pass
# 加载用户心跳Key
        for fname in os.listdir(HEARTBEAT_DIR):
            if not fname.endswith('.json'): continue
            try:
                hb = json.load(open(os.path.join(HEARTBEAT_DIR, fname)))
                k = hb.get("keys", {})
                api_key = (k.get("api_key", "") or "").strip()
                base_url = (k.get("api_base_url", "") or "").strip()
                if api_key and len(api_key) > 5 and base_url:
                    self.keys.append(PoolKey(
                        code=hb.get("code", fname.replace(".json","")),
                        api_key=api_key,
                        base_url=base_url,
                        model=k.get("model_name", "gpt-3.5"),
                        provider=k.get("provider_id", "unknown"),
                        qq=hb.get("qq", ""),
                    ))
            except: pass

        # 加载已有测试结果
        if os.path.exists(POOL_FILE):
            try:
                cached = json.load(open(POOL_FILE))
                for pk in self.keys:
                    if pk.code in cached:
                        c = cached[pk.code]
                        pk.status = c.get("status", "unknown")
                        pk.tested_at = c.get("tested_at", 0)
                        pk.error_msg = c.get("error_msg", "")
                        pk.usage_count = c.get("usage_count", 0)
            except: pass

    def _save(self):
        cache = {}
        for pk in self.keys:
            cache[pk.code] = {"status": pk.status, "tested_at": pk.tested_at,
                "error_msg": pk.error_msg, "usage_count": pk.usage_count}
        json.dump(cache, open(POOL_FILE, "w"), ensure_ascii=False, indent=2)

    def test_key(self, pk: PoolKey) -> bool:
        """测试单个Key是否可用"""
        import urllib.request
        try:
            url = f"{pk.base_url.rstrip('/')}/models"
            req = urllib.request.Request(url, headers={"Authorization": f"Bearer {pk.api_key}"})
            resp = urllib.request.urlopen(req, timeout=10)
            pk.status = "working" if resp.status == 200 else "failed"
            pk.error_msg = "" if resp.status == 200 else f"HTTP {resp.status}"
        except Exception as e:
            pk.status = "failed"
            pk.error_msg = str(e)[:100]
        pk.tested_at = time.time()
        self._save()
        return pk.status == "working"

    def test_all(self):
        """测试所有Key"""
        for pk in self.keys:
            self.test_key(pk)

    def get_working(self) -> list[PoolKey]:
        """获取所有可用的Key"""
        return [pk for pk in self.keys if pk.status == "working"]

    def pick(self) -> PoolKey | None:
        """选一个可用的Key（MiClaw实例优先，然后用户Key，用得少的优先）"""
        working = self.get_working()
        # 实例优先 (免费稳定), 用户Key备用
        inst = [k for k in working if k.provider == "miclaw-bridge"]
        if inst:
            pk = min(inst, key=lambda x: x.usage_count)
            pk.usage_count += 1; pk.last_used = time.time(); self._save()
            return pk
        real_keys = [k for k in working if k.provider != "miclaw-bridge"]
        if real_keys:
            pk = min(miclaw, key=lambda x: x.usage_count)
            pk.usage_count += 1; pk.last_used = time.time(); self._save()
            return pk
        if not working:
            # 没有测试过的，现场测一个
            untested = [pk for pk in self.keys if pk.status == "unknown"]
            if untested:
                pk = untested[0]
                if self.test_key(pk):
                    working = [pk]
        if working:
            pk = min(working, key=lambda x: x.usage_count)
            pk.usage_count += 1
            pk.last_used = time.time()
            self._save()
            return pk
        return None

    def get_best_for_llm(self) -> tuple[str, str, str] | None:
        """给LLMClient用的: (base_url, api_key, model)"""
        pk = self.pick()
        if pk:
            return (pk.base_url, pk.api_key, pk.model)
        return None

# 全局单例
_pool = None
def get_pool() -> TokenPool:
    global _pool
    if _pool is None:
        _pool = TokenPool()
    return _pool

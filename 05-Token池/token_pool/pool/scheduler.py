"""智能调度器 — 综合评分选最优Key，自动故障转移"""
from __future__ import annotations
import time, logging
from pool.registry import ProviderKey, get_registry
from pool.circuit import get_cb
from pool.metrics import get_hub

log = logging.getLogger(__name__)

# 任务类型 → 推荐供应商优先级
TASK_ROUTING = {
    "code":      ["anthropic", "openai", "deepseek"],
    "reasoning": ["anthropic", "openai", "deepseek"],
    "chat":      ["openai", "anthropic", "deepseek", "miclaw"],
    "cheap":     ["deepseek", "dashscope", "miclaw", "local"],
    "bulk":      ["deepseek", "dashscope", "local"],
    "embedding": ["dashscope", "openai"],
    "local":     ["local"],
    "any":       [],  # 纯评分排序
}

def _score(pk: ProviderKey, task: str, budget: float) -> float:
    """越高越优先"""
    s = float(pk.priority) * 2.0
    hub = get_hub()
    m = hub.snapshot(pk.alias)
    # 成功率加权（最重要）
    s += m["success_rate"] * 5.0
    # 延迟惩罚（ms → 秒）
    s -= m["avg_latency_ms"] / 1000.0
    # 成本惩罚
    if budget > 0: s -= pk.cost_per_1k * 3.0
    # 任务类型偏好
    pref = TASK_ROUTING.get(task, [])
    if pref and pk.provider in pref:
        s += 3.0 * (len(pref) - pref.index(pk.provider))
    # 0成本奖励（本地/MiClaw）
    if pk.cost_per_1k == 0: s += 1.0
    return s

def pick(task: str = "chat", budget: float = 0.0, require_model: str = "") -> ProviderKey | None:
    """选一个可用的Key。task: chat/code/cheap/bulk/any"""
    reg = get_registry(); cb = get_cb()
    keys = reg.all(enabled_only=True)
    if not keys: return None

    # 过滤：熔断 / api_key缺失(非local/miclaw) / 模型匹配
    def _ok(pk: ProviderKey) -> bool:
        if not pk.enabled: return False
        if cb.is_open(pk.alias): return False
        if require_model and pk.model != require_model: return False
        if pk.provider not in ("local", "miclaw") and not pk.api_key: return False
        return True

    candidates = [pk for pk in keys if _ok(pk)]
    if not candidates:
        # 所有Key都熔断了？重置成功率最高的那个
        reset_best = sorted(keys, key=lambda k: -k.success_count)
        if reset_best:
            cb.reset(reset_best[0].alias)
            candidates = [reset_best[0]]
        else: return None

    ranked = sorted(candidates, key=lambda pk: _score(pk, task, budget), reverse=True)
    return ranked[0]

def pick_all_ranked(task: str = "chat", budget: float = 0.0) -> list[ProviderKey]:
    """返回所有可用Key的排序列表（用于故障转移）"""
    reg = get_registry(); cb = get_cb()
    keys = reg.all(enabled_only=True)
    def _ok(pk):
        if not pk.enabled: return False
        if cb.is_open(pk.alias): return False
        if pk.provider not in ("local","miclaw") and not pk.api_key: return False
        return True
    candidates = [pk for pk in keys if _ok(pk)]
    return sorted(candidates, key=lambda pk: _score(pk, task, budget), reverse=True)

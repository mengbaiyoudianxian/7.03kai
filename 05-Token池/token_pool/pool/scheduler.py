"""智能调度器 — GuardRail 编排：Quota → RateLimit → Circuit

Gateway 只知道 Key 可用/不可用。
Mother 拥有最终选择权（基于 capability_score + reliability + speed）。
Token Pool 不做模型选择决策。
"""
from __future__ import annotations
import time, logging
from dataclasses import dataclass
from typing import List, Tuple
from pool.registry import ProviderKey, get_registry
from pool.ratelimit import get_limiter
from pool.scoring import (
    capability_score, reliability_score, speed_score,
    get_tracker,
)

log = logging.getLogger(__name__)

# ── 任务→Provider偏好（Mother决策用，不放Gateway） ──────────────────────────
TASK_ROUTING = {
    "code":      ["anthropic", "openai", "deepseek"],
    "reasoning": ["anthropic", "openai", "deepseek"],
    "chat":      ["deepseek-cn", "zhipu", "openai", "anthropic", "deepseek", "miclaw"],
    "cheap":     ["deepseek", "dashscope", "miclaw", "local"],
    "bulk":      ["deepseek", "dashscope", "local"],
    "embedding": ["dashscope", "openai"],
    "vision":    ["openai", "anthropic", "google", "dashscope"],
    "local":     ["local"],
    "any":       [],
}


# ═══════════════════════════════════════════════════════════════════════════════
# GuardRail 检查器
# ═══════════════════════════════════════════════════════════════════════════════

@dataclass
class GuardResult:
    passed: bool
    reason: str = ""


class QuotaGuard:
    """Token 额度检查：yesterday_token_usage × share_percent = today_quota"""

    @staticmethod
    def check(pk: ProviderKey) -> GuardResult:
        reg = get_registry()
        key = pk.alias

        # admin 配置的 Key（非用户共享）不受限
        # user_shared_keys 表用 user_code 字段，心跳Key的alias格式为 hb-{user_code[:16]}
        shared_keys = reg.list_shared_keys()
        shared_aliases = set()
        for sk in shared_keys:
            uc = sk.get("user_code", "")
            if uc:
                shared_aliases.add(f"hb-{uc[:16]}")

        if key not in shared_aliases:
            return GuardResult(True)

        # 查 call_log 算 yesterday_usage
        yesterday_start = (int(time.time()) // 86400) * 86400 - 86400
        yesterday_end = yesterday_start + 86400
        today_start = yesterday_end

        logs = reg.call_log(key, limit=1000)
        yesterday_used = sum(
            l["total_tokens"] for l in logs
            if yesterday_start <= l["ts"] <= yesterday_end and l.get("success")
        )
        today_used = sum(
            l["total_tokens"] for l in logs
            if l["ts"] >= today_start and l.get("success")
        )

        # 查共享比例
        share_pct = 0.0
        for sk in shared_keys:
            uc = sk.get("user_code", "")
            if uc and key == f"hb-{uc[:16]}":
                share_pct = sk.get("allowed_ratio", 0.0)
                break

        if share_pct <= 0.0:
            return GuardResult(True)  # 未设置共享比例 = 不共享

        today_quota = int(yesterday_used * share_pct)
        remaining = today_quota - today_used

        if remaining <= 0:
            return GuardResult(False,
                f"Quota耗尽: 昨日{yesterday_used:,}T × {share_pct:.0%}={today_quota:,}T, "
                f"今日已用{today_used:,}T")
        return GuardResult(True)


class RateLimitGuard:
    """速率限制检查（调用ratelimit.py）"""

    @staticmethod
    def check(pk: ProviderKey) -> GuardResult:
        rl = get_limiter()
        if rl.is_on_cooldown(pk.alias, pk.provider, pk.model):
            expiry = rl.get_cooldown_expiry(pk.alias, pk.provider, pk.model)
            remaining = max(0, (expiry or 0) - time.time() * 1000) / 1000
            return GuardResult(False, f"速率限制冷却中 ({remaining:.0f}s剩余)")
        return GuardResult(True)


class CircuitGuard:
    """熔断检查（委托ratelimit的冷却状态）"""

    @staticmethod
    def check(pk: ProviderKey) -> GuardResult:
        rl = get_limiter()
        if rl.is_on_cooldown(pk.alias, pk.provider, pk.model):
            expiry = rl.get_cooldown_expiry(pk.alias, pk.provider, pk.model)
            remaining = max(0, (expiry or 0) - time.time() * 1000) / 1000
            return GuardResult(False, f"熔断开放: {remaining:.0f}s后恢复")
        return GuardResult(True)


# ═══════════════════════════════════════════════════════════════════════════════
# GuardRail 编排 — 三层检查返回可调用Key
# ═══════════════════════════════════════════════════════════════════════════════

# 检查顺序
GUARDS = [QuotaGuard, RateLimitGuard, CircuitGuard]


def check_key(pk: ProviderKey) -> Tuple[bool, str]:
    """逐层检查一个Key，返回 (可用, 原因)"""
    for guard in GUARDS:
        result = guard.check(pk)
        if not result.passed:
            return False, f"[{guard.__name__.replace('Guard','')}] {result.reason}"
    return True, ""


def filter_candidates(
    require_model: str = "",
    require_apikey: bool = True,
) -> List[ProviderKey]:
    """获取所有Key并执行 GuardRail 过滤。

    返回: 通过所有检查的可调用Key列表（未排序）。
    Mother 自行对结果做能力匹配+最终选择。
    """
    reg = get_registry()
    keys = reg.all(enabled_only=True)

    candidates = []
    skipped: dict[str, list[str]] = {}

    for pk in keys:
        # ── 基础过滤 ──
        if require_model and pk.model != require_model:
            skipped.setdefault("model_mismatch", []).append(pk.alias)
            continue
        if require_apikey and pk.provider not in ("local", "miclaw") and not pk.api_key:
            skipped.setdefault("no_apikey", []).append(pk.alias)
            continue

        # ── GuardRail 三层 ──
        ok, reason = check_key(pk)
        if ok:
            candidates.append(pk)
        else:
            skipped.setdefault("guard", []).append(f"{pk.alias}: {reason}")

    if skipped:
        log.info("GuardRail 过滤: %d 通过, %s",
                 len(candidates),
                 ", ".join(f"{k}: {len(v)}" for k, v in skipped.items()))

    return candidates


def pick(task: str = "chat", require_model: str = "") -> ProviderKey | None:
    """选一个可用的Key（简化版：过 GuardRail + TASK_ROUTING 预排序）。

    注意: 这只是一个"可用建议"，Mother 拥有最终选择权。
    """
    candidates = filter_candidates(require_model=require_model)
    if not candidates:
        return None

    # 按 provider 偏好预排序（Mother 后续会用自己的评分覆盖）
    pref_order = TASK_ROUTING.get(task, [])
    if pref_order:
        def _pref(pk: ProviderKey) -> int:
            try:
                return pref_order.index(pk.provider)
            except ValueError:
                return len(pref_order)
        candidates.sort(key=_pref)

    return candidates[0]


def pick_all(task: str = "chat", require_model: str = "") -> List[ProviderKey]:
    """返回所有通过 GuardRail 的可用Key列表。

    Gateway 调用此函数获取可用Key池，
    Mother 在收到列表后自行做能力匹配+评分+最终选择。
    """
    candidates = filter_candidates(require_model=require_model)
    if not candidates:
        return []

    # 预排序（Mother 可覆盖）
    pref_order = TASK_ROUTING.get(task, [])
    if pref_order:
        def _pref(pk: ProviderKey) -> int:
            try:
                return pref_order.index(pk.provider)
            except ValueError:
                return len(pref_order)
        candidates.sort(key=_pref)

    return candidates


def pick_with_scores(
    task: str = "chat",
    require_model: str = "",
    strategy: str = "balanced",
) -> List[Tuple[ProviderKey, float, dict]]:
    """返回通过 GuardRail 的可用Key + scoring.py 评分详情。

    Mother 使用此函数获取完整信息：
    - ProviderKey: 连接信息
    - score: 综合评分 (rel+spd+cap的凸组合)
    - detail: {reliability, speed, capability} 各维度得分

    Mother 在收到后做最终决策。
    """
    candidates = filter_candidates(require_model=require_model)
    if not candidates:
        return []

    tracker = get_tracker()
    reg = get_registry()

    results = []
    for pk in candidates:
        sc = pk.success_count
        fc = pk.fail_count
        rel = reliability_score(sc, fc)
        spd = speed_score(tracker.get(pk.alias))
        cap = capability_score(pk.alias, task)

        from pool.scoring import score as calc_score
        final = calc_score(pk.alias, task, sc, fc, strategy)

        results.append((pk, final, {
            "alias": pk.alias,
            "provider": pk.provider,
            "model": pk.model,
            "reliability": round(rel, 3),
            "speed": round(spd, 3),
            "capability": round(cap, 3),
            "score": round(final, 4),
            "success_count": sc,
            "fail_count": fc,
            "cost_per_1k": pk.cost_per_1k,
            "enabled": pk.enabled,
        }))

    results.sort(key=lambda x: -x[1])
    return results


def guard_status() -> dict:
    """返回所有Key的 GuardRail 状态（管理面板用）"""
    reg = get_registry()
    keys = reg.all()
    status = {}
    for pk in keys:
        ok, reason = check_key(pk)
        status[pk.alias] = {
            "alias": pk.alias,
            "enabled": pk.enabled,
            "pass": ok,
            "reason": reason or "ok",
        }
    return status

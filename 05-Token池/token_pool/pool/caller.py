"""统一调用器 — 处理OpenAI/Anthropic差异，自动故障转移

增强功能 (P0-5):
  - 混合token估算 (rough //4 × content-type correction)
  - context感知过滤 (跳过窗口不够的模型)
  - vision/tools感知过滤 (图片请求跳过纯文本模型)
  - 失败诊断报告 (全部失败时输出每个模型的跳过原因)
  - 衰减统计 (ttfb + tok/s → MetricsTracker)
"""
from __future__ import annotations
import time, logging, json, re
import httpx
from pool.registry import ProviderKey, get_registry
from pool.ratelimit import get_limiter
from pool.metrics import get_hub
from pool.scheduler import pick_all
from pool.scoring import get_tracker, BUILTIN_CAPABILITIES

log = logging.getLogger(__name__)

# P3-2: Key脱敏 — 保留首尾4字符，中间替换为****
_KEY_RE = re.compile(r'(sk-[a-zA-Z0-9_-]{20,})')

def _redact_key(text: str) -> str:
    def _mask(m):
        k = m.group(1)
        return k[:4] + "****" + k[-4:] if len(k) > 12 else "****"
    return _KEY_RE.sub(_mask, text)


# ═══════════════════════════════════════════════════════════════════════════════
# Token 估算 (hybrid: rough + correction)
# ═══════════════════════════════════════════════════════════════════════════════

# Unicode范围
_CJK_RE = re.compile(r'[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]')
_CODE_INDICATORS = re.compile(r'[{}\[\]"\'`]|function|class |def |import |const |let |var |async|await|return', re.IGNORECASE)

CONTENT_FACTORS = {
    "chinese": 1.3,
    "english": 1.0,
    "code":    1.5,
    "mixed":   1.2,
}


def _detect_content_type(text: str) -> str:
    """检测文本类型"""
    cjk_chars = len(_CJK_RE.findall(text))
    code_signs = len(_CODE_INDICATORS.findall(text))
    total = max(len(text), 1)

    cjk_ratio = cjk_chars / total
    code_ratio = code_signs / total

    if cjk_ratio > 0.15:
        return "chinese" if code_ratio < 0.05 else "mixed"
    if code_ratio > 0.08:
        return "code"
    return "english"


def estimate_tokens(messages: list[dict], max_output_tokens: int = 1024) -> int:
    """混合token估算。

    Step 1: rough_tokens = len(json) // 4
    Step 2: content-type correction factor
    Step 3: + max_output_tokens (预估输出)

    返回: 预估总token数 (input + output)
    """
    text = json.dumps(messages, ensure_ascii=False)
    rough = len(text) // 4
    if rough == 0:
        return max_output_tokens

    content_type = _detect_content_type(text)
    factor = CONTENT_FACTORS.get(content_type, 1.0)
    estimated_input = int(rough * factor)
    return estimated_input + max_output_tokens


def _has_images(payload: dict) -> bool:
    """检查payload是否包含图片请求"""
    msgs = payload.get("messages", [])
    for m in msgs:
        content = m.get("content")
        if isinstance(content, list):
            for part in content:
                if isinstance(part, dict) and part.get("type") == "image_url":
                    return True
    return False


def _has_tools(payload: dict) -> bool:
    """检查payload是否包含工具调用"""
    return bool(payload.get("tools") or payload.get("tool_choice") or payload.get("functions"))


# ═══════════════════════════════════════════════════════════════════════════════
# 模型过滤
# ═══════════════════════════════════════════════════════════════════════════════

class SkipReason:
    def __init__(self):
        self.reasons: dict[str, str] = {}  # alias → reason

    def add(self, alias: str, reason: str):
        self.reasons[alias] = reason

    def summary(self) -> str:
        if not self.reasons:
            return ""
        lines = [f"  {a}: {r}" for a, r in self.reasons.items()]
        return "跳过原因:\n" + "\n".join(lines)

    def __bool__(self): return bool(self.reasons)


def _filter_model(pk: ProviderKey, payload: dict, estimated_tokens: int,
                  has_images: bool, has_tools: bool) -> str:
    """检查模型是否适合当前请求。返回空字符串=通过，否则=拒绝原因"""
    cap = BUILTIN_CAPABILITIES.get(pk.alias)
    # 别名匹配
    if cap is None:
        from pool.scoring import _MODEL_ALIASES
        canonical = _MODEL_ALIASES.get(pk.alias.lower())
        if canonical:
            cap = BUILTIN_CAPABILITIES.get(canonical)

    # context窗口检查
    if cap and cap.context > 0 and estimated_tokens > cap.context:
        return f"context {cap.context} < 需要 {estimated_tokens} tokens"

    # vision检查
    if has_images and cap and cap.vision <= 0:
        return "不支持vision(图片)"

    # tools检查
    if has_tools and cap and cap.tool_use <= 0:
        return "不支持tool_calls"

    return ""


# ═══════════════════════════════════════════════════════════════════════════════
# 调用
# ═══════════════════════════════════════════════════════════════════════════════

async def _call_openai_compat(pk: ProviderKey, payload: dict, timeout=120) -> dict:
    async with httpx.AsyncClient(timeout=timeout) as c:
        r = await c.post(f"{pk.base_url}/chat/completions",
            headers={"Authorization": f"Bearer {pk.api_key}", "Content-Type": "application/json"},
            json={**payload, "model": payload.get("model") or pk.model})
        r.raise_for_status()
        return r.json()


async def _call_anthropic(pk: ProviderKey, payload: dict, timeout=120) -> dict:
    """Anthropic API → 转换为 OpenAI 格式响应"""
    msgs = payload.get("messages", [])
    sys_msg = next((m["content"] for m in msgs if m["role"] == "system"), "")
    user_msgs = [m for m in msgs if m["role"] != "system"]
    body = {
        "model": payload.get("model") or pk.model,
        "max_tokens": payload.get("max_tokens", 1024),
        "messages": user_msgs,
    }
    if sys_msg: body["system"] = sys_msg
    if payload.get("temperature") is not None: body["temperature"] = payload["temperature"]
    if payload.get("stream"): body["stream"] = True
    async with httpx.AsyncClient(timeout=timeout) as c:
        r = await c.post(f"{pk.base_url}/messages",
            headers={"x-api-key": pk.api_key, "anthropic-version": "2023-06-01", "content-type": "application/json"},
            json=body)
        r.raise_for_status()
        data = r.json()
    content = data.get("content", [{}])[0].get("text", "")
    return {"id": data.get("id",""), "object": "chat.completion", "model": data.get("model",""),
            "choices": [{"index":0,"message":{"role":"assistant","content":content},"finish_reason":"stop"}],
            "usage": {"prompt_tokens": data.get("usage",{}).get("input_tokens",0),
                      "completion_tokens": data.get("usage",{}).get("output_tokens",0)}}


async def call_with_fallback(payload: dict, task: str = "chat", budget: float = 0.0, require_model: str = "",
                              max_retries: int = 3) -> tuple[dict, str]:
    """自动故障转移调用，返回 (response_dict, alias_used)。

    流程:
      1. Token估算 (hybrid rough+cjk/code correction)
      2. 图片/工具检测
      3. 候选Key过滤 (context/vision/tools)
      4. 按序尝试 → 成功返回 / 失败记录冷却+统计
      5. 全部失败 → 抛出带诊断的 RuntimeError
    """
    rl = get_limiter(); hub = get_hub(); reg = get_registry(); tracker = get_tracker()
    candidates = pick_all(task, require_model=require_model)

    if not candidates:
        raise RuntimeError("token pool 中没有可用的 Key（全部熔断或未配置）")

    # ── 请求特征分析 ──
    msgs = payload.get("messages", [])
    max_out = payload.get("max_tokens", 1024)
    estimated_tokens = estimate_tokens(msgs, max_out)
    has_images = _has_images(payload)
    has_tools  = _has_tools(payload)

    # ── 模型过滤 + 诊断收集 ──
    skipped = SkipReason()
    usable = []
    for pk in candidates:
        reason = _filter_model(pk, payload, estimated_tokens, has_images, has_tools)
        if reason:
            skipped.add(pk.alias, reason)
        else:
            usable.append(pk)

    if not usable:
        diag = skipped.summary()
        raise RuntimeError(
            f"所有 {len(candidates)} 个候选Key均不适合当前请求。\n"
            f"估算tokens: {estimated_tokens}, images={has_images}, tools={has_tools}\n{diag}"
        )

    if skipped:
        log.info("模型过滤: %d 跳过, %d 可用. %s",
                 len(skipped.reasons), len(usable), skipped.summary()[:200])

    # ── 故障转移循环 ──
    last_err = ""

    for pk in usable[:max_retries]:
        start = time.time()
        ttfb_ms = 0.0

        try:
            if pk.provider == "anthropic":
                resp = await _call_anthropic(pk, payload)
            else:
                resp = await _call_openai_compat(pk, payload)

            latency = (time.time() - start) * 1000
            tokens = resp.get("usage", {}).get("total_tokens", 0)
            out_tokens = resp.get("usage", {}).get("completion_tokens", 0)
            cost = tokens / 1000 * pk.cost_per_1k

            # TTFB 估算（非流式时用 latency/2 近似）
            ttfb_ms = latency * 0.3  # 非流式近似

            rl.clear_cooldown(pk.alias, pk.provider, pk.model)
            rl.record_request(pk.alias, pk.provider, pk.model)
            rl.record_tokens(pk.alias, tokens, pk.provider, pk.model)
            hub.record(pk.alias, latency, tokens, cost, True)
            tracker.record(pk.alias, ttfb_ms, latency, out_tokens, True, streaming=False)
            reg.update_stat(pk.alias, "working", latency, tokens, cost, True)
            log.info("✅ %s %.0fms %dt est=%d", pk.alias, latency, tokens, estimated_tokens)
            return resp, pk.alias

        except Exception as e:
            latency = (time.time() - start) * 1000
            last_err = _redact_key(str(e)[:200])
            status_code = _extract_status(e)

            rl.set_cooldown(pk.alias, pk.provider, pk.model, status_code=status_code)
            rl.record_request(pk.alias, pk.provider, pk.model)
            hub.record(pk.alias, latency, 0, 0, False)
            tracker.record(pk.alias, 0, latency, 0, False, streaming=False)
            reg.update_stat(pk.alias, "failed", latency, 0, 0, False, last_err)

            # 自学习limit
            rl.learn_from_error(pk.alias, pk.provider, pk.model, last_err)

            log.warning("❌ %s status=%d: %s", pk.alias, status_code, last_err[:80])

    diag = skipped.summary()
    raise RuntimeError(
        _redact_key(f"所有 {min(max_retries, len(usable))} 个候选Key均调用失败。\n"
        f"最后错误: {last_err}\n{diag}"))


def _extract_status(exc: Exception) -> int:
    """从异常中提取HTTP状态码"""
    s = str(exc)
    if "402" in s or "Payment Required" in s: return 402
    if "403" in s or "Forbidden" in s: return 403
    if "429" in s: return 429
    return 429

"""MBclaw 智能评分引擎 — 多维度模型能力匹配 + 运行时性能加权

评分优先级（6级）：
  1. Circuit      — 熔断直接跳过
  2. RateLimit    — 429限制直接跳过
  3. Quota        — 超过共享比例直接跳过
  4. Capability   — 任务匹配度 (intelligence/coding/reasoning/vision/...)
  5. Reliability  — Beta后验成功率
  6. Speed        — TTFB + tok/s (缺时降级为TTFB+latency)

所有得分归一化到 [0, 1]，凸组合（权重求和=1）。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Optional


# ═══════════════════════════════════════════════════════════════════════════════
# 模型能力画像
# ═══════════════════════════════════════════════════════════════════════════════

@dataclass
class ModelCapability:
    """ProviderKey 的模型能力画像，后续 Scheduler 直接靠它选模型"""
    intelligence: int = 3      # 综合能力 1~5
    coding: int = 0            # 编程 0~5
    reasoning: int = 0         # 推理 0~5
    vision: int = 0            # 视觉 0~5
    search: int = 0            # 搜索 0~5
    planning: int = 0          # 规划 0~5
    tool_use: int = 0          # 工具调用 0~5
    context: int = 4096        # 最大上下文窗口 (tokens)
    speed: int = 3             # 速度评级 1~5 (5最快)
    reliability: float = 0.95  # 静态可靠性基线 [0,1]

    def for_task(self, task: str) -> float:
        """任务→能力匹配得分 [0,1]"""
        if task in ("code", "coding", "programming"):
            dims = [self.coding, self.reasoning, self.intelligence]
        elif task in ("reasoning", "think", "analyze"):
            dims = [self.reasoning, self.intelligence, self.planning]
        elif task in ("vision", "image", "screenshot"):
            dims = [self.vision, self.intelligence]
        elif task in ("search", "research", "web"):
            dims = [self.search, self.intelligence, self.tool_use]
        elif task in ("planning", "plan", "orchestrate"):
            dims = [self.planning, self.reasoning, self.tool_use]
        elif task in ("tool", "function_call", "agent"):
            dims = [self.tool_use, self.planning, self.reasoning]
        elif task in ("cheap", "bulk", "simple"):
            dims = [self.intelligence]  # 只用综合能力
        else:  # chat / any
            dims = [self.intelligence, self.reasoning, self.coding]

        active = [d / 5.0 for d in dims if d > 0]
        return sum(active) / len(active) if active else 0.5

    def can_handle_context(self, estimated_tokens: int) -> bool:
        return self.context >= estimated_tokens


# ── 模型别名映射（模糊匹配） ──────────────────────────────────────────────────
_MODEL_ALIASES: Dict[str, str] = {}

def _alias(*names: str):
    """注册别名组：第一个是规范名，其余都是别名"""
    canonical = names[0]
    for n in names:
        _MODEL_ALIASES[n.lower()] = canonical


# ── 内置模型画像（覆盖约90%常用模型） ─────────────────────────────────────────
BUILTIN_CAPABILITIES: Dict[str, ModelCapability] = {}

def _reg(cap: ModelCapability, *names: str):
    """注册模型画像 + 别名"""
    canonical = names[0]
    BUILTIN_CAPABILITIES[canonical] = cap
    _alias(*names)


# === OpenAI ===
_reg(ModelCapability(intelligence=5, coding=5, reasoning=5, vision=4, search=3, planning=5, tool_use=5, context=128000, speed=3, reliability=0.98),
     "gpt-4o", "gpt-4o-2024-08-06", "gpt-4o-latest", "chatgpt-4o-latest")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=3, search=2, planning=3, tool_use=4, context=128000, speed=4, reliability=0.97),
     "gpt-4o-mini", "gpt-4o-mini-2024-07-18")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=3, search=3, planning=4, tool_use=4, context=128000, speed=3, reliability=0.96),
     "gpt-4-turbo", "gpt-4-turbo-2024-04-09", "gpt-4-turbo-preview", "gpt-4-1106-preview", "gpt-4-0125-preview")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=2, search=2, planning=3, tool_use=3, context=8192, speed=3, reliability=0.96),
     "gpt-4", "gpt-4-0613")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=0, search=1, planning=2, tool_use=2, context=4096, speed=5, reliability=0.95),
     "gpt-3.5-turbo", "gpt-3.5-turbo-0125", "gpt-3.5-turbo-1106", "gpt-3.5-turbo-0613")
# o-series
_reg(ModelCapability(intelligence=5, coding=5, reasoning=5, vision=3, search=3, planning=5, tool_use=4, context=200000, speed=1, reliability=0.99),
     "o1", "o1-preview", "o1-mini")
_reg(ModelCapability(intelligence=5, coding=5, reasoning=5, vision=3, search=3, planning=4, tool_use=4, context=200000, speed=2, reliability=0.98),
     "o3-mini", "o3")
_reg(ModelCapability(intelligence=5, coding=5, reasoning=5, vision=4, search=4, planning=5, tool_use=5, context=200000, speed=3, reliability=0.99),
     "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano")

# === Anthropic ===
_reg(ModelCapability(intelligence=5, coding=5, reasoning=5, vision=4, search=3, planning=5, tool_use=5, context=200000, speed=3, reliability=0.98),
     "claude-sonnet-4-6", "claude-sonnet-4", "claude-sonnet-4-20250514", "claude-4-sonnet")
_reg(ModelCapability(intelligence=5, coding=5, reasoning=5, vision=5, search=4, planning=5, tool_use=5, context=200000, speed=2, reliability=0.99),
     "claude-opus-4", "claude-opus-4-20250514", "claude-4-opus")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=3, vision=3, search=2, planning=3, tool_use=4, context=200000, speed=5, reliability=0.97),
     "claude-haiku-3.5", "claude-3.5-haiku", "claude-3-5-haiku-20241022")
_reg(ModelCapability(intelligence=4, coding=5, reasoning=4, vision=4, search=3, planning=4, tool_use=4, context=200000, speed=3, reliability=0.97),
     "claude-3.5-sonnet", "claude-3-5-sonnet-20241022", "claude-3-5-sonnet-20240620")
_reg(ModelCapability(intelligence=5, coding=4, reasoning=5, vision=4, search=3, planning=4, tool_use=4, context=200000, speed=2, reliability=0.97),
     "claude-3-opus", "claude-3-opus-20240229")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=0, search=1, planning=2, tool_use=2, context=200000, speed=5, reliability=0.95),
     "claude-3-haiku", "claude-3-haiku-20240307")

# === DeepSeek ===
_reg(ModelCapability(intelligence=4, coding=5, reasoning=4, vision=0, search=2, planning=3, tool_use=3, context=65536, speed=4, reliability=0.96),
     "deepseek-chat", "deepseek-v3", "deepseek-v3-0324")
_reg(ModelCapability(intelligence=5, coding=4, reasoning=5, vision=0, search=2, planning=4, tool_use=2, context=65536, speed=2, reliability=0.96),
     "deepseek-reasoner", "deepseek-r1", "deepseek-r1-0528")
_reg(ModelCapability(intelligence=4, coding=5, reasoning=3, vision=0, search=1, planning=2, tool_use=2, context=16384, speed=4, reliability=0.94),
     "deepseek-coder", "deepseek-coder-v2")

# === Qwen / DashScope ===
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=2, search=3, planning=3, tool_use=3, context=32768, speed=4, reliability=0.95),
     "qwen-plus", "qwen-plus-latest")
_reg(ModelCapability(intelligence=5, coding=4, reasoning=5, vision=3, search=3, planning=4, tool_use=4, context=32768, speed=3, reliability=0.96),
     "qwen-max", "qwen-max-latest", "qwen-max-longcontext")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=1, search=2, planning=2, tool_use=2, context=8192, speed=5, reliability=0.94),
     "qwen-turbo", "qwen-turbo-latest")
# Qwen VL
_reg(ModelCapability(intelligence=4, coding=3, reasoning=3, vision=5, search=2, planning=3, tool_use=2, context=32768, speed=3, reliability=0.94),
     "qwen-vl-plus", "qwen-vl-plus-latest")
_reg(ModelCapability(intelligence=5, coding=4, reasoning=4, vision=5, search=3, planning=4, tool_use=3, context=32768, speed=2, reliability=0.95),
     "qwen-vl-max", "qwen-vl-max-latest")
# Qwen Coder
_reg(ModelCapability(intelligence=4, coding=5, reasoning=4, vision=0, search=2, planning=3, tool_use=3, context=32768, speed=4, reliability=0.94),
     "qwen-coder-plus", "qwen-coder-plus-latest", "qwen2.5-coder-32b-instruct")
# Qwen THINKING
_reg(ModelCapability(intelligence=5, coding=4, reasoning=5, vision=0, search=2, planning=4, tool_use=2, context=32768, speed=2, reliability=0.95),
     "qwen3", "qwen3-235b-a22b", "qwq-32b", "qwq-plus", "qwen-thinking")

# === Google / Gemini ===
_reg(ModelCapability(intelligence=5, coding=5, reasoning=5, vision=5, search=5, planning=5, tool_use=4, context=1048576, speed=3, reliability=0.97),
     "gemini-2.5-pro", "gemini-2.5-pro-exp-03-25", "gemini-2.5-pro-preview-03-25")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=4, search=4, planning=3, tool_use=3, context=1048576, speed=5, reliability=0.96),
     "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-flash-preview-04-17")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=3, vision=4, search=3, planning=3, tool_use=3, context=1048576, speed=4, reliability=0.96),
     "gemini-2.0-flash", "gemini-2.0-flash-001", "gemini-2.0-flash-lite-001")
_reg(ModelCapability(intelligence=4, coding=3, reasoning=4, vision=3, search=3, planning=3, tool_use=3, context=2097152, speed=3, reliability=0.95),
     "gemini-1.5-pro", "gemini-1.5-pro-002")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=2, search=2, planning=2, tool_use=2, context=1048576, speed=5, reliability=0.94),
     "gemini-1.5-flash", "gemini-1.5-flash-002", "gemini-1.5-flash-8b")

# === Groq ===
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=0, search=1, planning=3, tool_use=3, context=8192, speed=5, reliability=0.93),
     "llama3-70b-8192", "llama-3.1-70b-versatile", "llama-3.3-70b-versatile")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=3, vision=0, search=1, planning=2, tool_use=2, context=8192, speed=5, reliability=0.92),
     "llama3-8b-8192", "llama-3.1-8b-instant")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=0, search=1, planning=3, tool_use=3, context=32768, speed=5, reliability=0.93),
     "mixtral-8x7b-32768")
_reg(ModelCapability(intelligence=4, coding=5, reasoning=3, vision=0, search=1, planning=2, tool_use=2, context=32768, speed=5, reliability=0.92),
     "deepseek-r1-distill-llama-70b", "deepseek-r1-distill-qwen-32b")

# === Mistral ===
_reg(ModelCapability(intelligence=5, coding=5, reasoning=4, vision=2, search=2, planning=4, tool_use=4, context=32768, speed=3, reliability=0.95),
     "mistral-large", "mistral-large-latest", "mistral-large-2407", "mistral-large-2411")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=3, vision=1, search=1, planning=2, tool_use=2, context=32768, speed=4, reliability=0.94),
     "mistral-medium", "mistral-medium-latest", "mistral-medium-2312")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=0, search=1, planning=2, tool_use=2, context=32768, speed=5, reliability=0.93),
     "mistral-small", "mistral-small-latest", "mistral-small-2402")
_reg(ModelCapability(intelligence=3, coding=4, reasoning=3, vision=0, search=1, planning=2, tool_use=2, context=32768, speed=4, reliability=0.93),
     "codestral", "codestral-latest", "codestral-2405", "codestral-mamba")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=3, vision=0, search=1, planning=2, tool_use=2, context=131072, speed=4, reliability=0.93),
     "mistral-nemo", "mistral-nemo-2407", "open-mistral-nemo")

# === Cohere ===
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=0, search=3, planning=3, tool_use=3, context=128000, speed=3, reliability=0.94),
     "command-r-plus", "command-r-plus-08-2024", "command-r7b-12-2024")
_reg(ModelCapability(intelligence=4, coding=3, reasoning=3, vision=0, search=2, planning=2, tool_use=2, context=128000, speed=4, reliability=0.93),
     "command-r", "command-r-03-2024", "command")
_reg(ModelCapability(intelligence=3, coding=2, reasoning=2, vision=0, search=1, planning=2, tool_use=1, context=4096, speed=5, reliability=0.92),
     "command-light", "command-nightly")

# === Zhipu / GLM ===
_reg(ModelCapability(intelligence=5, coding=5, reasoning=5, vision=4, search=4, planning=4, tool_use=5, context=128000, speed=3, reliability=0.95),
     "glm-4", "glm-4-plus", "glm-4-0520", "glm-4-air", "glm-4-airx")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=3, vision=3, search=2, planning=3, tool_use=3, context=128000, speed=5, reliability=0.94),
     "glm-4-flash", "glm-4-flashx", "glm-4-flash-202405")
_reg(ModelCapability(intelligence=4, coding=3, reasoning=3, vision=5, search=2, planning=2, tool_use=2, context=16384, speed=3, reliability=0.93),
     "glm-4v", "glm-4v-plus", "glm-4v-flash")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=1, search=1, planning=1, tool_use=1, context=128000, speed=4, reliability=0.91),
     "glm-4-long", "glm-4-128k")

# === Moonshot / Kimi ===
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=1, search=3, planning=3, tool_use=3, context=131072, speed=3, reliability=0.94),
     "moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "moonshot-v1-auto")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=2, search=3, planning=4, tool_use=3, context=131072, speed=3, reliability=0.94),
     "kimi-latest", "kimi-thinking")

# === 01.AI / Yi ===
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=0, search=2, planning=3, tool_use=3, context=32768, speed=3, reliability=0.93),
     "yi-large", "yi-large-turbo", "yi-large-fc")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=3, vision=0, search=1, planning=2, tool_use=2, context=16384, speed=4, reliability=0.92),
     "yi-medium", "yi-medium-200k")
_reg(ModelCapability(intelligence=3, coding=4, reasoning=3, vision=2, search=1, planning=2, tool_use=2, context=16384, speed=4, reliability=0.91),
     "yi-vision", "yi-vl-plus")

# === ByteDance / Doubao ===
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=3, search=3, planning=3, tool_use=3, context=32768, speed=4, reliability=0.93),
     "doubao-pro-32k", "doubao-pro-128k", "doubao-1.5-pro-32k", "doubao-1.5-pro-256k")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=1, search=1, planning=2, tool_use=2, context=32768, speed=5, reliability=0.92),
     "doubao-lite-32k", "doubao-lite-128k", "doubao-1.5-lite-32k")
_reg(ModelCapability(intelligence=4, coding=3, reasoning=3, vision=5, search=2, planning=2, tool_use=2, context=16384, speed=3, reliability=0.91),
     "doubao-vision-pro-32k", "doubao-1.5-vision-pro-32k")

# === OpenRouter / 免费模型 ===
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=1, search=2, planning=3, tool_use=3, context=32768, speed=3, reliability=0.90),
     "openrouter/auto", "openrouter-auto")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=0, search=1, planning=2, tool_use=2, context=4096, speed=5, reliability=0.88),
     "openchat-3.5", "nous-hermes", "mythomax", "zephyr-7b-beta")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=3, vision=0, search=1, planning=3, tool_use=3, context=32768, speed=3, reliability=0.90),
     "nous-hermes-2-mixtral", "wizardlm-2-8x22b", "dbrx-instruct")
_reg(ModelCapability(intelligence=3, coding=5, reasoning=3, vision=0, search=1, planning=2, tool_use=2, context=16384, speed=4, reliability=0.90),
     "deepseek-coder-v2-lite", "codellama-70b", "phind-codellama-34b")

# === NVIDIA ===
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=0, search=2, planning=3, tool_use=3, context=32768, speed=4, reliability=0.93),
     "nvidia/llama-3.1-nemotron-70b", "nemotron-4-340b", "nvidia-nemotron")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=0, search=1, planning=2, tool_use=2, context=8192, speed=5, reliability=0.91),
     "nvidia/llama-3.1-nemotron-8b")

# === Cloudflare Workers AI ===
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=0, search=1, planning=2, tool_use=2, context=4096, speed=5, reliability=0.88),
     "@cf/meta/llama-3.1-8b-instruct", "@cf/meta/llama-3-8b-instruct")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=0, search=1, planning=2, tool_use=2, context=8192, speed=4, reliability=0.89),
     "@cf/mistral/mistral-7b-instruct-v0.2", "@cf/deepseek-ai/deepseek-math-7b-instruct")

# === Local / Ollama ===
_reg(ModelCapability(intelligence=3, coding=3, reasoning=3, vision=0, search=1, planning=2, tool_use=2, context=8192, speed=5, reliability=0.85),
     "llama3", "llama3.1", "llama3.1:8b", "llama3:8b")
_reg(ModelCapability(intelligence=4, coding=4, reasoning=4, vision=0, search=1, planning=3, tool_use=3, context=32768, speed=3, reliability=0.86),
     "llama3.1:70b", "llama3:70b")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=0, search=1, planning=2, tool_use=2, context=32768, speed=4, reliability=0.83),
     "mistral", "mistral:7b", "mistral:latest")
_reg(ModelCapability(intelligence=4, coding=3, reasoning=3, vision=0, search=1, planning=2, tool_use=2, context=32768, speed=3, reliability=0.84),
     "mixtral", "mixtral:8x7b", "mixtral:latest")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=1, search=1, planning=2, tool_use=2, context=4096, speed=5, reliability=0.82),
     "phi3", "phi3:mini", "phi3:3.8b", "phi-3-mini")
_reg(ModelCapability(intelligence=3, coding=4, reasoning=2, vision=0, search=1, planning=2, tool_use=2, context=16384, speed=4, reliability=0.82),
     "codellama", "codellama:7b", "codellama:13b", "codellama:34b")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=0, search=1, planning=2, tool_use=2, context=4096, speed=4, reliability=0.83),
     "gemma2", "gemma2:9b", "gemma2:27b", "gemma:2b", "gemma:7b")

# === MiClaw / 免费代理 ===
_reg(ModelCapability(intelligence=3, coding=3, reasoning=3, vision=2, search=1, planning=2, tool_use=2, context=32768, speed=2, reliability=0.80),
     "miclaw", "mimo-pro", "xiaomi/mimo-pro", "mimo-v2.5-pro")
_reg(ModelCapability(intelligence=3, coding=3, reasoning=2, vision=1, search=1, planning=2, tool_use=2, context=16384, speed=3, reliability=0.78),
     "miclaw-flash", "mimo-flash", "mimo-v2.5-flash")

# === Custom / Unknown ===
_reg(ModelCapability(intelligence=2, coding=1, reasoning=1, vision=0, search=0, planning=1, tool_use=1, context=4096, speed=3, reliability=0.70),
     "custom", "unknown", "default")


# ═══════════════════════════════════════════════════════════════════════════════
# 运行时指标（100次滑动窗口平均）
# ═══════════════════════════════════════════════════════════════════════════════

@dataclass
class RuntimeMetrics:
    """单个 ProviderKey 的运行时指标，100次滑动窗口"""
    ttfb_ms: float = 0.0
    latency_ms: float = 0.0
    output_tokens: int = 0
    tokens_per_second: Optional[float] = None  # None=无流式数据
    success_rate: float = 1.0
    sample_count: int = 0


class MetricsTracker:
    """每个 alias 独立维护最近100次调用的滑动窗口"""
    MAX_SAMPLES = 100

    def __init__(self):
        self._samples: Dict[str, List[dict]] = {}  # alias → [sample, ...]

    def record(self, alias: str, ttfb_ms: float, latency_ms: float,
               output_tokens: int, success: bool, streaming: bool = False):
        if alias not in self._samples:
            self._samples[alias] = []
        buf = self._samples[alias]
        buf.append({
            "ttfb_ms": ttfb_ms, "latency_ms": latency_ms,
            "output_tokens": output_tokens, "success": success,
            "streaming": streaming,
        })
        if len(buf) > self.MAX_SAMPLES:
            buf.pop(0)

    def get(self, alias: str) -> RuntimeMetrics:
        buf = self._samples.get(alias, [])
        if not buf:
            return RuntimeMetrics()

        n = len(buf)
        successes = [s for s in buf if s["success"]]
        avg = lambda key: sum(s[key] for s in buf) / n if n else 0.0

        # tok/s: 有流式数据才计算
        stream_samples = [s for s in successes if s["streaming"] and s["output_tokens"] > 0]
        if stream_samples:
            tok_per_sec = sum(
                s["output_tokens"] / max(0.001, (s["latency_ms"] - s["ttfb_ms"]) / 1000)
                for s in stream_samples
            ) / len(stream_samples)
        else:
            tok_per_sec = None

        return RuntimeMetrics(
            ttfb_ms=avg("ttfb_ms"),
            latency_ms=avg("latency_ms"),
            output_tokens=int(avg("output_tokens")),
            tokens_per_second=tok_per_sec,
            success_rate=len(successes) / n if n else 1.0,
            sample_count=n,
        )


_tracker: Optional[MetricsTracker] = None


def get_tracker() -> MetricsTracker:
    global _tracker
    if _tracker is None:
        _tracker = MetricsTracker()
    return _tracker


# ═══════════════════════════════════════════════════════════════════════════════
# Provider 状态（Guard 用）
# ═══════════════════════════════════════════════════════════════════════════════

@dataclass
class ProviderState:
    rate_limited_until: float = 0.0    # 429冷却截止时间戳
    failure_count: int = 0
    success_rate: float = 1.0
    retry_after: float = 0.0           # 上游返回的 Retry-After 秒数


@dataclass
class ProviderQuota:
    yesterday_token_usage: int = 0
    share_percent: float = 0.0         # 管理员设置的共享比例 (0.0~1.0)
    today_quota: int = 0               # yesterday × share_percent
    today_used: int = 0
    remaining_tokens: int = 0

    def is_exhausted(self) -> bool:
        return self.today_quota > 0 and self.today_used >= self.today_quota


# ═══════════════════════════════════════════════════════════════════════════════
# 评分函数
# ═══════════════════════════════════════════════════════════════════════════════

# ── 可靠性：Beta 后验期望 ─────────────────────────────────────────────────────
PRIOR_SUCCESS = 1
PRIOR_FAILURE = 1


def reliability_score(success_count: int, fail_count: int) -> float:
    """Beta(α,β) 期望值 ∈ [0,1] — 无数据时 0.5 (均匀先验)"""
    alpha = max(0, success_count) + PRIOR_SUCCESS
    beta  = max(0, fail_count) + PRIOR_FAILURE
    return alpha / (alpha + beta)


# ── 速度：TTFB + tok/s 优先，降级为 TTFB + latency ───────────────────────────
TTFB_BEST_MS  = 200    # ≤此值满分
TTFB_WORST_MS = 8000   # ≥此值零分
TOKPS_BEST    = 80     # tok/s ≥此值满分
TOKPS_WORST   = 2      # tok/s ≤此值零分
LATENCY_BEST_MS  = 500
LATENCY_WORST_MS = 30000


def _ramp(value: float, best: float, worst: float) -> float:
    """线性映射到 [0,1]: best=1.0, worst=0.0"""
    if value <= best: return 1.0
    if value >= worst: return 0.0
    return 1.0 - (value - best) / (worst - best)


def speed_score(metrics: RuntimeMetrics) -> float:
    """计算速度得分。
    优先: 0.5×ttfb + 0.5×tokps
    降级(tokps=None): 0.5×ttfb + 0.5×latency
    无数据: 0.6 (乐观先验，鼓励探索)
    """
    if metrics.sample_count == 0:
        return 0.6

    ttfb = _ramp(metrics.ttfb_ms, TTFB_BEST_MS, TTFB_WORST_MS)

    if metrics.tokens_per_second is not None:
        tokps = _ramp(metrics.tokens_per_second, TOKPS_BEST, TOKPS_WORST)
        return 0.5 * ttfb + 0.5 * tokps
    else:
        lat = _ramp(metrics.latency_ms, LATENCY_BEST_MS, LATENCY_WORST_MS)
        return 0.5 * ttfb + 0.5 * lat


# ── 能力匹配 ──────────────────────────────────────────────────────────────────

def capability_score(alias: str, task: str) -> float:
    """查内置画像表+别名映射，返回任务匹配度。无画像时返回 0.5 中性分"""
    key = alias.lower()
    # 1. 精确匹配
    cap = BUILTIN_CAPABILITIES.get(key)
    # 2. 别名匹配
    if cap is None:
        canonical = _MODEL_ALIASES.get(key)
        if canonical:
            cap = BUILTIN_CAPABILITIES.get(canonical)
    # 3. 子串模糊匹配（兜底）
    if cap is None:
        for k, v in BUILTIN_CAPABILITIES.items():
            if k in key or key in k:
                cap = v
                break
    if cap is None:
        return 0.5
    return cap.for_task(task)


# ── 综合评分 ──────────────────────────────────────────────────────────────────

# 4种预设策略权重 (reliability, speed, capability)
# 所有权重自动归一化
PRESETS = {
    "balanced":  (0.5, 0.25, 0.25),
    "smartest":  (0.35, 0.1,  0.55),
    "fastest":   (0.35, 0.55, 0.1),
    "reliable":  (0.7,  0.15, 0.15),
}

DEFAULT_STRATEGY = "balanced"


def score(
    alias: str,
    task: str = "chat",
    success_count: int = 0,
    fail_count: int = 0,
    strategy: str = DEFAULT_STRATEGY,
) -> float:
    """综合评分 ∈ [0, 1]。

    调用顺序（由外部 scheduler 控制 Guard 优先）：
      Circuit → RateLimit → Quota → Capability → Reliability → Speed

    本函数只算后三项（Capability + Reliability + Speed）的加权和。
    """
    weights = PRESETS.get(strategy, PRESETS[DEFAULT_STRATEGY])
    w_rel, w_spd, w_cap = weights

    rel  = reliability_score(success_count, fail_count)
    spd  = speed_score(get_tracker().get(alias))
    cap  = capability_score(alias, task)

    total = w_rel * rel + w_spd * spd + w_cap * cap
    return round(total, 4)


def score_batch(
    aliases: list[str],
    task: str = "chat",
    stats: dict = None,  # {alias: (success_count, fail_count)}
    strategy: str = DEFAULT_STRATEGY,
) -> list[tuple[str, float]]:
    """批量评分，返回 [(alias, score), ...] 降序"""
    stats = stats or {}
    tracker = get_tracker()
    results = []
    for alias in aliases:
        sc, fc = stats.get(alias, (0, 0))
        s = score(alias, task, sc, fc, strategy)
        results.append((alias, s))
    results.sort(key=lambda x: -x[1])
    return results

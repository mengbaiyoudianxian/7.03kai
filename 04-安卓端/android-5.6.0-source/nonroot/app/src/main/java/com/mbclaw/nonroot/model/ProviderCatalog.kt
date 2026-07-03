package com.mbclaw.nonroot.model

/**
 * 大模型提供商目录 — 内置 25 个国内外主流 API
 *
 * 覆盖 90% 的 OpenAI 兼容接口
 * 用户只需要：选提供商 → 填 Key → 选模型
 */

data class LLMProvider(
    val id: String,              // 唯一标识
    val name: String,            // 显示名称
    val baseUrl: String,         // API 地址
    val models: List<String>,    // 推荐模型列表
    val region: String,          // 地区: CN/INTL
    val free: Boolean = false,   // 是否有免费额度
    val notes: String = "",      // 备注
    val protocol: String = "openai",  // openai / anthropic
)

data class ProviderCategory(
    val title: String,
    val providers: List<LLMProvider>,
)

/**
 * 内置提供商目录
 */
object ProviderCatalog {

    val all: List<LLMProvider> = listOf(
        // ═══════ 国际 ═══════
        LLMProvider(
            id = "openai", name = "OpenAI", baseUrl = "https://api.openai.com",
            region = "INTL",
            models = listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo"),
            notes = "全球最主流，需要外币卡或第三方充值",
        ),
        LLMProvider(
            id = "anthropic", name = "Anthropic Claude", baseUrl = "https://api.anthropic.com",
            region = "INTL",
            models = listOf("claude-sonnet-4-6", "claude-opus-4-8", "claude-haiku-4-5", "claude-fable-5"),
            notes = "Anthropic 原生协议 · 最強模型 · 需外币卡",
            protocol = "anthropic",
        ),
        LLMProvider(
            id = "google", name = "Google Gemini", baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            region = "INTL",
            models = listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash"),
            notes = "OpenAI 兼容端点 / 有免费额度",
            free = true,
        ),
        LLMProvider(
            id = "groq", name = "Groq", baseUrl = "https://api.groq.com/openai",
            region = "INTL",
            models = listOf("llama-3.3-70b", "mixtral-8x7b", "gemma2-9b-it"),
            notes = "极速推理 / 免费额度充裕",
            free = true,
        ),
        LLMProvider(
            id = "together", name = "Together AI", baseUrl = "https://api.together.xyz",
            region = "INTL",
            models = listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "mistralai/Mixtral-8x22B-Instruct-v0.1"),
            notes = "开源模型托管 / 按量付费",
        ),
        LLMProvider(
            id = "mistral", name = "Mistral AI", baseUrl = "https://api.mistral.ai",
            region = "INTL",
            models = listOf("mistral-large-latest", "mistral-medium-latest", "mistral-small-latest"),
            notes = "欧洲领先 / 多语言优秀",
        ),
        LLMProvider(
            id = "cohere", name = "Cohere", baseUrl = "https://api.cohere.ai",
            region = "INTL",
            models = listOf("command-r-plus", "command-r"),
            notes = "企业级 RAG / 非 OpenAI 兼容",
        ),
        LLMProvider(
            id = "deepseek-intl", name = "DeepSeek (国际)", baseUrl = "https://api.deepseek.com",
            region = "INTL",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            notes = "国产之光 / 性价比极高 / 支持中文母语",
        ),
        LLMProvider(
            id = "deepseek-anthropic", name = "DeepSeek (Anthropic协议)", baseUrl = "https://api.deepseek.com/anthropic",
            region = "INTL",
            models = listOf("deepseek-v4-pro", "deepseek-v4-flash"),
            notes = "新模型 · 兼容 Anthropic 协议 · 更强推理能力",
            protocol = "anthropic",
        ),

        // ═══════ 国内 ═══════
        LLMProvider(
            id = "deepseek-cn", name = "DeepSeek 深度求索", baseUrl = "https://api.deepseek.com",
            region = "CN",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            notes = "国产性价比之王 / 中文顶级 / 10元充够用半年",
        ),
        LLMProvider(
            id = "zhipu", name = "智谱 GLM", baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            region = "CN",
            models = listOf("glm-4-plus", "glm-4-flash", "glm-4v-plus"),
            notes = "清华系 / embedding模型兼容OpenAI / 有免费token",
            free = true,
        ),
        LLMProvider(
            id = "aliyun", name = "阿里云百炼", baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            region = "CN",
            models = listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen-vl-max"),
            notes = "OpenAI兼容 / text-embedding-v3可用 / 新用户免费额度",
            free = true,
        ),
        LLMProvider(
            id = "baidu", name = "百度文心", baseUrl = "https://qianfan.baidubce.com/v2",
            region = "CN",
            models = listOf("ernie-4.0-turbo-8k", "ernie-3.5-8k", "ernie-speed-8k"),
            notes = "非标准OpenAI兼容 / 需特殊适配",
        ),
        LLMProvider(
            id = "moonshot", name = "Moonshot 月之暗面", baseUrl = "https://api.moonshot.cn/v1",
            region = "CN",
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
            notes = "超长上下文 / Kimi同款 / OpenAI兼容",
        ),
        LLMProvider(
            id = "minimax", name = "MiniMax 稀宇", baseUrl = "https://api.minimax.chat/v1",
            region = "CN",
            models = listOf("abab6.5s-chat", "abab6.5t-chat"),
            notes = "海螺AI / OpenAI兼容",
        ),
        LLMProvider(
            id = "bytedance", name = "字节豆包", baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            region = "CN",
            models = listOf("doubao-pro-32k", "doubao-lite-32k"),
            notes = "字节系 / OpenAI兼容 / 企业认证可用",
        ),
        LLMProvider(
            id = "tencent", name = "腾讯混元", baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            region = "CN",
            models = listOf("hunyuan-pro", "hunyuan-standard", "hunyuan-lite"),
            notes = "腾讯系 / OpenAI兼容",
        ),
        LLMProvider(
            id = "xunfei", name = "讯飞星火", baseUrl = "https://spark-api-open.xf-yun.com/v1",
            region = "CN",
            models = listOf("generalv3.5", "generalv3", "4.0Ultra"),
            notes = "语音识别强项 / OpenAI兼容",
        ),
        LLMProvider(
            id = "siliconflow", name = "硅基流动", baseUrl = "https://api.siliconflow.cn/v1",
            region = "CN",
            models = listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct", "meta-llama/Llama-3.3-70B-Instruct"),
            notes = "开源模型集市 / 国产GPU推理 / 价格极低",
        ),
        LLMProvider(
            id = "yi", name = "零一万物", baseUrl = "https://api.lingyiwanwu.com/v1",
            region = "CN",
            models = listOf("yi-large", "yi-medium", "yi-vision"),
            notes = "李开复创办 / OpenAI兼容",
        ),
        LLMProvider(
            id = "baichuan", name = "百川智能", baseUrl = "https://api.baichuan-ai.com/v1",
            region = "CN",
            models = listOf("baichuan4", "baichuan3-turbo"),
            notes = "王小川创办 / OpenAI兼容",
        ),
        LLMProvider(
            id = "stepfun", name = "阶跃星辰", baseUrl = "https://api.stepfun.com/v1",
            region = "CN",
            models = listOf("step-2-16k", "step-1-8k", "step-1v-8k"),
            notes = "多模态强 / OpenAI兼容",
        ),
        LLMProvider(
            id = "mimo", name = "小米 MiMo", baseUrl = "https://token-plan-sgp.xiaomimimo.com/v1",
            region = "CN",
            models = listOf("mimo-v2.5-pro", "mimo-v2.5-flash"),
            notes = "820亿token免费 / 需小米账号 / 你的主力模型",
        ),

        // ═══════ 代理/中转 ═══════
        LLMProvider(
            id = "openrouter", name = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1",
            region = "INTL",
            models = listOf("openai/gpt-4o", "anthropic/claude-sonnet-4", "google/gemini-2.5-pro", "deepseek/deepseek-chat"),
            notes = "一站式调用所有模型 / OpenAI兼容 / 支持国内支付",
        ),
        LLMProvider(
            id = "api2d", name = "API2D", baseUrl = "https://openai.api2d.net/v1",
            region = "CN",
            models = listOf("gpt-4o-mini", "claude-3.5-sonnet", "deepseek-chat"),
            notes = "国内中转 / 无需翻墙调OpenAI / 微信支付",
        ),
        // 新增 ↓
        LLMProvider(
            id = "claude-subscription", name = "Claude (订阅 Pro/Max)", baseUrl = "https://api.anthropic.com",
            region = "INTL",
            models = listOf("claude-sonnet-4-6", "claude-opus-4-8", "claude-haiku-4-5", "claude-fable-5"),
            notes = "Pro/Max 订阅版有月度配额 · 比按量便宜",
            protocol = "anthropic",
        ),
        LLMProvider(
            id = "chatgpt-plus", name = "ChatGPT (Plus 订阅)", baseUrl = "https://api.openai.com",
            region = "INTL",
            models = listOf("gpt-4o", "gpt-4o-mini", "o1-preview", "o1-mini"),
            notes = "Plus 订阅 $20/月, 也可单独按量买 API",
        ),
        LLMProvider(
            id = "ooapi", name = "OOAPI (Claude 中转)", baseUrl = "https://api.ooapi.cc/v1",
            region = "INTL",
            models = listOf("claude-opus-4-7", "claude-sonnet-4-7", "claude-haiku-4-5"),
            notes = "Claude 国内中转, 无需外币卡",
        ),
        LLMProvider(
            id = "tu-zi", name = "兔子 API", baseUrl = "https://api.tu-zi.com/v1",
            region = "CN",
            models = listOf("gpt-4o", "claude-3.5-sonnet", "gemini-2.5-pro", "deepseek-chat"),
            notes = "国内热门中转 / 微信支付 / 模型多",
        ),
        LLMProvider(
            id = "siliconflow-intl", name = "SiliconFlow (国际)", baseUrl = "https://api.siliconflow.cn/v1",
            region = "INTL",
            models = listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct", "meta-llama/Llama-3.3-70B-Instruct"),
            notes = "硅基流动 国际版 / 部分开源模型限免",
            free = true,
        ),
        LLMProvider(
            id = "fireworks", name = "Fireworks AI", baseUrl = "https://api.fireworks.ai/inference/v1",
            region = "INTL",
            models = listOf("accounts/fireworks/models/llama-v3p3-70b-instruct", "accounts/fireworks/models/deepseek-v3"),
            notes = "高性能开源推理 / OpenAI 兼容",
        ),
        LLMProvider(
            id = "perplexity", name = "Perplexity Sonar", baseUrl = "https://api.perplexity.ai",
            region = "INTL",
            models = listOf("llama-3.1-sonar-large-128k-online", "llama-3.1-sonar-small-128k-online"),
            notes = "联网搜索 + LLM / OpenAI 兼容",
        ),
        LLMProvider(
            id = "xai", name = "xAI Grok", baseUrl = "https://api.x.ai/v1",
            region = "INTL",
            models = listOf("grok-beta", "grok-vision-beta"),
            notes = "马斯克 / OpenAI 兼容",
        ),
        LLMProvider(
            id = "custom", name = "自定义 (OpenAI兼容)", baseUrl = "",
            region = "INTL",
            models = listOf(""),
            notes = "填入任意 OpenAI 兼容的 API 地址和模型名",
        ),
        LLMProvider(
            id = "custom-anthropic", name = "自定义 (Anthropic协议)", baseUrl = "",
            region = "INTL",
            models = listOf(""),
            notes = "填入任意 Anthropic 兼容的 API 地址 · 如 DeepSeek Anthropic 端点",
            protocol = "anthropic",
        ),
    )

    val international = all.filter { it.region == "INTL" }
    val china = all.filter { it.region == "CN" }
    val withFreeTier = all.filter { it.free }
    val anthropicProtocol = all.filter { it.protocol == "anthropic" }
    val custom = all.filter { it.id == "custom" || it.id == "custom-anthropic" }

    fun find(id: String): LLMProvider? = all.find { it.id == id }
}

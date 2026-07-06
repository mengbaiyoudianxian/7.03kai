# MBclaw Token Pool 重构 — 完整审计报告与架构方案

> **审计日期**: 2026-07-04
> **阶段**: 规划设计阶段（未修改任何代码）
> **审计范围**: Token Pool / MiClaw Bridge / 后端核心 / freellmapi / 控制面板 / Gateway

---

## 一、当前架构全景

### 1.1 服务器拓扑

```
┌────────────────────────────────────────────────────────────────┐
│ 存储机 47.83.2.188 :80 (nginx → :8000 FastAPI)                  │
│ ├── admin/router.py      管理面板 API                            │
│ ├── admin/bridge_manager.py  MiClaw 实例管理                     │
│ ├── app/token_pool.py    旧 Token 池（153行，运行中）             │
│ ├── app/llm.py           LLM 调用客户端                          │
│ ├── app/providers.py     多 Provider 分发                        │
│ └── app/gateway/         Gateway 渠道（空壳）                    │
│                                                                  │
│ nginx: /bridge/miclaw/v1 → 100.126.55.0:8765 (Tailscale)        │
└──────────────────────────┬─────────────────────────────────────┘
                           │ Tailscale
┌──────────────────────────┼─────────────────────────────────────┐
│ 工具池 121.199.57.195 :8765                                     │
│ ├── miclaw_api_bridge (Rust binary)                             │
│ │   ├── OpenAI /v1/chat/completions                             │
│ │   ├── Anthropic /v1/messages                                  │
│ │   ├── 小米 MiClaw 登录/验证码/双因子                           │
│ │   ├── UsageStore (Token 统计 + 窗口图表)                       │
│ │   └── Admin 面板 + API Key 管理                                │
│ └── Token Pool (Docker :8100) — 设计完成，未部署                 │
└────────────────────────────────────────────────────────────────┘
```

### 1.2 Token Pool 的三重存在（问题根源）

| # | 文件位置 | 行数 | 状态 |
|---|---------|------|------|
| 1 | `07-后端核心/app/token_pool.py` | 153 | ⚠️ 运行中（嵌入后端，有 Bug） |
| 2 | `05-Token池/deployed_token_pool.py` | 153 | 📋 与 #1 完全相同（冗余） |
| 3 | `05-Token池/token_pool/` (20 文件) | ~1200 | 🔮 设计完成，从未部署 |

### 1.3 新 Token Pool 设计版架构（优秀，应以此为基础）

```
FastAPI (:8100)
├── /v1/chat/completions     proxy.py      OpenAI 兼容代理
├── /v1/models               模型列表
├── /api/keys/*              keys.py       Key CRUD
├── /api/stats/*             stats.py      统计监控
├── /api/heartbeat           heartbeat.py  用户心跳上报
├── /admin                   admin.py      HTML 管理面板
│
pool/
├── registry.py    SQLite 持久化 + Key CRUD + 内置预设
├── scheduler.py   综合评分排序 + 任务类型路由
├── circuit.py     独立熔断器 (threshold=3, cooldown=60s)
├── metrics.py     5分钟滑动窗口 (RPM/成功率/延迟)
├── health.py      后台定期探活所有 Key
└── caller.py      统一调用器 + OpenAI/Anthropic 转发 + 故障转移
```

---

## 二、严重 Bug

### 🔴 P0

| # | Bug | 位置 | 说明 |
|---|-----|------|------|
| 1 | **`miclaw` 变量名错误** | `app/token_pool.py:122` | `pk = min(miclaw, ...)` → 应为 `pk = min(real_keys, ...)`，未定义变量直接崩溃 |
| 2 | **三重代码冗余** | 三处 TokenPool | 同一逻辑维护三份，版本不一致 |
| 3 | **设计版未部署** | `05-Token池/token_pool/` | ~1200 行优质代码从未上线 |

### 🟡 P1 — 架构缺陷

| # | 问题 |
|---|------|
| 4 | 后端每调用一次 `pick()` 都遍历心跳目录读 JSON，极度低效 |
| 5 | 全局 `_pool` 单例，无用户/租户概念 |
| 6 | MiClaw 只是从 JSON 文件读到的 Key，不是真正的 Provider |
| 7 | 用户 Key 进入池子后无限使用，无共享比例控制 |
| 8 | MiClaw 实例 token 用量是 `时间差/60*500` 估算值，非真实数据 |
| 9 | API Key 明文存储在心跳文件和 SQLite 中 |
| 10 | Stream 模式无故障转移，只选第一个 Key |
| 11 | provider 枚举混乱（miclaw/miclaw-bridge/custom 混用） |
| 12 | MiClaw API 不可达时直接生成假 token 标记已登录 |
| 13 | `app/gateway/` 7个.py 全空壳，从未连线 |
| 14 | `app/mbos_core.py` MBOS 核心从未连线 |

---

## 三、freellmapi 分析

### 项目概览
- **技术栈**: TypeScript (Express.js) + SQLite + React 前端
- **规模**: 18 个 Provider，161 个模型，~90 个测试文件
- **定位**: 免费 LLM API 聚合中转站

### 值得借鉴的核心设计

| 模块 | 借鉴点 | 融合方式 |
|------|--------|----------|
| **Router** (`services/router.ts`) | 多模型链式路由 + 429 动态惩罚 + time-based 衰减恢复 | 替换当前 priority-only 排序 |
| **Scoring** (`services/scoring.ts`) | Thompson Sampling Bandit + 可靠性/速度/智能/余量多维评分 | 全新的 scheduler 核心 |
| **Ratelimit** (`services/ratelimit.ts`) | RPM/RPD/TPM/TPD 四级限制 + cooldown | 替代当前仅熔断的机制 |
| **Fusion** (`services/fusion.ts`) | 多 Provider 结果融合，选最优响应 | 可选高级功能 |
| **Budget** (`lib/budget.ts`) | 月度 Token 预算，超出软限制 | 匹配"共享昨天 N%"需求 |
| **Health** (`services/health.ts`) | 周期性探活 + 自动禁用/恢复 | 当前 health.py 可扩展 |
| **SSRF 防护** (`lib/url-guard.ts`) | URL 安全校验防内网访问 | 安全刚需 |
| **Key 加密** (`lib/crypto.ts`) | AES-256-GCM 加密存储 | 安全刚需 |
| **Analytics** | 按模型/Key/时间窗口完整统计 | 商业化必备 |

### 不适合借鉴的
- Electron Desktop（MBclaw 不需要）
- AI Horde / Cloudflare / Cohere Provider（小众）
- TypeScript Express 架构（MBclaw 已选定 Python FastAPI）

---

## 四、最终推荐架构

```
                        ┌──────────────────┐
                        │  APK / Mother /   │
                        │  控制面板 / QQBot  │
                        └────────┬─────────┘
                                 │ 统一入口 /v1/chat/completions
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Token Pool (:8100)                             │
│                                                                    │
│  ┌─────────────────────────┐  ┌────────────────────────────────┐ │
│  │ 认证层 (X-User-Token)   │  │ 多用户隔离 + 预留 tenant_id     │ │
│  └────────────┬────────────┘  └────────────────────────────────┘ │
│               │                                                    │
│  ┌────────────▼──────────────────────────────────────────────────┐│
│  │  Router（多维度评分路由）                                      ││
│  │  • Reliability (Thompson Sampling)   • Speed (滑动窗口)       ││
│  │  • Cost (价格权重)                   • Capacity (RPM/RPD/TPM) ││
│  │  • Context (上下文窗口匹配)           • Features (Vision/Tools)││
│  └────────────┬──────────────────────────────────────────────────┘│
│               │                                                    │
│  ┌────────────▼──────────────────────────────────────────────────┐│
│  │  Provider Registry                                             ││
│  │  openai | anthropic | deepseek | qwen | gemini | openrouter   ││
│  │  miclaw-proxy ← MiClaw Bridge 作为 Provider                   ││
│  │  user-shared  ← 用户共享 Key 池                                ││
│  │  local (ollama) | custom                                       ││
│  └────────────┬──────────────────────────────────────────────────┘│
│               │                                                    │
│  ┌────────────▼──────────────────────────────────────────────────┐│
│  │  Circuit Breaker + Rate Limiter + Health Check                 ││
│  │  • 独立熔断器 (每 Key)    • RPM/RPD/TPM/TPD 限制               ││
│  │  • 429 动态降权           • 定期探活 + 自动恢复                ││
│  └────────────┬──────────────────────────────────────────────────┘│
│               │                                                    │
│  ┌────────────▼──────────────────────────────────────────────────┐│
│  │  Analytics & Dashboard                                         ││
│  │  • 按用户/Key/模型的统计    • 昨天用量查询 (Mother 共享计算)   ││
│  │  • Token/费用/成功率/延迟   • HTML 管理面板 + REST API         ││
│  └───────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────┘
```

---

## 五、数据模型设计

```python
users:
  id, username, token (鉴权), quota_limit, share_ratio,
  is_admin, created_at

provider_keys:
  id, alias, provider_type, base_url, encrypted_api_key,
  model, cost_per_1k, priority, enabled,
  rpm_limit, rpd_limit, tpm_limit, tpd_limit

miclaw_accounts:
  id, username, encrypted_password, cookie, session_token,
  login_status, verified_at, qps_limit, rpm_limit,
  daily_limit, total_used_today, last_used

user_shared_keys:
  id, user_code, encrypted_api_key, base_url, model, provider,
  yesterday_usage, allowed_ratio, max_borrowable,
  borrowed_today, status, last_tested

call_log:
  id, user_id, key_alias, provider_type, model,
  ts, latency_ms, prompt_tokens, completion_tokens,
  cost, success, error_msg

usage_daily:
  user_id, date, key_alias, total_tokens, total_cost,
  success_count, fail_count, avg_latency_ms
```

---

## 六、分模块 TODO

<details>
<summary><b>📋 Mother（折叠）</b></summary>

1. LLMClient 改为统一调用 Token Pool API（`http://127.0.0.1:8100/v1/chat/completions`）
2. 设置 `X-User-Token` header 进行鉴权和用量归因
3. 删除 `app/token_pool.py`（功能迁移到 Token Pool）
4. 删除 `app/providers.py`（功能迁移到 Token Pool）
5. 新增 `GET /api/mother/yesterday-usage` → 调用 Token Pool 查询昨天用量

</details>

<details>
<summary><b>🖥️ 控制面板（折叠）</b></summary>

1. `/api/token-pool` 系列 API → 代理到 Token Pool 管理 API
2. `/api/miclaw-instances` → 代理到 Token Pool 的 MiClaw 账号管理 API
3. 新 Token Pool Admin Panel 替换旧管理面板中的 Token 标签页

</details>

<details>
<summary><b>📱 APK（折叠）</b></summary>

1. 默认 base_url 改为 Token Pool 地址
2. 心跳上报中的 Key 信息保持不变（用于共享 Key 收集）

</details>

<details>
<summary><b>🔑 Token Pool（展开 — 核心）</b></summary>

### P0（阻塞性）
1. **修复 `miclaw` 变量名 Bug** — `app/token_pool.py:122`
2. **统一代码** — 删除冗余，以 `05-Token池/token_pool/` 为基准

### P1（架构核心）
3. **多用户系统** — users 表 + 注册/登录/Token 鉴权 + 预留多租户
4. **用户共享 Key 管理** — 心跳 Key 自动注册 + 昨天用量查询 + 共享比例熔断
5. **MiClaw 作为 Provider 融合** — 多账号 + QPS/RPM/TPM 限制 + 自动轮询 + 熔断
6. **MiClaw 登录重构** — 管理员面板输入账号密码 + 验证码弹窗 + Cookie 自动维护
7. **四级 Rate Limit** — RPM/RPD/TPM/TPD，用户级/Key级/模型级
8. **API Key 加密存储** — AES-256-GCM，密钥从环境变量读取
9. **完整统计系统** — call_log + usage_daily + 昨天/7天/30天 API

### P2（完善）
10. **Stream 故障转移**
11. **Provider 枚举统一**
12. **多维度 Scoring 系统**（参考 freellmapi）
13. **预算系统**（月度 Token 预算 + 超额软限制）
14. **Dashboard 图表优化**

</details>

<details>
<summary><b>🌉 Gateway（折叠）</b></summary>

1. 所有渠道统一走 Token Pool 作为 LLM 后端

</details>

<details>
<summary><b>🔧 MiClaw Bridge（折叠）</b></summary>

1. Bridge 保持运行，成为 Token Pool 的 Provider 后端
2. 可选新增 API：`/api/usage/today`、`/api/account/multi`

</details>

<details>
<summary><b>📊 Provider 管理（折叠）</b></summary>

1. 所有 Provider 在 `provider_keys` 表统一管理
2. 未来支持：Gemini、OpenRouter、本地模型 (Ollama/vLLM)、自定义 OpenAI 兼容 API

</details>

<details>
<summary><b>📈 Dashboard / Analytics（折叠）</b></summary>

1. Token Pool 自带 HTML Dashboard（基于现有设计完善）
2. 统计维度：按用户/Key/Provider/模型 + 时间窗口

</details>

---

## 七、模块拆分（重构后文件结构）

```
token-pool/
├── main.py                    # FastAPI 入口
├── config.py                  # 配置管理
├── Dockerfile + docker-compose.yml
│
├── pool/
│   ├── registry.py            # Key 注册表（扩展多用户）
│   ├── scheduler.py           # 智能调度器（扩展 Rate Limit）
│   ├── circuit.py             # 熔断器
│   ├── metrics.py             # 实时指标
│   ├── health.py              # 健康检测
│   ├── caller.py              # 统一调用器（添加 Stream 故障转移）
│   ├── scorer.py              # 【新】多维度评分
│   ├── rate_limiter.py        # 【新】四级速率限制
│   ├── user_manager.py        # 【新】多用户管理
│   ├── miclaw_provider.py     # 【新】MiClaw 多账号 Provider
│   ├── user_shared_provider.py # 【新】用户共享 Key Provider
│   └── encryption.py          # 【新】API Key 加密
│
├── routes/
│   ├── proxy.py               # OpenAI 兼容代理
│   ├── keys.py                # Key 管理
│   ├── stats.py               # 统计
│   ├── heartbeat.py           # 心跳
│   ├── admin.py               # 管理面板
│   ├── auth.py                # 【新】用户认证
│   ├── miclaw.py              # 【新】MiClaw 账号管理
│   └── users.py               # 【新】用户管理
│
└── static/
    └── dashboard.html         # 管理面板
```

---

## 八、优先级与实施顺序

| 优先级 | 阶段 | 内容 |
|--------|------|------|
| **P0** | Phase 1 | 修复 miclaw Bug + 统一代码 + API Key 加密 + users 表 |
| **P1** | Phase 2 | 多用户系统 + 共享 Key 管理 + Rate Limit + Scoring |
| **P1** | Phase 3 | MiClaw Provider 融合 + 登录重构 + 多账号管理 |
| **P2** | Phase 4 | Dashboard 重写 + 统计图表 |
| **P3** | Phase 5 | 后端集成 + Nginx 配置 + Docker 部署 + 端到端测试 |

---

## 九、风险分析

| 风险 | 等级 | 应对 |
|------|------|------|
| Token Pool 独立部署后调用中断 | 🔴 高 | 先并行运行，验证后切换 |
| MiClaw Bridge 不响应登录 | 🟡 中 | 保留 fallback，标记不可用而非假 token |
| 用户 Key 泄露 | 🔴 高 | AES-256-GCM 加密，密钥环境变量读取 |
| 共享额度计算不准 | 🟡 中 | 初期手动设置，后期从心跳和 call_log 自动计算 |
| 性能瓶颈 (SQLite) | 🟢 低 | 个人规模可用，预留 PostgreSQL 迁移路径 |

---

> 审计完成，未修改任何代码。待确认架构方案后进入开发阶段。

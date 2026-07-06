# MBclaw 商业化 Token Pool — 长久施工计划

> 生成日期：2026-07-04
> 阶段：规划设计（禁止执行）
> 定位：APK 内置的 AI 中转站，MBclaw 盈利核心

---

## 📋 总览

```
          Token Pool（独立项目，现在做）
               │
     ┌─────────┼─────────┐
     │         │         │
  控制面板   APK端    母体端
  （独立改） （独立改） （独立改）
     │         │         │
     └─────────┼─────────┘
               │
          MiClaw Bridge
          （不改，作为Provider后端）
```

---

<details open>
<summary><h2>🔑 Token Pool 核心（← 现在只做这个）</h2></summary>

### P0 — 修复 & 清理（阻塞性，必须最先做）

- [ ] **修复 `miclaw` 变量名 Bug**
  - 位置：`07-后端核心/app/token_pool.py:122`、`05-Token池/deployed_token_pool.py:122`
  - `pk = min(miclaw, ...)` → `pk = min(real_keys, ...)`
  - 需要配合：❌ 无

- [ ] **删除冗余代码，统一基准**
  - 删除 `07-后端核心/app/token_pool.py`（Mother内嵌旧版）
  - 删除 `05-Token池/deployed_token_pool.py`（冗余备份）
  - 保留 `05-Token池/token_pool/` 作为唯一基准
  - 需要配合：❌ 无

- [ ] **API Key 加密存储**
  - 新建 `pool/encryption.py`：AES-256-GCM 加密/解密
  - 加密密钥从环境变量 `TP_ENCRYPTION_KEY` 读取
  - 参考：freellmapi `lib/crypto.ts`
  - `provider_keys` 表中 api_key 列改为 `encrypted_key + iv + auth_tag`
  - 需要配合：❌ 无

---

### P1 — 多用户系统（商业化的前提）

- [ ] **用户表 + 鉴权系统**
  - 新建 `pool/user_manager.py`：用户注册/登录/Token管理
  - 数据表：`users`（username, token, share_ratio, quota_limit, role, enabled）
  - 所有 `/v1/*` 请求需带 `Authorization: Bearer <user_token>`
  - 管理员通过 `/admin` 面板管理用户
  - 需要配合：
    - 🖥️ 控制面板：管理面板需新增用户管理页
    - 📱 APK：APK 端 API Key 配置页需改为 Token Pool 的 user_token
    - 🧠 母体：Mother 调用 Token Pool 时需传 user_token

- [ ] **用户共享 Key 管理系统**
  - 新建 `pool/user_shared_provider.py`
  - 数据表：`user_shared_keys`（user_code, encrypted_key, yesterday_usage, allowed_ratio, borrowed_today, status）
  - 心跳上报时自动注册用户Key
  - 管理员设置 `share_ratio`（如 5%/10%/20%），`max_borrowable = yesterday_usage * share_ratio`
  - `borrowed_today >= max_borrowable` → 自动熔断该Key
  - 需要配合：
    - 🖥️ 控制面板：管理面板需新增用户Key管理页 + 比例设置
    - 📱 APK：心跳上报中已有的 Key 信息保持不变
    - 🧠 母体：Mother 需提供 `GET /api/mother/yesterday-usage?user_code=xxx` 接口

- [ ] **第一次登录/面板管理流程**
  - 首次启动 Token Pool 时，`users` 表为空
  - 导航到 `/admin` → 检测无管理员 → 弹出首次设置向导：
    1. 设置管理员用户名和密码
    2. 自动生成 admin user_token
    3. 显示 admin user_token（提示保存，只显示一次）
  - 之后用 admin 账号登录 `/admin` 管理面板
  - 需要配合：
    - 🖥️ 控制面板：无（Token Pool 自带独立 Dashboard）

---

### P2 — 智能路由（商业竞争力核心）

- [ ] **Thompson Sampling 多维度评分**
  - 新建 `pool/scorer.py`
  - 三维基础分：reliability（Beta后验采样）+ speed（吞吐+TTFB混合）+ intelligence（Min-Max归一化）
  - 两个 guardrail 乘子：headroomFactor（月度预算剩余保护）+ rateLimitFactor（429动态惩罚）
  - 五种预设策略：balanced / smartest / fastest / reliable / custom
  - 参考：freellmapi `services/scoring.ts`
  - 需要配合：❌ 无

- [ ] **四级 Rate Limit（RPM/RPD/TPM/TPD）**
  - 新建 `pool/rate_limiter.py`
  - 内存滑动窗口 + SQLite 持久化（跨重启不丢失）
  - 三级限制：用户级 → Key级 → 模型级
  - `canMakeRequest()` / `canUseTokens()` 前置检查
  - 429 自动 cooldown：瞬态90s → 连续3次指数退避 → 最多30min
  - `learnLimitFromError()`：从错误消息体解析真实限额
  - 参考：freellmapi `services/ratelimit.ts`
  - 需要配合：❌ 无

- [ ] **重写 Scheduler（调度器）**
  - 重写 `pool/scheduler.py`
  - 用 scorer.py 的评分替换当前简单 priority 排序
  - 支持 task_type 路由（chat/code/cheap/bulk/vision/tools）
  - 支持 context_window 自动过滤
  - 支持 fallback_chain 可配置排序
  - 需要配合：❌ 无

- [ ] **重写 Caller（调用器）**
  - 重写 `pool/caller.py`
  - OpenAI / Anthropic 兼容调用
  - 自动从上游响应提取 `usage`（prompt_tokens/completion_tokens/total_tokens）
  - Stream 模式故障转移（当前只选第一个Key，失败不重试）
  - 每次调用后回调：`recordRequest()` + `recordTokens()` + `logRequest()`
  - 需要配合：❌ 无

---

### P3 — MiClaw 免费代理融合

- [ ] **MiClaw 多账号 Provider**
  - 新建 `pool/miclaw_provider.py`
  - 数据表：`miclaw_accounts`（username, encrypted_password, cookie, session_token, login_status, qps_limit, rpm_limit, tpm_limit, daily_limit, concurrent_limit）
  - HTTP 调用 MiClaw Bridge API（`http://100.126.55.0:8765`）
  - 注册为 `provider=miclaw`，走统一 Router → Scheduler → Caller 流程
  - 需要配合：
    - 🔧 MiClaw Bridge：保持运行，作为 Provider 后端

- [ ] **MiClaw 登录面板（内嵌在 Token Pool Dashboard）**
  - 管理员在 Dashboard 点击"添加 MiClaw 账号"
  - 输入：小米账号 + 密码
  - 如需验证码 → 调用 Bridge `/api/auth/login` → 获取验证码图片 → 弹窗显示 → 管理员输入
  - 如需双因子 → 调用 Bridge `/api/auth/two-factor/send` → 管理员输入短信验证码
  - 登录成功后 Cookie/Session 自动维护在 Bridge 端
  - 需要配合：
    - 🔧 MiClaw Bridge：已有完整 `/api/auth/*` 端点
    - 🖥️ 控制面板：无（Token Pool Dashboard 内嵌）

- [ ] **MiClaw 账号自动管理**
  - QPS 限制：每秒最多 `qps_limit` 个请求
  - RPM/TPM 限制 + 每日请求上限
  - 并发限制：同一账号最多 `concurrent_limit` 个并发请求
  - 自动轮询：多个账号间 round-robin + 评分
  - 自动熔断：429/500/超时 → 切换下一个账号
  - Cookie 过期检测 → 自动标记需要重新登录
  - 需要配合：
    - 🔧 MiClaw Bridge：新增 `/api/usage/today`（可选）
    - 🖥️ 控制面板：无

---

### P4 — 统计 & Dashboard

- [ ] **完整统计系统**
  - `call_log` 表：记录每次调用（user_id, key_alias, provider, model, ts, latency_ms, tokens, cost, success, error）
  - `usage_daily` 表：按天汇总（user_id, date, key_alias, total_tokens, total_cost, success/fail count, avg_latency）
  - API：
    - `GET /api/stats/summary?range=24h|7d|30d` — 总览
    - `GET /api/stats/by-key` — 按Key统计
    - `GET /api/stats/by-provider` — 按Provider统计
    - `GET /api/stats/by-model` — 按模型统计
    - `GET /api/stats/timeline?interval=hour|day` — 时间线
    - `GET /api/stats/yesterday?user_code=xxx` — 昨天用量（供 Mother 调用）
  - 参考：freellmapi `routes/analytics.ts`
  - 需要配合：
    - 🧠 母体：调用 `GET /api/stats/yesterday` 获取用户昨天用量

- [ ] **HTML Dashboard 重写**
  - 重写 `routes/admin.py`（当前是内嵌HTML字符串，约340行）
  - Dashboard 功能：
    1. 总览卡片：总Key数/可用数/熔断数/总Token/总Cost
    2. Provider Key 管理：添加/编辑/删除/启用/禁用/手动检测
    3. MiClaw 账号管理：添加账号/登录/验证码输入/状态查看
    4. 用户管理：用户列表/共享比例设置/禁用/启用
    5. 用户共享Key：列表/状态/昨日用量/借用比例/手动熔断
    6. 统计图表：时间线/按模型分布/按Provider分布/成功率趋势
    7. 调用日志：实时日志流
    8. 系统设置：Rate Limit参数/Scoring策略/默认Budget
  - 需要配合：❌ 无（完全自包含）

- [ ] **首次启动向导**
  - 首次访问 `/admin` → 检测 `users` 表是否为空
  - 空 → 显示设置向导：创建管理员账号 → 显示 user_token
  - 非空 → 显示登录页
  - 需要配合：❌ 无

---

### P5 — 部署 & 集成

- [ ] **Docker 部署**
  - 更新 `Dockerfile`：Python 3.11-slim + uvicorn
  - 更新 `docker-compose.yml`：挂载数据卷 `/var/lib/token_pool`
  - HEALTHCHECK：`curl -f http://localhost:8100/health`
  - 需要配合：❌ 无

- [ ] **Nginx 路由配置**
  - 存储机 nginx 新增：`/v1/chat/completions` → `http://127.0.0.1:8100`
  - 需要配合：
    - 🖥️ 控制面板：部署在存储机，Nginx 配置需协调

- [ ] **端到端测试**
  - 测试：用户注册 → 添加Key → 发起chat请求 → 统计验证
  - 测试：MiClaw登录 → 代理调用 → Token统计
  - 测试：用户共享Key → 比例熔断
  - 测试：Rate Limit触发 → cooldown恢复
  - 需要配合：❌ 无

</details>

---

<details>
<summary><h2>🖥️ 控制面板（独立改造，现在不展开）</h2></summary>

> - [ ] 管理面板适配 Token Pool 的 `/admin` 或 API 代理
> - [ ] `/api/token-pool` → 代理到 Token Pool 管理 API
> - [ ] `/api/miclaw-instances` → 代理到 Token Pool MiClaw API  
> - [ ] 新增用户管理页（调用 Token Pool 用户 API）
> - [ ] 新增用户共享Key管理页（调用 Token Pool 共享Key API）
> - [ ] 面板中的 Token 标签页 → 内嵌 Token Pool Dashboard
> - [ ] 管理面板登录鉴权保持不变（admin/密码）

</details>

<details>
<summary><h2>📱 APK 端（独立改造，现在不展开）</h2></summary>

> - [ ] Provider 默认 base_url 改为 Token Pool 地址
> - [ ] 模型列表改为从 Token Pool `/v1/models` 获取
> - [ ] LLM 调用的 Authorization header 改为 user_token（替代直接传 api_key）
> - [ ] 心跳上报中的 Key 信息保持不变（用于共享 Key 收集）
> - [ ] "白嫖算力"按钮指向 Token Pool（而不是直接指向 bridge）
> - [ ] 新增 user_token 配置入口（设置页）

</details>

<details>
<summary><h2>🧠 母体（独立改造，现在不展开）</h2></summary>

> - [ ] `LLMClient` 改为调用 Token Pool `http://127.0.0.1:8100/v1/chat/completions`
> - [ ] Header 设置 `Authorization: Bearer <mother_user_token>`
> - [ ] 删除 `app/token_pool.py`（功能完全迁移到 Token Pool）
> - [ ] 删除 `app/providers.py`（功能完全迁移到 Token Pool）
> - [ ] 新增 `GET /api/mother/yesterday-usage` → 调用 Token Pool 查询昨日用量
> - [ ] 新增 `GET /api/mother/shared-keys` → 调用 Token Pool 查询共享Key状态

</details>

<details>
<summary><h2>🔧 MiClaw Bridge（不改，作为 Provider 后端）</h2></summary>

> - [ ] 保持运行，作为 Token Pool 的 Provider 后端
> - [ ] 可选新增：`GET /api/usage/today`（返回到目前为止的 Token 用量）
> - [ ] 可选新增：`GET /api/account/multi`（查询多账号状态）

</details>

<details>
<summary><h2>🌉 Gateway（独立改造，现在不展开）</h2></summary>

> - [ ] 所有渠道（QQ/微信/飞书/Web/CLI）统一走 Token Pool 作为 LLM 后端
> - [ ] 每个渠道使用独立的 user_token

</details>

---

## 实施顺序（严格按此顺序）

```
现在 → 只做 Token Pool 核心
         │
    ┌────┴────┐
    P0  修复+清理+加密     ← 0.5天
    P1  多用户系统         ← 1天
    P2  智能路由           ← 1.5天
    P3  MiClaw融合         ← 1.5天
    P4  统计+Dashboard     ← 1天
    P5  部署+测试          ← 1天
         │
         ▼
    Token Pool 完成 ✅
         │
    ┌────┴────┐
    │         │
  控制面板   APK+母体
  （下一轮） （下一轮）
```

---

> **当前阶段：Token Pool P0 → P5，只计划不执行。确认后开始 P0。**

# MBclaw 商业化 Token Pool 重构 — 完整长期施工计划 (PRT)

> 审计完成时间：2026-07-04
> 阶段：**规划设计阶段（禁止修改代码）**
> 审阅源码总量：新版Token池 1200行 + 旧版 153行 + freellmapi参考 11文件 + MiClaw Bridge + bridge_manager + MiclawBridge.kt
> 定位：APK 内置的 AI 中转站，MBclaw 盈利核心

---

## 一、当前架构审计结果

### 1.1 现有Token Pool双重存在

| | 旧版(运行中) | 新版(设计) |
|---|---|---|
| 位置 | `/opt/mbclaw/app/token_pool.py` | `05-Token池/token_pool/` |
| 行数 | 153行 | ~1200行(20文件) |
| 存储 | 心跳JSON文件读取 | SQLite（AES-256-GCM加密） |
| 调度 | `min(usage_count)` | 综合评分(success_rate×5 + priority×2 − latency − cost×3 + task偏好) |
| 熔断 | 无 | 独立熔断器(threshold=3, cooldown=60s) |
| 多用户 | 无 | users表 + auth注册/登录 + API Token |
| 多协议 | 仅OpenAI | OpenAI + Anthropic自动转换 |
| MiClaw | 从JSON读token | miclaw_accounts独立表 + 加密密码 + Cookie/Session持久化 |
| 监控 | 无 | 5分钟滑动窗口RPM/成功率/延迟 + call_log持久化 |
| 后台探活 | `test_key()`手动触发 | `health.py` 异步定时全量检测 |
| 流式 | 无故障转移 | 只选第一个Key（未实现故障转移） |
| 部署 | **正在存储机嵌入运行** | **未部署** |

### 1.2 旧版关键Bug

```python
# token_pool.py:122 — 变量名错误，未定义变量直接崩溃
pk = min(miclaw, key=lambda x: x.usage_count)  # ← miclaw 未定义！
# 应为: pk = min(inst, key=lambda x: x.usage_count)
```

### 1.3 旧版架构缺陷

- 每次`pick()`都遍历 `/var/lib/mbclaw/heartbeat_logs/` 目录读所有JSON，O(n)磁盘IO
- 全局`_pool`单例，无用户/租户概念
- MiClaw token从JSON文件读（非实时验证）
- API Key明文存储在心跳文件和`token_pool.json`中
- provider枚举混乱（miclaw/miclaw-bridge/custom混用）
- Stream模式无故障转移

---

## 二、freellmapi 可借鉴之处

| 特性 | freellmapi实现 | 借鉴建议 |
|------|--------------|---------|
| 加密 | AES-256-GCM, authTagLength=16锁定防截断攻击 | ✅ 已借鉴，需加固authTag长度检查 |
| Key批量导入 | Multer文件上传+key-parser(.env/.json/.md) | 🟡 可选，管理员手动加Key已满足需求 |
| SSRF防护 | URL解析+内网IP黑名单 | ✅ 必须加（阻止用户把base_url指到内网） |
| 模型分组 | model-groups.ts自动检测模型能力(tools/vision) | 🟡 可选，后续加 |
| Rate Limit | proxy-rate-limit中间件 | ✅ 应在proxy.py加入用户级限流 |
| Proxy代理 | SOCKS/HTTP代理支持(undici) | ❌ 不需要（Token Pool不做出口代理） |
| Provider抽象 | base.ts抽象层+各provider实现 | ✅ 应抽象Provider接口，当前caller.py硬编码太死 |
| 错误脱敏 | 错误消息中剥离API Key | ✅ 必须加 |

---

## 三、MiClaw Bridge 现状与融合方案

### 当前两层架构（问题在里面）

```
APK → /bridge/miclaw/v1 → 存储机 nginx → 工具池 :8765 → 小米API
     ↑ bridge_manager管理实例          ↑ Rust binary独立项目
```

### 融合目标

```
APK → Token Pool :8100/v1/chat/completions
              │
              ├── miclaw provider (内部调用 Rust bridge)
              ├── openai provider
              ├── deepseek provider
              └── user-shared provider
```

MiClaw变成Token Pool的一个Provider，不再独立对外暴露。

---

## 四、最终推荐架构

```
┌──────────────────────────────────────────────────────────────────┐
│                    Token Pool :8100                                │
│                                                                   │
│  POST /v1/chat/completions  ← OpenAI兼容 (APK/Web/API用户)        │
│  GET  /v1/models                                               │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ pool/caller.py  — 统一调用器                                 │ │
│  │   ├── OpenAI兼容 (所有OpenAI格式provider)                     │ │
│  │   ├── Anthropic → OpenAI转换                                 │ │
│  │   ├── MiClaw (内部调用miclaw client)                         │ │
│  │   └── 自动故障转移 (up to 3 retries)                         │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                              │                                    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ pool/scheduler.py  — 智能调度                                │ │
│  │   综合评分 = success_rate×5 + priority×2 + task_pref×3        │ │
│  │             − latency_sec − cost_per_1k×3                   │ │
│  │   任务路由: code→anthropic/openai, cheap→deepseek/miclaw     │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                              │                                    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ pool/registry.py  — SQLite持久化                             │ │
│  │   keys表: 管理员配置的Provider Key (AES-256-GCM加密)          │ │
│  │   users表: 用户注册/登录/配额/余额                           │ │
│  │   user_shared_keys表: 用户共享Key (从心跳收集)               │ │
│  │   miclaw_accounts表: MiClaw多账号 (加密密码+Cookie)          │ │
│  │   call_log表: 调用日志 (user_id/key_alias/ts/latency/tokens) │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                              │                                    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ pool/circuit.py  — 熔断 (threshold=3, cooldown=60s)          │ │
│  │ pool/metrics.py  — 5分钟滑动窗口 (RPM/成功率/延迟)            │ │
│  │ pool/health.py   — 后台定时探活 (HEALTH_INTERVAL=300s)       │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  Routes:                                                          │
│  /api/keys/*     — Key CRUD (Admin)                              │
│  /api/stats/*    — 统计监控 (Admin)                               │
│  /api/heartbeat  — 设备心跳上报 (APK→自动注册Key)                │
│  /api/auth/*     — 用户注册/登录/查询                             │
│  /admin          — HTML管理面板                                    │
└──────────────────────────────────────────────────────────────────┘
```

---

## 五、分模块TODO清单

### 🔑 Token Pool（展开 — 核心项目）

#### P0 — 阻塞性（必须先做）

| # | TODO | 文件 | 说明 |
|---|------|------|------|
| P0-1 | **修复 `miclaw` 变量名Bug** | 07-后端核心/app/token_pool.py:122 | `miclaw` → `inst`，否则旧版pick()崩溃 |
| P0-2 | **删除冗余代码，统一基准** | 07-后端核心/app/token_pool.py + 05-Token池/deployed_token_pool.py | 删除两份旧版，`05-Token池/token_pool/`为唯一基准 |
| P0-3 | **加密加固** | pool/encryption.py | authTag长度锁定16字节防截断攻击（参考freellmapi） |
| P0-4 | **部署到工具池** | Docker | `docker-compose up -d` → :8100 |

#### P1 — 核心功能

| # | TODO | 文件 | 说明 |
|---|------|------|------|
| P1-1 | **抽象Provider接口** | 新建 pool/providers/ | 当前caller.py硬编码if/else，应抽象`BaseProvider`→`OpenAIProvider`/`AnthropicProvider`/`MiclawProvider` |
| P1-2 | **MiClaw融合为Provider** | 新建 pool/providers/miclaw.py | 内部调用Rust bridge :8765，统一走caller |
| P1-3 | **MiClaw多账号+自动登录** | pool/registry.py + routes/admin.py | 管理员输入账号密码→后台登录→存Cookie→自动维护Session |
| P1-4 | **MiClaw速率控制** | pool/scheduler.py + miclaw_accounts表 | QPS/RPM/TPM/每日限制/并发数，超限自动轮换下个账号 |
| P1-5 | **用户共享Key比例控制** | routes/proxy.py + pool/registry.py | `yesterday_usage × share_ratio = max_borrowable`，超限立即熔断 |
| P1-6 | **Stream模式故障转移** | routes/proxy.py `_stream_proxy()` | 当前只选第一个Key，失败就报错，应加入重试逻辑 |
| P1-7 | **SSRF防护** | routes/keys.py `add_key()` | 添加Key时检查base_url是否指向内网/私有IP |
| P1-8 | **用户级限流** | routes/proxy.py 中间件 | 每用户RPM/RPD/TPD限制 |

#### P2 — 完善

| # | TODO | 文件 | 说明 |
|---|------|------|------|
| P2-1 | **管理面板MiClaw标签** | routes/admin.py HTML | 添加"MiClaw账号"标签页：列表/添加/登录状态/Cookie/用量 |
| P2-2 | **管理面板用户标签** | routes/admin.py HTML | 用户列表/共享比例设置/配额/余额 |
| P2-3 | **错误消息脱敏** | pool/caller.py | 错误返回时剥离API Key |
| P2-4 | **Key批量导入** | routes/keys.py | 参考freellmapi的key-parser，支持.env上传 |
| P2-5 | **调用日志前端** | routes/admin.py HTML | 按Key/时间范围筛选，图表展示 |
| P2-6 | **成本/Token日报** | pool/registry.py | `get_daily_stats()`聚合今日/昨日/本周统计 |

#### P3 — 远期

| # | TODO | 说明 |
|---|------|------|
| P3-1 | Gemini/DeepSeek/Qwen原生协议适配 | 在providers/各加一个adapter |
| P3-2 | 模型能力自动检测(tools/vision) | 参考freellmapi model-groups.ts |
| P3-3 | OpenRouter代理支持 | 作为provider加入 |
| P3-4 | WebSocket流式 | 替代当前SSE |

### 🤖 Mother（折叠）

| # | TODO | 说明 |
|---|------|------|
| M-1 | `/api/mother/run` 走Token Pool | 替换当前直接用环境变量LLM Key的方式，调用Token Pool :8100 |
| M-2 | `mother/token_pool/client.py` 对接新Token Pool | 已有 `get_tp_client()` 框架，用 `PROXY_KEY` 鉴权 |
| M-3 | 去掉旧版token_pool.py引用 | main.py不再import app.token_pool |

### 🖥️ 控制面板（折叠）

| # | TODO | 说明 |
|---|------|------|
| CP-1 | Token池管理标签页替换 | 将当前`/admin/api/token-pool`相关端点改为代理到Token Pool :8100 |
| CP-2 | MiClaw实例管理迁移 | `bridge_manager.py`的角色由Token Pool的miclaw provider接管 |

### 📱 APK（折叠）

| # | TODO | 说明 |
|---|------|------|
| A-1 | 默认Provider改为Token Pool | 新用户默认base_url指向Token Pool而非直接配置LLM |
| A-2 | 白嫖算力入口改用Token Pool | `MiclawBridge.kt`申请/轮询逻辑走Token Pool的miclaw provider |
| A-3 | 心跳已自动注册 | `/api/heartbeat` 已支持 → Token Pool自动将用户Key注册到池 |

### 🌉 Gateway（折叠）

| # | TODO | 说明 |
|---|------|------|
| G-1 | Gateway消息 → Token Pool | qqbot/wechat/feishu等渠道消息的LLM调用统一走Token Pool |
| G-2 | 废弃独立bridge路由 | nginx `/bridge/miclaw/v1/` 指向改为Token Pool :8100 |

### ⚡ MiClaw Bridge（折叠）

| # | TODO | 说明 |
|---|------|------|
| MB-1 | 保持Rust binary独立运行 | 作为Token Pool的后端服务，不直接暴露 |
| MB-2 | 增加内部鉴权 | Token Pool → Bridge 之间加 `X-Internal-Key` header |
| MB-3 | 多账号session池 | 从单账号改为可维护多个登录session |

---

## 六、实施顺序

```
Phase 1 (P0, 1-2天)
├── P0-1: 修复miclaw变量名bug
├── P0-2: 删除两份冗余旧版代码
├── P0-3: encryption.py加固
└── P0-4: Docker部署Token Pool到工具池

Phase 2 (P1, 3-5天)
├── P1-1: 抽象Provider接口
├── P1-2: MiClaw融合为Provider
├── P1-3: MiClaw多账号+自动登录
├── P1-4: MiClaw速率控制
└── P1-5: 用户共享Key比例控制

Phase 3 (P1继续, 2-3天)
├── P1-6: Stream故障转移
├── P1-7: SSRF防护
├── P1-8: 用户级限流
├── M-1~M-3: Mother切换Token Pool
└── CP-1~CP-2: 控制面板适配

Phase 4 (P2, 2-3天)
├── 管理面板MiClaw/用户标签页
├── 错误脱敏/Key批量导入
└── A-1~A-3: APK适配

Phase 5 (P3, 远期)
└── Gemini/Qwen/OpenRouter适配
```

---

## 七、风险分析

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 新版部署后旧版仍在运行，Key池分裂 | 中 | 高 | 部署新版后立即停止旧版`token_pool.py`的pick()调用 |
| MiClaw登录验证码自动化失败 | 高 | 中 | 保留手动输入验证码的UI（routes/admin.py已预留） |
| Rust bridge单点故障 | 中 | 高 | Token Pool侧熔断+fallback到其他provider |
| 用户共享Key被滥用 | 中 | 中 | 比例控制+P1-8用户限流 |
| SQLite并发瓶颈 | 低 | 低 | 当前用户量不大，远期可换PostgreSQL |

---

*本文档基于对全部源码的实际阅读：新版Token池20文件1200行 + 旧版153行 + freellmapi 11文件 + MiClaw Bridge Rust/Android源码 + bridge_manager.py 301行*
*生成时间: 2026-07-04 | 阶段: 规划设计（禁止修改代码）*

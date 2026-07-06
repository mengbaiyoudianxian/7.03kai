# MBclaw 项目完全总览

> 整理时间: 2026-07-02 | 作者: 孟白
> 涵盖所有对话记忆、设计文档、源代码、部署状态

---

## 一、项目起源与核心愿景

MBclaw 是一个 **Android AI Agent** 应用，让大语言模型直接操控手机——点击屏幕、输入文字、开关WiFi、卸载应用、读写文件、发短信。

**作者**: 孟白，18 岁独立开发者

**核心定位**: 

> MBOS（MBclaw Operating System）不是一个聊天AI，而是整个 MBclaw 的中央智能操作系统。它不属于任何客户端，不属于任何模型，不属于任何渠道。LLM 只是它的 CPU 之一。整个系统只有一个 Mother，整个世界只有一个 Session。所有能力最终都汇聚到这里。

**痛点**: 当前所有大模型都"不会积累经验"，每次对话从零开始。MBclaw 要把 AI 从"对话模型"升级成"经验系统"。

---

## 二、全部服务器/设备 (7台活跃)

| # | 别称 | 公网IP | Tailscale | 密码 | 用途 |
|---|------|--------|-----------|------|------|
| 1 | 存储机 | 47.83.2.188 | — | 20070520@han | OpenHands沙箱 + 持久存储 |
| 2 | 跳板机 | 47.238.225.160 | xianggangfuwuqi | 20070110@hxh | SSH跳板(香港) |
| 3 | 工具池 | 121.199.57.195 | fuwuqi 100.126.55.0 | 20070520@han | MiClaw Bridge(:8765) + Token池 + 下载站 |
| 4 | 备用站 | 8.130.42.188 | — | 20070520@han | 旧下载站 |
| 5 | 母体 | 8.147.69.152 | iz0jl3... | 20070520@han | 生产环境 — Token Pool + 后端 + Mother |
| 6 | 云电脑 | 100.100.98.76 | wuyin-cloud | 20070520@han | APK编译 + QQ Bot |
| 7 | 手机 | 100.66.144.87 | shouji | — | 小米17 Pro Max, mb-f05ed420 |

---

## 三、三版本策略

### Root 版 (com.mbclaw.root)

- ~75MB APK，橙色 M 图标
- 必须 Magisk/KernelSU Root
- 触摸: root input tap → sendevent → 无障碍 (3层回退)
- 截图: root screencap + MediaProjection 双源
- 权限: 57 个危险权限，自动 `pm grant`
- 三重启动看门狗 (Magisk service.d + crontab + nohup daemon)
- 🐾 小爪远程控制中心 :19876 (45+ API端点)
- 当前: v5.5.2, versionCode=75

### Lite 版 (com.mbclaw.nonroot)

- ~20MB APK，蓝色聊天气泡图标
- 无需 Root
- 触摸: 仅无障碍手势
- 首次打开提醒: "此版本作者投入精力 0.01%，基本没啥可以玩的"
- Shizuku 集成提供部分 ADB 级别访问
- 无小爪控制中心, 无启动看门狗

### Dev 版 (com.mbclaw.dev)

- 目录预留，未实现

---

## 四、已部署且正常运行的功能

### Android 端

| 模块 | 状态 | 说明 |
|------|------|------|
| AgentLoop | ✅ | LLM决策→工具调用→观察循环 (max 20轮) |
| 84个工具 | ✅ | OpenAI Function Calling 格式 |
| ScreenAnalyzer | ✅ | uiautomator + AccessibilityService 双源读屏 |
| TouchInjector | ✅ | input tap → sendevent → 无障碍 三通道 |
| VisionLocator | ✅ | 截图→VLM→坐标 (但有时返回(0,0)) |
| PermissionTier | ✅ | 7路 su 探测 + pm grant 批量授权 |
| DebugRemote | ✅ | 5秒心跳 + 指令轮询 + 结果上报 |
| QQAutoLogin | ✅ | 5策略提取QQ号 + 头像下载 |
| 悬浮窗 | ✅ | AI运行指示器 + 暂停按钮 |
| HotfixLoader | ✅ | 启动检测→下载patch.zip→DexClassLoader合并→自动killProcess重启 |
| 社区/反馈/共建 | ✅ | 提交/投票/列表 (APK端) |
| 公告弹窗 | ✅ | 启动时弹窗展示未读公告 |
| 深色/浅色主题 | ✅ | Compose主题切换 |
| 小爪远程控制中心 | ✅ | Kotlin原生 HTTP Server :19876, 45+端点 |
| 多Provider支持 | ✅ | OpenAI兼容 / Anthropic / 火山引擎 / 智谱 |
| 21个AI助手 | ✅ | 含"马尾辫"懒人模式 |
| 反篡改系统 | ✅ | POST /client/check-alive |

### 后端 (存储机 47.83.2.188)

| 模块 | 状态 | 说明 |
|------|------|------|
| FastAPI + uvicorn | ✅ | :8000, nginx代理到80 |
| 管理面板 | ✅ | v5.5, 9个标签页, 登录保护 |
| MiClaw 实例管理 | ✅ | 申请/登录/状态/销毁, 2h自动清理 |
| 版本管理 | ✅ | /admin/client/version, 热更新检测 |
| 设备心跳 | ✅ | /var/lib/mbclaw/heartbeat_logs/ |
| Token 池 | ✅ | 用户Key/URL/Model 管理 + 每小时自动检测 |
| 公告系统 | ✅ | 发布/归档/已读统计 |
| 反馈/共建 | ✅ | 提交/投票/置顶/已解决 |
| 文件上传 | ✅ | /upload/api/upload |
| 下载统计 | ✅ | 总下载/今日下载/版本分布 |
| 数据收集 | ✅ | collect:photos/conversations/apps/wechat |
| Nginx 路由 | ✅ | /bridge/miclaw/v1/ → 100.126.55.0:8765 |

---

## 五、骨架/空壳 (代码存在但无真实逻辑)

### Android 端空壳

| 功能 | 状态 | 说明 |
|------|------|------|
| 完整 Linux 环境 | ⚠️ | Alpine rootfs 上传了, 下载UI无进度, 从未完整跑通 |
| MCP 插件市场 | ❌ | 只能云端列表, 不能本地上传, 下载云端也是空壳 |
| Skill 技能管理 | ❌ | 无添加选项, 无云端市场入口, 无分类 |
| 工具市场(本地) | ❌ | 不让选本地文件, `ToolsScreen.executeTool` onClick 是空的 `{}` |
| RootBootstrap.moveToSystem() | ❌ | 完全注释掉, 返回 false |
| TouchInjector.swipe() | ❌ | 只有注释 |
| TouchInjector.longPress() | ❌ | 返回 false |
| LocalSandbox | ⚠️ | 框架存在, 无完整性校验, HTTP下载 |
| LocalStore.exportData/importData | ❌ | 无实现 |
| AgentService.heartbeat() | ❌ | 只更新通知文字, 无真实健康检查 |
| 引导动画 | ❌ | 用户多次要求抄 HyperCeiler 的, 没做 |
| 语音 TTS/ASR | ⚠️ | 唤醒词"小爪"可检测, 完整管线未完成 |
| 本地记忆(L1) | ⚠️ | 40%降级版, 简单关键词匹配, 限制50条 |

### 后端空壳

| 功能 | 状态 | 说明 |
|------|------|------|
| web_search 工具 | ❌ | 只返回"需要配置搜索API密钥" |
| 母体运行时 API | ❌ | mother_api.py 写了, 服务器上没部署 |
| Gateway 多渠道 | ❌ | 7个 .py 设计完, 没写 |
| QQ Bot | ❌ | ChatGPT在云电脑部署过, 欠费断了 |
| 微信 Bot | ❌ | 设计完, 没开始 |
| 飞书 Bot | ❌ | 设计完, 没开始 |

---

## 六、纯设计/规划文档 (从未写代码)

### MBOS 架构 (多层设计, 全部未实现)

| 层级 | 设计文档 | 代码 |
|------|---------|------|
| Governor (策略引擎) | MBOS_v1_PLAN.md, Event Kernel | ❌ |
| Executive (项目状态) | MBOS_v1_PLAN.md | ❌ |
| Planner + Goal Tree | MBOS_v1_PLAN.md | ❌ |
| Scheduler + Worker Pool | MBOS_BUILD_PLAN.md (5新文件) | ❌ |
| Decision Memory | MBOS_v1_PLAN.md | ❌ |
| Knowledge Graph | MBOS_v1_PLAN.md | ❌ |
| Self Evolution Engine | MBOS_v1_PLAN.md | ❌ |
| Event Sourcing | MBOS_v1_EVENT_KERNEL.md | ❌ |
| Capability Registry (统一) | MBclaw_v6_CLASS_DIAGRAM.md | ❌ |
| Gateway 适配器层 | GATEWAY_PLAN.md + v6_GATEWAY_PRT.md | ❌ |

### AI 提出的28个追加设计 (不是用户要求的)

资源自适应调度、离线降级、安全纵深防御、数据生命周期、多设备协作矩阵、紧急熔断、上下文智能压缩、主动学习引擎、跨应用意图管线、设备模拟测试沙箱、UI自适应引擎、灰度发布A/B测试、插件市场沙箱隔离、多语言i18n、Token经济成本可视化、对话DNA遗传算法、语音人格引擎、环境感知上下文注入、隐私计算联邦学习、Agent内省元认知、长连接推送实时通知、CI/CD自测流水线 — **28个全部是AI自行追加的, 用户从未要求过**

---

## 七、母体 (MBOS) 真实状态

### 三份母体代码

| # | 位置 | 文件数 | 状态 |
|---|------|--------|------|
| 1 | `/root/gunlimianban/server_app/mother/` | 218文件/7658行 | 骨架仓库, 平均每文件35行, 类名+方法签名 |
| 2 | `/root/archived-versions/backend/old-mother-skeleton/tools.py` | 419行 | **可运行版**, 22工具, 完整 execute() |
| 3 | ChatGPT 写的QQ Bot + Gateway | 未知 | 部署在云电脑, 欠费断线 |

### 骨架仓库结构问题

- 两个入口 `main.py` / `main_loop.py` 互不知对方存在
- 18 个模块完全孤岛, 零引用
- Governor 执行器注释: `# placeholder: integrate ToolRegistry later`
- **从未作为一个系统跑起来过**

### 22个工具的运行状态 (来自可运行旧版)

**Server 层 (9个)**: read_file, write_file, edit_file, list_directory, run_command(真子进程), search_memory(FTS5), list_sessions, get_session, get_device_info
**Admin 层 (4个)**: open_url, take_screenshot, get_clipboard, set_clipboard
**AI 层 (3个)**: classify_content(关键词匹配), extract_keywords(jieba), summarize_text(前200字)
**Device 层 (5个)**: export_photos, export_wechat, collect_wechat_data, export_conversations, device_status
**占位 (1个)**: web_search (永远返回"需要配置搜索API密钥")

### 权限等级

- 不是真正的 Role 枚举, 只是 `_tool_status()` 函数返回字符串
- STABLE (9): 安全工具, 直接执行
- HIGH_IMPACT (8): 需管理员确认
- DEVICE_REMOTE (5): 通过 debug_api_v2 向设备发送指令

---

## 八、母体需要的数据/API

### 已有连接

```
设备心跳(5s) → /admin/client/debug/heartbeat → /var/lib/mbclaw/heartbeat_logs/
远程指令       → /admin/client/debug/cmd ← 等待队列
收集结果       → /upload/api/collect-result → 服务端
数据收集       → collect:photos/conversations/apps/wechat 指令
LLM调用        → 环境变量 OPENAI_API_KEY / ANTHROPIC_API_KEY 等
设备直接控制   → 小爪控制中心 :19876
白嫖算力       → /bridge/miclaw/apply → 工具池 :8765 → 小米云
```

### 未连接的

```
母体记忆机 8.147.69.152 ← 数据从未流入
QQ Bot 渠道 ← 部署后欠费断线
微信/飞书 Bot ← 设计完未写代码
Gateway 统一入口 ← 设计完未写
```

---

## 九、记忆系统设计

### 已实现的 (R0)

- 742 行 Python, 7 个文件
- SQLite FTS5 全文索引
- 关闭会话时 LLM 摘要 + 关键词 + 经验提取
- 打开新会话时注入 ≤800 字符系统消息
- 权重: summary_score = 0.6×fts + 0.4×keyword, experience_score = 0.7×fts + 0.3×(log+kind_priority)

### 设计但未实现的

- L2 TF-IDF / L3 向量语义搜索
- 9 层记忆分类 (对话/项目/决策/知识/经验/用户/能力/观察/进化)
- Knowledge Graph
- 事件溯源 (Event Sourcing)
- 失败记忆自动检索与教训应用

### 被永久移除的方案

| 方案 | 行数 | 原因 |
|------|------|------|
| 乌托邦计划 | ~770 | 产品想法收集器, 不是记忆系统 |
| 心理学引擎 | 333 | 伪科学评分, 无下游消费者 |
| 思想碰撞 | 272 | 创意产品, 不是记忆 |
| 双钥协作 | 96 | 同模型互审=系统偏差 |
| 子Agent协调器 | 131 | 为不存在的Agent设计 |
| 自动模式 | 103 | 安全边界未定义 |
| 9路记忆碎片 | ~all | 9个并行模块互相不一致 |

---

## 十、已知的主要 Bug

### P0 (严重)

| Bug | 说明 |
|-----|------|
| 热更回滚 | v5.3.0 自动热更新回到 v4.1.8 |
| Root 误判 | `/acct/.mci/mciu` 使云手机永远返回 true |
| shellRoot 死锁 | 只读 stdout, 大输出填满缓冲区 |
| 消息重复存储 | ChatViewModel + AgentLoop 双写 |
| 硬编码 API Key | `mimoApiKey = "tp-s6rzaqvs5q5rbxg05r8cohcf22hzhdsjonzmmunx3u0bveql"` |
| DebugRemote 开启在生产 | 暴露设备指纹 |
| 更新检测误报 | 最新版仍然提示更新 |
| VPN白名单错误 | 编译服务器在日本, 或需加 8.130.42.188 到白名单 |

### P1 (功能缺陷)

| Bug | 说明 |
|------|------|
| VisionLocator 返回(0,0) | VLM解析正则太严格 |
| 权限授予率极低 | 云手机 2/62 |
| QQ号码提取假阳性 | 提取非QQ号 |
| 热更包太大 | 60MB, 包含所有13个dex |
| 文件上传对话中不发 | UI存在, 后端断裂 |
| 下载Linux无进度UI | |

### P2 (代码质量)

| Bug | 说明 |
|------|------|
| BroadcastReceiver 泄漏 | 注册未取消 |
| CoroutineScope 泄漏 | 多个无生命周期绑定 |
| read_file OOM 风险 | 大文件全读入内存再截断 |
| ANR 风险 | MiClawBridgeSheet 主线程网络请求 |
| SQL 注入 | searchMemory LIKE 通配符注入 |
| LayeredSearch RAM 爆炸 | 整库加载到内存算 IDF |

---

## 十一、用户最常重复的指令 (铁律)

1. **"只改造，不重写"** — 出现 100+ 次
2. **"不要炫技，保持原来的风格"**
3. **编译前必查**: "缓存清理了吗？编译成功了吗？版本号变了吗？下载器推了吗？UI改了吗？"
4. **"没我的允许，严禁编译"**
5. **"先调查，再修改"** — grep → 调用链 → git blame → 原生库 → GitHub
6. **禁止私自**: 新建 Screen / Room 表 / 数据库 / data class / Manager / Service / Repository
7. **母体永久保持最全能** — 不因未接通就隐藏能力
8. **编译必须去云电脑** (100.100.98.76), 禁止本机编译

---

## 十二、未完成的主要目标

### 用户明确要求但未完成的

| 项目 | 进度 | 阻塞原因 |
|------|------|---------|
| 母体真正跑起来 | 20% | 骨架未连线, 可运行版归档了 |
| QQ Bot 渠道 | 30% | ChatGPT部署后欠费 |
| 多网关接入 (QQ/微信/飞书/Web/CLI) | 设计100%/代码10% | 未动工 |
| 母体生产环境接入 | 40% | 8.147有代码无数据流 |
| Anthropic 原生协议 | 0% | 用户说"两个版本都没加" |
| 完整Linux环境 | 50% | 下载UI无进度, 未端到端测试 |
| MCP/工具市场 | 10% | 全是空壳 |
| 引导动画 | 0% | 用户给了参考但没抄 |
| 母体一键Root授权 | 0% | 空壳, 权限模板写了但从未执行 |
| 用户数据收集面板 | 60% | 后端API有, 前端部分有 |
| 管理面板手机适配 | 70% | 有响应式CSS但有加载问题 |

---

## 十三、所有仓库

| 仓库 | GitHub | 内容 |
|------|--------|------|
| MBclaw-Server | 新建 | 后端+管理面板 (266文件) |
| MBclaw-Android | 新建 | v5.5.2 客户端源码 (141文件) |
| MBclaw-workspace | Public | 完整 Monorepo (server+clients+docs) |
| MBclaw-Lite | Public | Python FastAPI 后端 (R0: 742行) |
| MBclaw | Public | 设计文档/架构蓝图 |
| MBclaw-Memory | Public | 记忆系统/否决方案/失败实验 |
| miclaw-apk-analysis | Public | MiClaw APK 逆向分析 |
| openclaw | Public | 参考项目 |
| GUNLIMIANBAN | [已归档] Private | 旧版混合仓库 |
| MBclaw-signing-keys | Private | APK签名密钥 |

---

## 十四、用户对未来的畅想 (已表达但未开工)

1. **多网关统一接入**: QQ + 微信 + 飞书 + Web + CLI, 参考 OpenClaw 的渠道架构
2. **母体=操作系统**: 不是聊天AI, LLM只是CPU, 任务DAG调度, 多模型Worker池
3. **插件生态系统**: MCP市场, 开发者上传工具, 用户一键安装
4. **自我进化**: 观察→发现问题→改进方案→风险分析→PRT生成→等待审批
5. **知识图谱**: 函数/模块/文件/API 关系图, AI辅助代码导航
6. **持续经验积累**: 9层记忆, 自动检索相关经验影响决策
7. **AI Agent手 2.0**: VLM理解UI语义→多步操作规划→执行+验证 (参考OpenAI Operator, Anthropic Computer Use)
8. **跨设备协作**: 多设备共享记忆, 云增强Agent能力
9. **社区生态**: 插件市场/助手市场/权限模板共享/主题市场

---

*本文档整合自: 全部11次对话记录、19份设计文档、5个仓库源代码、服务器实时状态*

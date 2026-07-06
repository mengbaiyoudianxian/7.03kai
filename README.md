# MBclaw 7.03kai — 全项目归档

> 归档时间: 2026-07-03 | 审计时间: 2026-07-04
> 来源: 手机 /sdcard/aiMB/ + 生产服务器
> 工作区: /root/工作/

## 目录结构

| 目录 | 内容 | 说明 |
|------|------|------|
| 01-总体预览 | 5个总览文档 | 项目全貌/服务器/API/MiClaw/母体 |
| 02-控制面板 | 27个文件 | 管理面板完整代码(router/admin/panel.js/html) |
| 03-母体 | 45个文件 | 母体系统+老骨架+gateway+qqbot |
| 04-安卓端 | 230个Kotlin文件 | Root/Lite/Dev三版源码 v5.6.0 |
| 05-Token池 | 26个文件 | 独立Token Pool服务+生产代码 |
| 06-服务器配置 | 5个文件 | Nginx/main/cron配置 |
| 07-后端核心 | 53个文件 | FastAPI后端+Gateway+Capability |
| 08-Nginx配置 | Nginx配置 | 工具池/存储机Nginx配置 |
| 09-母体记忆机 | k2系列脚本 | 记忆系统+Claude Code脚本 |
| 10-工具池 | miclaw_bridge+memory | Rust Bridge源码+长期记忆 |

## 重要文档

| 文档 | 说明 |
|------|------|
| [Token-Pool重构审计报告.md](Token-Pool重构审计报告.md) | 🔥 2026-07-04 完整审计，含架构方案+TODO |
| [当前架构分析.md](当前架构分析.md) | 历史架构分析 |
| [复用分析文档.md](复用分析文档.md) | freellmapi/MiClaw 复用分析 |
| [完成报告.md](完成报告.md) | 历史完成报告 |

## 外部参考项目（本地克隆，不入库）

| 项目 | 路径 | 说明 |
|------|------|------|
| freellmapi | /root/工作/freellmapi/ | TypeScript LLM中转站，18 Provider，161模型 |
| miclaw | /root/工作/miclaw/ | MiClaw IT代理框架 |

## ⛔ 母体铁律

> **母体禁止任何形式的权限限制。**
>
> 不设用户鉴权、不设速率限制、不设配额、不设熔断、不设 API Key 校验。
> 母体是信任边界内的生产核心，所有安全控制在外层 Gateway/Nginx 完成。
> 任何向母体引入权限检查的代码变更一律拒绝。

## 🚫 Docker 禁令

> **所有服务器禁止使用 Docker。** 所有服务直接以进程方式运行（uvicorn / systemd / nohup）。
> 不写 Dockerfile，不写 docker-compose.yml，不构建镜像，不运行容器。
> 现有 `docker-compose.yml` 仅作文档参考，不用于实际部署。

## 服务器

| 别称 | IP | 用途 |
|------|-----|------|
| 母体 | 8.147.69.152 | 生产环境 — Token Pool + 后端 + Mother + MiClaw深度魔改 |
| 工具池 | 121.199.57.195 | 下载站 + MiClaw Bridge + Nginx |
| 存储机 | 47.83.2.188 | OpenHands沙箱 + 持久存储（本机） |
| 跳板机 | 47.238.225.160 | SSH跳板 + QQ Bot |
| 备用站 | 8.130.42.188 | 备用 |
| 云电脑 | 100.100.98.76 | APK编译 |
| 手机 | 100.66.144.87 | 调试机 |

## 版本

- Android Root: 5.6.0 (versionCode=76)
- Android Lite: 5.6.0-lite (versionCode=51)
- 管理面板: v5.5 (2026-07-01)
- 后端: MBclaw Server 0.4.0

## 持久化说明

本目录为 MBclaw 项目持久工作区，每次对话的分析结果和代码均保存于此。
同步到 GitHub: https://github.com/mengbaiyoudianxian/7.03kai

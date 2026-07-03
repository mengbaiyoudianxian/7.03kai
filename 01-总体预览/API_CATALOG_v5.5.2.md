# MBclaw v5.5.2 全部 API 端点清单

> 服务器: 47.83.2.188:80 (nginx → uvicorn :8000)
> 下载站: 121.199.57.195:80
> 桥接: miclaw_api_bridge (:8765)

## 编译进 APK 的接口

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | /admin/client/version?current={ver} | 版本检测 |
| GET | /client/notices | 公告拉取 |
| GET | /client/notices/history | 公告历史 |
| POST | /client/notices/mark-read | 标记已读 |
| POST | /admin/client/debug/heartbeat | 设备心跳 |
| GET | /admin/client/debug/cmd?code={c} | 拉取指令 |
| POST | /admin/client/debug/result | 上报结果 |
| POST | /admin/client/debug/send | 下发命令 |
| GET | /admin/client/debug/devices | 设备列表 |
| GET | /admin/client/debug/results | 结果历史 |
| GET | /admin/client/perm-template | 权限模板 |
| POST | /admin/client/account/sync | 账号同步 |
| GET | /admin/client/account/lookup | 账号查询 |
| POST | /admin/client/key-sync | 密钥上传 |
| GET | /admin/client/key-sync | 密钥拉取 |
| GET | /admin/client/tools/list | 工具列表 |
| POST | /admin/client/tools/upload | 工具上传 |
| GET | /admin/client/mcp/list | MCP列表 |
| POST | /admin/client/mcp/install | MCP安装 |
| GET | /admin/client/skills/list | 技能列表 |
| POST | /admin/client/skills/install | 技能安装 |

## 管理面板 API

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | /admin/api/login | 登录 |
| POST | /admin/api/logout | 登出 |
| GET | /admin/api/overview | 仪表盘统计 |
| GET | /admin/api/server-status | 服务器状态 |
| GET | /admin/api/users | 用户列表 |
| GET | /admin/api/bugs | Bug反馈 |
| POST | /admin/api/bugs | 提交Bug |
| POST | /admin/api/bugs/{id}/pin | 置顶 |
| POST | /admin/api/bugs/{id}/resolve | 已解决 |
| POST | /admin/api/bugs/{id}/vote | 投票 |
| POST | /admin/api/bugs/{id}/delete | 删除 |
| POST | /admin/api/bugs/{id}/set-votes | 修改点赞 |
| GET | /admin/api/features | 共建计划 |
| POST | /admin/api/features | 提交建议 |
| POST | /admin/api/features/{id}/pin | 置顶 |
| POST | /admin/api/features/{id}/resolve | 已解决 |
| POST | /admin/api/features/{id}/vote | 投票 |
| POST | /admin/api/features/{id}/delete | 删除 |
| POST | /admin/api/features/{id}/set-votes | 修改点赞 |
| GET | /admin/api/token-pool | Token池列表 |
| POST | /admin/api/token-pool/test-key | 单Key检测 |
| POST | /admin/api/token-pool/test-all | 批量检测 |
| GET | /admin/api/miclaw-instances | MiClaw实例 |
| POST | /admin/api/miclaw-instances/{id}/destroy | 销毁实例 |
| GET | /admin/api/notices | 公告管理 |
| POST | /admin/api/notices | 创建公告 |
| GET/POST | /admin/api/keys | Key管理 |
| GET | /admin/api/key-test | Key检测 |
| GET | /admin/api/providers | Provider列表 |
| GET | /admin/api/download-stats | 下载统计 |
| GET | /admin/api/stats/daily | 每日统计 |
| GET | /admin/api/chat-records/{code} | 对话记录 |
| POST | /admin/api/change-password | 修改密码 |

## Bridge/MiClaw

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | /bridge/miclaw/apply | 申请实例 |
| POST | /bridge/miclaw/login/{id} | 登录MiClaw |
| GET | /bridge/miclaw/status | 查询状态 |
| POST | /bridge/miclaw/v1/chat/completions | OpenAI代理 |
| POST | /bridge/miclaw/v1/messages | Anthropic代理 |

## Token Pool (新)

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | /api/admin/stats | Token池统计 |
| GET | /api/admin/metrics | 服务器指标 |
| GET | /api/admin/downloads | 下载统计 |
| POST | /api/admin/downloads/record | 下载记录 |
| GET | /api/admin/users | 用户列表 |

## 上传

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | /upload/api/upload | 文件上传 |
| GET | /upload/ | 上传Web界面 |

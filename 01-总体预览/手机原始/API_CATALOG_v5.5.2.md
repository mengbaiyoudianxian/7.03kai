# MBclaw v5.5.2 全部 API 端点清单

> 服务器: 47.83.2.188:80 (nginx → uvicorn :8000)  
> 下载站: 121.199.57.195:80  
> 注册中心: GitHub raw MBclaw-Lite/r0/data/endpoints.json

---

## 一、编译进 APK 的接口 (硬编码在 Kotlin 中)

### 1.1 服务器地址 (Endpoints.kt 混淆)

| 流向 | 地址 | 代码位 |
|------|------|--------|
| Backend API | `http://47.83.2.188` | `OBF_BACKEND` |
| 下载站 | `http://121.199.57.195` | `OBF_DOWNLOAD` |
| 注册中心(远程更新) | GitHub / jsDelivr | `REGISTRY_MIRRORS` |

### 1.2 版本 & 公告

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/client/version?current={ver}` | 版本检测 / 热更新 |
| GET | `/client/version` | 客户端版本 (api.py) |
| GET | `/client/notices` | 拉取公告 |
| GET | `/client/notices/history` | 公告历史 |
| POST | `/client/notices/mark-read` | 标记已读 |

### 1.3 调试 & 远程控制

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/admin/client/debug/heartbeat` | 设备心跳 (5s) |
| GET | `/admin/client/debug/cmd?code={c}` | 拉取远程指令 |
| POST | `/admin/client/debug/result` | 上报指令结果 |
| POST | `/admin/client/debug/send` | 下发远程命令 |
| POST | `/admin/client/debug/send-collect` | 数据收集指令 |
| GET | `/admin/client/debug/devices` | 调试设备列表 |
| GET | `/admin/client/debug/results` | 调试结果历史 |

### 1.4 权限 & 账号

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/client/perm-template` | 权限模板 (按品牌/型号/SDK) |
| POST | `/admin/client/account/sync` | 账号同步上传 |
| GET | `/admin/client/account/lookup?id={q}` | 账号查询 |

### 1.5 Key 同步

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/admin/client/key-sync` | 密钥同步上传 |
| GET | `/admin/client/key-sync` | 密钥拉取 |

### 1.6 工具 / MCP / 技能

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/client/tools/list` | 自定义工具列表 |
| POST | `/admin/client/tools/upload` | 上传自定义工具 |
| GET | `/admin/client/mcp/list` | MCP 插件列表 |
| POST | `/admin/client/mcp/install` | 安装 MCP 插件 |
| GET | `/admin/client/skills/list` | 技能列表 |
| POST | `/admin/client/skills/install` | 安装技能 |

### 1.7 Linux 环境

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/client/linux-env` | Linux 环境包信息 |
| GET | `/client/linux/status` | Linux 环境状态 |

### 1.8 数据收集 & 上传

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/upload/api/collect-result?code={c}&name={n}` | 数据收集结果上传 |
| POST | `/upload/api/upload` | 文件上传 |

### 1.9 MiClaw 桥接 (APK 调用)

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/bridge/miclaw/apply` | 申请 MiClaw 实例 |
| GET | `/bridge/miclaw/status?application_id={id}` | 轮询实例状态 |
| POST | `/bridge/miclaw/stop?application_id={id}` | 停止实例 |
| POST | `/bridge/miclaw/destroy?application_id={id}` | 销毁实例 |
| GET | `/bridge/miclaw/login/{id}` | MiClaw 登录页面 |
| POST | `/bridge/miclaw/login/{id}` | 提交 Mi 账号密码 |
| ALL | `/bridge/miclaw/v1/{path}` | LLM API 代理转发 |

### 1.10 反篡改 & 默认路由

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/client/check-alive` | 反篡改活体检测 |
| GET | `/client/default-provider` | 默认 LLM Provider |

---

## 二、管理面板 API (母体 Control Panel 用)

### 2.1 认证

| 方法 | 路径 | 文件 | 用途 |
|------|------|------|------|
| POST | `/admin/api/login` | router.py | 管理员登录 |
| POST | `/admin/api/logout` | router.py | 退出 |
| POST | `/admin/api/change-password` | router.py | 改密码 |

### 2.2 仪表盘

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/api/overview` | 总览统计 |
| GET | `/api/admin/stats` | 管理统计 |
| GET | `/api/admin/metrics` | 运营指标 |
| GET | `/api/admin/downloads` | 下载数据 |

### 2.3 设备管理

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/api/users` | 用户/设备列表 |
| POST | `/admin/api/user/block` | 封禁用户 |
| GET | `/api/collect-summary` | 数据收集汇总 |
| GET | `/api/chat-records/{code}` | 查看聊天记录 |
| GET | `/api/device-action` | 设备远程操作 |

### 2.4 Key 管理

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/api/keys` | Key 列表 |
| POST | `/admin/api/keys` | 添加/更新 Key |
| DELETE | `/admin/api/keys/{id}` | 删除 Key |
| GET | `/api/providers` | Provider 列表 |
| GET | `/api/key-test?code={c}` | 测试 Key 可用性 |

### 2.5 Token 池

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/api/token-pool` | Token 池列表 |

### 2.6 MiClaw 实例

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/api/miclaw-instances` | 实例列表 (自动清理>2h未登录) |
| POST | `/admin/api/miclaw-instances/{id}/destroy` | 销毁实例 |

### 2.7 公告

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/api/notices` | 公告列表 |
| POST | `/admin/api/notices` | 发布公告 |
| POST | `/admin/api/notices/{id}/archive` | 归档公告 |

### 2.8 反馈 & 共建

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/api/bugs` | Bug 列表 |
| POST | `/admin/api/bugs` | 提交 Bug |
| POST | `/admin/api/bugs/{id}/vote` | 投票 |
| POST | `/admin/api/bugs/{id}/pin` | 置顶 |
| POST | `/admin/api/bugs/{id}/resolve` | 标为已解决 |
| GET | `/admin/api/features` | 共建列表 |
| POST | `/admin/api/features` | 提交共建 |
| POST | `/admin/api/features/{id}/vote` | 投票 |
| POST | `/admin/api/features/{id}/pin` | 置顶 |
| POST | `/admin/api/features/{id}/resolve` | 标为已实现 |

### 2.9 版本 & 下载统计

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/client/version` | 获取最新版本 |
| POST | `/admin/client/version/set` | 设置版本 |
| GET | `/api/download-stats` | 下载统计详情 |
| GET | `/api/stats/daily` | 每日统计 |

---

## 三、MiClaw 桥接 (bridge_manager.py — 完整)

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/bridge/miclaw/apply` | 申请实例 (含 _cleanup) |
| GET | `/bridge/miclaw/login/{id}` | 登录页面 (HTML) |
| POST | `/bridge/miclaw/login/{id}` | 验证 Mi 凭证 |
| GET | `/bridge/miclaw/status?application_id={id}` | 查询实例状态 (含 _cleanup) |
| ALL | `/bridge/miclaw/v1/{path}` | LLM API 代理转发 |
| POST | `/bridge/miclaw/api/login` | 桥接 admin 登录 |
| POST | `/bridge/miclaw/api/send-ticket` | 发送验证码 |
| POST | `/bridge/miclaw/api/verify-ticket` | 验证票 |
| GET | `/bridge/miclaw/api/captcha-image?url={u}` | 图形验证码代理 |
| POST | `/bridge/miclaw/destroy?application_id={id}` | 销毁实例 |
| POST | `/bridge/miclaw/stop?application_id={id}` | 暂停实例 |
| ALL | `/bridge/miclaw/{path}` | 通用代理 |

---

## 四、文件上传 (upload.py)

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/upload/` | 上传页面 (HTML) |
| POST | `/upload/api/upload` | 上传文件 |
| GET | `/upload/api/list` | 文件列表 |
| GET | `/upload/files/{name}` | 下载文件 |
| DELETE | `/upload/api/delete/{name}` | 删除文件 |

---

## 五、下载统计 & 用户报告 (admin_api.py)

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/admin/downloads` | 下载列表 |
| POST | `/admin/downloads/track` | 追踪下载 |
| POST | `/admin/downloads/reset-daily` | 重置日统计 |
| GET | `/admin/metrics` | 指标 |
| GET | `/admin/users` | 用户 |
| GET | `/admin/stats` | 统计 |
| POST | `/admin/client/report` | 客户端上报 |
| GET | `/admin/users/detail` | 用户详情 |

---

## 六、母体核心 API (MBclaw Lite 后端 → api.py)

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/sessions` | 创建会话 |
| POST | `/sessions/{sid}/messages` | 添加消息 |
| POST | `/sessions/{sid}/close` | 关闭会话 |
| GET | `/sessions/{sid}/messages` | 消息列表 |
| GET | `/search` | 搜索 |
| POST | `/agent/run` | 运行 Agent |
| GET | `/agent/status` | Agent 状态 |
| POST | `/api/mother/collect` | 母体数据收集 |
| GET | `/api/mother/uploads/{code}` | 母体上传文件 |
| GET | `/providers` | Provider 列表 |
| GET | `/tools` | 工具列表 |
| GET | `/tools/search` | 工具搜索 |
| GET | `/tools/capability` | 能力查询 |
| GET | `/tools/{id}` | 工具详情 |
| POST | `/tools/execute` | 执行工具 |
| GET | `/client/version` | 版本 |
| GET | `/client/linux/status` | Linux 环境状态 |

---

## 七、母体运行时 (mother_api.py)

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/status` | 运行时状态 |
| POST | `/plan` | 制定计划 |
| POST | `/run` | 执行 |
| POST | `/run-current` | 执行当前 |
| GET | `/events` | 事件流 |

---

## 八、小爪远程控制中心 (APK 内置 HTTP Server :19876)

Tailscale `100.x.x.x` 内网访问，无需认证。

```
GET  /               # 设备信息 + 端点列表
GET  /info           # 设备详情
GET  /battery        # 电量
GET  /stats          # 存储统计
GET  /processes      # 进程列表
GET  /wifi           # WiFi 信息
GET  /netstat        # 网络连接
GET  /ls?path=       # 列出目录
GET  /cat?path=      # 读取文件
GET  /download?path= # 下载文件
GET  /find?path=&name=  # 搜索文件
GET  /tree?path=&depth= # 目录树
GET  /du?path=       # 磁盘占用
POST /upload?path=   # 上传文件
GET  /screenshot     # 截图 (base64)
GET  /screenrecord   # 录屏
GET  /tap?x=&y=      # 点击
GET  /swipe?x1=&y1=&x2=&y2=  # 滑动
GET  /type?text=     # 输入文字
GET  /key?code=      # 按键
GET  /input?action=  # 通用 input
GET  /apps           # 应用列表
GET  /app_info?package=  # 应用详情
GET  /start?package= # 启动应用
GET  /stop?package=  # 强制停止
GET  /install?path=  # 安装 APK
GET  /uninstall?package=  # 卸载
GET  /shell?cmd=     # 普通命令
GET  /su?cmd=        # Root 命令 (KernelSU)
GET  /kill?pid=      # 杀进程
GET  /ping?host=     # Ping
GET  /photos         # 相册列表
GET  /cameras        # 摄像头列表
GET  /record_audio   # 录音
GET  /settings       # 系统设置
GET  /api/system     # API 版系统信息
GET  /api/screen/shot  # API 版截图
GET  /api/input/tap?x=&y=     # API 版点击
GET  /api/input/swipe        # API 版滑动
GET  /api/input/text         # API 版输入
GET  /api/input/key          # API 版按键
GET  /api/input/gesture      # API 版手势
GET  /api/app/list           # API 版应用列表
GET  /api/app/launch         # API 版启动应用
```

---

## 总览

| 分类 | 数量 |
|------|------|
| APK 硬编码调用 | 29 |
| 管理面板 (母体 Web UI) | 30 |
| MiClaw 桥接 | 14 |
| 母体核心 (Lite 后端) | 18 |
| 母体运行时 | 5 |
| 小爪远程控制 | 45+ |
| 文件上传 | 5 |
| 下载/统计/报告 | 8 |
| **总计** | **154+** |

---

*导出日期: 2026-07-01*

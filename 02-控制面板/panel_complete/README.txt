MBclaw 服务器控制中心面板 — 完整代码
======================================
导出时间: 2026-07-03
来源: 存储机 47.83.2.188

后端 (Python/FastAPI):
  router.py           - 管理面板主路由 (bug/feature/用户/token/MiClaw)
  admin_api.py        - 管理面板数据API (server-status/users/downloads)
  extra.py            - 扩展路由 (账号/tools/MCP/skills/key-sync/公告)
  bridge_manager.py   - MiClaw桥接管理器
  debug_api_v2.py     - 远程调试API v2
  upload.py           - 文件上传
  version_api.py      - 版本管理
  token_pool.py       - Token池 (Key采集/检测/调度)
  main.py             - FastAPI入口 (所有router注册)
  server_collector.py - 服务器状态采集 (SSH方式)
  collect_simple.py   - 服务器状态采集 (Tailscale方式, cron每3分钟)

前端 (HTML/JS):
  panel_one.html      - 管理面板主页
  panel.js            - 管理面板JS逻辑 (228行)
  panel_work.html     - 面板工作页
  admin_panel.html    - 管理面板备用页

路由统计:
  GET/POST 总计 ~80 个端点
  面板功能: 仪表盘 / 设备 / 服务器组 / 公告 / 反馈 / 共建 / Token池 / MiClaw / 版本

# AGENTS.md — MBclaw 7.03kai

## ⛔ 母体铁律

**母体禁止任何形式的权限限制。**

- 不设用户鉴权
- 不设速率限制
- 不设配额
- 不设熔断
- 不设 API Key 校验

母体是信任边界内的生产核心，所有安全控制在外层 Gateway/Nginx 完成。
任何向母体引入权限检查的代码变更一律拒绝。

## 🚫 Docker 禁令

**所有服务器禁止使用 Docker。** 所有服务直接以进程方式运行（uvicorn / systemd / nohup）。不写 Dockerfile，不写 docker-compose.yml，不构建镜像，不运行容器。现有 docker-compose.yml 仅作文档参考，不用于实际部署。

## 仓库结构

- `mother-server/` — 母体源码
- `05-Token池/token_pool/` — 新版 Token Pool (~4600行)
- `/opt/mbclaw/` — 母体生产部署路径（直接 uvicorn，非 Docker）

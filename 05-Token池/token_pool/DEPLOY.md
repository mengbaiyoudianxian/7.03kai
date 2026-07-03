# Token Pool 部署说明

## 架构定位

```
MBclaw 母体 ──→ Token Pool :8100 ──→ OpenAI / Anthropic / DeepSeek / 本地模型
Android 心跳 ──→ /api/heartbeat    （用户贡献Key，自动注册）
Admin 面板   ──→ /admin             （浏览器管理）
```

---

## 一、Docker 部署（推荐）

```bash
# 1. 上传到服务器
scp -r token_pool/ root@你的IP:/opt/token_pool/
ssh root@你的IP

# 2. 配置
cd /opt/token_pool
cp .env.example .env
nano .env          # 至少改 TP_ADMIN_KEY

# 3. 启动
docker-compose up -d

# 4. 验证
curl http://localhost:8100/health
```

---

## 二、直接运行

```bash
cd token_pool
pip install -r requirements.txt
cp .env.example .env
# 编辑 .env

uvicorn main:app --host 0.0.0.0 --port 8100
# 或后台运行：
nohup uvicorn main:app --host 0.0.0.0 --port 8100 > /var/log/token_pool.log 2>&1 &
```

---

## 三、Nginx 反代（可选，推荐生产使用）

```nginx
server {
    listen 80;
    server_name pool.你的域名.com;

    location / {
        proxy_pass http://127.0.0.1:8100;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 300;     # 流式输出需要较长超时
        proxy_buffering off;        # 流式必须关buffering
    }
}
```

---

## 四、填写 API Key（三种方式均可）

### 方式A：Admin 面板（推荐）
1. 打开 `http://服务器IP:8100/admin`
2. 输入 `TP_ADMIN_KEY`（你在 .env 里设的那个）
3. 找到对应 alias，点 **编辑**，填入 API Key，保存

### 方式B：接口
```bash
curl -X PATCH http://localhost:8100/api/keys/openai-gpt4o/key \
  -H "X-Admin-Key: 你的TP_ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d '{"api_key": "sk-..."}'
```

### 方式C：初始化脚本（批量）
```bash
#!/bin/bash
ADMIN=你的TP_ADMIN_KEY
BASE=http://localhost:8100

patch() { curl -s -X PATCH $BASE/api/keys/$1/key -H "X-Admin-Key: $ADMIN" -H "Content-Type: application/json" -d "{\"api_key\":\"$2\"}"; echo; }

patch openai-gpt4o      "sk-..."
patch openai-gpt4o-mini "sk-..."
patch anthropic-sonnet  "sk-ant-..."
patch deepseek-chat     "sk-..."
patch qwen-plus         "sk-..."
```

---

## 五、MBclaw 母体集成

在 MBclaw `server/.env` 中配置：
```
MBCLAW_LLM_BASE_URL=http://localhost:8100/v1
MBCLAW_LLM_API_KEY=（填 TP_PROXY_KEY，空则不填）
MBCLAW_LLM_MODEL=gpt-4o   # Token Pool 会自动路由到最优Provider
```

调用示例：
```python
import httpx
r = httpx.post("http://localhost:8100/v1/chat/completions",
    headers={"Authorization": "Bearer 你的TP_PROXY_KEY"},  # 空则省略header
    json={"model": "gpt-4o", "messages": [{"role":"user","content":"hello"}]})
print(r.json())
```

---

## 六、Android 心跳（用户贡献 Key）

Android 客户端每隔 60s 发送心跳，自动注册到 Token Pool：
```
POST /api/heartbeat
{
  "code": "mb-设备码",
  "api_key": "用户的API Key",
  "base_url": "https://api.openai.com/v1",
  "model": "gpt-4o-mini",
  "provider": "openai"
}
```
服务器自动注册为 `hb-{code}` 的 Key，优先级 2（低于系统Key）。

---

## 七、关键接口速查

| 接口 | 说明 |
|------|------|
| `GET /health` | 健康检查 |
| `GET /admin` | 管理面板（浏览器） |
| `POST /v1/chat/completions` | LLM代理（OpenAI兼容） |
| `GET /v1/models` | 可用模型列表 |
| `GET /api/keys` | Key列表 `X-Admin-Key` |
| `POST /api/keys` | 添加Key |
| `PATCH /api/keys/{alias}/key` | 设置API Key |
| `POST /api/keys/{alias}/probe` | 手动探活 |
| `POST /api/keys/probe_all` | 全量探活 |
| `GET /api/stats` | 统计总览 |
| `GET /api/stats/log` | 调用日志 |
| `POST /api/heartbeat` | 心跳注册 |

---

## 八、内置 Key 别名说明

| Alias | Provider | 模型 |
|-------|----------|------|
| `openai-gpt4o` | OpenAI | gpt-4o |
| `openai-gpt4o-mini` | OpenAI | gpt-4o-mini |
| `openai-gpt41` | OpenAI | gpt-4.1 |
| `anthropic-sonnet` | Anthropic | claude-sonnet-4-6 |
| `anthropic-haiku` | Anthropic | claude-haiku-4-5 |
| `deepseek-chat` | DeepSeek | deepseek-chat |
| `deepseek-reasoner` | DeepSeek | deepseek-reasoner |
| `qwen-plus` | 阿里云DashScope | qwen-plus |
| `miclaw-bridge` | MiClaw | 桥接免费 |
| `local-ollama` | 本地 | llama3 |

内置 Key 默认无 api_key，进入 Admin 面板按 alias 填写即可。

---

## 九、运维

```bash
# 查看日志
docker-compose logs -f token-pool

# 重启
docker-compose restart token-pool

# 备份数据
cp /var/lib/token_pool/pool.db /backup/pool.db.$(date +%Y%m%d)
```

#!/bin/bash
cd /opt/mbclaw
export MBCLAW_LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export MBCLAW_LLM_API_KEY=sk-06fb2eb8742640d3be0eb9b7743df3c2
export MBCLAW_LLM_MODEL=deepseek-v4-pro
export MBCLAW_DB_PATH=/opt/mbclaw/data/mbclaw.db
python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000

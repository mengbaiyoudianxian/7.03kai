#!/bin/bash
# 验证脚本：检查所有关键文件是否已复制到持久分区

echo "=== 验证关键文件 ==="

# FreeLLMAPI 关键文件
echo "1. FreeLLMAPI 核心文件："
for file in \
    "freellmapi/server/src/routes/analytics.ts" \
    "freellmapi/server/src/lib/request-log.ts" \
    "freellmapi/server/src/routes/keys.ts" \
    "freellmapi/server/src/lib/url-guard.ts" \
    "freellmapi/server/src/lib/key-parser.ts" \
    "freellmapi/server/src/lib/config.ts" \
    "freellmapi/server/src/index.ts" \
    "freellmapi/docker-compose.yml"
do
    if [ -f "$file" ]; then
        echo "  ✓ $file"
    else
        echo "  ✗ $file (缺失)"
    fi
done

# MiClaw 关键文件
echo ""
echo "2. MiClaw 关键文件："
for file in \
    "07-后端核心/app/admin/miclaw_login.html" \
    "02-控制面板/miclaw_login.html" \
    "08-Nginx配置/工具池/miclaw-auth.conf" \
    "08-Nginx配置/工具池/miclaw-proxy.conf" \
    "08-Nginx配置/存储机/miclaw_proxy.conf" \
    "10-工具池/miclaw_bridge_src/src-tauri/src/bin/miclaw_api_bridge_desktop.rs"
do
    if [ -f "$file" ]; then
        echo "  ✓ $file"
    else
        echo "  ✗ $file (缺失)"
    fi
done

# 分析文档
echo ""
echo "3. 分析文档："
if [ -f "复用分析文档.md" ]; then
    echo "  ✓ 复用分析文档.md"
    echo "    大小: $(wc -c < 复用分析文档.md) 字节"
else
    echo "  ✗ 复用分析文档.md (缺失)"
fi

# Git 仓库
echo ""
echo "4. Git 仓库："
for dir in ".git" "freellmapi/.git" "miclaw/.git"; do
    if [ -d "$dir" ]; then
        echo "  ✓ $dir"
    else
        echo "  ✗ $dir (缺失)"
    fi
done

echo ""
echo "=== 验证完成 ==="
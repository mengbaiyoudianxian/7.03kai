#!/bin/bash
# 备份脚本：将持久分区内容打包备份

BACKUP_DIR="/root/备份"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="$BACKUP_DIR/工作备份_$TIMESTAMP.tar.gz"

echo "=== 开始备份 ==="
echo "备份文件：$BACKUP_FILE"

# 创建备份目录
mkdir -p "$BACKUP_DIR"

# 创建备份
tar -czf "$BACKUP_FILE" -C /root 工作

# 检查备份结果
if [ $? -eq 0 ]; then
    echo "✅ 备份成功！"
    echo "备份大小：$(du -h "$BACKUP_FILE" | cut -f1)"
    echo "备份位置：$BACKUP_FILE"
else
    echo "❌ 备份失败！"
    exit 1
fi

echo ""
echo "=== 备份完成 ==="
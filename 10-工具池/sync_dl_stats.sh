#!/bin/bash
# 从下载站 nginx 日志统计 APK 下载量，推送到 47.83.2.188
# 放到 crontab: */5 * * * * /root/sync_dl_stats.sh

STATS_FILE="/var/www/stats/downloads.json"
ACCESS_LOG="/var/log/nginx/access.log"
TODAY=$(date +%Y-%m-%d)

mkdir -p /var/www/stats

# 统计全部历史（从日志文件）
declare -A TOTAL
declare -A TODAY_COUNT

while IFS= read -r line; do
  # 提取 GET /xxx.apk
  file=$(echo "$line" | grep -oP 'GET /\K[^ ]+\.apk' | head -1)
  [ -z "$file" ] && continue

  # 统计总量
  TOTAL["$file"]=$((TOTAL["$file"] + 1))

  # 统计今日
  log_date=$(echo "$line" | grep -oP '^\S+ \S+ \S+ \[(\d+/\w+/\d+)' | sed 's/.*\[//')
  # nginx默认日志格式: IP - - [02/Jul/2026:...]
  log_date2=$(echo "$line" | grep -oP '\[(\d+/\w+/\d+)' | sed 's/\[//' | sed 's#/#-#g')
  # 简化：直接比较日期字符串
  time_str=$(echo "$line" | grep -oP '\[([0-9]+/[A-Z][a-z]+/20[0-9]+)' | head -1 | sed 's/\[//')
  if echo "$time_str" | grep -q "$(date +%d/%b/%Y)"; then
    TODAY_COUNT["$file"]=$((TODAY_COUNT["$file"] + 1))
  fi
done < "$ACCESS_LOG"

# 构建 JSON
JSON="{"
first=true
for file in "${!TOTAL[@]}"; do
  [ "$first" = false ] && JSON+=","
  JSON+="\"$file\":{\"total\":${TOTAL[$file]},\"today\":${TODAY_COUNT[$file]:-0}}"
  first=false
done
JSON+="}"

echo "$JSON" | python3 -m json.tool > "$STATS_FILE"

# 推送到 47.83.2.188
curl -s -X POST "http://47.83.2.188/admin/api/download-stats/push" \
  -H "Content-Type: application/json" \
  -d @"$STATS_FILE" > /dev/null 2>&1

echo "[$(date)] Synced $(echo "${!TOTAL[@]}" | wc -w) files, $(echo "${TOTAL[@]}" | tr ' ' '\n' | paste -sd+ | bc) total downloads"

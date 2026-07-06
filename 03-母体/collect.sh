#!/bin/bash
OUT=/var/lib/mbclaw/server_status.json
echo '{' > $OUT

# 本机
echo '  "存储机": {' >> $OUT
mem=( $(free -m | head -2 | tail -1) )
disk=( $(df -h / | tail -1) )
rx=0; tx=0
while read line; do
  parts=($line)
  [[ ${#parts[@]} -ge 10 ]] && { rx=$((rx+${parts[1]})); tx=$((tx+${parts[9]})); }
done < /proc/net/dev
cpu=$(top -bn1 | grep Cpu | awk '{print $2}' | head -1)
up=$(uptime -p | sed 's/up //')
echo "    \"status\": \"online\", \"ip\": \"47.83.2.188\", \"mem_total\": ${mem[1]}, \"mem_used\": ${mem[2]}, \"disk_total\": \"${disk[1]}\", \"disk_used\": \"${disk[2]}\", \"disk_pct\": \"${disk[4]}\", \"net_rx\": $rx, \"net_tx\": $tx, \"cpu\": \"$cpu\", \"uptime\": \"$up\"" >> $OUT
echo '  },' >> $OUT

# 远程 - 直接用ssh (不等subprocess)
declare -A NODES=(["跳板机"]="100.94.194.31" ["下载站"]="100.126.55.0" ["母体"]="100.64.17.81" ["云电脑"]="100.100.98.76")
for name in "${!NODES[@]}"; do
  ip="${NODES[$name]}"
  result=$(timeout 8 ssh -o StrictHostKeyChecking=no -o ConnectTimeout=4 -o BatchMode=yes root@$ip "free -m|head -2|tail -1;df -h /|tail -1;uptime -p|sed 's/up //';cat /proc/net/dev|tail -1;hostname -I|awk '{print \$1}'" 2>/dev/null)
  if [ -n "$result" ]; then
    ml=($(echo "$result" | head -1))
    dl=($(echo "$result" | head -2 | tail -1))
    up=$(echo "$result" | head -3 | tail -1)
    nl=($(echo "$result" | head -4 | tail -1))
    net_ip=$(echo "$result" | tail -1)
    echo "  \"$name\": {" >> $OUT
    echo "    \"status\": \"online\", \"ip\": \"$net_ip\", \"mem_total\": ${ml[1]:-0}, \"mem_used\": ${ml[2]:-0}, \"disk_total\": \"${dl[1]:-?}\", \"disk_used\": \"${dl[2]:-?}\", \"disk_pct\": \"${dl[4]:-?}\", \"net_rx\": ${nl[1]:-0}, \"net_tx\": ${nl[9]:-0}, \"uptime\": \"$up\", \"cpu\": \"\"" >> $OUT
    echo '  },' >> $OUT
  else
    echo "  \"$name\": { \"status\": \"offline\", \"ip\": \"$ip\" }," >> $OUT
  fi
done

echo "  \"updated\": $(date +%s)" >> $OUT
echo '}' >> $OUT
echo "Written to $OUT"

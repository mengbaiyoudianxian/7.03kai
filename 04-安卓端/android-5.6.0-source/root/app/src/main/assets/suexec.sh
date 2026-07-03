#!/system/bin/sh
# MBclaw su-exec — 200字节，挨个找su执行命令
for s in /debug_ramdisk/su /data/adb/magisk/su /data/adb/ksu/bin/su /system/xbin/su /sbin/su /system/bin/su su; do
  if [ -x "$s" ]; then exec "$s" -c "$*" 2>/dev/null; fi
done
echo "NO_SU"

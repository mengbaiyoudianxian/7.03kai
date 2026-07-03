#!/bin/bash
cd /opt/mbclaw
pkill -f k2_executor 2>/dev/null
sleep 1
python3 k2_executor.py &
disown
echo "K2 started PID=$!"

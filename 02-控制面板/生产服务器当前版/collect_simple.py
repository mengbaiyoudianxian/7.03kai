#!/usr/bin/env python3
"""每3分钟采集服务器状态，带 hostname"""
import json, subprocess, time, socket

d = {}
KNOWN = {
    "存储机": {"ip": "47.83.2.188", "hostname": "iZj6c6xhvpez8w1hk9pefuZ", "role": "OpenHands沙箱 + 持久存储", "public_ip": "47.83.2.188"},
    "工具池": {"ip": "121.199.57.195", "hostname": "iZbp14z7xg0itzgqgf1uc3Z", "role": "MiClaw Bridge :8765 + Token池 + APK下载站", "public_ip": "121.199.57.195"},
    "跳板机": {"ip": "47.238.225.160", "hostname": "iZj6camnt3ocwjveip3f7rZ", "role": "SSH跳板中转 (香港)", "public_ip": "47.238.225.160"},
    "备用站": {"ip": "8.130.42.188", "hostname": "iZ0jl0q0zxij3hfnwjfbekZ", "role": "旧下载站/备用文件服务", "public_ip": "8.130.42.188"},
    "母体": {"ip": "8.147.69.152", "hostname": "iZ0jl3aqsblqwrkyxt46tvZ", "role": "生产环境 — Token Pool + 后端 + Mother", "public_ip": "8.147.69.152"},
    "云电脑": {"ip": "100.100.98.76", "hostname": "", "role": "APK编译 + QQ Bot (无影云)", "public_ip": "100.100.98.76"},
    "小米手机": {"ip": "100.66.144.87", "hostname": "shouji", "role": "主力调试机 · 小爪远程控制中心 :19876", "public_ip": "100.66.144.87"},
}

# Local
try:
    mem = subprocess.run(["free","-m"], capture_output=True, text=True).stdout.split("\n")[1].split()
    disk = subprocess.run(["df","-h","/"], capture_output=True, text=True).stdout.split("\n")[1].split()
    rx=tx=0
    for line in open("/proc/net/dev").readlines()[2:]:
        p=line.split()
        if len(p)>=10:
            try: rx+=int(p[1]); tx+=int(p[9])
            except: pass
    cpu = subprocess.run("top -bn1|grep Cpu|awk '{print $2}'",shell=True,capture_output=True,text=True).stdout.strip()[:4]
    up = subprocess.run("uptime -p",shell=True,capture_output=True,text=True).stdout.strip().replace("up ","")
    d["存储机"] = {"status":"online","ip":"47.83.2.188","hostname":socket.gethostname(),"role":"OpenHands沙箱 + 持久存储","public_ip":"47.83.2.188",
        "mem_total":int(mem[1]),"mem_used":int(mem[2]),
        "disk_total":disk[1],"disk_used":disk[2],"disk_pct":disk[4],
        "net_rx":rx,"net_tx":tx,"cpu":cpu,"uptime":up}
except: d["存储机"] = {"status":"error","ip":"47.83.2.188","hostname":socket.gethostname()}

# Tailscale nodes - check online status, use known hostnames
try:
    ts = subprocess.run(["tailscale","status"], capture_output=True, text=True).stdout
    nodes = {"xianggangfuwuqi":"跳板机","fuwuqi":"工具池","iz0jl3aqsblqwrkyxt46tvz":"母体","wuyin-cloud":"云电脑"}
    skip = {"shouji","yundiannao","fuwuqi2"}
    for line in ts.split("\n"):
        p = line.split()
        if len(p)>=3 and p[0].startswith("100.") and p[1] not in skip:
            name = nodes.get(p[1], p[1])
            info = KNOWN.get(name, {})
            d[name] = {
                "status": "offline" if "offline" in line else "online",
                "ip": info.get("ip", p[0]),
                "hostname": info.get("hostname", ""),
                "role": info.get("role", ""),
                "public_ip": info.get("public_ip", info.get("ip", p[0])),
            }
except: pass

# Ensure all known servers exist
for name, info in KNOWN.items():
    if name not in d:
        d[name] = {"status": "offline", "ip": info["ip"], "hostname": info["hostname"], "role": info.get("role",""), "public_ip": info.get("public_ip", info["ip"])}

d["updated"] = time.time()
json.dump(d, open("/var/lib/mbclaw/server_status.json","w"), ensure_ascii=False, indent=2)
print(f"Collected {len(d)-1} servers")

#!/usr/bin/env python3
import json, subprocess, time

d = {}

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
    d["存储机"] = {"status":"online","ip":"47.83.2.188","mem_total":int(mem[1]),"mem_used":int(mem[2]),
        "disk_total":disk[1],"disk_used":disk[2],"disk_pct":disk[4],"net_rx":rx,"net_tx":tx,"cpu":cpu,"uptime":up}
except: d["存储机"] = {"status":"error"}

# Tailscale nodes
try:
    ts = subprocess.run(["tailscale","status"], capture_output=True, text=True).stdout
    nodes = {"xianggangfuwuqi":"跳板机","fuwuqi":"下载站","iz0jl3aqsblqwrkyxt46tvz":"母体","wuyin-cloud":"云电脑"}
    skip = {"shouji","yundiannao","fuwuqi2"}
    for line in ts.split("\n"):
        p = line.split()
        if len(p)>=3 and p[0].startswith("100.") and p[1] not in skip:
            d[nodes.get(p[1],p[1])] = {"status":"offline" if "offline" in line else "online","ip":p[0]}
except: pass

d["updated"] = time.time()
json.dump(d, open("/var/lib/mbclaw/server_status.json","w"), ensure_ascii=False, indent=2)
print(f"Collected {len(d)-1} servers")

#!/usr/bin/env python3
"""后台采集所有服务器状态 → /var/lib/mbclaw/server_status.json"""
import subprocess, json, time, os, socket

SERVERS = {
    "工具池": "121.199.57.195",
    "跳板机": "47.238.225.160",
    "备用站": "8.130.42.188",
    "母体": "8.147.69.152",
    "云电脑": "100.100.98.76",
    "小米手机": "100.66.144.87",
}

ROLES = {
    "存储机": "OpenHands沙箱 + 持久存储",
    "工具池": "MiClaw Bridge :8765 + Token池 + APK下载站",
    "跳板机": "SSH跳板中转 (香港)",
    "备用站": "旧下载站/备用文件服务",
    "母体": "生产环境 — Token Pool + 后端 + Mother",
    "云电脑": "APK编译 + QQ Bot (无影云)",
    "小米手机": "主力调试机 · 小爪远程控制中心 :19876",
}

KNOWN_HOSTNAMES = {
    "存储机": "iZj6c6xhvpez8w1hk9pefuZ",
    "工具池": "iZbp14z7xg0itzgqgf1uc3Z",
    "跳板机": "iZj6camnt3ocwjveip3f7rZ",
    "备用站": "iZ0jl0q0zxij3hfnwjfbekZ",
    "母体": "iZ0jl3aqsblqwrkyxt46tvZ",
    "云电脑": "",
    "小米手机": "shouji",
}

def get_local():
    try:
        mem = subprocess.run(["free","-m"], capture_output=True, text=True).stdout.split("\n")[1].split()
        disk = subprocess.run(["df","-h","/"], capture_output=True, text=True).stdout.split("\n")[1].split()
        net = subprocess.run(["cat","/proc/net/dev"], capture_output=True, text=True).stdout
        rx=tx=0
        for line in net.split("\n")[2:]:
            parts=line.split()
            if len(parts)>=10:
                try: rx+=int(parts[1]); tx+=int(parts[9])
                except: pass
        cpu = subprocess.run("top -bn1|grep Cpu|awk '{print $2}'",shell=True,capture_output=True,text=True).stdout.strip()[:4]
        uptime = subprocess.run("uptime -p",shell=True,capture_output=True,text=True).stdout.strip().replace("up ","")
        local_host = socket.gethostname()
        return {"mem_total":int(mem[1]),"mem_used":int(mem[2]),"disk_total":disk[1],"disk_used":disk[2],
                "disk_pct":disk[4],"net_rx":rx,"net_tx":tx,"cpu":cpu,"uptime":uptime,"status":"online",
                "ip":"47.83.2.188","hostname":local_host}
    except:
        return {"status":"error","ip":"47.83.2.188","hostname":socket.gethostname()}

def get_remote(ip, name):
    try:
        cmd = "free -m|head -2;df -h /|tail -1;uptime -p;cat /proc/net/dev|tail -1;hostname;hostname -I|awk '{print $1}'"
        r = subprocess.run(["ssh","-o","StrictHostKeyChecking=no","-o","ConnectTimeout=4","-o","BatchMode=yes",
            f"root@{ip}",cmd], capture_output=True,text=True,timeout=8)
        if r.returncode==0 and r.stdout.strip():
            lines = r.stdout.strip().split("\n")
            if len(lines) < 5:
                return {"status":"offline","ip":ip}
            ml = lines[0].split()
            dl = lines[1].split() if len(lines)>1 else ["?","?","?","?","?"]
            up = lines[2].replace("up ","") if len(lines)>2 else "?"
            nl = lines[3].split() if len(lines)>3 else ["0"]*10
            hostname = lines[4].strip() if len(lines)>4 else name
            net_ip = lines[5].strip() if len(lines)>5 else ip
            return {"status":"online","ip":net_ip,"hostname":hostname,
                    "mem_total":int(ml[1]) if len(ml)>1 else 0,
                    "mem_used":int(ml[2]) if len(ml)>2 else 0,
                    "disk_total":dl[1] if len(dl)>1 else "?",
                    "disk_used":dl[2] if len(dl)>2 else "?",
                    "disk_pct":dl[4] if len(dl)>4 else "?",
                    "net_rx":int(nl[1]) if len(nl)>1 else 0,
                    "net_tx":int(nl[9]) if len(nl)>9 else 0,
                    "uptime":up,"cpu":""}
        return {"status":"offline","ip":ip}
    except:
        return {"status":"offline","ip":ip}

def collect():
    data = {"存储机": get_local(), "updated": time.time()}
    data["存储机"]["role"] = ROLES.get("存储机", "")
    data["存储机"]["public_ip"] = "47.83.2.188"
    for name, ip in SERVERS.items():
        data[name] = get_remote(ip, name)
        if name in ROLES:
            data[name]["role"] = ROLES[name]
        # Ensure public IP is displayed
        data[name]["public_ip"] = ip
        # Ensure hostname is always set
        if "hostname" not in data[name]:
            data[name]["hostname"] = KNOWN_HOSTNAMES.get(name, "")
    # Ensure local has hostname too
    if "hostname" not in data["存储机"]:
        data["存储机"]["hostname"] = KNOWN_HOSTNAMES.get("存储机", socket.gethostname())
    out = "/var/lib/mbclaw/server_status.json"
    json.dump(data, open(out,"w"), ensure_ascii=False, indent=2)
    print(f"Written to {out}")

if __name__ == "__main__":
    collect()

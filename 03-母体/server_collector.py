#!/usr/bin/env python3
"""后台采集所有服务器状态 → /var/lib/mbclaw/server_status.json"""
import subprocess, json, time, os

SERVERS = {
    "跳板机": "100.94.194.31",
    "下载站": "100.126.55.0",
    "母体": "100.64.17.81",
    "云电脑": "100.100.98.76",
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
        return {"mem_total":int(mem[1]),"mem_used":int(mem[2]),"disk_total":disk[1],"disk_used":disk[2],
                "disk_pct":disk[4],"net_rx":rx,"net_tx":tx,"cpu":cpu,"uptime":uptime,"status":"online","ip":"47.83.2.188"}
    except:
        return {"status":"error"}

def get_remote(ip, name):
    try:
        cmd = f"free -m|head -2;df -h /|tail -1;uptime -p;cat /proc/net/dev|tail -1;hostname -I|awk '{{print $1}}'"
        r = subprocess.run(["ssh","-o","StrictHostKeyChecking=no","-o","ConnectTimeout=4","-o","BatchMode=yes",
            f"root@{ip}",cmd], capture_output=True,text=True,timeout=8)
        if r.returncode==0 and r.stdout.strip():
            lines=r.stdout.strip().split("\n")
            ml=lines[0].split();dl=lines[1].split() if len(lines)>1 else ["?","?","?","?","?"]
            up=lines[2].replace("up ","") if len(lines)>2 else "?"
            nl=lines[3].split() if len(lines)>3 else ["0"]*10
            net_ip=lines[4].strip() if len(lines)>4 else ip
            return {"status":"online","ip":net_ip,"mem_total":int(ml[1]) if len(ml)>1 else 0,
                    "mem_used":int(ml[2]) if len(ml)>2 else 0,"disk_total":dl[1] if len(dl)>1 else "?",
                    "disk_used":dl[2] if len(dl)>2 else "?","disk_pct":dl[4] if len(dl)>4 else "?",
                    "net_rx":int(nl[1]) if len(nl)>1 else 0,"net_tx":int(nl[9]) if len(nl)>9 else 0,"uptime":up,"cpu":""}
        return {"status":"offline","ip":ip}
    except:
        return {"status":"offline","ip":ip}

def collect():
    data = {"存储机": get_local(), "updated": time.time()}
    for name, ip in SERVERS.items():
        data[name] = get_remote(ip, name)
    out = "/var/lib/mbclaw/server_status.json"
    json.dump(data, open(out,"w"), ensure_ascii=False, indent=2)
    print(f"Written to {out}")

if __name__ == "__main__":
    collect()

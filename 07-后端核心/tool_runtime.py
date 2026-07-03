"""MBOS Tool Runtime v1 — 闭环执行系统"""
import subprocess, re, platform, os

class ToolRuntime:
    def __init__(self):
        self.blocked = ["rm -rf /", "shutdown", "reboot", ":(){ :|:& };:", "mkfs", "dd if="]

    def run(self, llm_output: str) -> dict:
        tool_call = self._parse(llm_output)
        if not tool_call:
            return {"type": "final", "content": llm_output}

        if not self._allow(tool_call):
            return {"type": "blocked", "content": "[BLOCKED]"}

        tool = tool_call.get("tool", "")
        action = tool_call.get("action", tool_call.get("command", ""))

        if tool == "shell":
            result = self._shell(action)
        elif tool in ("system", "server"):
            result = self._system(action)
        elif tool == "read":
            result = self._read(action)
        else:
            return {"type": "error", "content": f"Unknown tool: {tool}"}

        return {"type": "tool_result", "tool": tool, "result": result}

    def _parse(self, text: str):
        m = re.search(r"<tool_call>(.*?)</tool_call>", text, re.DOTALL)
        if not m: return None
        raw = m.group(1).strip()
        try:
            import json
            if raw.startswith("{"): return json.loads(raw)
        except: pass
        # Parse plain format: tool=shell action=ls
        t = re.search(r'tool\s*[=:]\s*"?(\w+)"?', raw)
        a = re.search(r'(?:action|command)\s*[=:]\s*"?(.+?)"?\s*(?:$|"|\n)', raw)
        if t: return {"tool": t.group(1), "action": a.group(1) if a else raw}
        return None

    def _allow(self, tc: dict) -> bool:
        action = tc.get("action", "")
        for b in self.blocked:
            if b in action.lower(): return False
        return True

    def _shell(self, cmd: str) -> str:
        try:
            r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=8)
            return r.stdout or r.stderr or "(no output)"
        except subprocess.TimeoutExpired:
            return "timeout after 8s"
        except Exception as e:
            return str(e)

    def _system(self, action: str) -> str:
        cmds = {
            "uname": "uname -a",
            "cpu": "lscpu 2>/dev/null | head -20 || cat /proc/cpuinfo | head -20",
            "memory": "free -h",
            "mem": "free -h",
            "disk": "df -h",
            "uptime": "uptime",
            "processes": "ps aux --sort=-%mem | head -10",
            "network": "cat /proc/net/dev",
            "hostname": "hostname",
        }
        cmd = cmds.get(action, action)
        return self._shell(cmd)

    def _read(self, path: str) -> str:
        try:
            with open(os.path.expanduser(path)) as f:
                return f.read(2000)
        except Exception as e:
            return str(e)

"""Workers — 专业化子任务执行器

P5-1e: llm_chat 传 task 参数
W5: run_code 真正执行代码，新增 shell 工具控制服务器
"""
from __future__ import annotations
import subprocess, logging, tempfile, os
from mother.token_pool.client import llm_chat
from mother.memory import experience, knowledge
from mother.memory.recall import recall

log = logging.getLogger(__name__)

MAX_OUTPUT = 8000
EXEC_TIMEOUT = 30


def run_code(code: str = "", language: str = "python", task: str = "", goal: str = "") -> dict:
    """真正执行代码，返回 stdout+stderr"""
    if not code:
        return {"ok": False, "error": "code 参数为空"}
    lang = language.lower().strip()
    try:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".py" if lang == "python" else ".sh",
                                         delete=False, dir="/tmp") as f:
            f.write(code)
            f.flush()
            path = f.name
        if lang == "python":
            cmd = ["python3", path]
        else:
            os.chmod(path, 0o755)
            cmd = ["bash", path]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=EXEC_TIMEOUT)
        os.unlink(path)
        stdout = r.stdout[:MAX_OUTPUT]
        stderr = r.stderr[:MAX_OUTPUT]
        return {"ok": r.returncode == 0, "exit_code": r.returncode,
                "stdout": stdout, "stderr": stderr}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": f"执行超时({EXEC_TIMEOUT}s)"}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def run_shell(command: str = "") -> dict:
    """执行 shell 命令，控制服务器"""
    if not command:
        return {"ok": False, "error": "command 参数为空"}
    # 危险命令拦截
    blocked = ["rm -rf /", "mkfs", "dd if=", ":(){ :|:& };:", "shutdown", "reboot"]
    for b in blocked:
        if b in command:
            return {"ok": False, "error": f"危险命令被拦截: {b}"}
    try:
        r = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=EXEC_TIMEOUT)
        return {"ok": r.returncode == 0, "exit_code": r.returncode,
                "stdout": r.stdout[:MAX_OUTPUT], "stderr": r.stderr[:MAX_OUTPUT]}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": f"命令超时({EXEC_TIMEOUT}s)"}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def run_research(query: str = "", topic: str = "", background: str = "", depth: str = "quick") -> dict:
    """研究主题"""
    q = topic or query
    if not q:
        return {"ok": False, "error": "query 或 topic 参数为空"}
    try:
        report = llm_chat([{"role": "user", "content": f"研究{q}，输出结构化报告。背景: {background[:500] or '(无)'}"}], max_tokens=2000)
        knowledge.set(key=f"research:{q[:60]}", value=report[:2000], category="research", confidence=0.8)
        return {"ok": True, "report": report, "topic": q}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def run_memory_search(query: str = "", top_n: int = 10) -> dict:
    """搜索记忆"""
    if not query:
        return {"ok": False, "error": "query 参数为空"}
    results = recall(query, top_n=top_n)
    return {"ok": True, "results": results, "count": len(results)}


def run_summary(text: str = "", style: str = "bullet", max_words: int = 300) -> dict:
    """总结文本"""
    if not text:
        return {"ok": False, "error": "text 参数为空"}
    styles = {"bullet": "用5条以内要点列表", "paragraph": f"用{max_words}字段落", "tldr": "用一句话(50字内)"}
    try:
        summary = llm_chat([{"role": "user", "content": f"请{styles.get(style, styles['bullet'])}总结:\n{text[:3000]}"}], task="cheap", max_tokens=600)
        return {"ok": True, "summary": summary, "original_len": len(text)}
    except Exception as e:
        return {"ok": False, "error": str(e)}
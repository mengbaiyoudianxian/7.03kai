"""Agent Runtime — LLM-driven conversation loop with tool execution.

Derived from agent_runtime.py. Uses MemoryRepo + tools module.
"""

import json, os, re
from app.llm import LLMClient
from app.memory import MemoryRepo
from app.tools import execute as exec_tool, bump_usage, list_tools
from app.models import Message, Session as SessionModel
from sqlalchemy.orm import Session as DBSession

AGENT_PROMPT = """你是"母体-小梦"，由孟白创造的 AI 助手，通过 QQ 和用户聊天。

你是聊天机器人，不是工具执行器。直接回应用户提问，只在以下情况才用工具:
- 用户明确要求执行操作（打开App、执行命令、查设备状态等）
- 需要查文件内容

严禁为自我介绍、问候、闲聊、感谢等日常对话使用任何工具。你是谁、你的名字、你有什么功能等问题直接从系统提示回答，不要搜记忆。

始终用中文回复。短小精炼，三句话以内。

工具格式: <tool>名称</tool><content>参数</content>
每轮最多一个工具。收到工具结果后必须直接回复，禁止继续调用工具。

工具速查:
- search_memory: 搜索记忆库 <tool>search_memory</tool><content>关键词</content>
- read_file: 读取文件 <tool>read_file</tool><content>路径</content>
- run_command: 执行命令 <tool>run_command</tool><content>命令</content>
- device_status: 查设备 <tool>device_status</tool><content>设备调试码</content>
- toggle_wifi: 开关WiFi <tool>toggle_wifi</tool><content>设备调试码 true/false</content>
- open_app: 打开App <tool>open_app</tool><content>设备调试码 包名</content>
- take_screenshot: 截图 <tool>take_screenshot</tool><content>/tmp/s.png</content>

规则:
- QQ纯文本，禁用Markdown、表格、代码块
- 短句分行，三行以内

可用工具完整列表:
{tools_list}

你有上面全部工具。被问工具数量时回答54。"""



TOOL_RE = re.compile(r'<tool>(.*?)</tool>\s*<content>(.*?)</content>', re.DOTALL)
THINK_RE = re.compile(r'<thinking>(.*?)</thinking>', re.DOTALL)



from dataclasses import dataclass, field
from typing import Optional

@dataclass
class StandardMessage:
    """跨渠道统一消息"""
    role: str = 'user'
    content: str = ''
    channel: str = 'unknown'
    user_id: str = ''
    user_name: str = ''
    session_id: str = ''
    timestamp: float = 0.0
    metadata: dict = field(default_factory=dict)

def _build_context(db: DBSession, session_id: int, user_msg: str) -> str:
    """Assemble context: memory hits + recent messages + tools."""
    parts = []

    # Memory
    hits = MemoryRepo(db).query(user_msg, top_n=3)
    if hits:
        parts.append("## 相关记忆")
        for h in hits:
            parts.append(f"- [#{h.session_id}] {h.summary[:200]}")
            if h.keywords:
                parts.append(f"  关键词: {', '.join(h.keywords[:5])}")
        parts.append("")

    # Recent messages
    recent = db.query(Message).filter(
        Message.session_id == session_id
    ).order_by(Message.created_at.desc()).limit(10).all()
    if recent:
        parts.append("## 对话历史")
        for m in reversed(recent):
            role = "用户" if m.role == "user" else "助手" if m.role == "assistant" else m.role
            parts.append(f"[{role}]: {m.content[:300]}")
        parts.append("")

    return "\n".join(parts)


def agent_run(db: DBSession, session_id: int, user_message: str, llm: LLMClient, max_turns: int = 5) -> dict:
    """Execute one agent turn loop."""
    session = db.query(SessionModel).filter(SessionModel.id == session_id).first()
    if not session:
        raise ValueError(f"Session {session_id} not found")
    if session.status == "closed":
        raise ValueError("Session is closed")

    # Record user message
    db.add(Message(session_id=session_id, role="user", content=user_message))
    db.commit()

    tools = list_tools(db)
    tools_text = "\n".join(f"- {t['name']} [{t.get('runtime','server')}]: {t['summary']}" for t in tools)
    system_prompt = AGENT_PROMPT.format(tools_list=tools_text)

    tools_used, thinking, turns = [], [], 0
    current = user_message
    final = ""

    while turns < max_turns:
        turns += 1
        ctx = _build_context(db, session_id, current)

        if os.getenv("MBCLAW_LLM_MOCK") == "1":
            final = f"[MOCK Agent] 收到: {user_message[:100]}。第{turns}轮，上下文{len(ctx)}字符。"
            break

        try:
            import httpx
            resp = httpx.post(f"{llm.base_url}/chat/completions", headers={
                "Content-Type": "application/json",
                **({"Authorization": f"Bearer {llm.api_key}"} if llm.api_key else {})
            }, json={"model": llm.model, "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": f"## 上下文\n{ctx}\n\n## 当前输入\n{current}"}
            ], "temperature": 0.3, "max_tokens": 2000}, timeout=120)
            resp.raise_for_status()
            raw = resp.json()["choices"][0]["message"]["content"]
        except Exception as e:
            final = f"LLM调用失败: {e}"
            break

        # Parse
        tool_matches = [(m.group(1).strip(), m.group(2).strip()) for m in TOOL_RE.finditer(raw)]
        think_matches = [m.group(1).strip() for m in THINK_RE.finditer(raw)]
        clean = TOOL_RE.sub('', raw)
        clean = THINK_RE.sub('', clean).strip()
        thinking.extend(think_matches)
        final = clean

        if tool_matches:
            results = []
            for tname, tcontent in tool_matches:
                tools_used.append(tname)
                bump_usage(db, tname)
                r = exec_tool(db, tname, tcontent)
                results.append(f"<tool-result name=\"{tname}\">\n{r}\n</tool-result>")
                db.add(Message(session_id=session_id, role="assistant",
                               content=f"[tool:{tname}] {r[:200]}"))
            db.commit()
            current = "工具执行结果:\n" + "\n".join(results)
        else:
            break

    db.add(Message(session_id=session_id, role="assistant", content=final))
    db.commit()

    return {"session_id": session_id, "response": final, "tools_used": tools_used,
            "turns": turns, "thinking": thinking, "messages_added": turns + 1}


class MotherAgent:
    """母体核心——接收跨渠道消息并回复"""
    def __init__(self):
        self.sessions = {}

    async def enqueue(self, msg) -> bool:
        """消息入队（同步实现，始终返回True）"""
        return True

    async def process_one(self, msg) -> str:
        """处理单条消息"""
        content = msg.content if hasattr(msg, 'content') else str(msg)
        uid = msg.user_id if hasattr(msg, 'user_id') else 'unknown'
        channel = msg.channel if hasattr(msg, 'channel') else 'unknown'
        return self.send(uid, content, channel)

    def send(self, user_id: str, message: str, channel: str = 'unknown') -> str:
        """处理一条消息并返回回复"""
        from app.llm import LLMClient
        import os
        try:
            llm = LLMClient()
            provider = os.environ.get('MBCLAW_PROVIDER', 'openai')
            model = os.environ.get('MBCLAW_MODEL', '')
            # 简单的单轮调用
            msgs = [{"role": "user", "content": message}]
            reply = llm.chat(msgs, model=model, provider=provider)
            if isinstance(reply, dict):
                return reply.get('content', str(reply))
            return str(reply or '')
        except Exception as e:
            return f'[母体错误] {e}'

def get_mother():
    """获取母体实例（懒加载）"""
    if not hasattr(get_mother, '_instance'):
        get_mother._instance = MotherAgent()
    return get_mother._instance

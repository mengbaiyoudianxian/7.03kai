"""Gateway agent — thin forwarder to MotherRuntime."""
import re

def handle_gateway_agent(msg: str, code: str) -> str:
    """Forward message to MotherRuntime, return reply."""
    from app.mother_runtime import get_runtime
    sid = abs(hash(f"gateway:{code}")) % 100000

    try:
        result = get_runtime().run(msg, session_id=sid, max_turns=5)
        reply = result.get("reply", "")

        # Strip markdown for QQ
        reply = re.sub(r'\*\*([^*]+)\*\*', r'\1', reply)
        reply = re.sub(r'\n{3,}', '\n\n', reply)
        reply = reply.replace('##', '')
        reply = re.sub(r'^#{1,6}\s', '', reply, flags=re.MULTILINE)
        reply = reply.strip()

        return reply or "收到（母体-小梦已读）"
    except Exception as e:
        return f"母体错误: {e}"

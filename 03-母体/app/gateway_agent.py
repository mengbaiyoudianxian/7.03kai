"""Gateway agent endpoint — QQ messages through agent loop (tools+memory+LLM)"""
import os

def handle_gateway_agent(msg: str, user_id: str) -> str:
    from app.llm import LLMClient
    from app.memory import MemoryRepo
    from app.tools import list_tools, execute as exec_tool, bump_usage
    from app.agent import AGENT_PROMPT, TOOL_RE, THINK_RE, _build_context
    from app.db import get_db, SessionLocal
    from app.models import Session as SessionModel, Message
    import httpx, re

    db = SessionLocal()
    try:
        # build tools text
        tools = list_tools(db)
        tools_text = "\n".join(
            f"- {t['name']} [{t.get('runtime','server')}]: {t['summary']}"
            for t in tools
        )
        system_prompt = AGENT_PROMPT.format(tools_list=tools_text)

        # LLM client via token pool
        api_key = os.getenv("MBCLAW_LLM_API_KEY", "")
        base = os.getenv("MBCLAW_LLM_BASE_URL", "")
        model = os.getenv("MBCLAW_LLM_MODEL", "")
        llm = None

        if not api_key:
            try:
                from app.token_pool import get_pool
                best = get_pool().get_best_for_llm()
                if best and len(best) == 3:
                    base, api_key, model = best
            except Exception:
                pass

        if not api_key:
            base = base or "https://api.openai.com/v1"
            model = model or "gpt-4o-mini"

        # Get or create session (skip if DB has schema issues)
        sid = None
        try:
            db.execute(db.text("SELECT 1 FROM sessions LIMIT 1"))
            existing = db.query(SessionModel).filter(
                SessionModel.title == f"qq-{user_id}",
                SessionModel.status == "active"
            ).order_by(SessionModel.started_at.desc()).first()
            if existing:
                sid = existing.id
            else:
                s = SessionModel(title=f"qq-{user_id}", status="active")
                db.add(s)
                db.commit()
                sid = s.id
            db.add(Message(session_id=sid, role="user", content=msg))
            db.commit()
        except Exception:
            sid = 0  # stateless mode

        # Memory recall
        memory_text = ""
        try:
            hits = MemoryRepo(db).query(msg, top_n=3)
            if hits:
                lines = ["## 相关记忆"]
                for h in hits:
                    lines.append(f"- [#{h.session_id}] {h.summary[:200]}")
                    if h.keywords:
                        lines.append(f"  关键词: {', '.join(h.keywords[:5])}")
                memory_text = "\n".join(lines)
        except Exception:
            pass

        # Agent loop (max 5 turns)
        tools_used = []
        current = msg
        final = ""
        turns = 0

        while turns < 5:
            turns += 1

            ctx = memory_text
            try:
                recent = db.query(Message).filter(Message.session_id == sid).order_by(Message.created_at.desc()).limit(10).all()
                if recent:
                    ctx += "\n\n## 对话历史\n" + "\n".join(
                        f"[{m.role}]: {m.content[:200]}" for m in reversed(recent)
                    )
            except Exception:
                pass

            try:
                if not api_key:
                    final = f"[Mock] 收到: {msg[:100]}"
                    break

                resp = httpx.post(
                    f"{base}/chat/completions",
                    headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
                    json={
                        "model": model,
                        "messages": [
                            {"role": "system", "content": system_prompt},
                            {"role": "user", "content": f"## 上下文\n{ctx}\n\n## 当前输入\n{current}"}
                        ],
                        "temperature": 0.3,
                        "max_tokens": 2000,
                    },
                    timeout=120,
                )
                resp.raise_for_status()
                raw = resp.json()["choices"][0]["message"]["content"]
            except Exception as e:
                final = f"LLM调用失败: {e}"
                break

            # Parse tools and thinking
            tool_matches = [(m.group(1).strip(), m.group(2).strip()) for m in TOOL_RE.finditer(raw)]
            think_matches = [m.group(1).strip() for m in THINK_RE.finditer(raw)]
            clean = TOOL_RE.sub('', raw)
            clean = THINK_RE.sub('', clean).strip()
            final = clean

            if tool_matches:
                results = []
                for tname, tcontent in tool_matches:
                    tools_used.append(tname)
                    try:
                        bump_usage(db, tname)
                    except Exception:
                        pass
                    try:
                        r = exec_tool(db, tname, tcontent)
                    except Exception as e:
                        r = f"工具执行错误 [{tname}]: {e}"
                    results.append(f'<tool-result name="{tname}">\n{r}\n</tool-result>')
                current = "工具执行结果:\n" + "\n".join(results)
            else:
                break

        # Strip markdown for QQ plain text
        final = re.sub(r'\*\*([^*]+)\*\*', r'\1', final)
        final = re.sub(r'__([^_]+)__', r'\1', final)
        final = re.sub(r'^#{1,6}\s', '', final, flags=re.MULTILINE)
        final = final.replace('##', '')
        final = re.sub(r'\*\*', '', final)
        final = re.sub(r'\n{3,}', r'\n\n', final)
        final = final.strip()

        # Save assistant response
        try:
            db.add(Message(session_id=sid or 0, role="assistant", content=final))
            db.commit()
        except Exception:
            pass

        if tools_used:
            return f"{final}\n\n[使用了工具: {', '.join(tools_used)}]"
        return final

    except Exception as e:
        return f"母体异常: {e}"
    finally:
        db.close()

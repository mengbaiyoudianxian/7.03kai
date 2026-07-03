"""Gateway agent — QQ -> agent loop with key fallback"""
import os, httpx, re, json

def handle_gateway_agent(msg, user_id):
    from app.memory import MemoryRepo
    from app.tools import list_tools, execute as exec_tool, bump_usage
    from app.agent import AGENT_PROMPT, TOOL_RE, THINK_RE
    from app.db import SessionLocal
    from app.models import Session as SessionModel, Message

    db = SessionLocal()
    try:
        tools = list_tools(db)
        tools_text = "\n".join(
            "- %s [%s]: %s" % (t['name'], t.get('runtime','server'), t['summary'])
            for t in tools
        )
        total = len(tools)
        srv = sum(1 for t in tools if t.get('runtime')=='server')
        adm = sum(1 for t in tools if t.get('runtime')=='admin')
        dev = sum(1 for t in tools if t.get('runtime')=='device-remote')
        tools_text = "[共%d工具 S%d A%d D%d]\n%s" % (total, srv, adm, dev, tools_text)
        sp = AGENT_PROMPT.format(tools_list=tools_text)

        # Build candidate key list ranked by provider order
        candidates = []
        ordered = ['zhipu', 'deepseek-cn', 'custom', 'miclaw-bridge']
        from app.token_pool import get_pool
        pool = get_pool()
        seen = set()
        for pfx in ordered:
            for k in sorted(pool.keys, key=lambda x: (0 if x.status=='working' else 1)):
                if k.provider == pfx and k.api_key and k.base_url not in seen:
                    seen.add(k.base_url)
                    candidates.append((k.base_url, k.api_key, k.model, k.provider))
        # Add remaining working keys
        for k in pool.keys:
            if k.api_key and k.base_url not in seen:
                seen.add(k.base_url)
                candidates.append((k.base_url, k.api_key, k.model, k.provider))

        # Session
        sid = None
        try:
            existing = db.query(SessionModel).filter(
                SessionModel.title=="qq-"+user_id, SessionModel.status=="active"
            ).order_by(SessionModel.started_at.desc()).first()
            if existing: sid = existing.id
            else:
                s = SessionModel(title="qq-"+user_id, status="active")
                db.add(s); db.commit(); sid = s.id
            db.add(Message(session_id=sid, role="user", content=msg))
            db.commit()
        except: sid = 0

        # Memory
        mem = ""
        try:
            hits = MemoryRepo(db).query(msg, 3)
            if hits:
                mem = "## Memory\n" + "\n".join(
                    "- [#%d] %s" % (h.session_id, h.summary[:200]) for h in hits
                )
        except: pass

        tools_used = []
        current = msg
        final = ""
        turns = 0

        while turns < 5:
            turns += 1
            ctx = mem
            try:
                recent = db.query(Message).filter(Message.session_id==sid).order_by(Message.created_at.desc()).limit(10).all()
                if recent:
                    ctx += "\n## History\n" + "\n".join("[%s]: %s"%(m.role,m.content[:200]) for m in reversed(recent))
            except: pass

            raw = None
            last_err = ""
            for ci, (b, ak, mdl, prv) in enumerate(candidates):
                try:
                    r = httpx.post(b.rstrip("/") + "/chat/completions",
                        headers={"Authorization":"Bearer "+ak,"Content-Type":"application/json"},
                        json={"model":mdl,"messages":[
                            {"role":"system","content":sp},
                            {"role":"user","content":"Context:\n"+ctx+"\n\nInput:\n"+current}
                        ],"temperature":0.3,"max_tokens":2000}, timeout=60)
                    if r.status_code == 200:
                        raw = r.json()["choices"][0]["message"]["content"]
                        break
                    elif r.status_code in (401, 403):
                        last_err = "%s:%d" % (prv, r.status_code)
                        continue
                    else:
                        last_err = "%s:%d %s" % (prv, r.status_code, r.text[:60])
                        continue
                except Exception as e:
                    last_err = "%s:%s" % (prv, str(e)[:60])
                    continue

            if raw is None:
                final = "LLMfail: tried %d keys, last: %s" % (len(candidates), last_err)
                break

            tms = [(m.group(1).strip(), m.group(2).strip()) for m in TOOL_RE.finditer(raw)]
            clean = TOOL_RE.sub('', raw).strip()
            clean = THINK_RE.sub('', clean).strip()
            final = clean

            if tms:
                results = []
                for tn, tc in tms:
                    tools_used.append(tn)
                    try: bump_usage(db, tn)
                    except: pass
                    try: r = exec_tool(db, tn, tc)
                    except Exception as e: r = "err [%s]: %s"%(tn,e)
                    results.append('<tool-result name="%s">\n%s\n</tool-result>'%(tn,r))
                current = "Tool results:\n" + "\n".join(results)
            else: break

        # Strip markdown for QQ
        final = re.sub(r'\*\*([^*]+)\*\*', r'\1', final)
        final = final.replace('##','')
        final = re.sub(r'^#{1,6}\s','', final, flags=re.MULTILINE)
        final = re.sub(r'\*\*','', final)
        final = re.sub(r'\n{3,}','\n\n', final)
        final = final.strip()

        try:
            db.add(Message(session_id=sid or 0, role="assistant", content=final))
            db.commit()
        except: pass

        if tools_used:
            return final + "\n\n[tools: %s]" % ', '.join(tools_used)
        return final
    except Exception as e:
        return "error: " + str(e)[:500]
    finally:
        db.close()

import json, struct, math, sqlite3
from app.models import Memory
from app.phase1_db import search_fts

def cosine_similarity(a, b):
    if not a or not b or len(a)!=len(b): return 0.0
    dot=sum(x*y for x,y in zip(a,b))
    na=math.sqrt(sum(x*x for x in a)); nb=math.sqrt(sum(y*y for y in b))
    return dot/(na*nb) if na>0 and nb>0 else 0.0

def decode_embedding(blob):
    n=len(blob)//4
    return list(struct.unpack('<'+str(n)+'f', blob))

def hybrid_search(db, workspace_id, query, query_vec=None, top_k=5):
    # ★ 直接查memory_nodes表 (不是memory表)
    db_path = db.get_bind().url.database
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute(
        'SELECT id, layer, content_json, summary, importance, embedding, usage_count FROM memory_nodes WHERE workspace_id=?',
        (workspace_id,)
    )
    rows = cur.fetchall()
    conn.close()

    # FTS5文本召回
    fts_hits = {}
    try:
        fts_rows = search_fts(query, workspace_id=workspace_id, limit=200)
        for i, r in enumerate(fts_rows):
            raw_id = r.get('id', '')
            fts_hits[raw_id] = 1.0 / (i + 1)
    except:
        pass

    results = []
    for row in rows:
        nid, layer, content_json, summary, importance, embedding, usage_count = row
        score = float(importance or 0.5)

        # 文本匹配
        content_str = str(content_json) + ' ' + (summary or '')
        if query and query in content_str:
            score += 0.30
        for raw_id, fts_score in fts_hits.items():
            if raw_id in content_str:
                score = max(score, fts_score * 0.40)

        # embedding相似度
        if query_vec and embedding:
            try:
                score = cosine_similarity(query_vec, decode_embedding(embedding)) * 0.6 + score * 0.4
            except:
                pass

        # failure boost
        if layer == 'failure':
            score *= 1.30

        # tag匹配
        try:
            content_obj = json.loads(content_json) if content_json else {}
            tags = content_obj.get('tags', [])
            if isinstance(tags, list):
                for t in tags:
                    if t in query:
                        score *= 1.15
                        break
            # 也搜topic/facts
            facts = content_obj.get('facts', [])
            if isinstance(facts, list):
                for f in facts:
                    if query in str(f):
                        score += 0.10
        except:
            pass

        results.append({
            'id': nid, 'type': layer, 'content': json.loads(content_json) if content_json else {},
            'score': round(score, 4), 'importance': importance, 'usage': usage_count or 0,
            'summary': (summary or str(content_json)[:120]), '_memory_obj': None
        })

    results.sort(key=lambda x: x['score'], reverse=True)
    return results[:top_k]

def fts5_search(workspace_id, query, top_k=5):
    rows = search_fts(query, workspace_id=workspace_id, limit=max(top_k * 3, 30))
    results = []
    for i, r in enumerate(rows):
        score = 1.0 / (i + 1)
        if r.get('content_type') == 'failure':
            score *= 1.5
        results.append({
            'id': r['id'], 'type': r.get('content_type'),
            'content': r.get('content'), 'snippet': r.get('snippet'),
            'score': round(score, 4), 'workspace_id': r.get('workspace_id'),
            'role': r.get('role'), 'created_at': r.get('created_at'),
        })
    results.sort(key=lambda x: x['score'], reverse=True)
    return results[:top_k]

def fts5_search_simple(query, workspace_id=None, limit=10):
    from app.phase1_db import search_fts
    return search_fts(query, workspace_id, limit)

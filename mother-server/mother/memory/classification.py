"""P0-1: 树形分类记忆 — ClassificationNode CRUD + 失败方案检索"""
from __future__ import annotations
import json, time
from .registry import get_db


def add_node(parent_id: int | None, level: int, category_name: str,
             summary: str = "", summary_detailed: str = "",
             failed_approaches: list | None = None,
             keywords: list | None = None,
             source_episodes: list | None = None) -> int:
    conn = get_db()
    now = time.time()
    cur = conn.execute(
        "INSERT INTO mother_classification_nodes(parent_id,level,category_name,summary,summary_detailed,"
        "failed_approaches,keywords,source_episodes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        (parent_id, level, category_name, summary, summary_detailed,
         json.dumps(failed_approaches or [], ensure_ascii=False),
         json.dumps(keywords or [], ensure_ascii=False),
         json.dumps(source_episodes or [], ensure_ascii=False),
         now, now))
    conn.commit()
    return cur.lastrowid


def get_node(node_id: int) -> dict | None:
    conn = get_db()
    row = conn.execute("SELECT * FROM mother_classification_nodes WHERE id=?", (node_id,)).fetchone()
    return _row_to_dict(row) if row else None


def get_tree(root_id: int | None = None) -> list[dict]:
    """返回完整分类树"""
    conn = get_db()
    if root_id is not None:
        rows = conn.execute(
            "SELECT * FROM mother_classification_nodes WHERE id=? OR parent_id=? ORDER BY level, id",
            (root_id, root_id)).fetchall()
    else:
        rows = conn.execute(
            "SELECT * FROM mother_classification_nodes ORDER BY level, id").fetchall()
    return [_row_to_dict(r) for r in rows]


def get_children(parent_id: int) -> list[dict]:
    conn = get_db()
    rows = conn.execute(
        "SELECT * FROM mother_classification_nodes WHERE parent_id=? ORDER BY id", (parent_id,)).fetchall()
    return [_row_to_dict(r) for r in rows]


def search_nodes(query: str, limit: int = 10) -> list[dict]:
    conn = get_db()
    rows = conn.execute(
        "SELECT * FROM mother_classification_nodes WHERE category_name LIKE ? OR summary LIKE ? "
        "OR keywords LIKE ? ORDER BY use_count DESC LIMIT ?",
        (f"%{query}%", f"%{query}%", f"%{query}%", limit)).fetchall()
    return [_row_to_dict(r) for r in rows]


def get_failed_approaches(limit: int = 20) -> list[dict]:
    """获取所有失败方案"""
    conn = get_db()
    rows = conn.execute(
        "SELECT * FROM mother_classification_nodes WHERE failed_approaches != '[]' "
        "ORDER BY updated_at DESC LIMIT ?", (limit,)).fetchall()
    results = []
    for r in rows:
        d = _row_to_dict(r)
        fas = d.get("failed_approaches", [])
        if isinstance(fas, str):
            try: fas = json.loads(fas)
            except: fas = []
        for fa in fas:
            results.append({"node_id": d["id"], "category": d["category_name"],
                           "approach": fa.get("approach", ""),
                           "why_failed": fa.get("why_failed", ""),
                           "lesson": fa.get("lesson", "")})
    return results


def get_failed_for_context(category_query: str, limit: int = 5) -> str:
    """为 System Prompt 生成失败方案注入文本"""
    fas = get_failed_approaches(limit * 3)
    # 简单关键词过滤
    matched = [fa for fa in fas if category_query.lower() in fa.get("category", "").lower()
               or any(kw.lower() in fa.get("approach", "").lower() for kw in category_query.split())]
    if not matched:
        matched = fas[:limit]
    if not matched:
        return ""
    lines = ["\n⚠️ 已知失败方案（请避免）："]
    for fa in matched[:limit]:
        lines.append(f"- [{fa['category']}] {fa['approach'][:80]}: {fa['why_failed'][:80]}")
    return "\n".join(lines)


def update_node(node_id: int, **kwargs) -> bool:
    conn = get_db()
    allowed = {"parent_id", "level", "category_name", "summary", "summary_detailed",
               "failed_approaches", "keywords", "source_episodes"}
    updates = {k: v for k, v in kwargs.items() if k in allowed}
    if not updates:
        return False
    updates["updated_at"] = time.time()
    for k in ("failed_approaches", "keywords", "source_episodes"):
        if k in updates and isinstance(updates[k], list):
            updates[k] = json.dumps(updates[k], ensure_ascii=False)
    set_clause = ", ".join(f"{k}=?" for k in updates)
    values = list(updates.values()) + [node_id]
    conn.execute(f"UPDATE mother_classification_nodes SET {set_clause} WHERE id=?", values)
    conn.commit()
    return True


def mark_used(node_id: int):
    conn = get_db()
    conn.execute("UPDATE mother_classification_nodes SET use_count=use_count+1 WHERE id=?", (node_id,))
    conn.commit()


def count() -> int:
    conn = get_db()
    return conn.execute("SELECT COUNT(*) FROM mother_classification_nodes").fetchone()[0]


def _row_to_dict(row) -> dict:
    d = dict(row)
    for f in ("failed_approaches", "keywords", "source_episodes"):
        try:
            d[f] = json.loads(d.get(f, "[]"))
        except (json.JSONDecodeError, TypeError):
            d[f] = []
    return d

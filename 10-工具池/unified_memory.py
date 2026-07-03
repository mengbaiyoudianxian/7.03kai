#!/usr/bin/env python3
"""
统一记忆系统 v3
合并事实级记忆 + 项目级知识树

架构：
  快速记忆（事实级）—— 用户偏好、设备信息、关键事实
  深度记忆（项目级）—— 项目摘要、详细内容、失败方案、关键词索引

检索时两个都查，合并结果，按相关度排序
"""

import json
import os
import re
import hashlib
import requests
from datetime import datetime
from pathlib import Path

# ==================== 配置 ====================

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
FACTS_FILE = os.path.join(DATA_DIR, "facts.json")           # 事实级记忆
KNOWLEDGE_DIR = os.path.join(DATA_DIR, "knowledge")          # 项目级知识树
INDEX_FILE = os.path.join(DATA_DIR, "index.json")            # 全局索引

LLM_URL = os.environ.get("LLM_URL", "https://ark.cn-beijing.volces.com/api/v3/chat/completions")
LLM_MODEL = os.environ.get("LLM_MODEL", "doubao-1-5-vision-pro-32k-250115")
LLM_KEY = os.environ.get("LLM_KEY", "ark-99f1fc64b5fc42cabd9f7c294567c2fc7b")

os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(KNOWLEDGE_DIR, exist_ok=True)


# ==================== LLM调用 ====================

def llm_call(prompt, max_tokens=2000):
    """调用LLM"""
    headers = {
        "Authorization": f"Bearer {LLM_KEY}",
        "Content-Type": "application/json"
    }
    data = {
        "model": LLM_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens
    }
    try:
        resp = requests.post(LLM_URL, headers=headers, json=data, timeout=60)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]
    except Exception as e:
        print(f"[Memory] LLM error: {e}")
        return None


# ==================== 分词工具 ====================

def tokenize(text):
    """简单中英文分词"""
    cn_words = re.findall(r'[\u4e00-\u9fff]{2,4}', text)
    en_words = re.findall(r'[a-zA-Z]+', text.lower())
    return cn_words + en_words


def memory_id(text):
    return hashlib.md5(text.encode("utf-8")).hexdigest()[:12]


# ==================== 快速记忆（事实级）====================

def load_facts():
    """加载事实记忆"""
    if os.path.exists(FACTS_FILE):
        with open(FACTS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {"memories": [], "updated_at": None}


def save_facts(data):
    """保存事实记忆"""
    data["updated_at"] = datetime.now().isoformat()
    with open(FACTS_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def add_fact(fact_text, category="其他", keywords=None):
    """添加事实"""
    data = load_facts()
    mid = memory_id(fact_text)

    # 去重
    for m in data["memories"]:
        if m["id"] == mid:
            m["count"] = m.get("count", 1) + 1
            m["last_seen"] = datetime.now().isoformat()
            save_facts(data)
            return mid

    if not keywords:
        keywords = tokenize(fact_text)

    entry = {
        "id": mid,
        "fact": fact_text,
        "category": category,
        "keywords": keywords,
        "tokens": tokenize(fact_text),
        "created_at": datetime.now().isoformat(),
        "last_seen": datetime.now().isoformat(),
        "count": 1
    }
    data["memories"].append(entry)
    save_facts(data)
    return mid


def search_facts(query, top_k=5, threshold=0.15):
    """搜索事实记忆"""
    data = load_facts()
    if not data["memories"]:
        return []

    query_tokens = set(tokenize(query))
    query_lower = query.lower()

    results = []
    for m in data["memories"]:
        score = 0.0

        # 关键词匹配
        m_tokens = set(m.get("tokens", []) + m.get("keywords", []))
        if query_tokens and m_tokens:
            overlap = query_tokens & m_tokens
            if overlap:
                score += len(overlap) / max(len(query_tokens), 1) * 0.6

        # 事实文本包含查询词
        fact_lower = m["fact"].lower()
        for qt in query_tokens:
            if len(qt) >= 2 and qt in fact_lower:
                score += 0.3

        # 分类匹配加分
        category_hints = {
            "偏好": ["喜欢", "爱", "偏好", "最爱"],
            "习惯": ["习惯", "经常", "每天"],
            "个人信息": ["名字", "叫", "是"],
            "设备": ["手机", "电脑", "设备"],
            "项目": ["项目", "代码", "开发"],
        }
        for cat, hints in category_hints.items():
            if m.get("category") == cat:
                for h in hints:
                    if h in query_lower:
                        score += 0.2
                        break

        # 频次加权
        count = m.get("count", 1)
        score *= (1 + min(count - 1, 5) * 0.05)

        if score >= threshold:
            results.append({
                "type": "fact",
                "fact": m["fact"],
                "category": m["category"],
                "score": round(score, 4),
                "count": m.get("count", 1)
            })

    results.sort(key=lambda x: x["score"], reverse=True)
    return results[:top_k]


# ==================== 深度记忆（项目级）====================

def load_index():
    """加载全局索引"""
    if os.path.exists(INDEX_FILE):
        with open(INDEX_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {"projects": {}, "keywords": {}, "updated_at": None}


def save_index(index):
    """保存全局索引"""
    index["updated_at"] = datetime.now().isoformat()
    with open(INDEX_FILE, "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)


def create_project(name, description=""):
    """创建项目"""
    project_dir = os.path.join(KNOWLEDGE_DIR, name)
    os.makedirs(project_dir, exist_ok=True)
    os.makedirs(os.path.join(project_dir, "details"), exist_ok=True)

    # 创建项目元数据
    meta = {
        "name": name,
        "description": description,
        "created_at": datetime.now().isoformat(),
        "updated_at": datetime.now().isoformat(),
        "summary": "",
        "keywords": [],
        "failed_approaches": [],
        "status": "active"
    }
    meta_path = os.path.join(project_dir, "meta.json")
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)

    # 更新全局索引
    index = load_index()
    index["projects"][name] = {
        "dir": project_dir,
        "description": description,
        "created_at": datetime.now().isoformat()
    }
    save_index(index)

    return project_dir


def update_project_summary(project_name, summary, keywords=None):
    """更新项目摘要"""
    project_dir = os.path.join(KNOWLEDGE_DIR, project_name)
    meta_path = os.path.join(project_dir, "meta.json")

    if not os.path.exists(meta_path):
        create_project(project_name)

    with open(meta_path, "r", encoding="utf-8") as f:
        meta = json.load(f)

    meta["summary"] = summary
    meta["updated_at"] = datetime.now().isoformat()
    if keywords:
        meta["keywords"] = keywords

    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)

    # 更新全局关键词索引
    if keywords:
        index = load_index()
        for kw in keywords:
            if kw not in index["keywords"]:
                index["keywords"][kw] = []
            if project_name not in index["keywords"][kw]:
                index["keywords"][kw].append(project_name)
        save_index(index)


def add_failed_approach(project_name, approach, reason):
    """记录失败方案"""
    project_dir = os.path.join(KNOWLEDGE_DIR, project_name)
    meta_path = os.path.join(project_dir, "meta.json")

    if not os.path.exists(meta_path):
        create_project(project_name)

    with open(meta_path, "r", encoding="utf-8") as f:
        meta = json.load(f)

    meta["failed_approaches"].append({
        "approach": approach,
        "reason": reason,
        "recorded_at": datetime.now().isoformat()
    })
    meta["updated_at"] = datetime.now().isoformat()

    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)


def add_project_detail(project_name, topic, content):
    """添加项目详细内容"""
    project_dir = os.path.join(KNOWLEDGE_DIR, project_name)
    details_dir = os.path.join(project_dir, "details")
    os.makedirs(details_dir, exist_ok=True)

    # 文件名用topic的hash
    filename = f"{memory_id(topic)}.md"
    filepath = os.path.join(details_dir, filename)

    with open(filepath, "w", encoding="utf-8") as f:
        f.write(f"# {topic}\n\n")
        f.write(f"创建时间: {datetime.now().isoformat()}\n\n")
        f.write(content)

    # 更新项目关键词
    meta_path = os.path.join(project_dir, "meta.json")
    if os.path.exists(meta_path):
        with open(meta_path, "r", encoding="utf-8") as f:
            meta = json.load(f)
        new_keywords = tokenize(topic)
        existing = set(meta.get("keywords", []))
        existing.update(new_keywords)
        meta["keywords"] = list(existing)
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)

    return filepath


def search_knowledge(query, top_k=5):
    """搜索项目级知识"""
    index = load_index()
    query_tokens = set(tokenize(query))

    results = []

    # 1. 关键词精确匹配
    for kw, projects in index.get("keywords", {}).items():
        if kw in query_tokens:
            for project_name in projects:
                project_dir = os.path.join(KNOWLEDGE_DIR, project_name)
                meta_path = os.path.join(project_dir, "meta.json")
                if os.path.exists(meta_path):
                    with open(meta_path, "r", encoding="utf-8") as f:
                        meta = json.load(f)
                    results.append({
                        "type": "project",
                        "project": project_name,
                        "summary": meta.get("summary", ""),
                        "keywords": meta.get("keywords", []),
                        "failed": meta.get("failed_approaches", []),
                        "score": 0.8,
                        "match_type": "keyword_exact"
                    })

    # 2. 项目名匹配
    for project_name, info in index.get("projects", {}).items():
        project_tokens = set(tokenize(project_name))
        overlap = query_tokens & project_tokens
        if overlap:
            score = len(overlap) / max(len(query_tokens), 1) * 0.6
            # 检查是否已经添加
            existing = [r["project"] for r in results]
            if project_name not in existing:
                meta_path = os.path.join(info["dir"], "meta.json")
                if os.path.exists(meta_path):
                    with open(meta_path, "r", encoding="utf-8") as f:
                        meta = json.load(f)
                    results.append({
                        "type": "project",
                        "project": project_name,
                        "summary": meta.get("summary", ""),
                        "keywords": meta.get("keywords", []),
                        "failed": meta.get("failed_approaches", []),
                        "score": round(score, 4),
                        "match_type": "name_match"
                    })

    # 3. 摘要文本匹配
    for project_name, info in index.get("projects", {}).items():
        existing = [r["project"] for r in results]
        if project_name in existing:
            continue
        meta_path = os.path.join(info["dir"], "meta.json")
        if os.path.exists(meta_path):
            with open(meta_path, "r", encoding="utf-8") as f:
                meta = json.load(f)
            summary = meta.get("summary", "")
            summary_tokens = set(tokenize(summary))
            overlap = query_tokens & summary_tokens
            if overlap:
                score = len(overlap) / max(len(query_tokens), 1) * 0.4
                results.append({
                    "type": "project",
                    "project": project_name,
                    "summary": summary,
                    "keywords": meta.get("keywords", []),
                    "failed": meta.get("failed_approaches", []),
                    "score": round(score, 4),
                    "match_type": "summary_match"
                })

    results.sort(key=lambda x: x["score"], reverse=True)
    return results[:top_k]


# ==================== 统一检索 ====================

def unified_search(query, top_k=8):
    """统一检索：事实级 + 项目级"""
    facts = search_facts(query, top_k=top_k)
    knowledge = search_knowledge(query, top_k=top_k)

    # 合并并按分数排序
    all_results = facts + knowledge
    all_results.sort(key=lambda x: x["score"], reverse=True)

    return all_results[:top_k]


def format_for_prompt(query, top_k=8):
    """格式化为prompt注入"""
    results = unified_search(query, top_k)
    if not results:
        return ""

    lines = ["## 相关记忆（自动召回）"]

    # 先输出项目级
    projects = [r for r in results if r["type"] == "project"]
    if projects:
        lines.append("\n### 项目记忆")
        for r in projects:
            lines.append(f"- **{r['project']}**: {r['summary'][:200]}")
            if r.get("failed"):
                lines.append(f"  - ⚠️ 失败方案: {r['failed'][0]['approach']} ({r['failed'][0]['reason'][:50]})")

    # 再输出事实级
    fact_results = [r for r in results if r["type"] == "fact"]
    if fact_results:
        lines.append("\n### 事实记忆")
        for r in fact_results:
            lines.append(f"- [{r['category']}] {r['fact']}")

    return "\n".join(lines)


# ==================== 自动分析（项目二）====================

def analyze_conversation(text, session_id="unknown"):
    """分析对话，提取事实和项目信息"""
    prompt = f"""分析以下对话，提取信息。用JSON格式输出：

1. 事实（facts）：用户偏好、设备信息、关键事实
2. 项目（projects）：涉及的项目、进展、失败方案

输出格式：
```json
{{
  "facts": [
    {{"fact": "事实", "category": "分类", "keywords": ["关键词"]}}
  ],
  "projects": [
    {{
      "name": "项目名",
      "summary": "简短摘要",
      "keywords": ["关键词"],
      "detail": "详细内容",
      "failed": [{{"approach": "方案", "reason": "失败原因"}}]
    }}
  ]
}}
```

分类选项：偏好、习惯、个人信息、日程、技能、关系、设备、项目、其他

对话内容：
{text[:4000]}"""

    result = llm_call(prompt, max_tokens=2000)
    if not result:
        return {"facts": [], "projects": []}

    # 提取JSON
    try:
        # 找到JSON块
        json_match = re.search(r'```json\s*(.*?)\s*```', result, re.DOTALL)
        if json_match:
            return json.loads(json_match.group(1))
        # 直接尝试解析
        return json.loads(result)
    except:
        return {"facts": [], "projects": []}


def process_conversation(text, session_id="unknown"):
    """处理对话，提取并存储"""
    analysis = analyze_conversation(text, session_id)

    added_facts = []
    added_projects = []

    # 存储事实
    for f in analysis.get("facts", []):
        mid = add_fact(f["fact"], f.get("category", "其他"), f.get("keywords", []))
        if mid:
            added_facts.append(f["fact"])

    # 存储项目
    for p in analysis.get("projects", []):
        name = p.get("name", "unnamed")
        summary = p.get("summary", "")
        keywords = p.get("keywords", [])
        detail = p.get("detail", "")
        failed = p.get("failed", [])

        # 更新摘要
        update_project_summary(name, summary, keywords)

        # 添加详细内容
        if detail:
            add_project_detail(name, summary[:50], detail)

        # 记录失败方案
        for f in failed:
            add_failed_approach(name, f.get("approach", ""), f.get("reason", ""))

        added_projects.append(name)

    return {
        "facts": added_facts,
        "projects": added_projects
    }


# ==================== CLI ====================

if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("统一记忆系统 v3")
        print("用法:")
        print("  python3 unified_memory.py search '查询'")
        print("  python3 unified_memory.py learn '对话文本'")
        print("  python3 unified_memory.py inject '查询'")
        print("  python3 unified_memory.py add-fact '事实' [分类]")
        print("  python3 unified_memory.py add-project '项目名' '摘要'")
        print("  python3 unified_memory.py add-failed '项目名' '方案' '原因'")
        print("  python3 unified_memory.py stats")
        sys.exit(0)

    cmd = sys.argv[1]

    if cmd == "search":
        q = sys.argv[2] if len(sys.argv) > 2 else input("查询: ")
        results = unified_search(q)
        if results:
            for r in results:
                if r["type"] == "fact":
                    print(f"[{r['score']}] [事实:{r['category']}] {r['fact']}")
                else:
                    print(f"[{r['score']}] [项目:{r['project']}] {r['summary'][:80]}")
                    if r.get("failed"):
                        for f in r["failed"]:
                            print(f"  ⚠️ 失败: {f['approach']} - {f['reason'][:50]}")
        else:
            print("没有相关记忆")

    elif cmd == "learn":
        text = sys.argv[2] if len(sys.argv) > 2 else input("对话: ")
        result = process_conversation(text)
        print(f"学习了 {len(result['facts'])} 条事实, {len(result['projects'])} 个项目")
        for f in result["facts"]:
            print(f"  事实: {f}")
        for p in result["projects"]:
            print(f"  项目: {p}")

    elif cmd == "inject":
        q = sys.argv[2] if len(sys.argv) > 2 else input("查询: ")
        result = format_for_prompt(q)
        print(result if result else "(无相关记忆)")

    elif cmd == "add-fact":
        fact = sys.argv[2]
        cat = sys.argv[3] if len(sys.argv) > 3 else "其他"
        mid = add_fact(fact, cat)
        print(f"已添加: {mid}")

    elif cmd == "add-project":
        name = sys.argv[2]
        summary = sys.argv[3] if len(sys.argv) > 3 else ""
        create_project(name)
        update_project_summary(name, summary)
        print(f"已创建项目: {name}")

    elif cmd == "add-failed":
        name = sys.argv[2]
        approach = sys.argv[3]
        reason = sys.argv[4] if len(sys.argv) > 4 else ""
        add_failed_approach(name, approach, reason)
        print(f"已记录失败方案: {name}")

    elif cmd == "stats":
        facts = load_facts()
        index = load_index()
        print(f"事实记忆: {len(facts.get('memories', []))} 条")
        print(f"项目数: {len(index.get('projects', {}))} 个")
        print(f"关键词数: {len(index.get('keywords', {}))} 个")
        print("\n项目列表:")
        for name, info in index.get("projects", {}).items():
            print(f"  - {name}")

    else:
        print(f"未知命令: {cmd}")

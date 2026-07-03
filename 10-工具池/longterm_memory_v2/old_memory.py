"""
小爪轻量长记忆系统 v2
基于 LLM 事实提取 + 关键词/分类匹配（不依赖 embedding）
"""

import json
import os
import re
import time
import hashlib
import requests
from datetime import datetime
from collections import Counter

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
MEMORIES_FILE = os.path.join(DATA_DIR, "memories.json")

LLM_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
LLM_MODEL = "doubao-1-5-vision-pro-32k-250115"
ARK_API_KEY = "ark-99d16dd6-2f84-4ed6-a561-d055a0387f59-9fc7b"

os.makedirs(DATA_DIR, exist_ok=True)


def llm_call(prompt, max_tokens=500):
    """调用 LLM"""
    headers = {
        "Authorization": "Bearer {}".format(ARK_API_KEY),
        "Content-Type": "application/json"
    }
    data = {
        "model": LLM_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens
    }
    try:
        resp = requests.post(LLM_URL, headers=headers, json=data, timeout=30)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]
    except Exception as e:
        print("[Memory] LLM error: {}".format(e))
        return None


def extract_facts(text):
    """从对话中提取关键事实"""
    prompt = """分析以下对话，提取用户的关键信息。每条用一行JSON表示：
{{"fact": "事实", "category": "分类", "keywords": ["关键词1","关键词2"]}}

分类选项：偏好、习惯、个人信息、日程、技能、关系、设备、项目、其他
关键词：用于后续搜索匹配的中文/英文关键词

只输出JSON行，无事实则输出空。对话：
{text}""".format(text=text[:3000])

    result = llm_call(prompt)
    if not result:
        return []
    
    facts = []
    for line in result.strip().split("\n"):
        line = line.strip()
        if not line or not line.startswith("{"):
            continue
        try:
            obj = json.loads(line)
            if "fact" in obj:
                obj.setdefault("keywords", [])
                obj.setdefault("category", "其他")
                facts.append(obj)
        except:
            continue
    return facts


def load_memories():
    if os.path.exists(MEMORIES_FILE):
        with open(MEMORIES_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {"memories": [], "updated_at": None}


def save_memories(data):
    data["updated_at"] = datetime.now().isoformat()
    with open(MEMORIES_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def memory_id(fact_text):
    return hashlib.md5(fact_text.encode("utf-8")).hexdigest()[:12]


def tokenize(text):
    """简单中英文分词"""
    # 提取中文词和英文词
    cn_words = re.findall(r'[\u4e00-\u9fff]{2,4}', text)
    en_words = re.findall(r'[a-zA-Z]+', text.lower())
    return cn_words + en_words


def add_memory(fact_text, category="其他", keywords=None, source="conversation"):
    """添加记忆"""
    data = load_memories()
    mid = memory_id(fact_text)
    
    # 检查重复
    for m in data["memories"]:
        if m["id"] == mid:
            m["count"] = m.get("count", 1) + 1
            m["last_seen"] = datetime.now().isoformat()
            save_memories(data)
            return mid
    
    # 自动提取关键词
    if not keywords:
        keywords = tokenize(fact_text)
    
    entry = {
        "id": mid,
        "fact": fact_text,
        "category": category,
        "keywords": keywords,
        "tokens": tokenize(fact_text),  # 用于匹配的分词
        "source": source,
        "created_at": datetime.now().isoformat(),
        "last_seen": datetime.now().isoformat(),
        "count": 1
    }
    data["memories"].append(entry)
    save_memories(data)
    return mid


def search_memory(query, top_k=5, threshold=0.15):
    """搜索相关记忆（关键词 + 分类 + 语义匹配）"""
    data = load_memories()
    if not data["memories"]:
        return []
    
    query_tokens = set(tokenize(query))
    query_lower = query.lower()
    
    results = []
    for m in data["memories"]:
        score = 0.0
        
        # 1. 关键词匹配
        m_tokens = set(m.get("tokens", []) + m.get("keywords", []))
        if query_tokens and m_tokens:
            overlap = query_tokens & m_tokens
            if overlap:
                score += len(overlap) / max(len(query_tokens), 1) * 0.6
        
        # 2. 事实文本包含查询词
        fact_lower = m["fact"].lower()
        for qt in query_tokens:
            if len(qt) >= 2 and qt in fact_lower:
                score += 0.3
        
        # 3. 分类匹配加分
        category_hints = {
            "偏好": ["喜欢", "爱", "偏好", "最爱", "prefer"],
            "习惯": ["习惯", "经常", "每天", "always"],
            "个人信息": ["名字", "叫", "是", "name"],
            "设备": ["手机", "电脑", "设备", "phone", "device"],
            "项目": ["项目", "代码", "开发", "project", "code"],
            "日程": ["时间", "日期", "提醒", "schedule"],
        }
        for cat, hints in category_hints.items():
            if m.get("category") == cat:
                for h in hints:
                    if h in query_lower:
                        score += 0.2
                        break
        
        # 4. 记忆频次加权
        count = m.get("count", 1)
        score *= (1 + min(count - 1, 5) * 0.05)
        
        if score >= threshold:
            results.append({
                "fact": m["fact"],
                "category": m["category"],
                "score": round(score, 4),
                "count": m.get("count", 1)
            })
    
    results.sort(key=lambda x: x["score"], reverse=True)
    return results[:top_k]


def process_conversation(text):
    """处理对话，提取并存储事实"""
    facts = extract_facts(text)
    added = []
    for f in facts:
        mid = add_memory(
            f["fact"],
            f.get("category", "其他"),
            f.get("keywords", [])
        )
        if mid:
            added.append(f["fact"])
    return added


def get_all_memories(category=None):
    data = load_memories()
    memories = data["memories"]
    if category:
        memories = [m for m in memories if m["category"] == category]
    return sorted(memories, key=lambda x: x.get("count", 1), reverse=True)


def format_for_prompt(query, top_k=5):
    """格式化为 prompt 注入"""
    results = search_memory(query, top_k)
    if not results:
        return ""
    lines = ["## 用户相关记忆（自动召回）"]
    for r in results:
        lines.append("- [{cat}] {fact}".format(cat=r["category"], fact=r["fact"]))
    return "\n".join(lines)


def delete_memory(mid):
    """删除记忆"""
    data = load_memories()
    data["memories"] = [m for m in data["memories"] if m["id"] != mid]
    save_memories(data)


# ============ CLI ============
if __name__ == "__main__":
    import sys
    
    if len(sys.argv) < 2:
        print("小爪长记忆系统 v2")
        print("用法:")
        print("  python memory.py add '事实' [分类] [关键词,逗号分隔]")
        print("  python memory.py search '查询'")
        print("  python memory.py process '对话文本'")
        print("  python memory.py list [分类]")
        print("  python memory.py stats")
        print("  python memory.py delete 'id'")
        sys.exit(0)
    
    cmd = sys.argv[1]
    
    if cmd == "add":
        fact = sys.argv[2] if len(sys.argv) > 2 else input("事实: ")
        cat = sys.argv[3] if len(sys.argv) > 3 else "其他"
        kws = sys.argv[4].split(",") if len(sys.argv) > 4 else None
        mid = add_memory(fact, cat, kws)
        print("已添加: {}".format(mid))
    
    elif cmd == "search":
        q = sys.argv[2] if len(sys.argv) > 2 else input("查询: ")
        results = search_memory(q)
        if results:
            for r in results:
                print("[{score}] [{category}] {fact}".format(**r))
        else:
            print("没有相关记忆")
    
    elif cmd == "process":
        t = sys.argv[2] if len(sys.argv) > 2 else input("对话: ")
        added = process_conversation(t)
        print("提取了 {} 条:".format(len(added)))
        for a in added:
            print("  - {}".format(a))
    
    elif cmd == "list":
        cat = sys.argv[2] if len(sys.argv) > 2 else None
        mems = get_all_memories(cat)
        print("共 {} 条记忆:".format(len(mems)))
        for m in mems:
            print("  [{cat}] {fact} (x{count})".format(**m))
    
    elif cmd == "stats":
        data = load_memories()
        cats = {}
        for m in data["memories"]:
            c = m.get("category", "其他")
            cats[c] = cats.get(c, 0) + 1
        print("记忆总数: {}".format(len(data["memories"])))
        print("分类:")
        for c, n in sorted(cats.items(), key=lambda x: -x[1]):
            print("  {}: {}".format(c, n))
    
    elif cmd == "delete":
        mid = sys.argv[2]
        delete_memory(mid)
        print("已删除: {}".format(mid))

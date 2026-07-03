#!/usr/bin/env python3
"""
OpenClaw 长记忆集成脚本
用法:
  python3 openclaw_memory.py recall "用户查询"   # 召回相关记忆
  python3 openclaw_memory.py learn "对话文本"    # 从对话中学习
  python3 openclaw_memory.py inject "用户查询"   # 生成 prompt 注入文本
"""

import sys
import os

# 添加模块路径
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from memory import search_memory, process_conversation, format_for_prompt


def recall(query, top_k=5):
    """召回相关记忆"""
    results = search_memory(query, top_k)
    if not results:
        return "暂无相关记忆"
    lines = []
    for r in results:
        lines.append("[{score}] [{category}] {fact}".format(**r))
    return "\n".join(lines)


def learn(text):
    """从对话中学习"""
    added = process_conversation(text)
    if not added:
        return "没有提取到新事实"
    return "学习了 {} 条新事实:\n{}".format(
        len(added), "\n".join("- " + a for a in added))


def inject(query, top_k=5):
    """生成 prompt 注入文本"""
    return format_for_prompt(query, top_k)


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("用法: python3 openclaw_memory.py [recall|learn|inject] '文本'")
        sys.exit(1)
    
    cmd = sys.argv[1]
    text = sys.argv[2]
    
    if cmd == "recall":
        print(recall(text))
    elif cmd == "learn":
        print(learn(text))
    elif cmd == "inject":
        result = inject(text)
        print(result if result else "(无相关记忆)")
    else:
        print("未知命令: {}".format(cmd))

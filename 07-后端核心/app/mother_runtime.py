"""MotherRuntime — session-aware agent loop with WorkingMemory.

One instance per session. Gateway is a thin forwarder.
"""
import re, time, httpx

# ── WorkingMemory ──────────────────────────────────────────
class WorkingMemory:
    """In-memory context for one session. Auto-compresses at 80% token limit."""
    COMPRESS_THRESHOLD = 0.80

    def __init__(self, token_limit=6000):
        self.limit = token_limit
        self.system = ""
        self.messages: list[dict] = []  # {role, content, ts}
        self.recall = ""
        self._compress_count = 0

    def set_system(self, text: str):
        self.system = text

    def add(self, role: str, content: str):
        self.messages.append({"role": role, "content": content, "ts": time.time()})
        self._maybe_compress()

    def set_recall(self, texts: list[str]):
        if not texts:
            self.recall = ""
            return
        self.recall = "相关记忆：\n" + "\n".join(f"- {t[:200]}" for t in texts[:3])

    def to_messages(self) -> list[dict]:
        out = [{"role": "system", "content": self.system}]
        if self.recall:
            out.append({"role": "system", "content": self.recall})
        for m in self.messages[-20:]:  # last 20
            out.append({"role": m["role"], "content": m["content"]})
        return out

    def total_tokens(self) -> int:
        n = len(self.system) // 4
        for m in self.messages:
            n += len(str(m.get("content", ""))) // 4
        n += len(self.recall) // 4
        return n

    def _maybe_compress(self):
        if self.total_tokens() < self.limit * self.COMPRESS_THRESHOLD:
            return
        if len(self.messages) < 4:
            return
        half = len(self.messages) // 2
        old = self.messages[:half]
        self.messages = self.messages[half:]
        summary = " | ".join(str(m.get("content", ""))[:80] for m in old[-3:])
        self.messages.insert(0, {"role": "system",
            "content": f"[历史摘要 #{self._compress_count}] {summary}",
            "ts": time.time()})
        self._compress_count += 1


# ── Tool defs — 全部42技能 ──────────────────────────────────
TOOL_DEFS_TEXT = """工具 (格式: <tool>名称</tool><content>参数</content>):

【系统】
- run_command: 执行shell命令
  参数: 命令文本
- read_file: 读取文件 (也支持 write_file/edit_file/list_directory)
  参数: 文件路径
- search_memory: 搜索记忆库
  参数: 关键词

【GitHub】 (需 GITHUB_TOKEN)
- github_search_code: 搜索代码
  参数: [owner/repo] 关键词
- github_list_repos: 列出仓库
  参数: [用户名, 不填则自己]
- github_get_pr: 获取PR详情
  参数: owner/repo PR编号
- github_create_pr: 创建PR
  参数: owner/repo\\n标题\\n分支\\n[base分支]\\n[描述]
- github_list_issues: 列出Issues
  参数: owner/repo
- github_create_issue: 创建Issue
  参数: owner/repo\\n标题\\n[描述]
- github_get_file: 读取仓库文件
  参数: owner/repo 路径 [分支]
- github_list_workflows: 列出Actions工作流
  参数: owner/repo
- github_workflow_runs: 查看工作流运行
  参数: owner/repo [workflow_id]
- github_pr_review: PR审查
  参数: owner/repo\\nPR编号\\naction(approve/comment/request_changes)\\n[内容]
- github_pr_diff: 获取PR diff
  参数: owner/repo PR编号
- github_compare: 比较分支差异
  参数: owner/repo base head
- github_create_release: 创建Release
  参数: owner/repo\\ntag\\n[名称]\\n[说明]

【远程/服务器】
- ssh_exec: SSH远程执行命令
  参数: host 命令 [user] [port]

【设备】
- device_status: 查设备状态
  参数: 设备调试码
- open_app: 远程打开App
  参数: 设备调试码 包名

【外部API — 暂不可用，需配置Token】
- gitlab_api / bitbucket_api / linear_api / jira_api / notion_api
- datadog_api / vercel_api / discord_api / slack_api / azure_devops_api
  以上API工具需要对应Token环境变量"""

TOOL_RE = re.compile(r'<tool>(.*?)</tool>\s*<content>(.*?)</content>', re.DOTALL)
THINK_RE = re.compile(r'<thinking>(.*?)</thinking>', re.DOTALL)


# ── System prompt (含全技能) ────────────────────────────────
SYSTEM_PROMPT = """你是"母体-小梦"，由孟白创造的超级 AI 助手，通过 QQ 和用户交互。

你有 42 项内置技能覆盖 5 大领域:

## 代码
- **code-review**: 严格代码审查。数据结构→安全→简洁→风险。分级输出(严重/警告/建议)+修复建议。
- **code-simplifier**: 代码精简分析。重用性→质量→效率，三维度评估。
- **add-javadoc**: Java代码加完整文档。
- **spark-version-upgrade**: Spark版本迁移指南。
- **qa-changes**: PR变更的功能验证流程。

## 项目/产品
- **prd**: 生成产品需求文档(背景/用户/功能/非功能/时间线)。
- **release-notes**: 从git历史生成分类changelog。
- **learn-from-code-review**: 从审查评论提炼为编码规范。
- **evidence-based-citations**: 为事实性断言引用来源链接。
- **research-brief**: 调研简报(摘要/发现/引用)。

## 前端/设计
- **frontend-design**: 生成精美前端代码，避免AI感。
- **ui-ux-pro-max**: UI/UX设计系统(50+风格/161色板/57字体)。
- **theme-factory**: 为产出物应用10种预设主题。

## 文档
- **plain-english-content**: 简明语言改写。
- **pdflatex**: LaTeX编译PDF。

## 运维/安全
- **security**: 安全审查(SQL注入/XSS/认证/密钥/权限)。
- **docker/kubernetes**: 容器/集群操作(通过run_command)。
- **ssh**: 远程执行(通过ssh_exec工具)。

## 自动化/Agent
- **github系列**: 仓库/PR/Issue/Workflow/Release 全套API(通过github_*工具)。
- **iterate**: PR全自动迭代(CI→Review→QA→push→重复至合并)。
- **agent-creator / agent-sdk-builder / skill-creator**: 创建子Agent/技能指南。
- **agent-memory**: 重要信息存入记忆库。
- **incident-retrospective**: 事件复盘(时间线→影响→根因→改进)。

## 聊天原则
优先直接回应用户的提问。只在以下情况用工具:
- 用户明确要求执行操作(执行命令、查设备、GitHub操作、SSH等)
- 需要查文件或记忆
- 用户要求使用某个具体技能

严禁为自我介绍、问候、闲聊使用任何工具。
始终用中文回复，短小精炼，三句话以内。

工具格式: <tool>名称</tool><content>参数</content>
每轮最多一个工具。收到结果后直接回复，禁止继续调工具。

""" + TOOL_DEFS_TEXT


# ── Session-aware runtime ──────────────────────────────────
class MotherRuntime:
    def __init__(self, db_session_factory=None):
        self._sessions: dict[int, WorkingMemory] = {}
        self._db_factory = db_session_factory

    def _get_session(self, sid: int) -> WorkingMemory:
        if sid not in self._sessions:
            wm = WorkingMemory()
            wm.set_system(SYSTEM_PROMPT)
            self._sessions[sid] = wm
        return self._sessions[sid]

    def run(self, message: str, session_id: int, max_turns: int = 5) -> dict:
        """Execute agent loop. Returns {reply, turns, tool_calls}."""
        wm = self._get_session(session_id)
        wm.add("user", message)

        # Prime memory
        try:
            if self._db_factory:
                db = self._db_factory()
                try:
                    from app.memory import MemoryRepo
                    hits = MemoryRepo(db).query(message, 3)
                    wm.set_recall([f"[#{h.session_id}] {h.summary}" for h in hits])
                finally:
                    db.close()
        except Exception:
            pass

        tools_used = []
        final_reply = ""
        error_count = 0

        for turn in range(max_turns):
            candidates = self._build_candidates()

            raw = None
            last_err = ""
            for base_url, api_key, model in candidates[:4]:  # 最多试前4个
                try:
                    r = httpx.post(
                        f"{base_url}/chat/completions",
                        headers={"Authorization": f"Bearer {api_key}",
                                 "Content-Type": "application/json"},
                        json={"model": model,
                              "messages": wm.to_messages(),
                              "temperature": 0.3,
                              "max_tokens": 600},
                        timeout=15)
                    if r.status_code == 200:
                        raw = r.json()["choices"][0]["message"]["content"]
                        break
                    last_err = f"{r.status_code}"
                except Exception as e:
                    last_err = str(e)[:60]

            if raw is None:
                error_count += 1
                if error_count >= 2:
                    final_reply = f"LLM调用失败: {last_err}"
                    break
                continue

            # Parse tools with regex
            tms = [(m.group(1).strip(), m.group(2).strip())
                   for m in TOOL_RE.finditer(raw)]
            clean = TOOL_RE.sub('', raw).strip()
            clean = THINK_RE.sub('', clean).strip()

            if not tms:
                final_reply = clean
                break

            # Execute tools (max 2 rounds)
            if turn >= 2:
                final_reply = clean or "收到（母体-小梦已读）"
                break

            results = []
            for tname, tcontent in tms[:3]:
                tools_used.append(tname)
                result = self._execute_tool(tname, tcontent)
                results.append(f"[{tname}] 结果:\n{result[:600]}")

            wm.add("assistant", clean or "[工具调用]")
            wm.add("user", "工具执行结果:\n" + "\n".join(results))

            if clean:
                final_reply = clean
                break

        if not final_reply:
            final_reply = "收到（母体-小梦已读）"

        wm.add("assistant", final_reply)
        turn_count = turn + 1 if 'turn' in dir() else 0
        return {"reply": final_reply, "turns": turn_count,
                "tool_calls": tools_used}

    def _build_candidates(self) -> list[tuple]:
        """Build LLM key candidates from token_pool."""
        candidates = []
        seen = set()
        try:
            from app.token_pool import get_pool
            pool = get_pool()
            for provider in ['custom', 'zhipu', 'deepseek-cn', 'miclaw-bridge']:
                for k in sorted(pool.keys,
                                key=lambda x: (0 if x.status == 'working' else 1)):
                    if k.provider == provider and k.api_key and k.base_url not in seen:
                        seen.add(k.base_url)
                        candidates.append((k.base_url.rstrip("/"),
                                           k.api_key, k.model))
            for k in pool.keys:
                if k.api_key and k.base_url not in seen:
                    seen.add(k.base_url)
                    candidates.append((k.base_url.rstrip("/"),
                                       k.api_key, k.model))
        except Exception:
            pass
        return candidates

    def _execute_tool(self, name: str, arg: str) -> str:
        """Execute tool — route to app.skills or app.tools."""
        try:
            if name.startswith("github_") or name == "ssh_exec":
                from app.skills import execute_skill
                return execute_skill(name, arg)[:2000]
            if name in ("gitlab_api", "bitbucket_api", "linear_api",
                        "jira_api", "notion_api", "datadog_api",
                        "vercel_api", "discord_api", "slack_api",
                        "azure_devops_api"):
                from app.skills import api_placeholder
                return api_placeholder(name.replace("_api", ""))[:500]
            if self._db_factory:
                db = self._db_factory()
                try:
                    from app.tools import execute as exec_tool
                    return str(exec_tool(db, name, str(arg).strip()))[:2000]
                finally:
                    db.close()
        except Exception as e:
            return f"工具错误: {e}"
        return "数据库不可用"

    def reset_session(self, sid: int):
        self._sessions.pop(sid, None)


# Singleton
_runtime: MotherRuntime | None = None


def get_runtime() -> MotherRuntime:
    global _runtime
    if _runtime is None:
        from app.db import SessionLocal
        _runtime = MotherRuntime(db_session_factory=SessionLocal)
    return _runtime

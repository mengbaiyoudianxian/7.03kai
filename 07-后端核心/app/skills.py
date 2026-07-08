"""MBclaw Skills — 母体全技能实现

A — API 技能 (有 token 则真调用，无 token 返回配置指引)
B — Shell 技能 (统一走 run_command)
C — LLM 技能 (嵌入 prompt，无需额外代码)
"""
import os, subprocess, json, httpx

GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", os.environ.get("GH_TOKEN", ""))
GITHUB_API = "https://api.github.com"

# ═══════════════════════════════════════════════════════════
#  GitHub 全家桶 (GITHUB_TOKEN ✅)
# ═══════════════════════════════════════════════════════════

def _gh_headers():
    return {"Authorization": f"Bearer {GITHUB_TOKEN}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28"}


def github_api(method: str, path: str, data: dict = None) -> dict:
    """Generic GitHub API call."""
    if not GITHUB_TOKEN:
        return {"error": "GITHUB_TOKEN 未配置"}
    r = httpx.request(method, f"{GITHUB_API}{path}",
                      headers=_gh_headers(), json=data, timeout=30)
    if r.status_code == 204:
        return {"ok": True}
    try:
        return r.json()
    except Exception:
        return {"status": r.status_code, "body": r.text[:500]}


def github_search_code(query: str, repo: str = "") -> str:
    """搜索代码。格式: owner/repo 关键词 或直接关键词"""
    parts = query.strip().split(None, 1)
    if len(parts) == 2 and "/" in parts[0]:
        q = f"{parts[1]} repo:{parts[0]}"
    else:
        q = query.strip()
    r = github_api("GET", f"/search/code?q={q}&per_page=5")
    items = r.get("items", [])
    if not items:
        return "未找到匹配代码"
    return "\n".join(f"- {i['repository']['full_name']}: {i['path']}" for i in items[:10])


def github_list_repos(user: str = "") -> str:
    """列出仓库。不填 user 则列出当前用户的。"""
    if user:
        r = github_api("GET", f"/users/{user}/repos?per_page=20&sort=updated")
    else:
        r = github_api("GET", "/user/repos?per_page=20&sort=updated")
    if isinstance(r, dict) and r.get("error"):
        return r["error"]
    repos = r if isinstance(r, list) else r.get("items", r)
    return "\n".join(f"- {repo.get('full_name','')} ⭐{repo.get('stargazers_count',0)} {repo.get('description','')[:60]}" for repo in repos[:20])


def github_get_pr(repo: str, pr_number: int) -> str:
    """获取 PR 详情."""
    r = github_api("GET", f"/repos/{repo}/pulls/{pr_number}")
    if r.get("error"):
        return r["error"]
    return (f"PR #{r['number']}: {r['title']}\n"
            f"状态: {r['state']} | 作者: {r['user']['login']}\n"
            f"分支: {r['head']['ref']} → {r['base']['ref']}\n"
            f"描述: {r.get('body','')[:500]}")


def github_create_pr(repo: str, title: str, head: str, base: str = "main", body: str = "") -> str:
    """创建 Pull Request."""
    r = github_api("POST", f"/repos/{repo}/pulls",
                   {"title": title, "head": head, "base": base, "body": body})
    if r.get("html_url"):
        return f"PR 创建成功: {r['html_url']}"
    return f"失败: {r}"


def github_list_issues(repo: str, state: str = "open") -> str:
    """列出 Issues."""
    r = github_api("GET", f"/repos/{repo}/issues?state={state}&per_page=10")
    if isinstance(r, dict) and r.get("error"):
        return r["error"]
    items = r if isinstance(r, list) else []
    return "\n".join(f"#{i['number']} [{i['state']}] {i['title']} (@{i['user']['login']})" for i in items[:15]) or "无 Issues"


def github_create_issue(repo: str, title: str, body: str = "") -> str:
    """创建 Issue."""
    r = github_api("POST", f"/repos/{repo}/issues", {"title": title, "body": body})
    if r.get("html_url"):
        return f"Issue 创建成功: {r['html_url']}"
    return f"失败: {r}"


def github_get_file(repo: str, path: str, ref: str = "main") -> str:
    """读取仓库文件内容."""
    r = github_api("GET", f"/repos/{repo}/contents/{path}?ref={ref}")
    if r.get("error"):
        return r["error"]
    import base64
    try:
        return base64.b64decode(r["content"]).decode()[:3000]
    except Exception:
        return "解码失败"


def github_list_workflows(repo: str) -> str:
    """列出 GitHub Actions 工作流."""
    r = github_api("GET", f"/repos/{repo}/actions/workflows")
    if isinstance(r, dict) and r.get("error"):
        return r["error"]
    wfs = r.get("workflows", [])
    return "\n".join(f"- {w['name']} ({w['state']}) [{w['path']}]" for w in wfs[:15]) or "无工作流"


def github_get_workflow_runs(repo: str, workflow_id: str = "") -> str:
    """获取工作流运行记录."""
    path = f"/repos/{repo}/actions/runs?per_page=10"
    if workflow_id:
        path = f"/repos/{repo}/actions/workflows/{workflow_id}/runs?per_page=10"
    r = github_api("GET", path)
    if isinstance(r, dict) and r.get("error"):
        return r["error"]
    runs = r.get("workflow_runs", [])
    return "\n".join(f"- [{r['status']}/{r['conclusion']}] {r['name']} ({r['head_branch']})" for r in runs[:10]) or "无运行记录"


def github_pr_review(repo: str, pr_number: int, action: str, body: str = "") -> str:
    """PR 审查: approve / comment / request_changes."""
    data = {"event": action.upper()}
    if body:
        data["body"] = body
    r = github_api("POST", f"/repos/{repo}/pulls/{pr_number}/reviews", data)
    if r.get("state"):
        return f"审查提交: {r['state']}"
    return f"审查失败: {r}"


def github_pr_diff(repo: str, pr_number: int) -> str:
    """获取 PR diff."""
    if not GITHUB_TOKEN:
        return "GITHUB_TOKEN 未配置"
    r = httpx.get(f"{GITHUB_API}/repos/{repo}/pulls/{pr_number}",
                  headers={**_gh_headers(), "Accept": "application/vnd.github.diff"},
                  timeout=30)
    return r.text[:4000] if r.status_code == 200 else f"获取失败: {r.status_code}"


def github_compare(repo: str, base: str, head: str) -> str:
    """比较两个分支/commit 的差异."""
    if not GITHUB_TOKEN:
        return "GITHUB_TOKEN 未配置"
    r = httpx.get(f"{GITHUB_API}/repos/{repo}/compare/{base}...{head}",
                  headers=_gh_headers(), timeout=30)
    data = r.json()
    files = data.get("files", [])
    summary = f"共 {len(files)} 个文件变更, +{data.get('ahead_by',0)}/-{data.get('behind_by',0)} commits"
    detail = "\n".join(f"- {f['filename']} (+{f['additions']}/-{f['deletions']})" for f in files[:20])
    return f"{summary}\n{detail}" if detail else summary


def github_create_release(repo: str, tag: str, name: str = "", body: str = "") -> str:
    """创建 Release."""
    r = github_api("POST", f"/repos/{repo}/releases",
                   {"tag_name": tag, "name": name or tag, "body": body})
    if r.get("html_url"):
        return f"Release 创建成功: {r['html_url']}"
    return f"失败: {r}"


# ═══════════════════════════════════════════════════════════
#  SSH 远程操作
# ═══════════════════════════════════════════════════════════

def ssh_exec(host: str, command: str, user: str = "root", port: int = 22) -> str:
    """SSH 执行远程命令。用 sshpass + GITHUB_TOKEN 或 SSH_PASS."""
    pw = os.environ.get("SSHPASS", os.environ.get("SSH_PASS", GITHUB_TOKEN))
    if not pw:
        return "SSH 密钥/密码未配置 (需 SSHPASS 或 GITHUB_TOKEN)"
    try:
        r = subprocess.run(
            ["sshpass", "-p", pw, "ssh", "-o", "StrictHostKeyChecking=no",
             "-p", str(port), f"{user}@{host}", command],
            capture_output=True, text=True, timeout=60)
        return r.stdout[:2000] or r.stderr[:500] or "(无输出)"
    except subprocess.TimeoutExpired:
        return "SSH 命令超时 (60s)"
    except FileNotFoundError:
        return "sshpass 未安装"


# ═══════════════════════════════════════════════════════════
#  API 技能占位 (无 token)
# ═══════════════════════════════════════════════════════════

_PLACEHOLDER = {
    "gitlab":      "需要 GITLAB_TOKEN 环境变量，格式: https://gitlab.com 或自建实例",
    "bitbucket":   "需要 BITBUCKET_TOKEN 或 BITBUCKET_DATA_CENTER_TOKEN",
    "linear":      "需要 LINEAR_API_KEY",
    "jira":        "需要 JIRA_API_TOKEN + JIRA_BASE_URL",
    "notion":      "需要 NOTION_TOKEN",
    "datadog":     "需要 DD_API_KEY + DD_APP_KEY",
    "vercel":      "需要 VERCEL_TOKEN",
    "discord":     "需要 DISCORD_BOT_TOKEN",
    "slack":       "需要 SLACK_BOT_TOKEN",
    "azure_devops": "需要 AZURE_DEVOPS_TOKEN + AZURE_DEVOPS_ORG",
}

def api_placeholder(service: str) -> str:
    return f"{service} API 暂不可用: {_PLACEHOLDER.get(service, '需要配置对应 Token')}"


# ═══════════════════════════════════════════════════════════
#  LLM 技能 — 纯 prompt 指令, 嵌入系统提示
# ═══════════════════════════════════════════════════════════

LLM_SKILL_PROMPTS = """
## 内置技能

你有以下内置技能，无需使用工具即可执行。用户提到相关需求时直接应用:

- **code-review**: 严格代码审查。关注数据结构、简洁性、安全性、实用性、风险评估。输出: 问题分级(严重/警告/建议) + 具体修复建议。
- **code-simplifier**: 代码精简。从代码重用、代码质量、效率三个维度分析，给出可行建议。
- **prd**: 生成产品需求文档。包含: 背景、目标用户、核心功能、非功能需求、时间线。
- **security**: 安全审查。检查 SQL注入、XSS、认证缺陷、密钥泄漏、权限绕过等。
- **plain-english-content**: 简明语言改写。主动语态、前置关键信息、无粗体斜体。
- **evidence-based-citations**: 为事实性断言提供引用来源和链接。
- **release-notes**: 从 git 历史生成分类 changelog (breaking/features/fixes/other)。
- **frontend-design**: 生成精美前端代码 (React/HTML/Tailwind)。避免AI感，追求独特设计。
- **ui-ux-pro-max**: UI/UX 设计智能。50+风格、161色板、57字体配对。为界面选择最佳方案。
- **theme-factory**: 为文档/幻灯片/页面应用10种预设主题之一。
- **add-javadoc**: 为 Java 类和方法添加完整 JavaDoc 文档。
- **spark-version-upgrade**: Spark 版本升级指南 (2.x→3.x, 3.x→4.x)。
- **learn-from-code-review**: 从代码审查中提炼为可复用规范。
- **qa-changes**: PR 功能验证方法。搭建环境→测试变更→报告结果。
- **research-brief**: 生成调研简报。包含执行摘要、关键发现、引用来源。
- **agent-creator**: 创建子 Agent 的指南。收集需求→生成配置→部署。
- **agent-sdk-builder**: 使用 SDK 构建 Agent 的指南。
- **skill-creator**: 创建新技能的指南。结构、描述、触发条件。
- **agent-memory**: 持久记忆管理。重要信息存入 AGENTS.md。
- **iterate**: PR 全自动迭代流程: CI → Review → QA → push → 重复至合并。
- **incident-retrospective**: 事件复盘模板。时间线→影响→根因→改进项。
"""
# ═══════════════════════════════════════════════════════════
#  Skill 执行路由
# ═══════════════════════════════════════════════════════════

def execute_skill(name: str, arg: str) -> str:
    """Dispatch skill by name. arg is the content from <content> tag."""
    arg = arg.strip()

    # GitHub skills
    if name == "github_search_code":
        return github_search_code(arg)
    if name == "github_list_repos":
        return github_list_repos(arg)
    if name == "github_get_pr":
        parts = arg.split(None, 1)
        return github_get_pr(parts[0], int(parts[1])) if len(parts) == 2 else "需要: owner/repo PR编号"
    if name == "github_create_pr":
        # arg: owner/repo title head [base] [body]
        parts = arg.split("\n", 4)
        if len(parts) < 3:
            return "需要: owner/repo\\n标题\\n分支名\\n[base]\\n[body]"
        repo = parts[0].strip()
        title = parts[1].strip()
        head = parts[2].strip()
        base = parts[3].strip() if len(parts) > 3 and parts[3].strip() else "main"
        body = parts[4].strip() if len(parts) > 4 else ""
        return github_create_pr(repo, title, head, base, body)
    if name == "github_list_issues":
        return github_list_issues(arg)
    if name == "github_create_issue":
        parts = arg.split("\n", 2)
        if len(parts) < 2:
            return "需要: owner/repo\\n标题\\n[描述]"
        return github_create_issue(parts[0].strip(), parts[1].strip(),
                                   parts[2].strip() if len(parts) > 2 else "")
    if name == "github_get_file":
        parts = arg.split(None, 2)
        if len(parts) < 2:
            return "需要: owner/repo 文件路径 [ref]"
        ref = parts[2].strip() if len(parts) > 2 else "main"
        return github_get_file(parts[0], parts[1], ref)
    if name == "github_list_workflows":
        return github_list_workflows(arg)
    if name == "github_workflow_runs":
        parts = arg.split(None, 1)
        return github_get_workflow_runs(parts[0],
                                        parts[1].strip() if len(parts) > 1 else "")
    if name == "github_pr_review":
        parts = arg.split("\n", 3)
        if len(parts) < 3:
            return "需要: owner/repo\\nPR编号\\napprove/comment/request_changes\\n[内容]"
        return github_pr_review(parts[0].strip(), int(parts[1].strip()),
                                parts[2].strip(),
                                parts[3].strip() if len(parts) > 3 else "")
    if name == "github_pr_diff":
        parts = arg.split(None, 1)
        return github_pr_diff(parts[0], int(parts[1])) if len(parts) == 2 else "需要: owner/repo PR编号"
    if name == "github_compare":
        parts = arg.split()
        if len(parts) < 3:
            return "需要: owner/repo base head"
        return github_compare(parts[0], parts[1], parts[2])
    if name == "github_create_release":
        parts = arg.split("\n", 3)
        if len(parts) < 2:
            return "需要: owner/repo\\ntag\\n[名称]\\n[说明]"
        return github_create_release(parts[0].strip(), parts[1].strip(),
                                     parts[2].strip() if len(parts) > 2 else "",
                                     parts[3].strip() if len(parts) > 3 else "")

    # SSH
    if name == "ssh_exec":
        parts = arg.split()
        if len(parts) < 2:
            return "需要: host 命令 [user] [port]"
        host = parts[0]
        cmd = parts[1] if len(parts) > 1 else ""
        user = parts[2] if len(parts) > 2 else "root"
        port = int(parts[3]) if len(parts) > 3 else 22
        return ssh_exec(host, cmd, user, port)

    # Placeholder API skills
    if name in _PLACEHOLDER:
        return api_placeholder(name)

    return f"未知技能: {name}"

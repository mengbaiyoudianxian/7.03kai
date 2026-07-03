"""K2 Loop — MiMo(exec) + Qwen(review) cycling until quota exhausted."""
import os, json, httpx, time

_env_path = "/opt/mbclaw/.env"
if os.path.exists(_env_path):
    for line in open(_env_path):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ[k.strip()] = v.strip()

EXEC = {
    "url": os.getenv("MBCLAW_LLM_BASE_URL","") + "/chat/completions",
    "api_key": os.getenv("MBCLAW_LLM_API_KEY",""),
    "model": os.getenv("MBCLAW_LLM_MODEL","mimo-v2.5-pro"),
    "name": "MiMo",
}
REVIEW = {
    "url": "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
    "api_key": "sk-06fb2eb8742640d3be0eb9b7743df3c2",
    "model": "qwen3.7-max",
    "name": "Qwen",
}
SWITCHED = False

def call(cfg, messages):
    headers = {"Content-Type": "application/json"}
    if cfg.get("api_key"):
        headers["Authorization"] = "Bearer " + cfg["api_key"]
    try:
        r = httpx.post(cfg["url"], headers=headers, json={
            "model": cfg["model"], "messages": messages,
            "temperature": 0.3, "max_tokens": 4096
        }, timeout=120)
        if r.status_code == 200:
            return r.json()["choices"][0]["message"]["content"]
        return "HTTP " + str(r.status_code) + ": " + r.text[:200]
    except Exception as e:
        return "Error: " + str(e)

def run():
    global SWITCHED
    task = "Implement MBclaw Memory System v1. Write Python code for MemoryEncoder, MemoryRetrieval, ContextBuilder."
    for rnd in range(1, 100001):
        print("")
        print("=" * 50)
        print("ROUND " + str(rnd) + " [" + EXEC["name"] + " exec | " + REVIEW["name"] + " review]")
        print("=" * 50)

        code = call(EXEC, [
            {"role":"system","content":"You are MBclaw Memory System v1 engineer. Output only Python code."},
            {"role":"user","content": task}
        ])
        print("[" + EXEC["name"] + "] " + str(len(code)) + " chars: " + code[:150])

        review = call(REVIEW, [
            {"role":"system","content":"You are a code reviewer. Score the code 0-1. Output: score: X.XX\\nissues: ..."},
            {"role":"user","content": "Review:\n```python\n" + code[:6000] + "\n```"}
        ])
        print("[" + REVIEW["name"] + "] " + review[:250])

        score = 0.5
        for line in review.split("\n"):
            if "score:" in line.lower():
                try: score = float(line.split(":")[1].strip()[:4])
                except: pass

        with open("/opt/mbclaw/k2_build.log", "a") as f:
            f.write("ROUND " + str(rnd) + " | " + EXEC["name"] + "=" + str(len(code)) + "chars | score=" + str(score) + "\n")
            f.write("Review: " + review[:300] + "\n\n")

        if score >= 0.7:
            print("PASS (score=" + str(score) + ")")
            task = "Continue next construction task"
        else:
            print("RETRY (score=" + str(score) + ")")

        if "429" in code or "quota" in code.lower() or "insufficient" in code.lower():
            if not SWITCHED:
                print("*** MiMo quota exhausted! Switching to Alibaba ***")
                EXEC.update(REVIEW)
                REVIEW.update({
                    "url": "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                    "api_key": "sk-06fb2eb8742640d3be0eb9b7743df3c2",
                    "model": "deepseek-v4-pro",
                    "name": "DSv4",
                })
                SWITCHED = True

        time.sleep(1)

    print("K2 done: 100000 rounds completed")

run()

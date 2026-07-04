"""Evolution — 每日自动进化循环"""
from __future__ import annotations
import json, time, asyncio, logging
from datetime import datetime
from pathlib import Path
from config import cfg
from mother.event_log import append_event, read_events

log = logging.getLogger(__name__)
_REPORT_DIR = Path(cfg.data_dir) / "mother" / "evolution_reports"
_REPORT_DIR.mkdir(parents=True, exist_ok=True)
_STATE_FILE = Path(cfg.data_dir) / "mother" / "evolution_state.json"

def _load_state() -> dict:
    if _STATE_FILE.exists():
        try: return json.loads(_STATE_FILE.read_text())
        except: pass
    return {"last_evolve_ts": 0, "evolve_count": 0, "health_score": 80}

def _save_state(s): _STATE_FILE.write_text(json.dumps(s, ensure_ascii=False, indent=2))

def should_evolve() -> bool:
    return time.time() - _load_state().get("last_evolve_ts", 0) >= 86400

async def run_evolution() -> dict:
    log.info("开始每日进化...")
    append_event("phase.started", "evolution", {"phase": "daily"})
    events = read_events(50)
    events_text = "\n".join(f"[{e['event_type']}] {json.dumps(e['payload'], ensure_ascii=False)[:100]}" for e in events[-50:])
    from mother.memory import experience
    exps = experience.list_recent(20)
    exp_stats = f"近期经验 {len(exps)} 条"

    report = {}
    try:
        from mother.token_pool.client import llm_chat
        raw = llm_chat([{"role": "user", "content": f"分析今日事件，输出JSON进化报告:\n事件:\n{events_text[:2000]}\n{exp_stats}"}], task="cheap", max_tokens=1000)
        clean = raw.strip()
        if clean.startswith("```"): clean = clean.split("```")[1].lstrip("json").strip()
        report = json.loads(clean)
    except Exception as e:
        log.error("进化LLM失败: %s", e)
        report = {"summary": "进化分析失败", "health_score": 70}

    today = datetime.now().strftime("%Y-%m-%d")
    report["generated_at"] = time.time(); report["event_count"] = len(events)
    (_REPORT_DIR / f"{today}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))

    state = _load_state()
    state.update(last_evolve_ts=time.time(), evolve_count=state.get("evolve_count",0)+1, health_score=report.get("health_score",80))
    _save_state(state)
    append_event("phase.completed", "evolution", {"health_score": report.get("health_score",80)})
    return report

def get_state() -> dict: return _load_state()

def list_reports(limit: int = 10) -> list[dict]:
    return [{"date": f.stem, "summary": json.loads(f.read_text()).get("summary",""), "health_score": json.loads(f.read_text()).get("health_score",0)}
            for f in sorted(_REPORT_DIR.glob("*.json"), reverse=True)[:limit]]

def get_report(date: str) -> dict:
    p = _REPORT_DIR / f"{date}.json"
    return json.loads(p.read_text()) if p.exists() else {}

async def run_forever():
    while True:
        try:
            if should_evolve(): await run_evolution()
        except Exception as e: log.error("进化循环异常: %s", e)
        await asyncio.sleep(3600)

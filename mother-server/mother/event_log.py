"""事件日志 — 母体全局事件总线"""
from __future__ import annotations
import json, time
from pathlib import Path
from config import cfg

_DIR = Path(cfg.data_dir) / "mother" / "events"
_DIR.mkdir(parents=True, exist_ok=True)

def append_event(event_type: str, source: str, payload: dict):
    event = {"ts": time.time(), "event_type": event_type, "source": source, "payload": payload}
    fname = f"{int(event['ts']*1000)}_{event_type}.json"
    (_DIR / fname).write_text(json.dumps(event, ensure_ascii=False))

def read_events(limit: int = 100) -> list[dict]:
    files = sorted(_DIR.glob("*.json"), reverse=True)[:limit]
    return [json.loads(f.read_text()) for f in reversed(files)]

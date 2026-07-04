"""归档记忆 — 永久保存原始数据"""
from __future__ import annotations
import json, time, gzip
from pathlib import Path
from config import cfg

_DIR = Path(cfg.data_dir) / "mother" / "archive"
_DIR.mkdir(parents=True, exist_ok=True)

def archive_episode(ep: dict):
    ts = int(ep.get("started_at", time.time()))
    data = json.dumps(ep, ensure_ascii=False).encode()
    with gzip.open(str(_DIR / f"{ts}_{ep.get('id','')}.json.gz"), "wb") as f: f.write(data)

def archive_text(key: str, content: str, category: str = "raw"):
    safe = "".join(c for c in key if c.isalnum() or c in "_-")[:60]
    ts = int(time.time())
    with gzip.open(str(_DIR / f"{ts}_{category}_{safe}.txt.gz"), "wb") as f: f.write(content.encode())

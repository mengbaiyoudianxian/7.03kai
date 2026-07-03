from dataclasses import dataclass, field
from typing import Optional
import time, uuid

@dataclass
class Event:
    id: str = field(default_factory=lambda: str(uuid.uuid4())[:8])
    timestamp: float = field(default_factory=time.time)
    type: str = ""
    action: str = ""
    actor: str = "system"
    trace_id: str = ""
    payload: dict = field(default_factory=dict)
    session_id: Optional[str] = None
    parent_event_id: Optional[str] = None
    risk_level: str = "low"
    source: str = "engine"
    version: str = "v3"

    def __post_init__(self):
        if not self.trace_id:
            self.trace_id = str(uuid.uuid4())[:8]

def create_event(type, action, payload=None, actor="system", trace_id=None, risk="low"):
    return Event(
        type=type, action=action,
        payload=payload or {},
        actor=actor,
        trace_id=trace_id or str(uuid.uuid4())[:8],
        risk_level=risk,
    )

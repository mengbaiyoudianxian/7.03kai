import json, os, uuid, time
from datetime import date

EVENTS_DIR = os.path.join(os.environ.get('MBCLAW_DATA', '/var/lib/mbclaw'), 'mother', 'events')
os.makedirs(EVENTS_DIR, exist_ok=True)

VALID_TYPES = {
    'intent.received', 'execution.complete', 'execution.rejected',
    'goal.created', 'goal.updated', 'goal.completed',
    'task.created', 'task.started', 'task.done', 'task.failed', 'task.blocked',
    'decision.made', 'decision.revoked',
    'capability.registered', 'capability.unregistered',
    'phase.started', 'phase.completed',
    'governor.rule_violation', 'governor.approval_required',
    'worker.error', 'system.snapshot',
}

def append_event(event_type, actor, payload, parent_event_id=None):
    if event_type not in VALID_TYPES:
        raise ValueError('Invalid event_type: ' + event_type)
    eid = str(uuid.uuid4())[:8]
    event = {
        'event_id': eid, 'event_type': event_type,
        'timestamp': time.time(), 'actor': actor,
        'payload': payload, 'parent_event_id': parent_event_id,
    }
    today = date.today().isoformat()
    path = os.path.join(EVENTS_DIR, today + '.jsonl')
    with open(path, 'a') as f:
        f.write(json.dumps(event, ensure_ascii=False) + '\n')
    return eid

def read_events(since=None, limit=500):
    events = []
    files = sorted(os.listdir(EVENTS_DIR), reverse=True)
    for fname in files:
        if not fname.endswith('.jsonl'): continue
        if since and fname < since: continue
        with open(os.path.join(EVENTS_DIR, fname)) as f:
            for line in f:
                events.append(json.loads(line.strip()))
        if len(events) >= limit: break
    return events[-limit:]

def read_events_by_type(event_type, limit=100):
    return [e for e in read_events(limit=limit*2) if e['event_type'] == event_type][:limit]

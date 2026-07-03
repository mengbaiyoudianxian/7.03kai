import json, os
from .event_log import read_events, read_events_by_type, append_event

DATA_DIR = os.environ.get('MBCLAW_DATA', '/var/lib/mbclaw')
MOTHER_DIR = os.path.join(DATA_DIR, 'mother')

def project_state(events=None):
    if events is None: events = read_events(limit=1000)
    state = {'current_goal': None, 'current_phase': None, 'current_task': None,
             'blocked': [], 'completed_phases': [], 'last_updated': None}
    for e in events:
        t = e['event_type']
        if t == 'goal.created': state['current_goal'] = e['payload'].get('goal_id')
        if t == 'phase.started': state['current_phase'] = e['payload'].get('phase_name')
        if t == 'phase.completed': state['completed_phases'].append(e['payload'].get('phase_name'))
        if t == 'task.started': state['current_task'] = e['payload'].get('task_id')
        if t == 'task.blocked': state['blocked'].append(e['payload'].get('task_id'))
        state['last_updated'] = e['timestamp']
    os.makedirs(MOTHER_DIR, exist_ok=True)
    path = os.path.join(MOTHER_DIR, 'project_state.json')
    with open(path, 'w') as f:
        json.dump(state, f, ensure_ascii=False, indent=2)
    return state

def project_decisions(events=None):
    if events is None: events = read_events_by_type('decision.made', limit=50)
    revoked = {e['payload'].get('decision_id') for e in read_events_by_type('decision.revoked', limit=50)}
    decisions = []
    for e in events:
        did = e['payload'].get('decision_id')
        if did in revoked: continue
        decisions.append({
            'id': did, 'topic': e['payload'].get('topic', ''),
            'reasoning': e['payload'].get('reasoning', ''),
            'rejected': e['payload'].get('rejected', []),
            'timestamp': e['timestamp']
        })
    return decisions

def project_workspace(events=None):
    if events is None: events = read_events(limit=100)
    recent_errors = [e for e in events if e['event_type'] in ('task.failed', 'worker.error', 'governor.rule_violation')]
    active_tasks = [e for e in events if e['event_type'] == 'task.started'][-5:]
    return {
        'active_tasks': [t['payload'].get('task_id') for t in active_tasks],
        'recent_errors': [
            {'type': e['event_type'], 'detail': str(e['payload'])[:200], 'time': e['timestamp']}
            for e in recent_errors[-10:]
        ],
    }

def record_decision(topic, reasoning, rejected_alternatives, actor='human'):
    decision_id = 'DEC-' + topic[:30].replace(' ', '-')
    return append_event('decision.made', actor, {
        'decision_id': decision_id, 'topic': topic,
        'reasoning': reasoning, 'rejected': rejected_alternatives
    })

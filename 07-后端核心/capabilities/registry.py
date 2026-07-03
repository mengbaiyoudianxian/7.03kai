import asyncio, json, os, time
from .models import Capability

DATA_DIR = os.environ.get('MBCLAW_DATA', '/var/lib/mbclaw')
REGISTRY_FILE = os.path.join(DATA_DIR, 'capabilities', 'registry.json')

class CapabilityRegistry:
    def __init__(self):
        self._items: dict[str, Capability] = {}
        self._subscribers: list[asyncio.Queue] = []
        self._lock = asyncio.Lock()
        self._load()

    def _load(self):
        try:
            if os.path.exists(REGISTRY_FILE):
                for d in json.loads(open(REGISTRY_FILE).read()).get('items', []):
                    self._items[d['id']] = Capability.from_dict(d)
        except: pass

    def _save(self):
        os.makedirs(os.path.dirname(REGISTRY_FILE), exist_ok=True)
        json.dump({'items': [c.to_dict() for c in self._items.values()], 'updated_at': time.time()},
                  open(REGISTRY_FILE, 'w'), ensure_ascii=False, indent=2)

    async def register(self, cap: Capability):
        async with self._lock:
            self._items[cap.id] = cap; self._save()
        await self._emit('register', cap)

    async def unregister(self, id: str):
        async with self._lock:
            self._items.pop(id, None); self._save()
        await self._emit('unregister', id)

    def list(self, type: str = None) -> list[Capability]:
        return [c for c in self._items.values() if type is None or c.type.value == type]

    def search(self, query: str, limit: int = 10) -> list[Capability]:
        q = query.lower()
        return [c for c in self.list() if q in c.name.lower() or q in c.description.lower()][:limit]

    def get(self, id: str) -> Capability | None:
        return self._items.get(id)

    def tools_for_llm(self) -> list[dict]:
        return [{'type':'function','function':{'name':c.name,'description':c.description,'parameters':c.parameters}}
                for c in self.list() if c.enabled]

    async def subscribe(self):
        q = asyncio.Queue()
        self._subscribers.append(q)
        try:
            while True: yield await q.get()
        finally: self._subscribers.remove(q)

    async def _emit(self, action: str, data):
        for q in self._subscribers:
            try: q.put_nowait({'action': action, 'data': data})
            except asyncio.QueueFull: pass

_registry = CapabilityRegistry()
def get_registry() -> CapabilityRegistry:
    return _registry

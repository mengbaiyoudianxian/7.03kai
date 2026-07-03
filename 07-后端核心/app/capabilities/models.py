from enum import Enum
from dataclasses import dataclass, field
import time

class CapabilityType(str, Enum):
    SKILL = 'skill'; MCP = 'mcp'; API = 'api'; TOOL = 'tool'; WORKFLOW = 'workflow'

class CapabilitySource(str, Enum):
    BUILTIN = 'builtin'; CLOUD = 'cloud'; RUNTIME = 'runtime'; IMPORT = 'import'

@dataclass
class Capability:
    id: str
    type: CapabilityType = CapabilityType.TOOL
    source: CapabilitySource = CapabilitySource.BUILTIN
    entry: str = ''
    manifest: dict = field(default_factory=dict)
    enabled: bool = True
    installed_at: float = field(default_factory=time.time)
    downloads: int = 0

    @property
    def name(self) -> str: return self.manifest.get('name', self.id)
    @property
    def description(self) -> str: return self.manifest.get('description', '')
    @property
    def parameters(self) -> dict: return self.manifest.get('parameters', {})

    def to_dict(self) -> dict:
        return {'id':self.id,'type':self.type.value,'source':self.source.value,'entry':self.entry,'manifest':self.manifest,'enabled':self.enabled,'downloads':self.downloads}

    @classmethod
    def from_dict(cls, d: dict) -> 'Capability':
        return cls(id=d['id'],type=CapabilityType(d.get('type','tool')),source=CapabilitySource(d.get('source','builtin')),entry=d.get('entry',''),manifest=d.get('manifest',{}),enabled=d.get('enabled',True),downloads=d.get('downloads',0))

    @classmethod
    def from_tool(cls, name: str, desc: str, params: dict, source: str = 'builtin') -> 'Capability':
        return cls(id=name,type=CapabilityType.TOOL,source=CapabilitySource(source),manifest={'name':name,'description':desc,'parameters':params})

"""WorkspaceManager — 工作区管理器 v1.

Workspace 是记忆隔离边界。不同项目的记忆互不污染。
"""

import time as _time
from sqlalchemy.orm import Session

from app.models import Workspace


class WorkspaceManager:
    """工作区 CRUD + 上下文获取。"""

    def __init__(self, db: Session):
        self.db = db

    def create(self, name: str, topic: str = "") -> Workspace:
        """创建新工作区。同名抛 ValueError。"""
        existing = self.db.query(Workspace).filter(Workspace.name == name).first()
        if existing:
            raise ValueError(f"Workspace '{name}' already exists")
        ws = Workspace(name=name, topic=topic)
        self.db.add(ws)
        self.db.commit()
        self.db.refresh(ws)
        return ws

    def get(self, ws_id: int) -> Workspace | None:
        """获取工作区。"""
        return self.db.get(Workspace, ws_id)

    def get_or_create_default(self) -> Workspace:
        """获取或创建默认工作区。兼容旧session(ws_id=NULL)。"""
        ws = self.db.query(Workspace).filter(Workspace.name == "Default").first()
        if ws is None:
            ws = Workspace(id=1, name="Default", topic="默认工作区")
            self.db.add(ws)
            self.db.commit()
            self.db.refresh(ws)
        return ws

    def list_active(self) -> list[Workspace]:
        """列出活跃(未归档)工作区。"""
        return self.db.query(Workspace).filter(
            Workspace.is_archived == 0
        ).order_by(Workspace.updated_at.desc()).all()

    def archive(self, ws_id: int) -> Workspace:
        """归档工作区(软删除,记忆保留)。"""
        ws = self.db.get(Workspace, ws_id)
        if ws is None:
            raise ValueError(f"Workspace {ws_id} not found")
        ws.is_archived = 1
        ws.updated_at = _time.strftime("%Y-%m-%dT%H:%M:%S")
        self.db.commit()
        return ws

    def touch(self, ws_id: int):
        """更新 updated_at (有新的session/消息时)。"""
        ws = self.db.get(Workspace, ws_id)
        if ws:
            ws.updated_at = _time.strftime("%Y-%m-%dT%H:%M:%S")
            self.db.commit()

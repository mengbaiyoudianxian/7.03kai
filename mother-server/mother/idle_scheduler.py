"""P0-1: 空闲任务调度器 — 后台定期处理未分类的 episodes"""
from __future__ import annotations
import asyncio, logging, time
from config import cfg

log = logging.getLogger(__name__)

_CHECK_INTERVAL = 600  # 10 分钟
_IDLE_THRESHOLD = 300  # 5 分钟内无新 episode 视为空闲

_last_episode_ts: float = time.time()
_last_classify_ts: float = 0
_processed_episodes: set = set()


def touch():
    """标记有新活动，重置空闲计时"""
    global _last_episode_ts
    _last_episode_ts = time.time()


async def run_forever():
    """后台循环：空闲时自动分类"""
    global _last_classify_ts, _processed_episodes
    while True:
        await asyncio.sleep(_CHECK_INTERVAL)
        try:
            now = time.time()
            if now - _last_episode_ts < _IDLE_THRESHOLD:
                continue  # 还在活跃，跳过
            if now - _last_classify_ts < 3600:
                continue  # 1 小时内不重复运行

            from mother.memory.episode import Episode
            from mother.classification_engine import classify_episode
            from mother.memory.classification import count as node_count

            episodes = Episode.recent(50)
            unclassified = [e for e in episodes
                          if e["id"] not in _processed_episodes
                          and e.get("status") == "completed"]

            if not unclassified:
                continue

            log.info("空闲分类: %d 个未处理 episode", len(unclassified))
            for ep in unclassified[:5]:  # 每次最多处理 5 个
                try:
                    classify_episode(ep["id"])
                    _processed_episodes.add(ep["id"])
                except Exception as e:
                    log.error("分类 episode %s 失败: %s", ep["id"], e)

            # 清理已处理集合，防止内存泄漏
            if len(_processed_episodes) > 1000:
                recent_ids = {e["id"] for e in episodes}
                _processed_episodes &= recent_ids

            _last_classify_ts = now
            log.info("空闲分类完成: 分类树节点=%d", node_count())
        except Exception as e:
            log.error("空闲调度异常: %s", e)

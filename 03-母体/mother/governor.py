from .event_log import append_event, read_events_by_type

IRON_RULES = [
    '先搜再改: grep -> 调用链 -> git blame -> GitHub -> 确认无现成实现后才改',
    '只改造不重写: 复用现有代码, 保持代码风格/命名/目录/架构',
    '禁止新建: 未经批准不新建Screen/DB/Service/Repository',
    '不改无关代码: 不借机重构/优化/统一风格',
    '不擅自部署: 编译/重启/杀进程/改Nginx/推送Git需用户批准',
    '单Agent: 全局只有一个Agent实例, FIFO消费消息',
    'API Key不硬编码: 从环境变量读取',
    '先编译验证再推送: 编译失败不上传',
    '推两个下载站: 121.199.57.195 + 8.130.42.188',
    'MD5三端一致: 本地 = 121 = 8.130',
]

class Governor:
    """Policy Enforcement Point - only produces events, never executes"""

    def check_action(self, action, context=None):
        high_risk = ['compile', 'deploy', 'restart_server', 'kill_process', 'push_git', 'modify_nginx']
        arch_changes = ['new_screen', 'new_database', 'new_service', 'new_room_table', 'modify_api']

        if action in high_risk or action in arch_changes:
            reason = '高风险动作' if action in high_risk else '架构变更'
            eid = append_event('governor.approval_required', 'governor',
                {'action': action, 'context': context or {}, 'reason': reason})
            return False, reason + ', 需用户批准 [' + eid + ']'
        return True, 'allowed'

    def inject_rules(self):
        return '\n'.join(str(i+1) + '. ' + r for i, r in enumerate(IRON_RULES))

    def record_violation(self, rule_index, detail, actor='unknown'):
        append_event('governor.rule_violation', 'governor',
            {'rule_index': rule_index, 'rule': IRON_RULES[rule_index], 'detail': detail, 'actor': actor})

    def get_violations(self, limit=20):
        return read_events_by_type('governor.rule_violation', limit)

_governor = Governor()
def get_governor():
    return _governor

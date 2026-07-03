class PolicyEngine:
    MAX_NODES = 50
    HIGH_RISK_TYPES = {"deploy", "delete", "drop", "truncate", "rm", "uninstall", "format"}

    def _get(self, t, key, default=""):
        if hasattr(t, "get"): return t.get(key, default)
        return getattr(t, key, default)

    def validate(self, dag):
        nodes = dag.get("nodes", [])
        if len(nodes) > self.MAX_NODES: return False
        for t in nodes:
            ttype = str(self._get(t, "type", "")).lower()
            inp = str(self._get(t, "input", "")).lower()
            for risk in self.HIGH_RISK_TYPES:
                if risk in ttype or risk in inp:
                    return False
        return True

    def risk_score(self, dag):
        nodes = dag.get("nodes", [])
        if not nodes: return 0
        score = min(len(nodes) * 2, 50)
        for t in nodes:
            ttype = str(self._get(t, "type", "")).lower()
            inp = str(self._get(t, "input", "")).lower()
            for risk in self.HIGH_RISK_TYPES:
                if risk in ttype or risk in inp:
                    score += 20
        return min(score, 100)

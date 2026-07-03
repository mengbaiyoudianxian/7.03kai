from dataclasses import dataclass, field

@dataclass
class Task:
    id: str
    type: str
    input: dict = field(default_factory=dict)
    depends_on: list[str] = field(default_factory=list)

class DagCompiler:

    def compile(self, goal, context=None):
        tasks = self._decompose(goal)
        edges = self._build_edges(tasks)
        return {"nodes": tasks, "edges": edges}

    def _decompose(self, goal):
        goal_str = str(goal).lower()
        tasks = [
            Task(id="t1", type="analyze", input={"goal": goal}),
            Task(id="t2", type="execute", input={"goal": goal}, depends_on=["t1"]),
        ]
        if len(goal_str) > 50 or "code" in goal_str or "fix" in goal_str:
            tasks.append(Task(id="t3", type="review", input={"goal": goal}, depends_on=["t2"]))
        return tasks

    def _build_edges(self, tasks):
        edges = []
        for t in tasks:
            for dep in t.depends_on:
                edges.append((dep, t.id))
        return edges

    def topo_sort(self, dag):
        nodes_raw = dag["nodes"]
        nodes_list = []
        for t in nodes_raw:
            if hasattr(t, "id"):
                nodes_list.append({"id": t.id, "type": t.type, "input": t.input, "depends_on": t.depends_on})
            else:
                nodes_list.append(t)
        nodes = {t["id"]: t for t in nodes_list}
        edges = dag.get("edges", [])
        in_degree = {t["id"]: 0 for t in nodes_list}
        adj = {t["id"]: [] for t in nodes_list}
        for src, dst in edges:
            adj[src].append(dst)
            in_degree[dst] = in_degree.get(dst, 0) + 1
        queue = [nid for nid, deg in in_degree.items() if deg == 0]
        order = []
        while queue:
            nid = queue.pop(0)
            order.append(nodes.get(nid, {"id": nid}))
            for neighbor in adj.get(nid, []):
                in_degree[neighbor] -= 1
                if in_degree[neighbor] == 0:
                    queue.append(neighbor)
        return order

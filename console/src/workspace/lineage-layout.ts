import { Graph, layout } from "@dagrejs/dagre";
import type { LineageEdge, LineageNode } from "./types";

export type NodePosition = {
  x: number;
  y: number;
  width: number;
  height: number;
};
export function nodeSize(node: LineageNode) {
  if (node.kind === "FIELD") return { width: 156, height: 74 };
  if (node.kind === "DECISION_PLAN") return { width: 194, height: 108 };
  if (node.kind === "BUSINESS_ENTITY" || node.kind === "METRIC")
    return { width: 190, height: 96 };
  return { width: 194, height: 100 };
}

export function layoutLineage(nodes: LineageNode[], edges: LineageEdge[]) {
  const positions = new Map<string, NodePosition>();
  if (!nodes.length) return { positions, width: 900, height: 500 };
  const graph = new Graph({ multigraph: true });
  graph.setGraph({
    rankdir: "LR",
    nodesep: 32,
    ranksep: 48,
    marginx: 18,
    marginy: 30,
    ranker: "network-simplex",
  });
  graph.setDefaultEdgeLabel(() => ({}));
  nodes.forEach((node) => graph.setNode(node.id, nodeSize(node)));
  const ids = new Set(nodes.map((node) => node.id));
  edges
    .filter((edge) => ids.has(edge.from) && ids.has(edge.to))
    .forEach((edge) =>
      graph.setEdge(
        edge.from,
        edge.to,
        { weight: edge.kind === "CONTAINS" ? 1 : 3 },
        edge.id,
      ),
    );
  layout(graph);
  nodes.forEach((node) => {
    const point = graph.node(node.id);
    const size = nodeSize(node);
    positions.set(node.id, {
      x: point.x - size.width / 2,
      y: point.y - size.height / 2,
      ...size,
    });
  });
  return {
    positions,
    width: Math.max(600, graph.graph().width ?? 0),
    height: Math.max(400, graph.graph().height ?? 0),
  };
}

export function relatedNodeIds(
  id: string,
  edges: LineageEdge[],
  direction: "upstream" | "downstream" | "both",
) {
  const found = new Set([id]);
  const visit = (mode: "upstream" | "downstream") => {
    const pending = [id];
    const visited = new Set([id]);
    while (pending.length) {
      const current = pending.shift()!;
      edges.forEach((edge) => {
        const next =
          mode === "upstream"
            ? edge.to === current
              ? edge.from
              : null
            : edge.from === current
              ? edge.to
              : null;
        if (next && !visited.has(next)) {
          visited.add(next);
          found.add(next);
          pending.push(next);
        }
      });
    }
  };
  if (direction !== "downstream") visit("upstream");
  if (direction !== "upstream") visit("downstream");
  return found;
}

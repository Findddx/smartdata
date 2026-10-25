import { useSyncExternalStore, type CSSProperties } from "react";
import { ShieldCheck } from "lucide-react";
import { SmartDataGlyph } from "../brand/SmartDataIcons";
import { FRESHNESS, KIND_LABEL, LAYERS, NATURES } from "./graph-semantics";
import type { NodePosition } from "./lineage-layout";
import type { LineageEdge, LineageNode } from "./types";

const readReducedMotion = () => typeof window === "undefined" || typeof window.matchMedia !== "function"
  ? true : window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const subscribeReducedMotion = (changed: () => void) => {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return () => {};
  const query = window.matchMedia("(prefers-reduced-motion: reduce)");
  query.addEventListener("change", changed);
  return () => query.removeEventListener("change", changed);
};

/** Presentation only. Edges are drawn solely from the server's registered graph. */
export function GraphCanvas({
  nodes,
  edges,
  positions,
  width,
  height,
  viewport,
  dragging,
  selectedId,
  selectedEdgeId,
  onSelect,
  onSelectEdge,
  sourceLabels,
}: {
  nodes: LineageNode[];
  edges: LineageEdge[];
  positions: Map<string, NodePosition>;
  width: number;
  height: number;
  viewport: { x: number; y: number; scale: number };
  dragging: boolean;
  selectedId: string | null;
  selectedEdgeId: string | null;
  onSelect: (node: LineageNode) => void;
  onSelectEdge: (id: string) => void;
  sourceLabels: Map<string, string>;
}) {
  const reduceMotion = useSyncExternalStore(subscribeReducedMotion, readReducedMotion, () => true);
  const labels = new Map(nodes.map((node) => [node.id, node.label]));
  return (
    <div
      className={`sd-graph-plane ${dragging ? "is-dragging" : ""} ${viewport.scale < 0.58 ? "is-semantic-compact" : ""}`}
      style={{
        width,
        height,
        transform: `translate(${viewport.x}px, ${viewport.y}px) scale(${viewport.scale})`,
      }}
    >
      <svg
        className="sd-graph-edges"
        width={width}
        height={height}
        aria-label="节点关系"
      >
        <defs>
          <marker
            id="sd-edge-arrow"
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="6"
            markerHeight="6"
            orient="auto-start-reverse"
          >
            <path
              d="M1 1 L9 5 L1 9"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.4"
            />
          </marker>
        </defs>
        {edges.map((edge) => {
          const from = positions.get(edge.from)!,
            to = positions.get(edge.to)!;
          const x1 = from.x + from.width,
            y1 = from.y + from.height / 2;
          const x2 = to.x,
            y2 = to.y + to.height / 2;
          const bend = Math.max(24, (x2 - x1) * 0.5);
          const d = `M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`;
          return (
            <g
              key={edge.id}
              data-edge-id={edge.id}
              className={`sd-graph-edge ${selectedEdgeId === edge.id ? "is-selected" : ""}`}
            >
              <path
                d={d}
                className="sd-edge-hit"
                role="button"
                tabIndex={0}
                aria-label={`${labels.get(edge.from)} → ${labels.get(edge.to)}，${NATURES[edge.evidenceNature]}`}
                onClick={() => onSelectEdge(edge.id)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    onSelectEdge(edge.id);
                  }
                }}
              />
              <path
                d={d}
                fill="none"
                strokeDasharray={
                  edge.lineStyle === "DASHED"
                    ? "7 5"
                    : edge.lineStyle === "DOTTED"
                      ? "2 5"
                      : undefined
                }
                markerEnd="url(#sd-edge-arrow)"
              />
              {selectedEdgeId === edge.id && !reduceMotion && (
                <g data-direction-preview="true" aria-hidden="true" pointerEvents="none" opacity="0">
                  <circle r="6" fill="var(--sd-accent)" opacity="0.14" />
                  <circle r="2.6" fill="var(--sd-accent)" />
                  <animateMotion path={d} dur="1.8s" repeatCount="2" fill="freeze" />
                  <animate attributeName="opacity" values="0;1;1;0" keyTimes="0;0.1;0.85;1" dur="1.8s" repeatCount="2" fill="freeze" />
                </g>
              )}
            </g>
          );
        })}
      </svg>
      {nodes.map((node) => {
        const point = positions.get(node.id)!;
        const source = sourceLabels.get(
          node.ref.sourceId ?? node.sourceIds[0] ?? "",
        );
        return (
          <button
            key={node.id}
            type="button"
            data-node-id={node.id}
            data-layer={node.layer}
            data-node-kind={node.kind}
            className={`sd-graph-node sd-node-${node.kind.toLowerCase()} ${selectedId === node.id ? "is-selected" : ""}`}
            style={
              {
                left: point.x,
                top: point.y,
                width: point.width,
                height: point.height,
                "--layer-color": LAYERS[node.layer].color,
              } as CSSProperties
            }
            aria-pressed={selectedId === node.id}
            aria-label={`${node.label}，${node.layer} ${KIND_LABEL[node.kind]}，${NATURES[node.evidenceNature]}`}
            onClick={() => onSelect(node)}
          >
            <span className="sd-node-heading">
              <span
                className={`sd-node-glyph sd-shape-${node.shape.toLowerCase()}`}
              >
                <SmartDataGlyph kind={node.kind} size={18} />
              </span>
              <strong title={node.label}>{node.label}</strong>
            </span>
            <span className="sd-node-meta">
              {node.layer} · {KIND_LABEL[node.kind]}
              <span>
                {node.materialization === "NOT_MATERIALIZED"
                  ? "尚未生成"
                  : "元数据"}
              </span>
            </span>
            {node.kind === "TABLE" && source && source !== node.label && (
              <span className="sd-node-source" title={source}>
                {source}
              </span>
            )}
            <span className="sd-node-status">
              <i className={`fresh-${node.freshness.toLowerCase()}`} />
              {FRESHNESS[node.freshness]}
              <ShieldCheck size={11} />
              {node.provenance === "DEMO" && <b>演示</b>}
            </span>
          </button>
        );
      })}
    </div>
  );
}

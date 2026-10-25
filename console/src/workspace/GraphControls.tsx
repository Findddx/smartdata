import { CircleHelp, Maximize2, Minus, Plus } from "lucide-react";
import { LAYERS } from "./graph-semantics";
import type { DataLayer, LineageNode } from "./types";

export function GraphLayerNavigator({
  nodes,
  onLocate,
  legend,
  onLegend,
}: {
  nodes: LineageNode[];
  onLocate: (layer: DataLayer) => void;
  legend: boolean;
  onLegend: () => void;
}) {
  return (
    <nav className="sd-graph-layer-nav" aria-label="跳到数据层">
      {(Object.keys(LAYERS) as DataLayer[]).map((layer) => {
        const count = nodes.filter((node) => node.layer === layer).length;
        return (
          <button
            key={layer}
            type="button"
            style={{ color: LAYERS[layer].color }}
            disabled={!count}
            title={`${count} 个可见节点；定位此层，位置不表示计算已执行`}
            onClick={() => onLocate(layer)}
          >
            {layer}
            <span>{LAYERS[layer].name}</span>
          </button>
        );
      })}
      <button
        type="button"
        className="sd-layer-help"
        aria-label="图例与阅读方式"
        title="图例与阅读方式"
        aria-expanded={legend}
        onClick={onLegend}
      >
        <CircleHelp size={14} />
      </button>
    </nav>
  );
}

export function GraphCameraControls({
  scale,
  onZoom,
  onFit,
}: {
  scale: number;
  onZoom: (delta: number) => void;
  onFit: () => void;
}) {
  return (
    <div className="sd-graph-zoom">
      <button type="button" aria-label="缩小图谱" onClick={() => onZoom(-0.1)}>
        <Minus size={14} />
      </button>
      <span>{Math.round(scale * 100)}%</span>
      <button type="button" aria-label="放大图谱" onClick={() => onZoom(0.1)}>
        <Plus size={14} />
      </button>
      <i />
      <button type="button" aria-label="适应画布" onClick={onFit}>
        <Maximize2 size={14} />
      </button>
    </div>
  );
}

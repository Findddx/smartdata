import type { CSSProperties, RefObject } from "react";
import {
  ArrowDownLeft,
  ArrowRight,
  ArrowUpRight,
  Focus,
  GitBranch,
  Table2,
  X,
} from "lucide-react";
import { SmartDataGlyph } from "../brand/SmartDataIcons";
import { LiquidMaterial } from "../materials/LiquidMaterial";
import {
  LAYERS,
  KIND_LABEL,
  NATURES,
  FRESHNESS,
  SENSITIVITY,
  date,
  type FocusMode,
} from "./graph-semantics";
import type {
  LineageNode,
  LineageEdge,
  WorkspaceState,
  WorkspaceLineage,
  WorkspaceAsset,
  WorkspaceViewId,
} from "./types";
export function GraphInspector({
  selected,
  edge,
  graph,
  workspace,
  focusMode,
  onFocus,
  closeInspector,
  onAsset,
  expandTable,
  navigate,
  artRef,
}: {
  artRef?: RefObject<HTMLImageElement | null>;
  selected?: LineageNode;
  edge?: LineageEdge;
  graph?: WorkspaceLineage;
  workspace: WorkspaceState;
  focusMode: FocusMode;
  onFocus: (mode: FocusMode) => void;
  closeInspector: () => void;
  onAsset: (asset: WorkspaceAsset) => void;
  expandTable: (node: LineageNode) => void;
  navigate: (view: WorkspaceViewId) => void;
}) {
  return (
    <aside
      className="sd-graph-inspector sd-liquid-host"
      aria-label="图谱上下文"
    >
      <LiquidMaterial
        variant="inspector"
        radius={20}
        strength={8}
        artRef={artRef}
      />
      <header>
        <span>
          {selected
            ? `${selected.layer} / ${KIND_LABEL[selected.kind]}`
            : "依赖关系"}
        </span>
        <button
          type="button"
          className="sd-icon"
          aria-label="关闭上下文"
          onClick={closeInspector}
        >
          <X size={16} />
        </button>
      </header>
      {selected ? (
        <>
          <div
            className="sd-inspector-identity"
            style={
              {
                "--layer-color": LAYERS[selected.layer].color,
              } as CSSProperties
            }
          >
            <SmartDataGlyph kind={selected.kind} size={27} />
            <h2>{selected.label}</h2>
            <p>{selected.summary}</p>
          </div>
          <div className="sd-inspector-badges">
            <span>{NATURES[selected.evidenceNature]}</span>
            <span>
              {selected.materialization === "NOT_MATERIALIZED"
                ? "尚未生成"
                : "仅元数据"}
            </span>
            {selected.provenance === "DEMO" && <span>合成演示</span>}
          </div>
          {selected.materialization === "NOT_MATERIALIZED" && (
            <p className="sd-inspector-definition-note">
              {selected.kind === "DECISION_PLAN"
                ? "这里只登记了计划，没有执行和结果核验记录。"
                : "这里只登记了定义与依赖，还没有实际生成数据。“物化”指真正生成这些结果。"}
            </p>
          )}
          <div className="sd-inspector-section">
            <h3>独立状态</h3>
            <dl>
              <dt>观察时效</dt>
              <dd>{FRESHNESS[selected.freshness]}</dd>
              <dt>元数据权限</dt>
              <dd>
                {selected.authorization === "METADATA_VISIBLE"
                  ? "本工作区元数据可见"
                  : "元数据可见性未确认"}
              </dd>
              <dt>敏感级别</dt>
              <dd>{SENSITIVITY[selected.sensitivity]}</dd>
              <dt>观察时间</dt>
              <dd>{date(selected.observedAt)}</dd>
              {selected.ref.definitionVersion != null && (
                <>
                  <dt>定义版本</dt>
                  <dd>v{selected.ref.definitionVersion}</dd>
                </>
              )}
            </dl>
            <small>
              观察时效不证明表内业务记录都已更新；元数据可见不代表已获来源读写权限，也不证明凭据已验证。
            </small>
          </div>
          <div className="sd-inspector-section">
            <h3>沿关系查看</h3>
            <div className="sd-focus-actions">
              {(["upstream", "downstream", "both"] as const).map((mode) => (
                <button
                  key={mode}
                  type="button"
                  aria-pressed={focusMode === mode}
                  onClick={() => {
                    onFocus(focusMode === mode ? "all" : mode);
                  }}
                >
                  {mode === "upstream" ? (
                    <ArrowDownLeft size={13} />
                  ) : mode === "downstream" ? (
                    <ArrowUpRight size={13} />
                  ) : (
                    <Focus size={13} />
                  )}
                  {
                    {
                      upstream: "上游",
                      downstream: "下游",
                      both: "整条链路",
                    }[mode]
                  }
                </button>
              ))}
            </div>
            <small>只沿本视图已显示的关系聚焦，不补全未知或未覆盖依赖。</small>
          </div>
          {selected.notices.length > 0 && (
            <div className="sd-inspector-section">
              <h3>范围与限制</h3>
              {selected.notices.map((notice, index) => (
                <p key={index}>{notice}</p>
              ))}
            </div>
          )}
          {selected.evidenceRefs.length > 0 && (
            <div className="sd-inspector-section">
              <h3>观察依据</h3>
              {selected.evidenceRefs.map((ref, index) => (
                <button
                  type="button"
                  className="sd-inspector-evidence"
                  key={`${ref.scanId}-${index}`}
                  onClick={() => {
                    const asset = workspace.assets.find(
                      (item) => item.id === ref.assetId,
                    );
                    if (asset) onAsset(asset);
                  }}
                  disabled={
                    !workspace.assets.some((item) => item.id === ref.assetId)
                  }
                >
                  <Table2 size={14} />
                  <span>
                    {workspace.assets.find((item) => item.id === ref.assetId)
                      ?.name ?? ref.assetId}
                    <small>
                      {date(ref.observedAt)} · {ref.sha256.slice(0, 12)}
                    </small>
                  </span>
                  <ArrowUpRight size={13} />
                </button>
              ))}
            </div>
          )}
          {selected.ref.assetId && (
            <button
              type="button"
              className="sd-button sd-secondary"
              onClick={() => {
                const asset = workspace.assets.find(
                  (item) => item.id === selected.ref.assetId,
                );
                if (asset) onAsset(asset);
              }}
              disabled={
                !workspace.assets.some(
                  (item) => item.id === selected.ref.assetId,
                )
              }
            >
              查看字段与证据
              <ArrowUpRight size={14} />
            </button>
          )}
          {selected.kind === "TABLE" &&
            selected.ref.sourceId &&
            selected.ref.assetId && (
              <button
                type="button"
                className="sd-button sd-secondary"
                onClick={() => expandTable(selected)}
              >
                在图上展开本表字段
                <GitBranch size={14} />
              </button>
            )}
          {(selected.kind === "METRIC" ||
            selected.kind === "DECISION_PLAN") && (
            <button
              type="button"
              className="sd-button sd-secondary"
              onClick={() => navigate("business")}
            >
              查看业务定义
              <ArrowRight size={14} />
            </button>
          )}
        </>
      ) : (
        edge && (
          <>
            <div className="sd-inspector-identity">
              <GitBranch size={25} />
              <h2>
                {graph?.nodes.find((node) => node.id === edge.from)?.label}
                <ArrowRight size={16} />
                {graph?.nodes.find((node) => node.id === edge.to)?.label}
              </h2>
              <p>{edge.description}</p>
            </div>
            <div className="sd-inspector-badges">
              <span>{NATURES[edge.evidenceNature]}</span>
              <span>方向示意</span>
            </div>
            <div className="sd-inspector-section">
              <h3>转换依据</h3>
              <p>
                {edge.transformationStatus === "UNKNOWN"
                  ? "字段级转换未采集，不能据连线推断计算公式。"
                  : edge.transformationStatus === "DECLARED_ONLY"
                    ? "只有人工文字声明，尚无实际转换运行证据。"
                    : "物理包含关系，无业务计算转换。"}
              </p>
            </div>
            {edge.evidenceRefs.length > 0 && (
              <div className="sd-inspector-section">
                <h3>关系观察依据</h3>
                {edge.evidenceRefs.map((ref, index) => (
                  <button
                    type="button"
                    className="sd-inspector-evidence"
                    key={`${ref.scanId}-${index}`}
                    disabled={
                      !workspace.assets.some(
                        (asset) => asset.id === ref.assetId,
                      )
                    }
                    onClick={() => {
                      const asset = workspace.assets.find(
                        (item) => item.id === ref.assetId,
                      );
                      if (asset) onAsset(asset);
                    }}
                  >
                    <Table2 size={14} />
                    <span>
                      {workspace.assets.find(
                        (asset) => asset.id === ref.assetId,
                      )?.name ?? ref.assetId}
                      <small>
                        {date(ref.observedAt)} · {ref.sha256.slice(0, 12)}
                      </small>
                    </span>
                    <ArrowUpRight size={13} />
                  </button>
                ))}
              </div>
            )}
          </>
        )
      )}
    </aside>
  );
}

import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowDownLeft,
  ArrowRight,
  ArrowUpRight,
  ChevronDown,
  Filter,
  GitBranch,
  List,
  LoaderCircle,
  Plus,
  Search,
  ShieldCheck,
  X,
} from "lucide-react";
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type RefObject,
  type PointerEvent,
} from "react";
import { workspaceClient } from "./client";
import { layoutLineage, relatedNodeIds } from "./lineage-layout";
import { MappingEditor } from "./MappingEditor";
import { boundFieldPage } from "./lineage-fields";
import { SmartDataGlyph } from "../brand/SmartDataIcons";
import type {
  DataLayer,
  LineageNode,
  SaveLineageMappingsCommand,
  WorkspaceAsset,
  WorkspaceState,
  WorkspaceLineage,
  WorkspaceViewId,
} from "./types";
import { GraphLayerNavigator, GraphCameraControls } from "./GraphControls";
import { GraphCanvas } from "./GraphCanvas";
import { GraphInspector } from "./GraphInspector";
import { LiquidMaterial } from "../materials/LiquidMaterial";
import {
  LAYERS,
  NATURES,
  KIND_LABEL,
  FRESHNESS,
  type FocusMode,
} from "./graph-semantics";
import "./graph.css";

export function GraphView({
  workspace,
  canEdit,
  onAsset,
  navigate,
  onConnect,
  onDemo,
  pending,
  onSave,
  artRef,
}: {
  workspace: WorkspaceState;
  canEdit: boolean;
  onAsset: (asset: WorkspaceAsset) => void;
  navigate: (view: WorkspaceViewId) => void;
  onConnect: () => void;
  onDemo: () => void;
  pending: boolean;
  artRef?: RefObject<HTMLImageElement | null>;
  onSave: (
    command: SaveLineageMappingsCommand,
  ) => Promise<WorkspaceState | undefined>;
}) {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: ["workspace-lineage", workspace.stateVersion],
    queryFn: workspaceClient.lineage,
    retry: false,
  });
  const snapshotMismatch =
    !!query.data && query.data.stateVersion !== workspace.stateVersion;
  const observedGraph =
    snapshotMismatch || query.isError ? undefined : query.data;
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [focusMode, setFocusMode] = useState<FocusMode>("all");
  const [search, setSearch] = useState("");
  const [layers, setLayers] = useState<DataLayer[]>(["L0", "L1", "L2", "L3"]);
  const [nature, setNature] = useState("ALL");
  const [fields, setFields] = useState(false);
  const [sources, setSources] = useState(false);
  const [fieldRequest, setFieldRequest] = useState<{
    sourceId: string;
    assetId: string;
    tableNodeId: string;
    offset: number;
  } | null>(null);
  const [listMode, setListMode] = useState(false);
  const [legend, setLegend] = useState(false);
  const [filterOpen, setFilterOpen] = useState(false);
  const [mappingOpen, setMappingOpen] = useState(false);
  const [mappingGraph, setMappingGraph] = useState<WorkspaceLineage | null>(
    null,
  );
  const [dragging, setDragging] = useState(false);
  const [viewport, setViewport] = useState({ x: 16, y: 12, scale: 0.85 });
  const canvasRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{
    x: number;
    y: number;
    originalX: number;
    originalY: number;
    pointerId: number;
  } | null>(null);
  const fieldQuery = useQuery({
    queryKey: [
      "lineage-fields",
      workspace.stateVersion,
      fieldRequest?.sourceId,
      fieldRequest?.assetId,
      fieldRequest?.offset,
    ],
    queryFn: () =>
      workspaceClient.lineageFields(
        fieldRequest!.sourceId,
        fieldRequest!.assetId,
        fieldRequest!.offset,
      ),
    enabled: !!fieldRequest && !!observedGraph,
    retry: false,
  });
  const fieldPage = fieldRequest
    ? boundFieldPage(
        fieldQuery.data,
        workspace.stateVersion,
        fieldRequest.sourceId,
        fieldRequest.assetId,
        fieldRequest.offset,
      )
    : undefined;
  const graph = useMemo(() => {
    const base = observedGraph;
    if (!base || !fieldPage || !fieldRequest) return base;
    const nodes = new Map(base.nodes.map((node) => [node.id, node]));
    fieldPage.nodes.forEach((node) => nodes.set(node.id, node));
    const edges = new Map(base.edges.map((edge) => [edge.id, edge]));
    fieldPage.edges
      .filter(
        (edge) =>
          edge.from === fieldRequest.tableNodeId &&
          fieldPage.nodes.some((node) => node.id === edge.to) &&
          edge.kind === "CONTAINS" &&
          edge.evidenceNature === "OBSERVED",
      )
      .forEach((edge) => edges.set(edge.id, edge));
    return { ...base, nodes: [...nodes.values()], edges: [...edges.values()] };
  }, [observedGraph, fieldPage, fieldRequest]);
  const selected = graph?.nodes.find((node) => node.id === selectedId);
  const editorGraph = graph ?? mappingGraph;
  const edge = graph?.edges.find((item) => item.id === selectedEdgeId);
  const focused = useMemo(
    () =>
      selectedId && focusMode !== "all" && graph
        ? relatedNodeIds(selectedId, graph.edges, focusMode)
        : null,
    [selectedId, focusMode, graph],
  );
  const dependencyFields = useMemo(
    () =>
      new Set(
        (graph?.edges ?? [])
          .filter((edge) => edge.kind !== "CONTAINS")
          .map((edge) => edge.from),
      ),
    [graph],
  );
  const visibleNodes = useMemo(
    () =>
      (graph?.nodes ?? []).filter(
        (node) =>
          layers.includes(node.layer) &&
          (sources ||
            node.kind !== "SOURCE" ||
            node.id === selectedId ||
            focused?.has(node.id) ||
            !graph?.nodes.some(
              (item) =>
                item.kind === "TABLE" &&
                item.ref.sourceId === node.ref.sourceId,
            )) &&
          (nature === "ALL" || node.evidenceNature === nature) &&
          (fields ||
            node.kind !== "FIELD" ||
            node.id === selectedId ||
            dependencyFields.has(node.id)) &&
          (node.kind !== "FIELD" ||
            !fieldRequest ||
            dependencyFields.has(node.id) ||
            fieldPage?.nodes.some((field) => field.id === node.id) ||
            node.id === selectedId ||
            focused?.has(node.id)) &&
          (!focused || focused.has(node.id)) &&
          (!search ||
            `${node.label} ${node.summary}`
              .toLowerCase()
              .includes(search.toLowerCase())),
      ),
    [
      graph,
      layers,
      nature,
      fields,
      sources,
      focused,
      search,
      selectedId,
      fieldRequest,
      fieldPage,
      dependencyFields,
    ],
  );
  const visibleIds = new Set(visibleNodes.map((node) => node.id));
  const visibleEdges = (graph?.edges ?? []).filter(
    (item) => visibleIds.has(item.from) && visibleIds.has(item.to),
  );
  const positioned = useMemo(
    () => layoutLineage(visibleNodes, visibleEdges),
    [visibleNodes, graph, fields, focusMode, nature, layers],
  );
  const inspectorOpen = !!(selected || edge);
  useEffect(() => {
    if (!fieldPage || !fieldRequest) return;
    const point = positioned.positions.get(fieldRequest.tableNodeId);
    if (point)
      setViewport((old) => ({
        ...old,
        scale: Math.max(0.72, old.scale),
        y: 76 - point.y * Math.max(0.72, old.scale),
      }));
  }, [fieldPage?.offset, fieldPage?.assetId]);
  useEffect(() => {
    if (!selectedId || !canvasRef.current) return;
    const point = positioned.positions.get(selectedId);
    if (!point) return;
    const width = canvasRef.current.clientWidth;
    if (!width) return;
    setViewport((old) => {
      const right = old.x + (point.x + point.width) * old.scale;
      const left = old.x + point.x * old.scale;
      return right > width - 24
        ? { ...old, x: old.x - (right - width + 24) }
        : left < 20
          ? { ...old, x: old.x + 20 - left }
          : old;
    });
  }, [selectedId, inspectorOpen, positioned]);

  function fit(initial = false) {
    const element = canvasRef.current;
    if (!element) return;
    const width = element.clientWidth || 1000;
    const height = element.clientHeight || 580;
    const scale = initial
      ? width < 640
        ? 0.88
        : 1
      : Math.min(
          1,
          Math.max(
            0.3,
            Math.min(
              (width - 42) / positioned.width,
              (height - 40) / positioned.height,
            ),
          ),
        );
    setViewport({
      x: initial ? 24 : Math.max(20, (width - positioned.width * scale) / 2),
      y: initial ? 124 : 98,
      scale,
    });
  }
  useEffect(() => {
    if (!graph) return;
    fit(true);
    // A new server revision resets only framing, never fabricates graph content.
  }, [graph?.stateVersion, listMode]);
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const wheel = (event: WheelEvent) => {
      event.preventDefault();
      if (!(event.ctrlKey || event.metaKey)) {
        setViewport((old) => ({
          ...old,
          x: old.x - (event.shiftKey ? event.deltaY : event.deltaX),
          y: old.y - (event.shiftKey ? 0 : event.deltaY),
        }));
        return;
      }
      const rect = canvas.getBoundingClientRect();
      const px = event.clientX - rect.left,
        py = event.clientY - rect.top;
      setViewport((old) => {
        const scale = Math.min(
          1.8,
          Math.max(0.3, old.scale * Math.exp(-event.deltaY * 0.008)),
        );
        return {
          scale,
          x: px - ((px - old.x) * scale) / old.scale,
          y: py - ((py - old.y) * scale) / old.scale,
        };
      });
    };
    canvas.addEventListener("wheel", wheel, { passive: false });
    return () => canvas.removeEventListener("wheel", wheel);
  }, [listMode, query.isPending]);
  useEffect(() => {
    if (!selectedId) return;
    const params = new URLSearchParams(
      window.location.hash.split("?")[1] ?? "",
    );
    params.set("node", selectedId);
    window.history.replaceState(null, "", `#/graph?${params}`);
  }, [selectedId]);
  useEffect(() => {
    const id = new URLSearchParams(
      window.location.hash.split("?")[1] ?? "",
    ).get("node");
    if (id && graph?.nodes.some((node) => node.id === id)) setSelectedId(id);
  }, [graph?.stateVersion]);
  function select(node: LineageNode) {
    setSelectedId(node.id);
    setSelectedEdgeId(null);
  }
  function expandTable(node: LineageNode) {
    if (!node.ref.sourceId || !node.ref.assetId) return;
    setFieldRequest({
      sourceId: node.ref.sourceId,
      assetId: node.ref.assetId,
      tableNodeId: node.id,
      offset: 0,
    });
    setFields(true);
    setFocusMode("all");
    setSearch("");
    setNature("ALL");
    setLayers((old) => (old.includes("L0") ? old : ["L0", ...old]));
  }
  function closeInspector() {
    canvasRef.current?.focus();
    setSelectedId(null);
    setSelectedEdgeId(null);
    setFocusMode("all");
    window.history.replaceState(null, "", "#/graph");
  }
  function panStart(event: PointerEvent<HTMLDivElement>) {
    if (
      (event.target as Element).closest(
        "button, a, input, select, [role=button]",
      )
    )
      return;
    dragRef.current = {
      x: event.clientX,
      y: event.clientY,
      originalX: viewport.x,
      originalY: viewport.y,
      pointerId: event.pointerId,
    };
    setDragging(true);
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  return (
    <section
      className={`sd-graph-workbench ${inspectorOpen ? "has-inspector" : ""}`}
      aria-label="分层数据图谱"
      onKeyDown={(event) => {
        if (event.key === "Escape" && (selectedId || selectedEdgeId)) {
          event.preventDefault();
          closeInspector();
        }
      }}
    >
      <div className="sd-graph-toolbar sd-liquid-host">
        <LiquidMaterial
          variant="toolbar"
          radius={18}
          strength={9}
          artRef={artRef}
        />
        <div className="sd-graph-title">
          <GitBranch size={17} />
          <h1>数据图谱</h1>
          <span>业务语义与来源</span>
        </div>
        <div className="sd-graph-tools">
          <label className="sd-graph-search">
            <Search size={14} />
            <input
              aria-label="搜索图谱节点"
              placeholder="查找表、实体或指标"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            {search && (
              <button
                type="button"
                aria-label="清空图谱搜索"
                onClick={() => setSearch("")}
              >
                <X size={12} />
              </button>
            )}
          </label>
          <button
            type="button"
            className="sd-button sd-quiet"
            aria-expanded={filterOpen}
            onClick={() => setFilterOpen(!filterOpen)}
          >
            <Filter size={14} />
            <span>筛选</span>
            <ChevronDown size={12} />
          </button>
          <button
            type="button"
            className="sd-icon"
            aria-label={listMode ? "显示图谱" : "显示节点列表"}
            onClick={() => setListMode(!listMode)}
          >
            {listMode ? <GitBranch size={16} /> : <List size={16} />}
          </button>
          <button
            type="button"
            className="sd-button sd-secondary"
            onClick={() => {
              setMappingGraph(graph ?? null);
              setMappingOpen(true);
            }}
            disabled={!canEdit || !graph || pending}
          >
            <Plus size={14} />
            <span>管理映射</span>
          </button>
        </div>
      </div>
      {filterOpen && (
        <div className="sd-graph-filters sd-liquid-host" aria-label="图谱筛选">
          <LiquidMaterial
            variant="toolbar"
            radius={18}
            strength={8}
            artRef={artRef}
          />
          <div>
            {(Object.keys(LAYERS) as DataLayer[]).map((layer) => (
              <label key={layer}>
                <input
                  type="checkbox"
                  checked={layers.includes(layer)}
                  onChange={(event) =>
                    setLayers(
                      event.target.checked
                        ? [...layers, layer]
                        : layers.filter((item) => item !== layer),
                    )
                  }
                />
                <i style={{ background: LAYERS[layer].color }} />
                {layer} {LAYERS[layer].name}
              </label>
            ))}
          </div>
          <label>
            依据类型
            <select
              aria-label="按关系依据筛选"
              value={nature}
              onChange={(event) => setNature(event.target.value)}
            >
              <option value="ALL">全部依据</option>
              {Object.entries(NATURES).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label>
            <input
              type="checkbox"
              checked={sources}
              onChange={(event) => setSources(event.target.checked)}
            />
            显示来源结构
          </label>
          <label>
            <input
              type="checkbox"
              checked={fields}
              onChange={(event) => {
                setFields(event.target.checked);
                if (!event.target.checked) {
                  setFieldRequest(null);
                  return;
                }
                const table =
                  selected?.kind === "TABLE"
                    ? selected
                    : graph?.nodes.find((node) => node.kind === "TABLE");
                if (graph?.coverage?.partial && table) {
                  select(table);
                  expandTable(table);
                }
              }}
            />
            展开其余字段
          </label>
        </div>
      )}
      {graph && (
        <div className="sd-graph-scope">
          <span>
            <ShieldCheck size={12} />
            本轮可见范围 · {graph.scope.sourceIds.length} 个来源
          </span>
          <span>
            工作区 v{graph.stateVersion} · 映射 v{graph.mappingVersion}
          </span>
          {graph.scope.excludedAuthorizedSourceCount > 0 && (
            <button type="button" onClick={() => navigate("business")}>
              {graph.scope.excludedAuthorizedSourceCount} 个来源未纳入本轮
            </button>
          )}
          {workspace.sources.some((source) => source.provenance === "DEMO") && (
            <b>合成演示</b>
          )}
        </div>
      )}
      {graph && !listMode && graph.nodes.length > 0 && (
        <GraphLayerNavigator
          nodes={visibleNodes}
          legend={legend}
          onLegend={() => setLegend(!legend)}
          onLocate={(layer) => {
            const node = visibleNodes.find((item) => item.layer === layer);
            const point = node && positioned.positions.get(node.id);
            if (point)
              setViewport({ scale: 1, x: 32 - point.x, y: 125 - point.y });
          }}
        />
      )}
      {graph &&
        (!graph.coverage ||
          graph.coverage.partial ||
          (fieldPage && fieldPage.totalFields > fieldPage.nodes.length)) && (
          <div className="sd-graph-coverage" role="status">
            <strong>
              {graph.coverage ? "当前仅展示部分依赖" : "覆盖范围尚未确认"}
            </strong>
            <span>
              {graph.coverage
                ? `主图返回：来源 ${graph.coverage.shownSources}/${graph.coverage.totalSources}，表 ${graph.coverage.shownTables}/${graph.coverage.totalTables}，字段 ${graph.coverage.shownFields}/${graph.coverage.totalFields}。`
                : "当前服务没有提供覆盖统计。"}
              不能据此判断完整影响；可按表分批查看字段。
              {graph.coverage && graph.coverage.omittedDerivedDefinitions > 0
                ? ` ${graph.coverage.omittedDerivedDefinitions} 项派生定义因输入未完整展示而隐藏。`
                : ""}
            </span>
          </div>
        )}
      {fieldRequest && fields && (
        <div className="sd-graph-field-scope">
          <strong>
            {workspace.assets.find((asset) => asset.id === fieldRequest.assetId)
              ?.name ?? "当前表"}{" "}
            · 字段
          </strong>
          {fieldPage ? (
            <>
              <span>
                {fieldPage.totalFields ? fieldPage.offset + 1 : 0}–
                {fieldPage.offset + fieldPage.nodes.length} /{" "}
                {fieldPage.totalFields}
              </span>
              <button
                type="button"
                className="sd-icon"
                aria-label="图谱字段上一页"
                disabled={!fieldPage.offset || fieldQuery.isFetching}
                onClick={() =>
                  setFieldRequest({
                    ...fieldRequest,
                    offset: Math.max(0, fieldPage.offset - fieldPage.limit),
                  })
                }
              >
                <ArrowDownLeft size={13} />
              </button>
              <button
                type="button"
                className="sd-icon"
                aria-label="图谱字段下一页"
                disabled={!fieldPage.hasMore || fieldQuery.isFetching}
                onClick={() =>
                  setFieldRequest({
                    ...fieldRequest,
                    offset: fieldPage.offset + fieldPage.nodes.length,
                  })
                }
              >
                <ArrowUpRight size={13} />
              </button>
            </>
          ) : (
            <span>
              {fieldQuery.isError
                ? "字段读取失败，主图仍保留"
                : fieldQuery.data
                  ? "版本或对象不一致，请刷新工作区"
                  : "正在按表读取字段元数据…"}
            </span>
          )}
          {fieldQuery.isError && (
            <button type="button" onClick={() => fieldQuery.refetch()}>
              重试
            </button>
          )}
          <button
            type="button"
            className="sd-graph-field-exit"
            onClick={() => {
              setSelectedId(fieldRequest.tableNodeId);
              setFieldRequest(null);
              setFields(false);
            }}
          >
            返回主图
            <X size={12} />
          </button>
        </div>
      )}
      {graph && selectedId && !selected && (
        <div className="sd-graph-selection-note" role="status">
          选中的节点当前不可见。请核对范围与观察状态；这不表示相关问题已修复。
          <button type="button" onClick={closeInspector}>
            清除选择
          </button>
        </div>
      )}
      <div className="sd-graph-body">
        <div className="sd-graph-stage">
          {query.isPending ? (
            <div className="sd-graph-state" role="status">
              <LoaderCircle size={22} className="sd-spinning" />
              <h2>正在读取图谱</h2>
              <p>仅加载已授权范围的元数据与声明。</p>
            </div>
          ) : query.isError ? (
            <div className="sd-graph-state" role="alert">
              <GitBranch size={28} />
              <h2>图谱暂时不可用</h2>
              <p>
                {query.error instanceof Error
                  ? query.error.message
                  : "请确认服务已启用图谱接口。"}
              </p>
              <button
                className="sd-button sd-secondary"
                type="button"
                onClick={() => query.refetch()}
              >
                重新读取图谱
              </button>
              <button
                className="sd-text-button"
                type="button"
                onClick={() => navigate("overview")}
              >
                返回工作空间
                <ArrowRight size={13} />
              </button>
            </div>
          ) : snapshotMismatch ? (
            <div className="sd-graph-state" role="status">
              <GitBranch size={25} />
              <h2>工作区观察已经更新</h2>
              <p>
                请刷新后查看同一版本的来源、图谱和证据，避免把不同观察混在一起。
              </p>
              <button
                type="button"
                className="sd-button sd-secondary"
                onClick={() =>
                  queryClient.invalidateQueries({ queryKey: ["workspace"] })
                }
              >
                刷新工作区
              </button>
            </div>
          ) : !graph?.nodes.length ? (
            <div className="sd-graph-empty">
              <figure className="sd-graph-art">
                <img
                  src="/art/strata-study-v1.webp"
                  alt=""
                  width="1536"
                  height="1024"
                  loading="lazy"
                  decoding="async"
                />
                <figcaption className="sd-sr-only">
                  分层空间习作，非业务数据或实际血缘
                </figcaption>
              </figure>
              <h2>
                {workspace.sources.length
                  ? "本轮范围还没有可见节点"
                  : "从一份真实数据开始"}
              </h2>
              <p>
                {workspace.sources.length
                  ? "检查业务约定中的来源范围及连接状态；没有节点不代表企业没有数据。"
                  : "接入来源后查看实际的表与字段，再逐步登记业务含义。"}
              </p>
              <div>
                <button
                  type="button"
                  className="sd-button sd-primary"
                  onClick={
                    workspace.sources.length
                      ? () => navigate("business")
                      : onConnect
                  }
                >
                  {workspace.sources.length ? "调整本轮范围" : "连接数据"}
                  <ArrowRight size={14} />
                </button>
                {!workspace.sources.length && (
                  <button
                    type="button"
                    className="sd-button sd-quiet"
                    disabled={pending}
                    onClick={onDemo}
                  >
                    先体验电商示例
                  </button>
                )}
              </div>
              <small>
                后续可登记实体、指标和决策用途，所有关系都保留依据。
              </small>
            </div>
          ) : listMode ? (
            <div className="sd-graph-node-list" aria-label="节点列表">
              <div className="sd-graph-list-head">
                <span>节点</span>
                <span>层级</span>
                <span>依据</span>
                <span>观察时效</span>
              </div>
              {visibleNodes.map((node) => {
                return (
                  <button
                    type="button"
                    key={node.id}
                    aria-pressed={node.id === selectedId}
                    onClick={() => select(node)}
                  >
                    <span>
                      <SmartDataGlyph
                        kind={node.kind}
                        size={16}
                        style={{ color: LAYERS[node.layer].color }}
                      />
                      <strong>{node.label}</strong>
                    </span>
                    <span>
                      {node.layer} · {KIND_LABEL[node.kind]}
                    </span>
                    <span>{NATURES[node.evidenceNature]}</span>
                    <span>{FRESHNESS[node.freshness]}</span>
                  </button>
                );
              })}
              {!visibleNodes.length && (
                <p className="sd-graph-no-match">
                  筛选后没有节点。请调整搜索或层级。
                </p>
              )}
            </div>
          ) : (
            <div
              ref={canvasRef}
              className="sd-graph-canvas"
              tabIndex={0}
              aria-label="图谱画布；方向键平移，加减缩放，0适应，Escape退出聚焦"
              onPointerDown={panStart}
              onPointerMove={(event) => {
                const drag = dragRef.current;
                if (drag?.pointerId === event.pointerId)
                  setViewport((old) => ({
                    ...old,
                    x: drag.originalX + event.clientX - drag.x,
                    y: drag.originalY + event.clientY - drag.y,
                  }));
              }}
              onPointerUp={() => {
                dragRef.current = null;
                setDragging(false);
              }}
              onPointerCancel={() => {
                dragRef.current = null;
                setDragging(false);
              }}
              onKeyDown={(event) => {
                if (event.target !== event.currentTarget) return;
                if (
                  ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(
                    event.key,
                  )
                ) {
                  event.preventDefault();
                  setViewport((old) => ({
                    ...old,
                    x:
                      old.x +
                      (event.key === "ArrowLeft"
                        ? 48
                        : event.key === "ArrowRight"
                          ? -48
                          : 0),
                    y:
                      old.y +
                      (event.key === "ArrowUp"
                        ? 48
                        : event.key === "ArrowDown"
                          ? -48
                          : 0),
                  }));
                }
                if (event.key === "+" || event.key === "=")
                  setViewport((old) => ({
                    ...old,
                    scale: Math.min(1.8, old.scale + 0.1),
                  }));
                if (event.key === "-")
                  setViewport((old) => ({
                    ...old,
                    scale: Math.max(0.3, old.scale - 0.1),
                  }));
                if (event.key === "0") fit();
                if (event.key === "Escape") closeInspector();
              }}
            >
              <GraphCanvas
                nodes={visibleNodes}
                edges={visibleEdges}
                positions={positioned.positions}
                width={positioned.width}
                height={positioned.height}
                viewport={viewport}
                dragging={dragging}
                selectedId={selectedId}
                selectedEdgeId={selectedEdgeId}
                onSelect={select}
                onSelectEdge={(id) => {
                  setSelectedEdgeId(id);
                  setSelectedId(null);
                }}
                sourceLabels={
                  new Map(
                    workspace.sources.map((source) => [source.id, source.name]),
                  )
                }
              />

              {!visibleNodes.length && (
                <p className="sd-graph-no-match">
                  筛选后没有节点。请调整搜索或层级。
                </p>
              )}
            </div>
          )}
          {graph?.nodes.length ? (
            <div className="sd-graph-canvas-bottom">
              {!listMode && (
                <GraphCameraControls
                  scale={viewport.scale}
                  onZoom={(delta) =>
                    setViewport((old) => ({
                      ...old,
                      scale: Math.min(1.8, Math.max(0.3, old.scale + delta)),
                    }))
                  }
                  onFit={() => fit()}
                />
              )}
            </div>
          ) : null}
          {legend && (
            <div className="sd-graph-legend sd-liquid-host">
              <button
                className="sd-icon"
                type="button"
                aria-label="关闭图例"
                onClick={() => setLegend(false)}
              >
                <X size={14} />
              </button>
              <LiquidMaterial
                variant="dialog"
                radius={18}
                strength={7}
                artRef={artRef}
              />
              <h3>读懂图谱</h3>
              <p>
                颜色表示业务层级，图标与轮廓表示类型；箭头从输入指向使用它的对象。位置按依赖排列，不是固定层级分栏。
              </p>
              <div>
                <span className="sd-legend-line" />
                实线：实际观察的包含关系
              </div>
              <div>
                <span className="sd-legend-line is-declared" />
                虚线：人工登记的依赖
              </div>
              <div>
                <span className="sd-legend-line is-planned" />
                点线：尚在规划的用途
              </div>
              <p>
                声明与规划不证明数据已经生成。新鲜度、授权和敏感级别分别核对。
              </p>
              <p>
                默认折叠来源登记与未使用字段。在筛选中打开“显示来源结构”或“展开其余字段”，可查看已返回的细节。
              </p>
              <small>
                拖动或滚动画布 · Ctrl/⌘ + 滚轮缩放 · 方向键平移 · 0 适应画布
                <br />
                使用“节点列表”可不依赖空间图形阅读当前筛选下的节点。
              </small>
            </div>
          )}
        </div>
        {inspectorOpen && (
          <GraphInspector
            selected={selected}
            edge={edge}
            graph={graph}
            workspace={workspace}
            focusMode={focusMode}
            artRef={artRef}
            onFocus={(mode) => {
              setFocusMode(mode);
              setSearch("");
              setNature("ALL");
              setLayers(["L0", "L1", "L2", "L3"]);
              setFields(true);
              setSources(true);
            }}
            closeInspector={closeInspector}
            onAsset={onAsset}
            expandTable={expandTable}
            navigate={navigate}
          />
        )}
      </div>
      {graph && (
        <div className="sd-graph-footnote">
          <span>
            {visibleNodes.length} / {graph.nodes.length} 个节点 ·{" "}
            {visibleEdges.length} 条可见关系
          </span>
          <span>
            {graph.notices[0] ?? "图谱展示观察与已登记语义，不推测企业全貌。"}
          </span>
        </div>
      )}
      {mappingOpen && editorGraph && (
        <MappingEditor
          workspace={workspace}
          graph={editorGraph}
          pending={pending}
          onClose={() => setMappingOpen(false)}
          onSave={async (command) => {
            const next = await onSave(command);
            if (next) {
              setMappingOpen(false);
            }
            return next;
          }}
        />
      )}
    </section>
  );
}

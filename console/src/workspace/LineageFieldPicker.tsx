import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, Search, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { workspaceClient } from "./client";
import { boundFieldPage } from "./lineage-fields";
import type {
  PhysicalFieldRef,
  WorkspaceLineage,
  WorkspaceState,
} from "./types";

const equalField = (left: PhysicalFieldRef, right: PhysicalFieldRef) =>
  left.sourceId === right.sourceId &&
  left.assetId === right.assetId &&
  left.fieldName === right.fieldName;

/** Field choices come from scoped, versioned metadata pages, never the drawing budget. */
export function LineageFieldPicker({
  workspace,
  graph,
  selected,
  onChange,
  label,
}: {
  workspace: WorkspaceState;
  graph: WorkspaceLineage;
  selected: PhysicalFieldRef[];
  onChange: (fields: PhysicalFieldRef[]) => void;
  label: string;
}) {
  const assets = useMemo(() => {
    const sourceIds = new Set(
      workspace.sources
        .filter(
          (source) =>
            source.status === "SCANNED" &&
            graph.scope.sourceIds.includes(source.id),
        )
        .map((source) => source.id),
    );
    return workspace.assets.filter(
      (asset) =>
        sourceIds.has(asset.sourceId) &&
        workspace.scans
          .findLast(
            (scan) =>
              scan.sourceId === asset.sourceId && scan.status === "SUCCEEDED",
          )
          ?.assetIds.includes(asset.id),
    );
  }, [
    workspace.sources,
    workspace.assets,
    workspace.scans,
    graph.scope.sourceIds,
  ]);
  const [assetId, setAssetId] = useState(
    () =>
      assets.find((asset) => selected.some((ref) => ref.assetId === asset.id))
        ?.id ??
      assets[0]?.id ??
      "",
  );
  const [offset, setOffset] = useState(0);
  const [search, setSearch] = useState("");
  useEffect(() => {
    if (!assets.some((asset) => asset.id === assetId)) {
      setAssetId(assets[0]?.id ?? "");
      setOffset(0);
    }
  }, [assets, assetId]);
  const asset = assets.find((item) => item.id === assetId);
  const query = useQuery({
    queryKey: [
      "lineage-fields",
      workspace.stateVersion,
      asset?.sourceId,
      assetId,
      offset,
    ],
    queryFn: () =>
      workspaceClient.lineageFields(asset!.sourceId, assetId, offset),
    enabled: !!asset,
    retry: false,
  });
  const page = asset
    ? boundFieldPage(
        query.data,
        workspace.stateVersion,
        asset.sourceId,
        assetId,
        offset,
      )
    : undefined;
  const nodes =
    page?.nodes.filter(
      (node) =>
        !search || node.label.toLowerCase().includes(search.toLowerCase()),
    ) ?? [];
  const missing = selected.filter(
    (ref) =>
      !assets.some(
        (asset) => asset.id === ref.assetId && asset.sourceId === ref.sourceId,
      ),
  );
  return (
    <fieldset className="sd-mapping-field-picker sd-paged-fields">
      <legend>{label}</legend>
      <div className="sd-field-picker-tools">
        <label>
          数据表
          <select
            aria-label={`${label} 数据表`}
            value={assetId}
            onChange={(event) => {
              setAssetId(event.target.value);
              setOffset(0);
              setSearch("");
            }}
          >
            <option value="">选择本轮数据表…</option>
            {assets.map((asset) => (
              <option key={asset.id} value={asset.id}>
                {
                  workspace.sources.find(
                    (source) => source.id === asset.sourceId,
                  )?.name
                }{" "}
                / {asset.name}
              </option>
            ))}
          </select>
        </label>
        <label className="sd-field-page-search">
          <Search size={13} />
          <input
            aria-label={`${label} 筛选本页`}
            value={search}
            placeholder="筛选本页字段"
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
      </div>
      {missing.map((ref, index) => (
        <label key={`missing-${index}`} className="sd-mapping-missing">
          <input
            type="checkbox"
            checked
            onChange={() => onChange(selected.filter((item) => item !== ref))}
          />
          <span>
            {ref.fieldName}
            <small>不在本轮有效范围；取消勾选可移除此旧引用</small>
          </span>
        </label>
      ))}
      {!assets.length && (
        <p>本轮没有可登记的当前字段。请先成功盘点并纳入工作范围。</p>
      )}
      {asset && query.isPending && (
        <p role="status">正在读取该表的字段元数据…</p>
      )}
      {query.isError && (
        <p className="sd-error" role="alert">
          字段读取未完成，已选引用仍保留。
          <button
            type="button"
            className="sd-text-button"
            onClick={() => query.refetch()}
          >
            重新读取
          </button>
        </p>
      )}
      {query.data && !page && (
        <p className="sd-error" role="alert">
          工作区版本已变化，请关闭并刷新后继续，避免把不同观察混在一起。
        </p>
      )}
      {page && (
        <>
          <div className="sd-field-page-heading">
            <span>
              字段 {page.totalFields ? page.offset + 1 : 0}–
              {page.offset + page.nodes.length} / {page.totalFields}
            </span>
            <span>元数据 · 不读取业务行</span>
          </div>
          <div className="sd-field-page-options">
            {nodes.map((node) => {
              const ref: PhysicalFieldRef = {
                sourceId: node.ref.sourceId!,
                assetId: node.ref.assetId!,
                fieldName: node.ref.fieldName!,
              };
              const checked = selected.some((item) => equalField(item, ref));
              return (
                <label key={node.id}>
                  <input
                    type="checkbox"
                    checked={checked}
                    disabled={!checked && selected.length >= 100}
                    onChange={(event) =>
                      onChange(
                        event.target.checked
                          ? [...selected, ref]
                          : selected.filter((item) => !equalField(item, ref)),
                      )
                    }
                  />
                  <span>{ref.fieldName}</span>
                </label>
              );
            })}
            {!nodes.length && (
              <p>本页没有匹配字段；搜索只筛选当前页，可继续翻页。</p>
            )}
          </div>
          <div className="sd-field-pagination">
            <button
              type="button"
              className="sd-button sd-quiet"
              disabled={offset === 0 || query.isFetching}
              onClick={() => {
                setOffset(Math.max(0, offset - page.limit));
                setSearch("");
              }}
            >
              <ArrowLeft size={13} />
              上一页
            </button>
            <span>分批读取，每页最多128个字段</span>
            <button
              type="button"
              className="sd-button sd-quiet"
              disabled={!page.hasMore || query.isFetching}
              onClick={() => {
                setOffset(page.offset + page.nodes.length);
                setSearch("");
              }}
            >
              下一页
              <ArrowRight size={13} />
            </button>
          </div>
        </>
      )}
      {selected.length > 0 && (
        <details className="sd-selected-fields">
          <summary>已选 {selected.length} 个字段（跨页保留）</summary>
          {selected.map((ref, index) => (
            <div key={index}>
              <span>
                {workspace.assets.find((asset) => asset.id === ref.assetId)
                  ?.name ?? ref.assetId}{" "}
                / <strong>{ref.fieldName}</strong>
              </span>
              <button
                type="button"
                className="sd-icon"
                aria-label={`取消选择字段 ${ref.fieldName}`}
                onClick={() =>
                  onChange(selected.filter((item) => !equalField(item, ref)))
                }
              >
                <X size={12} />
              </button>
            </div>
          ))}
        </details>
      )}
    </fieldset>
  );
}

import { useQuery } from "@tanstack/react-query";
import { ArrowRight, GitBranch, Plus, Save, Trash2 } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { workspaceClient } from "./client";
import { Dialog } from "./Dialog";
import { LineageFieldPicker } from "./LineageFieldPicker";
import type {
  EntityLineageMapping,
  LineageMappings,
  PhysicalFieldRef,
  SaveLineageMappingsCommand,
  WorkspaceLineage,
  WorkspaceState,
} from "./types";

type Draft = Pick<LineageMappings, "entities" | "metrics" | "decisions">;
const MAPPING_TABS = ["entities", "metrics", "decisions"] as const;
export function MappingEditor({
  workspace,
  graph,
  pending,
  onClose,
  onSave,
}: {
  workspace: WorkspaceState;
  graph: WorkspaceLineage;
  pending: boolean;
  onClose: () => void;
  onSave: (
    command: SaveLineageMappingsCommand,
  ) => Promise<WorkspaceState | undefined>;
}) {
  const query = useQuery({
    queryKey: ["lineage-mappings", graph.mappingVersion],
    queryFn: workspaceClient.lineageMappings,
    retry: false,
  });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [editingVersion, setEditingVersion] = useState<number | null>(null);
  const [tab, setTab] = useState<"entities" | "metrics" | "decisions">(
    "entities",
  );
  const [error, setError] = useState("");
  const [dirty, setDirty] = useState(false);
  const stale =
    editingVersion != null &&
    (!query.data || query.data.version !== editingVersion);
  function load(value: LineageMappings) {
    setDraft({
      entities: value.entities,
      metrics: value.metrics,
      decisions: value.decisions,
    });
    setEditingVersion(value.version);
    setDirty(false);
    setError("");
  }
  useEffect(() => {
    if (query.data && !draft) load(query.data);
  }, [query.data, draft]);
  const visibleMetrics = workspace.businessBrief?.metrics ?? [];
  const visibleGoals = workspace.businessBrief?.goals ?? [];
  function change(next: Draft) {
    setDraft(next);
    setDirty(true);
    setError("");
  }
  function fieldPicker(
    selected: PhysicalFieldRef[],
    onChange: (fields: PhysicalFieldRef[]) => void,
    label: string,
  ) {
    return (
      <LineageFieldPicker
        workspace={workspace}
        graph={graph}
        selected={selected}
        onChange={onChange}
        label={label}
      />
    );
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!draft || !query.data || editingVersion == null || stale) return;
    setError("");
    if (
      draft.entities.some(
        (item) =>
          !item.name.trim() ||
          !item.entityType.trim() ||
          !item.transformationDescription.trim() ||
          !item.inputFields.length,
      )
    ) {
      setTab("entities");
      setError("每个实体需要名称、业务类型、至少一个输入字段和映射说明。");
      return;
    }
    if (
      draft.metrics.some(
        (item) =>
          !item.transformationDescription.trim() ||
          !(item.inputFields.length + item.inputEntityIds.length),
      )
    ) {
      setTab("metrics");
      setError("每个指标映射需要至少一个输入与依赖说明。");
      return;
    }
    if (
      draft.decisions.some(
        (item) => !item.decisionQuestion.trim() || !item.inputMetricIds.length,
      )
    ) {
      setTab("decisions");
      setError("每项决策用途需要至少一个指标和明确的决策问题。");
      return;
    }
    const result = await onSave({
      ...draft,
      expectedStateVersion: workspace.stateVersion,
      expectedMappingVersion: editingVersion,
    });
    if (!result) {
      setError(
        "保存未完成，草稿仍保留。请核对页面错误提示与最新版本，不要直接覆盖他人的修改。",
      );
      void query.refetch();
    }
  }
  return (
    <Dialog
      title="登记业务映射"
      subtitle="这是业务人员对依赖的明确声明，不会执行SQL、生成客户画像或计算指标。"
      onClose={onClose}
      wide
    >
      <div className="sd-mapping-intro">
        <GitBranch size={16} />
        <p>
          从实际字段到业务实体，再到指标与决策。图中的虚线只表示你已登记的关系；运行结果需要另外验证。
        </p>
      </div>
      <div className="sd-mapping-tabs" role="tablist" aria-label="映射层级">
        {MAPPING_TABS.map((key, index) => (
          <button
            type="button"
            key={key}
            role="tab"
            id={`sd-map-tab-${key}`}
            aria-controls="sd-map-panel"
            aria-selected={tab === key}
            tabIndex={tab === key ? 0 : -1}
            onClick={() => setTab(key)}
            onKeyDown={(event) => {
              const next =
                event.key === "ArrowRight"
                  ? (index + 1) % 3
                  : event.key === "ArrowLeft"
                    ? (index + 2) % 3
                    : event.key === "Home"
                      ? 0
                      : event.key === "End"
                        ? 2
                        : -1;
              if (next < 0) return;
              event.preventDefault();
              const target = MAPPING_TABS[next]!;
              setTab(target);
              document.getElementById(`sd-map-tab-${target}`)?.focus();
            }}
          >
            {`L${index + 1}`} ·{" "}
            {
              {
                entities: "业务实体",
                metrics: "指标依赖",
                decisions: "决策用途",
              }[key]
            }
            <span>{draft?.[key].length ?? 0}</span>
          </button>
        ))}
      </div>
      {query.isPending && (
        <p className="sd-mapping-state" role="status">
          正在读取已登记的映射…
        </p>
      )}
      {query.isError && (
        <div className="sd-mapping-state" role="alert">
          <p>无法读取映射，不能用空配置覆盖已有声明。</p>
          <button
            className="sd-button sd-secondary"
            type="button"
            onClick={() => query.refetch()}
          >
            重新读取
          </button>
        </div>
      )}
      {stale && (
        <div className="sd-mapping-stale" role="alert">
          <p>
            已有更新的映射版本。你的草稿仍保留，请先查看并载入最新配置，再重新登记修改。
          </p>
          <button
            type="button"
            className="sd-text-button"
            disabled={!query.data}
            onClick={() => {
              if (query.data) load(query.data);
            }}
          >
            载入最新映射（替换草稿）
          </button>
        </div>
      )}
      {draft && (
        <form
          onSubmit={submit}
          className="sd-mapping-form"
          id="sd-map-panel"
          role="tabpanel"
          aria-labelledby={`sd-map-tab-${tab}`}
        >
          <fieldset disabled={pending}>
            {tab === "entities" && (
              <>
                <div className="sd-mapping-section-head">
                  <p>
                    例如：把明确的客户字段登记为“客户实体”。平台不会从列名自动猜测身份。
                  </p>
                  <button
                    type="button"
                    className="sd-button sd-secondary"
                    disabled={draft.entities.length >= 50}
                    onClick={() =>
                      change({
                        ...draft,
                        entities: [
                          ...draft.entities,
                          {
                            id: `entity-${crypto.randomUUID()}`,
                            name: "",
                            entityType: "",
                            inputFields: [],
                            transformationDescription: "",
                          },
                        ],
                      })
                    }
                  >
                    <Plus size={14} />
                    添加实体
                  </button>
                </div>
                {draft.entities.map((entity, index) => {
                  const update = (patch: Partial<EntityLineageMapping>) =>
                    change({
                      ...draft,
                      entities: draft.entities.map((item) =>
                        item.id === entity.id ? { ...item, ...patch } : item,
                      ),
                    });
                  return (
                    <section className="sd-mapping-entry" key={entity.id}>
                      <header>
                        <span>L1 / 实体 {index + 1}</span>
                        <button
                          type="button"
                          className="sd-icon"
                          aria-label={`移除实体 ${entity.name || index + 1}`}
                          onClick={() => {
                            if (
                              draft.metrics.some((metric) =>
                                metric.inputEntityIds.includes(entity.id),
                              )
                            ) {
                              setError(
                                "此实体仍被指标引用，请先移除对应指标依赖，再删除实体。",
                              );
                              return;
                            }
                            change({
                              ...draft,
                              entities: draft.entities.filter(
                                (item) => item.id !== entity.id,
                              ),
                            });
                          }}
                        >
                          <Trash2 size={14} />
                        </button>
                      </header>
                      <div className="sd-mapping-two-columns">
                        <label>
                          实体名称
                          <input
                            required
                            maxLength={100}
                            value={entity.name}
                            placeholder="例如：客户"
                            onChange={(event) =>
                              update({ name: event.target.value })
                            }
                          />
                        </label>
                        <label>
                          业务类型
                          <input
                            required
                            maxLength={80}
                            value={entity.entityType}
                            placeholder="例如：客户主档"
                            onChange={(event) =>
                              update({ entityType: event.target.value })
                            }
                          />
                        </label>
                      </div>
                      {fieldPicker(
                        entity.inputFields,
                        (inputFields) => update({ inputFields }),
                        `实体 ${index + 1} 的输入字段`,
                      )}
                      <label>
                        映射与转换说明
                        <textarea
                          required
                          rows={2}
                          maxLength={2000}
                          value={entity.transformationDescription}
                          onChange={(event) =>
                            update({
                              transformationDescription: event.target.value,
                            })
                          }
                          placeholder="字段表示什么？如何识别同一对象？尚不知道的转换请明确说明。"
                        />
                      </label>
                    </section>
                  );
                })}
                {!draft.entities.length && (
                  <p className="sd-mapping-empty">
                    尚未登记业务实体。只有明确选择输入字段并填写说明后，图谱才会出现L1节点。
                  </p>
                )}
              </>
            )}
            {tab === "metrics" && (
              <>
                <div className="sd-mapping-section-head">
                  <p>引用“业务约定”中的已有指标版本，不另造一套口径。</p>
                  <select
                    aria-label="添加指标依赖"
                    value=""
                    onChange={(event) => {
                      const metric = visibleMetrics.find(
                        (item) => item.id === event.target.value,
                      );
                      if (metric)
                        change({
                          ...draft,
                          metrics: [
                            ...draft.metrics,
                            {
                              metricId: metric.id,
                              definitionVersion: metric.stateVersion,
                              inputEntityIds: [],
                              inputFields: [],
                              transformationDescription: "",
                            },
                          ],
                        });
                    }}
                  >
                    <option value="">选择指标…</option>
                    {visibleMetrics
                      .filter(
                        (metric) =>
                          !draft.metrics.some(
                            (item) => item.metricId === metric.id,
                          ),
                      )
                      .map((metric) => (
                        <option key={metric.id} value={metric.id}>
                          {metric.name} · v{metric.stateVersion}
                        </option>
                      ))}
                  </select>
                </div>
                {draft.metrics.map((mapping, index) => {
                  const update = (patch: Partial<typeof mapping>) =>
                    change({
                      ...draft,
                      metrics: draft.metrics.map((item) =>
                        item.metricId === mapping.metricId
                          ? { ...item, ...patch }
                          : item,
                      ),
                    });
                  const metric = visibleMetrics.find(
                    (item) => item.id === mapping.metricId,
                  );
                  return (
                    <section
                      className="sd-mapping-entry"
                      key={mapping.metricId}
                    >
                      <header>
                        <strong>
                          {metric?.name ?? mapping.metricId}{" "}
                          <small>
                            定义 v{mapping.definitionVersion} · 尚未计算
                          </small>
                        </strong>
                        <button
                          type="button"
                          className="sd-icon"
                          aria-label={`移除指标映射 ${metric?.name ?? mapping.metricId}`}
                          onClick={() => {
                            if (
                              draft.decisions.some((item) =>
                                item.inputMetricIds.includes(mapping.metricId),
                              )
                            ) {
                              setError(
                                "该指标仍被决策用途引用，请先移除对应用途。",
                              );
                              return;
                            }
                            change({
                              ...draft,
                              metrics: draft.metrics.filter(
                                (item) => item.metricId !== mapping.metricId,
                              ),
                            });
                          }}
                        >
                          <Trash2 size={14} />
                        </button>
                      </header>
                      {metric &&
                        metric.stateVersion !== mapping.definitionVersion && (
                          <p className="sd-error">
                            定义已变化，请明确复核依赖后更新到当前版本。
                            <button
                              type="button"
                              className="sd-text-button"
                              onClick={() =>
                                update({
                                  definitionVersion: metric.stateVersion,
                                })
                              }
                            >
                              确认使用 v{metric.stateVersion}
                            </button>
                          </p>
                        )}
                      <fieldset className="sd-mapping-field-picker">
                        <legend>输入业务实体</legend>
                        {draft.entities.map((entity) => (
                          <label key={entity.id}>
                            <input
                              type="checkbox"
                              checked={mapping.inputEntityIds.includes(
                                entity.id,
                              )}
                              onChange={(event) =>
                                update({
                                  inputEntityIds: event.target.checked
                                    ? [...mapping.inputEntityIds, entity.id]
                                    : mapping.inputEntityIds.filter(
                                        (id) => id !== entity.id,
                                      ),
                                })
                              }
                            />
                            {entity.name || "未命名实体"}
                          </label>
                        ))}
                      </fieldset>
                      {fieldPicker(
                        mapping.inputFields,
                        (inputFields) => update({ inputFields }),
                        `指标 ${index + 1} 的直接输入字段`,
                      )}
                      <label>
                        依赖与计算说明
                        <textarea
                          required
                          rows={2}
                          maxLength={2000}
                          value={mapping.transformationDescription}
                          onChange={(event) =>
                            update({
                              transformationDescription: event.target.value,
                            })
                          }
                        />
                      </label>
                    </section>
                  );
                })}
                {!draft.metrics.length && (
                  <p className="sd-mapping-empty">
                    先在业务约定中登记指标，再选择其明确的输入。不登记依赖的指标仍会显示为独立定义。
                  </p>
                )}
              </>
            )}
            {tab === "decisions" && (
              <>
                <div className="sd-mapping-section-head">
                  <p>
                    说明哪些指标服务于哪个业务目标；点线不表示计划已经执行。
                  </p>
                  <select
                    aria-label="添加决策用途"
                    value=""
                    onChange={(event) => {
                      const goal = visibleGoals.find(
                        (item) => item.id === event.target.value,
                      );
                      if (goal)
                        change({
                          ...draft,
                          decisions: [
                            ...draft.decisions,
                            {
                              goalId: goal.id,
                              inputMetricIds: [],
                              decisionQuestion: "",
                            },
                          ],
                        });
                    }}
                  >
                    <option value="">选择业务目标…</option>
                    {visibleGoals
                      .filter(
                        (goal) =>
                          !draft.decisions.some(
                            (item) => item.goalId === goal.id,
                          ),
                      )
                      .map((goal) => (
                        <option key={goal.id} value={goal.id}>
                          {goal.title}
                        </option>
                      ))}
                  </select>
                </div>
                {draft.decisions.map((mapping) => {
                  const update = (patch: Partial<typeof mapping>) =>
                    change({
                      ...draft,
                      decisions: draft.decisions.map((item) =>
                        item.goalId === mapping.goalId
                          ? { ...item, ...patch }
                          : item,
                      ),
                    });
                  return (
                    <section className="sd-mapping-entry" key={mapping.goalId}>
                      <header>
                        <strong>
                          {visibleGoals.find(
                            (goal) => goal.id === mapping.goalId,
                          )?.title ?? mapping.goalId}
                        </strong>
                        <button
                          type="button"
                          className="sd-icon"
                          aria-label={`移除决策用途 ${mapping.goalId}`}
                          onClick={() =>
                            change({
                              ...draft,
                              decisions: draft.decisions.filter(
                                (item) => item.goalId !== mapping.goalId,
                              ),
                            })
                          }
                        >
                          <Trash2 size={14} />
                        </button>
                      </header>
                      <fieldset className="sd-mapping-field-picker">
                        <legend>依据哪些指标</legend>
                        {visibleMetrics.map((metric) => (
                          <label key={metric.id}>
                            <input
                              type="checkbox"
                              checked={mapping.inputMetricIds.includes(
                                metric.id,
                              )}
                              onChange={(event) =>
                                update({
                                  inputMetricIds: event.target.checked
                                    ? [...mapping.inputMetricIds, metric.id]
                                    : mapping.inputMetricIds.filter(
                                        (id) => id !== metric.id,
                                      ),
                                })
                              }
                            />
                            {metric.name}
                          </label>
                        ))}
                      </fieldset>
                      <label>
                        要回答的决策问题
                        <textarea
                          required
                          rows={2}
                          maxLength={1000}
                          value={mapping.decisionQuestion}
                          onChange={(event) =>
                            update({ decisionQuestion: event.target.value })
                          }
                        />
                      </label>
                    </section>
                  );
                })}
                {!draft.decisions.length && (
                  <p className="sd-mapping-empty">
                    登记明确的指标用途，避免把数据之间的连线误当成业务决策已经成立。
                  </p>
                )}
              </>
            )}
          </fieldset>
          {error && (
            <p className="sd-error" role="alert">
              {error}
            </p>
          )}
          <footer className="sd-mapping-footer">
            <span>
              映射 v{editingVersion} · {dirty ? "有未保存修改" : "尚未修改"}
            </span>
            <button
              type="button"
              className="sd-button sd-quiet"
              onClick={onClose}
            >
              取消
            </button>
            <button
              type="submit"
              className="sd-button sd-primary"
              disabled={!dirty || pending || stale}
            >
              <Save size={14} />
              {pending ? "正在保存…" : "保存声明"}
              <ArrowRight size={13} />
            </button>
          </footer>
        </form>
      )}
    </Dialog>
  );
}

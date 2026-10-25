import { useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  Check,
  ChevronDown,
  FileCheck2,
  History,
  Plus,
  Save,
  Target,
  Trash2,
  X,
} from "lucide-react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { workspaceClient } from "./client";
import { Dialog } from "./Dialog";
import type {
  BusinessBrief,
  BusinessBriefCommand,
  BusinessGoal,
  BusinessGoalType,
  MetricDraft,
  WorkspaceState,
} from "./types";
import "./business-brief.css";

export const GOAL_TYPES: Record<
  BusinessGoalType,
  { label: string; outcome: string }
> = {
  DATA_RELIABILITY: {
    label: "让数据按时更新",
    outcome: "找出更新延迟与同步问题，明确负责人及复查方式。",
  },
  ANALYTICS_TRUST: {
    label: "让分析结果可信",
    outcome: "核对缺失、重复与字段类型，先确认业务口径。",
  },
  ORDER_FULFILLMENT: {
    label: "提升订单履约",
    outcome: "优先核实影响订单处理的线索，再结合来源业务记录判断。",
  },
  INVENTORY_ACCURACY: {
    label: "核实库存数据",
    outcome: "核对库存记录中的质量与时效线索，不把样本直接当作实物盘点。",
  },
  COST_CONTROL: {
    label: "理清投入与成本",
    outcome: "先核实成本分析所依赖的数据；实际收益需后续对照验证。",
  },
  CUSTOM: { label: "自定义业务目标", outcome: "" },
};
type Draft = Omit<
  BusinessBriefCommand,
  "expectedStateVersion" | "expectedBriefVersion"
>;
export interface BusinessEditorSnapshot {
  draft: Draft;
  editingVersion: number;
  dirty: boolean;
}
function toDraft(brief?: BusinessBrief | null): Draft {
  return {
    enterpriseName: brief?.enterpriseName ?? "",
    businessDescription: brief?.businessDescription ?? "",
    goals:
      brief?.goals.map((goal) => ({
        ...goal,
        sourceIds: [...goal.sourceIds],
      })) ?? [],
    includedSourceIds: [...(brief?.includedSourceIds ?? [])],
    metrics:
      brief?.metrics.map(
        ({
          id,
          name,
          businessDefinition,
          owner,
          timeZone,
          currency,
          formulaDescription,
          sourceIds,
        }) => ({
          id,
          name,
          businessDefinition,
          owner,
          timeZone,
          currency,
          formulaDescription,
          sourceIds: [...sourceIds],
        }),
      ) ?? [],
  };
}
const id = (prefix: string) => `${prefix}-${crypto.randomUUID()}`;

export function BusinessBriefPage({
  workspace,
  pending,
  canEdit,
  canConfirm,
  onSave,
  onConfirm,
  editor,
}: {
  workspace: WorkspaceState;
  pending: boolean;
  canEdit: boolean;
  canConfirm: boolean;
  onSave: (body: BusinessBriefCommand) => Promise<WorkspaceState | undefined>;
  onConfirm: (
    id: string,
    briefVersion: number,
    metricVersion: number,
  ) => Promise<WorkspaceState | undefined>;
  editor: { current: BusinessEditorSnapshot | null };
}) {
  const brief = workspace.businessBrief;
  const [draft, setDraft] = useState<Draft>(() =>
    editor.current?.dirty ? editor.current.draft : toDraft(brief),
  );
  const [editingVersion, setEditingVersion] = useState(() =>
    editor.current?.dirty
      ? editor.current.editingVersion
      : (brief?.version ?? 0),
  );
  const [dirty, setDirty] = useState(editor.current?.dirty ?? false);
  const [metric, setMetric] = useState<MetricDraft | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [localError, setLocalError] = useState("");
  const [errorFor, setErrorFor] = useState<string | null>(null);
  const formError = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (localError && errorFor === "form") formError.current?.focus();
  }, [localError, errorFor]);
  const history = useQuery({
    queryKey: ["business-brief-history", brief?.version],
    queryFn: workspaceClient.businessBriefHistory,
    enabled: historyOpen,
    retry: false,
  });
  function load(next?: BusinessBrief | null) {
    setDraft(toDraft(next));
    setEditingVersion(next?.version ?? 0);
    setDirty(false);
    setLocalError("");
    setErrorFor(null);
  }
  useEffect(() => {
    if (!dirty) load(brief);
  }, [brief?.version]);
  useEffect(() => {
    editor.current = { draft, editingVersion, dirty };
  }, [draft, editingVersion, dirty, editor]);
  const stale = dirty && (brief?.version ?? 0) !== editingVersion;
  function change(next: Partial<Draft>) {
    setDraft((previous) => ({ ...previous, ...next }));
    setDirty(true);
    setLocalError("");
  }
  function changeGoal(goalId: string, next: Partial<BusinessGoal>) {
    change({
      goals: draft.goals.map((goal) =>
        goal.id === goalId ? { ...goal, ...next } : goal,
      ),
    });
  }
  function setScope(sourceId: string, checked: boolean) {
    const selected = checked
      ? [...draft.includedSourceIds, sourceId]
      : draft.includedSourceIds.filter((value) => value !== sourceId);
    const stranded = draft.goals.filter(
      (goal) =>
        goal.sourceIds.length > 0 &&
        goal.sourceIds.every((value) => !selected.includes(value)),
    );
    if (stranded.length) {
      setErrorFor("scope");
      setLocalError(
        `此来源仍是“${stranded.map((goal) => goal.title).join("、")}”的唯一指定来源。请先为目标重选来源、明确使用全部范围，或移除目标。`,
      );
      return;
    }
    change({
      includedSourceIds: selected,
      goals: draft.goals.map((goal) => ({
        ...goal,
        sourceIds: goal.sourceIds.filter((value) => selected.includes(value)),
      })),
      metrics: draft.metrics.map((item) => ({
        ...item,
        sourceIds: item.sourceIds.filter((value) => selected.includes(value)),
      })),
    });
  }
  function addGoal(type: BusinessGoalType) {
    if (draft.goals.length >= 12) return;
    const template = GOAL_TYPES[type];
    change({
      goals: [
        ...draft.goals,
        {
          id: id("goal"),
          type,
          title: template.label,
          expectedOutcome: template.outcome,
          priority: "MEDIUM",
          sourceIds: [],
        },
      ],
    });
  }
  async function save(event: FormEvent) {
    event.preventDefault();
    if (!canEdit) return;
    if (stale) {
      setErrorFor("form");
      setLocalError("已有新版本，请先载入最新约定再编辑。");
      return;
    }
    const body = {
      ...draft,
      expectedStateVersion: workspace.stateVersion,
      expectedBriefVersion: editingVersion,
    };
    if (
      new TextEncoder().encode(JSON.stringify(body)).byteLength >
      256 * 1024
    ) {
      setErrorFor("form");
      setLocalError("这份约定过长，请精简部分定义或条目后保存。草稿仍保留。");
      return;
    }
    const next = await onSave(body);
    if (next) load(next.businessBrief);
  }
  const changedMetrics = draft.metrics.filter((item) => {
    const old = brief?.metrics.find((value) => value.id === item.id);
    return (
      old?.status === "CONFIRMED" &&
      JSON.stringify(toDraft({ ...brief!, metrics: [old] }).metrics[0]) !==
        JSON.stringify(item)
    );
  });
  return (
    <>
      <header className="sd-page-title">
        <div>
          <span className="sd-overline">约定 / BUSINESS CONTEXT</span>
          <h1>业务约定</h1>
          <p>
            目标决定优先顺序，范围限定本轮工作。共同确认口径，让团队沿着同一个方向推进。
          </p>
        </div>
        <div className="sd-page-actions">
          <button
            type="button"
            className="sd-button sd-secondary"
            onClick={() => setHistoryOpen(true)}
            disabled={!brief}
          >
            <History size={15} />
            版本记录
          </button>
          <button
            type="submit"
            form="business-brief-form"
            className="sd-button sd-primary"
            disabled={!canEdit || pending || stale || !dirty}
          >
            <Save size={15} />
            {pending ? "正在保存…" : "保存业务约定"}
          </button>
        </div>
      </header>
      {stale && (
        <div className="sd-business-stale" role="alert">
          <span>
            其他操作已更新这份约定。当前草稿仍保留，请先查看最新内容。
          </span>
          <button
            type="button"
            className="sd-text-button"
            onClick={() => load(brief)}
          >
            载入最新约定（替换草稿）
            <ArrowRight size={14} />
          </button>
        </div>
      )}
      <div className="sd-brief-state">
        <span>
          <i className={dirty ? "is-dirty" : ""} />
          {dirty
            ? "有未保存的修改"
            : brief
              ? `已保存 · 约定 v${brief.version}`
              : "尚未设置业务约定"}
        </span>
        <span>定义已确认 ≠ 指标已计算</span>
      </div>
      {!canEdit && (
        <p className="sd-scope-note" role="status">
          当前为只读审计身份，可以查看约定与版本记录；修改需切换为有编辑权限的身份。
        </p>
      )}
      {localError && errorFor === "form" && (
        <p ref={formError} tabIndex={-1} className="sd-error" role="alert">
          {localError}
        </p>
      )}
      <form
        id="business-brief-form"
        className="sd-business-form"
        onSubmit={save}
      >
        <fieldset disabled={!canEdit || pending}>
          <section className="sd-business-section">
            <div className="sd-business-caption">
              <span>01 / 背景</span>
              <h2>我们在解决什么问题</h2>
              <p>
                写业务人员会用的语言。你确认的内容会保留版本，方便团队持续校正。
              </p>
            </div>
            <div className="sd-business-fields">
              <label>
                企业或业务团队名称
                <input
                  required
                  maxLength={120}
                  value={draft.enterpriseName}
                  onChange={(e) => change({ enterpriseName: e.target.value })}
                  placeholder="例如：某电商业务团队"
                />
              </label>
              <label>
                业务简介与本次背景
                <textarea
                  maxLength={2000}
                  rows={3}
                  value={draft.businessDescription}
                  onChange={(e) =>
                    change({ businessDescription: e.target.value })
                  }
                  placeholder="主要经营什么？哪些系统参与？最近遇到了什么具体问题？"
                />
              </label>
            </div>
          </section>
          <section className="sd-business-section">
            <div className="sd-business-caption">
              <span>02 / 目标</span>
              <h2>这次优先做好什么</h2>
              <p>
                目标会影响助手筛选问题和排列任务。每个目标都需要一句可核对的期望结果。
              </p>
            </div>
            <div className="sd-business-fields">
              <div className="sd-goal-presets" aria-label="添加业务目标">
                {Object.entries(GOAL_TYPES).map(([type, item]) => (
                  <button
                    type="button"
                    key={type}
                    onClick={() => addGoal(type as BusinessGoalType)}
                    disabled={draft.goals.length >= 12}
                  >
                    <Plus size={13} />
                    {item.label}
                  </button>
                ))}
              </div>
              {!draft.goals.length && (
                <p className="sd-business-empty">
                  还没有业务目标。选择上方方向，再改成你自己的完成条件。
                </p>
              )}
              {draft.goals.map((goal, index) => (
                <div className="sd-goal-editor" key={goal.id}>
                  <div className="sd-goal-editor-head">
                    <span>{String(index + 1).padStart(2, "0")}</span>
                    <label className="sd-sr-only" htmlFor={`title-${goal.id}`}>
                      目标名称 {index + 1}
                    </label>
                    <input
                      id={`title-${goal.id}`}
                      required
                      maxLength={100}
                      value={goal.title}
                      onChange={(e) =>
                        changeGoal(goal.id, { title: e.target.value })
                      }
                    />
                    <label>
                      <span className="sd-sr-only">目标优先级 {index + 1}</span>
                      <select
                        value={goal.priority}
                        onChange={(e) =>
                          changeGoal(goal.id, {
                            priority: e.target
                              .value as BusinessGoal["priority"],
                          })
                        }
                      >
                        <option value="HIGH">优先处理</option>
                        <option value="MEDIUM">常规推进</option>
                        <option value="LOW">后续关注</option>
                      </select>
                    </label>
                    <button
                      type="button"
                      className="sd-icon"
                      aria-label={`移除目标 ${goal.title}`}
                      onClick={() =>
                        change({
                          goals: draft.goals.filter(
                            (value) => value.id !== goal.id,
                          ),
                        })
                      }
                    >
                      <X size={15} />
                    </button>
                  </div>
                  <label>
                    期望结果
                    <textarea
                      required
                      rows={2}
                      maxLength={1000}
                      value={goal.expectedOutcome}
                      onChange={(e) =>
                        changeGoal(goal.id, { expectedOutcome: e.target.value })
                      }
                    />
                  </label>
                  <details className="sd-goal-sources">
                    <summary>
                      限定到特定来源
                      <span>
                        {goal.sourceIds.length
                          ? `${goal.sourceIds.length} 个来源`
                          : "跟随本轮全部范围"}
                      </span>
                      <ChevronDown size={13} />
                    </summary>
                    <button
                      className="sd-text-button"
                      type="button"
                      onClick={() => changeGoal(goal.id, { sourceIds: [] })}
                    >
                      使用本轮全部范围
                    </button>
                    <SourceChoices
                      workspace={workspace}
                      selected={
                        goal.sourceIds.length
                          ? goal.sourceIds
                          : draft.includedSourceIds
                      }
                      allowed={draft.includedSourceIds}
                      onChange={(sourceId, checked) => {
                        const effective = goal.sourceIds.length
                          ? goal.sourceIds
                          : draft.includedSourceIds;
                        const next = checked
                          ? [...effective, sourceId]
                          : effective.filter((value) => value !== sourceId);
                        if (!next.length) {
                          setErrorFor(goal.id);
                          setLocalError(
                            `“${goal.title}”至少保留一个指定来源；如不再推进此目标，请移除目标。使用全部来源应明确点击相应按钮。`,
                          );
                          return;
                        }
                        changeGoal(goal.id, { sourceIds: next });
                      }}
                    />
                  </details>
                  {localError && errorFor === goal.id && (
                    <p className="sd-error" role="alert">
                      {localError}
                    </p>
                  )}
                </div>
              ))}
            </div>
          </section>
          <section className="sd-business-section" aria-label="本轮看哪些来源">
            <div className="sd-business-caption">
              <span>03 / 范围</span>
              <h2>本轮看哪些来源</h2>
              <p>
                接入来源与纳入本轮工作分开管理。移出范围会保留历史任务和证据，不代表问题已解决。
              </p>
            </div>
            <div className="sd-business-fields">
              <div className="sd-scope-heading">
                <strong>
                  {draft.includedSourceIds.length} / {workspace.sources.length}{" "}
                  个已登记来源
                </strong>
                <span>新接入的来源需要明确纳入</span>
              </div>
              <SourceChoices
                workspace={workspace}
                selected={draft.includedSourceIds}
                onChange={setScope}
              />
              {localError && errorFor === "scope" && (
                <p className="sd-error" role="alert">
                  {localError}
                </p>
              )}
              {!draft.includedSourceIds.length && (
                <p className="sd-scope-note">
                  当前未选来源。保存后本轮不安排来源任务；已有历史工作仍保留。
                </p>
              )}
              <p className="sd-business-help">
                取消来源也会移除目标和口径中的相应关联；发生变化的已确认口径将退回草稿。
              </p>
            </div>
          </section>
          <section className="sd-business-section">
            <div className="sd-business-caption">
              <span>04 / 口径</span>
              <h2>同一个数字，同一种理解</h2>
              <p>
                登记指标怎样定义、由谁负责、使用哪些来源。当前保存业务说明，不执行公式或计算指标值。
              </p>
            </div>
            <div className="sd-business-fields">
              <div className="sd-metric-toolbar">
                <span>
                  {draft.metrics.length} 项口径 ·{" "}
                  {brief?.metrics.filter((item) => item.status === "CONFIRMED")
                    .length ?? 0}{" "}
                  项已确认
                </span>
                <button
                  type="button"
                  className="sd-button sd-secondary"
                  disabled={draft.metrics.length >= 40}
                  onClick={() =>
                    setMetric({
                      id: id("metric"),
                      name: "",
                      businessDefinition: "",
                      owner: "",
                      timeZone: "Asia/Shanghai",
                      currency: null,
                      formulaDescription: "",
                      sourceIds: [],
                    })
                  }
                >
                  <Plus size={14} />
                  登记指标口径
                </button>
              </div>
              {!draft.metrics.length && (
                <p className="sd-business-empty">
                  例如：实付销售额是否扣退款，订单按支付日还是下单日归属。
                </p>
              )}
              {draft.metrics.map((item) => {
                const stored = brief?.metrics.find(
                  (value) => value.id === item.id,
                );
                const confirmed =
                  stored?.status === "CONFIRMED" &&
                  !changedMetrics.some((value) => value.id === item.id);
                return (
                  <article className="sd-metric-row" key={item.id}>
                    <FileCheck2 size={20} />
                    <div>
                      <strong>{item.name}</strong>
                      <p>{item.businessDefinition || "定义尚待补充"}</p>
                      <small>
                        {item.owner || "尚未登记负责人"} ·{" "}
                        {item.sourceIds.length} 个来源 · 尚未计算
                      </small>
                      {confirmed && (
                        <small>
                          由 {stored.confirmedBy} 确认 · 定义 v
                          {stored.stateVersion}
                        </small>
                      )}
                    </div>
                    <span
                      className={`sd-metric-status ${confirmed ? "is-confirmed" : ""}`}
                    >
                      {confirmed ? "口径已确认" : "口径草稿"}
                    </span>
                    <button
                      type="button"
                      className="sd-text-button"
                      onClick={() =>
                        setMetric({ ...item, sourceIds: [...item.sourceIds] })
                      }
                    >
                      编辑
                    </button>
                    {!confirmed && stored && (
                      <button
                        type="button"
                        className="sd-text-button"
                        disabled={!canConfirm || dirty || stale}
                        title={
                          dirty
                            ? "先保存修改，再确认口径"
                            : !canConfirm
                              ? "需要业务负责人或管理员身份"
                              : "确认的是业务定义，不是计算结果"
                        }
                        onClick={async () => {
                          const next = await onConfirm(
                            item.id,
                            brief!.version,
                            stored.stateVersion,
                          );
                          if (next) load(next.businessBrief);
                        }}
                      >
                        <Check size={13} />
                        确认口径
                      </button>
                    )}
                    <button
                      type="button"
                      className="sd-icon"
                      aria-label={`移除口径 ${item.name}`}
                      onClick={() =>
                        change({
                          metrics: draft.metrics.filter(
                            (value) => value.id !== item.id,
                          ),
                        })
                      }
                    >
                      <Trash2 size={14} />
                    </button>
                  </article>
                );
              })}
              {!!changedMetrics.length && (
                <p className="sd-scope-note">
                  {changedMetrics.map((item) => item.name).join("、")}{" "}
                  的定义或范围已修改，保存后需要重新确认。
                </p>
              )}
            </div>
          </section>
        </fieldset>
        <footer className="sd-business-footer">
          <span>业务原文保留在本机，当前不自动加入模型上下文。</span>
          <button
            type="submit"
            className="sd-button sd-primary"
            disabled={!canEdit || pending || stale || !dirty}
          >
            <Save size={15} />
            {pending ? "正在保存…" : "保存业务约定"}
          </button>
        </footer>
      </form>
      {metric && (
        <MetricEditor
          value={metric}
          workspace={workspace}
          allowed={draft.includedSourceIds}
          onClose={() => setMetric(null)}
          onSave={(next) => {
            change({
              metrics: draft.metrics.some((value) => value.id === next.id)
                ? draft.metrics.map((value) =>
                    value.id === next.id ? next : value,
                  )
                : [...draft.metrics, next],
            });
            setMetric(null);
          }}
        />
      )}
      {historyOpen && (
        <Dialog
          title="业务约定的版本记录"
          subtitle="历史定义保持原样；确认人与来源归属可追溯。"
          onClose={() => setHistoryOpen(false)}
          wide
        >
          <div className="sd-inspector-body">
            {history.isLoading ? (
              <p role="status">正在读取历史约定…</p>
            ) : history.isError ? (
              <p role="alert" className="sd-error">
                版本记录读取失败，请关闭后重试。
              </p>
            ) : (
              history.data
                ?.slice()
                .reverse()
                .map((version) => (
                  <details
                    className="sd-business-history"
                    key={version.version}
                  >
                    <summary>
                      <span>v{version.version}</span>
                      <strong>{version.enterpriseName}</strong>
                      <time>
                        {new Date(version.updatedAt).toLocaleString("zh-CN")}
                      </time>
                      <ChevronDown size={14} />
                    </summary>
                    <p>
                      由 {version.updatedBy} 保存 ·{" "}
                      {version.includedSourceIds.length} 个来源
                    </p>
                    <p>{version.businessDescription || "未填写业务简介"}</p>
                    {version.goals.map((goal) => (
                      <p key={goal.id}>
                        {goal.title}：{goal.expectedOutcome}
                      </p>
                    ))}
                    {version.metrics.map((item) => (
                      <p key={item.id}>
                        {item.name} ·{" "}
                        {item.status === "CONFIRMED"
                          ? `口径由 ${item.confirmedBy} 确认`
                          : "口径草稿"}{" "}
                        · 尚未计算
                        <br />
                        {item.businessDefinition}
                        <br />
                        {item.formulaDescription}
                      </p>
                    ))}
                  </details>
                ))
            )}
          </div>
        </Dialog>
      )}
    </>
  );
}

function SourceChoices({
  workspace,
  selected,
  allowed,
  onChange,
}: {
  workspace: WorkspaceState;
  selected: string[];
  allowed?: string[];
  onChange: (id: string, checked: boolean) => void;
}) {
  const sources = workspace.sources.filter(
    (source) => !allowed || allowed.includes(source.id),
  );
  return (
    <div className="sd-business-sources">
      {sources.length ? (
        sources.map((source) => (
          <label key={source.id}>
            <input
              type="checkbox"
              checked={selected.includes(source.id)}
              onChange={(event) => onChange(source.id, event.target.checked)}
            />
            <span>
              <strong>{source.name}</strong>
              <small>
                {source.status === "DISCONNECTED"
                  ? "已断开 · 仅历史依据"
                  : source.status === "SCANNED"
                    ? "已有盘点记录"
                    : source.status === "FAILED"
                      ? "最近盘点失败"
                      : "尚待盘点"}
                {source.provenance === "DEMO" ? " · 合成演示" : ""}
              </small>
            </span>
            <span className="sd-business-source-count">
              {source.assetCount} 项资产
            </span>
          </label>
        ))
      ) : (
        <p className="sd-business-empty">
          {workspace.sources.length
            ? "请先在本轮范围中纳入来源。"
            : "还没有来源。可以先保存背景，再连接数据。"}
        </p>
      )}
    </div>
  );
}

function MetricEditor({
  value,
  workspace,
  allowed,
  onClose,
  onSave,
}: {
  value: MetricDraft;
  workspace: WorkspaceState;
  allowed: string[];
  onClose: () => void;
  onSave: (value: MetricDraft) => void;
}) {
  const [draft, setDraft] = useState(value);
  function update(next: Partial<MetricDraft>) {
    setDraft((previous) => ({ ...previous, ...next }));
  }
  return (
    <Dialog
      title="给一个指标定口径"
      subtitle="先明确业务定义，再由负责人确认。保存草稿不会执行查询。"
      onClose={onClose}
      wide
    >
      <form
        className="sd-metric-form"
        onSubmit={(event) => {
          event.preventDefault();
          onSave(draft);
        }}
      >
        <div className="sd-business-fields">
          <label>
            指标名称
            <input
              required
              maxLength={100}
              value={draft.name}
              onChange={(e) => update({ name: e.target.value })}
              placeholder="例如：实付销售额"
            />
          </label>
          <label>
            业务定义
            <textarea
              maxLength={2000}
              rows={3}
              value={draft.businessDefinition}
              onChange={(e) => update({ businessDefinition: e.target.value })}
              placeholder="统计什么，不统计什么？是否扣除取消订单、退款和运费？"
            />
          </label>
          <div className="sd-business-pair">
            <label>
              口径负责人
              <input
                maxLength={120}
                value={draft.owner}
                onChange={(e) => update({ owner: e.target.value })}
                placeholder="负责解释和审核定义的人或团队"
              />
            </label>
            <label>
              业务时区
              <input
                list="business-timezones"
                maxLength={80}
                value={draft.timeZone}
                onChange={(e) => update({ timeZone: e.target.value })}
                placeholder="Asia/Shanghai"
              />
              <datalist id="business-timezones">
                <option value="Asia/Shanghai" />
                <option value="UTC" />
                <option value="America/Los_Angeles" />
                <option value="Europe/London" />
              </datalist>
            </label>
          </div>
          <label>
            币种（可选）
            <input
              className="sd-currency-input"
              maxLength={3}
              pattern="[A-Z]{3}"
              value={draft.currency ?? ""}
              onChange={(e) =>
                update({ currency: e.target.value.toUpperCase() || null })
              }
              placeholder="CNY / USD"
            />
          </label>
          <label>
            计算口径说明
            <textarea
              maxLength={2000}
              rows={3}
              value={draft.formulaDescription}
              onChange={(e) => update({ formulaDescription: e.target.value })}
              placeholder="例如：按支付日汇总实付金额，扣除同批订单已退款金额；每日结算后复核。"
            />
          </label>
          <div>
            <span className="sd-metric-source-label">依据哪些来源</span>
            <SourceChoices
              workspace={workspace}
              selected={draft.sourceIds}
              allowed={allowed}
              onChange={(sourceId, checked) =>
                update({
                  sourceIds: checked
                    ? [...draft.sourceIds, sourceId]
                    : draft.sourceIds.filter((value) => value !== sourceId),
                })
              }
            />
          </div>
        </div>
        <footer>
          <p>
            这是一份业务定义。当前不会执行公式，也不会生成未经核对的指标数值。
          </p>
          <button type="submit" className="sd-button sd-primary">
            <Check size={15} />
            加入待保存约定
          </button>
        </footer>
      </form>
    </Dialog>
  );
}

export function BusinessContextSummary({
  brief,
  onOpen,
}: {
  brief?: BusinessBrief | null;
  onOpen: () => void;
}) {
  return (
    <button type="button" className="sd-business-summary" onClick={onOpen}>
      <span className="sd-business-summary-mark">
        <Target size={20} strokeWidth={1.4} />
      </span>
      <span>
        <strong>
          {brief
            ? `${brief.enterpriseName} · 当前业务约定`
            : "让团队先知道这次要做好什么"}
        </strong>
        <small>
          {brief
            ? `${brief.goals.length} 项目标 · ${brief.includedSourceIds.length} 个纳入来源 · ${brief.metrics.filter((item) => item.status === "CONFIRMED").length}/${brief.metrics.length} 项口径已确认`
            : "说明目标、选择本轮范围，并记录需要共同确认的指标口径。"}
        </small>
      </span>
      <span>{brief ? `v${brief.version}` : "设置目标"}</span>
      <ArrowRight size={15} />
    </button>
  );
}

import type { DataLayer, EvidenceNature, LineageNode } from "./types";

export const LAYERS: Record<
  DataLayer,
  { name: string; caption: string; color: string }
> = {
  L0: {
    name: "原始数据",
    caption: "来源 · 表 · 字段",
    color: "var(--sd-layer-L0)",
  },
  L1: {
    name: "业务实体",
    caption: "客户 · 商品 · 画像",
    color: "var(--sd-layer-L1)",
  },
  L2: {
    name: "指标与分析",
    caption: "口径 · 统计 · 分析",
    color: "var(--sd-layer-L2)",
  },
  L3: {
    name: "决策与行动",
    caption: "目标 · 计划 · 验收",
    color: "var(--sd-layer-L3)",
  },
};
export const NATURES: Record<EvidenceNature, string> = {
  OBSERVED: "实际观察",
  DECLARED: "人工声明",
  PLANNED: "尚在规划",
};
export const KIND_LABEL: Record<LineageNode["kind"], string> = {
  SOURCE: "数据来源",
  TABLE: "数据表",
  FIELD: "字段",
  BUSINESS_ENTITY: "业务实体",
  METRIC: "指标定义",
  DECISION_PLAN: "决策计划",
};
export const FRESHNESS = {
  CURRENT: "观察未过期",
  STALE: "观察已过期",
  UNKNOWN: "观察时效未知",
};
export const SENSITIVITY = {
  UNKNOWN: "敏感级别未确认",
  PUBLIC: "公开",
  INTERNAL: "内部",
  CONFIDENTIAL: "机密",
  RESTRICTED: "受限",
};
export const date = (value?: string | null) =>
  value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "尚无观察时间";
export type FocusMode = "all" | "upstream" | "downstream" | "both";

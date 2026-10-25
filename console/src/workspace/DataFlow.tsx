import { motion, useReducedMotion } from "motion/react";
import { ArrowUpRight, Database, Fingerprint, UsersRound } from "lucide-react";
import type { WorkspaceState } from "./types";

export function DataFlow({
  workspace,
  onSource,
  onFindings,
  onTeam,
}: {
  workspace: WorkspaceState;
  onSource: () => void;
  onFindings: () => void;
  onTeam: () => void;
}) {
  const reduce = useReducedMotion();
  const stages = [
    {
      icon: Database,
      label: "看见数据",
      caption: "连接 · 盘点",
      value: workspace.assets.length,
      unit: "项数据资产",
      action: onSource,
    },
    {
      icon: Fingerprint,
      label: "理解问题",
      caption: "检查 · 核实",
      value: workspace.findings.filter((finding) => finding.active).length,
      unit: "项待核实发现",
      action: onFindings,
    },
    {
      icon: UsersRound,
      label: "一起解决",
      caption: "分工 · 协作",
      value: workspace.tasks.filter((task) => task.status !== "VERIFIED")
        .length,
      unit: "项协作任务",
      action: onTeam,
    },
  ];
  return (
    <section className="sd-flow" aria-label="数据到协作的工作链路">
      <div className="sd-flow-top">
        <span className="sd-overline">从来源到行动</span>
        <span>每一步，保留依据</span>
      </div>
      <svg
        className="sd-flow-connections"
        viewBox="0 0 900 150"
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        <defs>
          <linearGradient id="flow-line">
            <stop stopColor="currentColor" stopOpacity="0.05" />
            <stop offset=".5" stopColor="currentColor" stopOpacity=".5" />
            <stop offset="1" stopColor="currentColor" stopOpacity="0.05" />
          </linearGradient>
        </defs>
        <path
          d="M80 75 C220 75 210 75 365 75 S610 75 820 75"
          fill="none"
          stroke="url(#flow-line)"
          strokeWidth="1"
        />
        <path
          d="M80 95 C220 95 210 55 365 55 S610 95 820 55"
          fill="none"
          stroke="currentColor"
          strokeOpacity=".08"
        />
        {!reduce && workspace.assets.length > 0 && (
          <circle r="2.5" fill="currentColor">
            <animateMotion
              dur="2.8s"
              repeatCount="1"
              fill="freeze"
              path="M80 75 C220 75 210 75 365 75 S610 75 820 75"
            />
          </circle>
        )}
      </svg>
      <div className="sd-flow-stages">
        {stages.map((stage, index) => (
          <motion.button
            key={stage.label}
            type="button"
            onClick={stage.action}
            className="sd-stage"
            whileHover={reduce ? undefined : { y: -3 }}
            transition={{ type: "spring", stiffness: 350, damping: 28 }}
          >
            <span className="sd-stage-index">
              0{index + 1} <ArrowUpRight size={13} />
            </span>
            <span className={`sd-stage-symbol sd-stage-symbol-${index}`}>
              <stage.icon size={23} strokeWidth={1.3} />
            </span>
            <strong>{stage.label}</strong>
            <span className="sd-stage-caption">{stage.caption}</span>
            <span className="sd-stage-value">
              <b>{stage.value.toString().padStart(2, "0")}</b>
              {stage.unit}
            </span>
          </motion.button>
        ))}
      </div>
    </section>
  );
}

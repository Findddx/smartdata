import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import {
  Activity,
  ArrowDownToLine,
  ArrowLeft,
  ArrowRight,
  ArrowUpRight,
  BookOpen,
  Boxes,
  Braces,
  Check,
  ChevronDown,
  ChevronRight,
  CircleHelp,
  CirclePause,
  Database,
  FileCheck2,
  Fingerprint,
  FlaskConical,
  Globe2,
  LayoutGrid,
  Link2,
  ListChecks,
  LoaderCircle,
  MessageSquare,
  Plus,
  RefreshCw,
  Search,
  Settings2,
  ShieldCheck,
  Target,
  Sparkles,
  UsersRound,
  Workflow,
  GitBranch,
  PanelLeft,
  X,
} from "lucide-react";
import {
  lazy,
  Suspense,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { workspaceClient } from "./client";
import { LiquidMaterial } from "../materials/LiquidMaterial";
import { SmartDataMark } from "../brand/SmartDataMark";
import { localRolesEnabled } from "./localRoles";
import {
  BusinessBriefPage,
  type BusinessEditorSnapshot,
} from "./BusinessBrief";
import { Dialog } from "./Dialog";
import { SourceDialog } from "./SourceDialog";
import type {
  SourceDraft,
  WorkspaceAsset,
  WorkspaceAssistant,
  WorkspaceFinding,
  WorkspaceState,
  WorkspaceTask,
  WorkspaceViewId,
  WorkspaceSource,
} from "./types";
import "./workspace.css";
import "./desktop.css";
import "./islands.css";

const FulfillmentApp = lazy(() =>
  import("../FulfillmentApp").then((module) => ({
    default: module.FulfillmentApp,
  })),
);
const GraphView = lazy(() =>
  import("./GraphView").then((module) => ({ default: module.GraphView })),
);
const LOCAL_ACTORS = [
  ["admin", "本地管理员"],
  ["order-specialist", "订单专员"],
  ["warehouse-approver", "仓库审批人"],
  ["sales-owner", "业务负责人"],
  ["auditor", "只读审计员"],
] as const;

const NAV = [
  { id: "graph", label: "数据图谱", icon: GitBranch },
  { id: "overview", label: "工作清单", icon: LayoutGrid },
  { id: "sources", label: "数据连接", icon: Link2 },
  { id: "assets", label: "数据资产", icon: Database },
  { id: "findings", label: "诊断发现", icon: Fingerprint },
  { id: "team", label: "服务团队", icon: UsersRound },
  { id: "tasks", label: "协作任务", icon: ListChecks },
  { id: "business", label: "业务约定", icon: Target },
] as const;
const TITLES: Record<WorkspaceViewId, string> = {
  graph: "数据图谱",
  overview: "工作清单",
  business: "业务约定",
  sources: "数据连接",
  assets: "数据资产",
  findings: "诊断发现",
  team: "服务团队",
  tasks: "协作任务",
  settings: "空间设置",
  fulfillment: "履约专项",
};
const ROLE = {
  OPERATIONS: {
    label: "运维助手",
    title: "让数据稳定流转",
    short: "运维",
    icon: Activity,
    color: "olive",
  },
  ANALYSIS: {
    label: "分析助手",
    title: "让分析有据可查",
    short: "分析",
    icon: Boxes,
    color: "clay",
  },
  DECISION: {
    label: "决策助手",
    title: "让下一步更清晰",
    short: "决策",
    icon: Workflow,
    color: "stone",
  },
};
const STATUS: Record<string, string> = {
  CONFIGURED: "待盘点",
  SCANNED: "已盘点",
  FAILED: "读取失败",
  DISCONNECTED: "已断开",
  QUEUED: "排队中",
  RUNNING: "盘点中",
  COMPLETED: "待复核",
  VERIFIED: "已核验",
  REOPENED: "已重新打开",
  PROPOSED: "待接手",
  OPEN: "待接手",
  ACCEPTED: "处理中",
  READY: "已就绪",
  ACTIVE: "可协作",
  SUCCEEDED: "已完成",
  PENDING: "待接手",
};
const sourceKindLabel: Record<string, string> = {
  JSON_SNAPSHOT: "JSON 文件",
  HTTP_JSON: "业务 API",
  JDBC_POSTGRES_READONLY: "PostgreSQL",
};
function time(value?: string | null) {
  if (!value) return "尚未观测";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "时间未知"
    : new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      }).format(date);
}
function severity(value: string) {
  return (
    (
      {
        CRITICAL: "严重",
        HIGH: "高优先",
        WARNING: "需关注",
        MEDIUM: "需关注",
        LOW: "提示",
        INFO: "提示",
      } as Record<string, string>
    )[value] ?? value
  );
}
function message(error: unknown) {
  return error instanceof Error ? error.message : "请求未完成，请重试。";
}
function assistantMethod(method?: string) {
  return method === "SPRING_AI_EVIDENCE_V1" ? "模型分析" : "规则分析";
}
function auditLabel(event: string) {
  return (
    (
      {
        "metric_definition.confirmed": "确认了指标口径",
        "business_brief.saved": "保存了业务约定",
        "assistant.message_answered": "助手已整理答复",
        "assistant.message_failed": "助手答复未完成",
        "demo.loaded": "载入并检查了合成演示",
        "demo.cleared": "退出了合成演示",
        "source.added": "登记了数据来源",
        "source.scanned": "完成了一次数据盘点",
        "source.disconnected": "断开了数据来源",
        "team.provisioned": "按当前发现更新了分工",
        "lineage.mappings_saved": "保存了业务映射声明",
        "task.accepted": "接手了协作任务",
        "task.completed": "提交了处理结果",
        "task.verified": "独立复核了任务结果",
        "task.reopened": "重新打开了任务",
        "task.commented": "补充了协作说明",
      } as Record<string, string>
    )[event] ?? event
  );
}
function ruleLabel(ruleId: string) {
  if (ruleId.includes("MISSING")) return "字段完整性";
  if (ruleId.includes("MIXED")) return "字段类型";
  if (ruleId.includes("DUPLICATE_PRIMARY")) return "业务标识唯一性";
  if (ruleId.includes("DUPLICATE")) return "重复记录";
  if (ruleId.includes("FRESHNESS") || ruleId.includes("STALE"))
    return "数据更新";
  return "数据质量检查";
}
function firstView(): WorkspaceViewId {
  const value = window.location.hash.replace("#/", "").split("?")[0] ?? "";
  return Object.keys(TITLES).includes(value)
    ? (value as WorkspaceViewId)
    : "graph";
}

export function Workspace() {
  const queryClient = useQueryClient();
  const reduce = useReducedMotion();
  const [view, setView] = useState<WorkspaceViewId>(firstView);
  const [theme, setTheme] = useState(() => {
    const saved = localStorage.getItem("smartdata.theme");
    return saved && ["mineral", "titanium", "muse", "cinnabar"].includes(saved)
      ? saved
      : "mineral";
  });
  const [navOpen, setNavOpen] = useState(false);
  const [mobileViewport, setMobileViewport] = useState(
    () => window.innerWidth <= 760,
  );
  useEffect(() => {
    const resized = () => setMobileViewport(window.innerWidth <= 760);
    window.addEventListener("resize", resized);
    return () => window.removeEventListener("resize", resized);
  }, []);
  const [navCollapsed, setNavCollapsed] = useState(
    () => localStorage.getItem("smartdata.navCollapsed") === "true",
  );
  const environmentArtRef = useRef<HTMLImageElement | null>(null);
  const sidebarRef = useRef<HTMLElement>(null);
  const businessEditor = useRef<BusinessEditorSnapshot | null>(null);
  const [sourceOpen, setSourceOpen] = useState(false);
  const [replaceSource, setReplaceSource] = useState<
    WorkspaceSource | undefined
  >();
  const [resetOpen, setResetOpen] = useState(false);
  const [disconnectSource, setDisconnectSource] =
    useState<WorkspaceSource | null>(null);
  const [localActor, setLocalActor] = useState(() =>
    localRolesEnabled()
      ? (localStorage.getItem("smartdata.localActor") ?? "admin")
      : "order-specialist",
  );
  const [searchOpen, setSearchOpen] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const [asset, setAsset] = useState<WorkspaceAsset | null>(null);
  const [finding, setFinding] = useState<WorkspaceFinding | null>(null);
  const [task, setTask] = useState<WorkspaceTask | null>(null);
  const [toast, setToast] = useState("");
  const [error, setError] = useState("");
  const [operation, setOperation] = useState("");
  const query = useQuery({
    queryKey: ["workspace"],
    queryFn: workspaceClient.get,
    retry: false,
  });
  const storedWorkspace = query.data;
  const workspace = storedWorkspace
    ? {
        ...storedWorkspace,
        findings: storedWorkspace.findings.filter((finding) => finding.active),
      }
    : undefined;
  const pending = !!operation;
  function navigate(next: WorkspaceViewId) {
    setView(next);
    window.location.hash = `/${next}`;
    setNavOpen(false);
  }
  useEffect(() => {
    const changed = () => setView(firstView());
    window.addEventListener("hashchange", changed);
    return () => window.removeEventListener("hashchange", changed);
  }, []);
  useEffect(() => {
    localStorage.setItem("smartdata.theme", theme);
  }, [theme]);
  useEffect(() => {
    localStorage.setItem("smartdata.navCollapsed", String(navCollapsed));
  }, [navCollapsed]);
  useEffect(() => {
    const leaving = (event: BeforeUnloadEvent) => {
      if (!businessEditor.current?.dirty) return;
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", leaving);
    return () => window.removeEventListener("beforeunload", leaving);
  }, []);
  useEffect(() => {
    const main = document.getElementById("sd-main");
    if (main) main.scrollTop = 0;
  }, [view]);
  useEffect(() => {
    if (!navOpen) return;
    const previous = document.activeElement as HTMLElement | null;
    sidebarRef.current?.querySelector<HTMLButtonElement>("button")?.focus();
    const closeOnResize = () => {
      if (window.innerWidth > 760) setNavOpen(false);
    };
    window.addEventListener("resize", closeOnResize);
    return () => {
      window.removeEventListener("resize", closeOnResize);
      previous?.focus();
    };
  }, [navOpen]);
  useEffect(() => {
    const shortcut = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setSearchOpen((value) => !value);
      }
    };
    window.addEventListener("keydown", shortcut);
    return () => window.removeEventListener("keydown", shortcut);
  }, []);
  useEffect(() => {
    if (!toast) return;
    const id = window.setTimeout(() => setToast(""), 6000);
    return () => clearTimeout(id);
  }, [toast]);
  async function run(label: string, action: () => Promise<WorkspaceState>) {
    if (operation) return;
    setOperation(label);
    setError("");
    try {
      const next = await action();
      queryClient.setQueryData(["workspace"], next);
      const failedScan = next.scans.find(
        (scan) =>
          scan.status === "FAILED" &&
          !storedWorkspace?.scans.some((previous) => previous.id === scan.id),
      );
      if (failedScan) {
        setError(failedScan.error ?? "盘点没有完成，请检查来源连接。");
        setToast("盘点未完成，已保存失败记录");
      } else {
        const failedMessage = next.messages.find(
          (item) =>
            item.status === "FAILED" &&
            !storedWorkspace?.messages.some(
              (previous) => previous.id === item.id,
            ),
        );
        if (failedMessage) {
          setError(failedMessage.answer);
          setToast("答复未完成，已保存原因");
        } else setToast(label.replace("正在", "") + "已完成");
      }
      return next;
    } catch (failure) {
      setError(message(failure));
      void query.refetch();
      return undefined;
    } finally {
      setOperation("");
    }
  }
  async function addSource(draft: SourceDraft) {
    if (replaceSource) {
      const selectedId = replaceSource.id;
      const updated = await run("正在更新来源快照", () =>
        workspaceClient.replaceSnapshot(
          selectedId,
          draft.expectedStateVersion,
          draft.snapshotRows!,
          draft.observedAt!,
        ),
      );
      if (!updated) return;
      setReplaceSource(undefined);
      setSourceOpen(false);
      await run("正在读取并检查数据", () =>
        workspaceClient.scan(selectedId, updated.stateVersion),
      );
      return;
    }
    const before = new Set(workspace?.sources.map((source) => source.id));
    const next = await run("正在保存数据来源", () =>
      workspaceClient.addSource(draft),
    );
    if (!next) return;
    setSourceOpen(false);
    navigate("sources");
    const added = next.sources.find((source) => !before.has(source.id));
    if (added)
      await run("正在读取并检查数据", () =>
        workspaceClient.scan(added.id, next.stateVersion),
      );
  }
  async function exportReport() {
    try {
      const report = await workspaceClient.report();
      const content = [
        report.title,
        `生成时间：${report.generatedAt}`,
        `工作区版本：${report.stateVersion}`,
        "",
        ...report.sections.flatMap((section) => [
          section.title,
          "─".repeat(24),
          section.text,
          "",
        ]),
        "可追溯的发现编号",
        ...report.evidenceRefs,
        "",
        "本报告只覆盖实际观察范围。证据与原始系统共同用于复核，不能单凭报告判断企业整体健康。",
      ].join("\n");
      const url = URL.createObjectURL(
        new Blob(["\uFEFF", content], {
          type: "text/plain;charset=utf-8",
        }),
      );
      const a = document.createElement("a");
      a.href = url;
      a.download = "smartdata-工作空间报告.txt";
      a.click();
      URL.revokeObjectURL(url);
      setToast("已导出包含检查依据的工作空间报告");
    } catch (failure) {
      setError(message(failure));
    }
  }
  const taskCount =
    workspace?.tasks.filter((item) => item.status !== "VERIFIED").length ?? 0;
  const currentTask =
    workspace?.tasks.find((item) => item.id === task?.id) ?? task;
  const currentFinding =
    storedWorkspace?.findings.find((item) => item.id === finding?.id) ??
    finding;
  if (view === "fulfillment")
    return (
      <div className="sd-legacy">
        <button
          className="sd-legacy-return"
          type="button"
          onClick={() => navigate("overview")}
        >
          <ArrowLeft size={14} />
          返回智能数据基座
        </button>
        <Suspense
          fallback={
            <div className="sd-loading" role="status">
              正在打开履约专项…
            </div>
          }
        >
          <FulfillmentApp />
        </Suspense>
      </div>
    );
  return (
    <div
      className={`sd-workspace sd-desktop ${navCollapsed ? "sd-nav-collapsed" : ""}`}
      data-theme={theme === "linen" ? "mineral" : theme}
      data-view={view}
    >
      <div className="sd-environment" aria-hidden="true">
        <img
          ref={environmentArtRef}
          src="/art/strata-study-v1.webp"
          alt=""
          decoding="async"
        />
      </div>
      <a className="sd-skip" href="#sd-main">
        跳到工作区
      </a>
      {navOpen && (
        <button
          type="button"
          className="sd-nav-backdrop"
          aria-label="关闭导航"
          tabIndex={-1}
          onClick={() => setNavOpen(false)}
        />
      )}
      <aside
        id="sd-navigation"
        ref={sidebarRef}
        className={`sd-sidebar sd-liquid-host ${navOpen ? "sd-nav-open" : ""}`}
        onKeyDown={(event) => {
          if (!navOpen) return;
          if (event.key === "Escape") {
            event.preventDefault();
            setNavOpen(false);
          }
          if (event.key !== "Tab") return;
          const controls = Array.from(
            event.currentTarget.querySelectorAll<HTMLElement>(
              "button:not(:disabled), a[href], select",
            ),
          );
          const first = controls[0];
          const last = controls[controls.length - 1];
          if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last?.focus();
          }
          if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first?.focus();
          }
        }}
      >
        <LiquidMaterial
          variant="navigation"
          radius={20}
          strength={12}
          artRef={environmentArtRef}
        />
        <button
          type="button"
          className="sd-nav-close sd-icon"
          aria-label="收起导航"
          onClick={() => setNavOpen(false)}
        >
          <X size={18} />
        </button>
        <button
          type="button"
          className="sd-wordmark"
          onClick={() => navigate("graph")}
          aria-label="SmartData 首页"
        >
          <BrandMark />
          <span>
            SmartData<span>智能数据基座</span>
          </span>
        </button>
        <button
          className="sd-org"
          type="button"
          onClick={() => navigate("settings")}
        >
          <span className="sd-org-avatar">
            {workspace?.businessBrief?.enterpriseName.slice(0, 1) ?? "S"}
          </span>
          <span>
            <strong>
              {workspace?.businessBrief?.enterpriseName ?? "我的工作空间"}
            </strong>
            <small>{workspace ? "服务端文件存储" : "等待连接"}</small>
          </span>
          <ChevronDown size={14} />
        </button>
        <button
          className="sd-search-trigger"
          type="button"
          onClick={() => setSearchOpen(true)}
        >
          <Search size={15} />
          <span>搜索与命令</span>
          <kbd>⌘ K</kbd>
        </button>
        <div className="sd-nav-label">工作台</div>
        <nav aria-label="主导航">
          {NAV.filter(
            (item) =>
              item.id !== "business" ||
              (!!storedWorkspace && "businessBrief" in storedWorkspace),
          ).map((item) => (
            <button
              type="button"
              key={item.id}
              className={view === item.id ? "is-active" : ""}
              title={item.label}
              aria-label={item.label}
              onClick={() => navigate(item.id)}
              aria-current={view === item.id ? "page" : undefined}
            >
              {view === item.id && (
                <motion.span
                  className="sd-nav-indicator"
                  layoutId="navigation-active"
                  transition={
                    reduce
                      ? { duration: 0 }
                      : { type: "spring", stiffness: 450, damping: 36 }
                  }
                />
              )}
              <item.icon size={17} />
              <span>{item.label}</span>
              {item.id === "tasks" && taskCount > 0 && <b>{taskCount}</b>}
            </button>
          ))}
        </nav>
        <div className="sd-nav-label sd-tool-label">专项工具</div>
        <button
          className="sd-specialty"
          type="button"
          aria-label="履约异常处理"
          title="履约异常处理"
          onClick={() => navigate("fulfillment")}
        >
          <FileCheck2 size={17} />
          <span>履约异常处理</span>
          <ArrowUpRight size={13} />
        </button>
        <div className="sd-sidebar-bottom">
          <button
            type="button"
            aria-label="使用指南"
            title="使用指南"
            onClick={() => setGuideOpen(true)}
          >
            <CircleHelp size={17} />
            <span>使用指南</span>
            <ArrowUpRight size={13} />
          </button>
          <button
            type="button"
            className={view === "settings" ? "is-active" : ""}
            aria-label="空间设置"
            title="空间设置"
            onClick={() => navigate("settings")}
          >
            <Settings2 size={17} />
            <span>空间设置</span>
          </button>
          <div className="sd-local-identity">
            <span className="sd-profile-dot" />
            <span>
              {localRolesEnabled() ? (
                <select
                  className="sd-identity-select"
                  aria-label="本地演示身份"
                  value={localActor}
                  disabled={pending}
                  onChange={(event) => {
                    const actor = event.target.value;
                    setLocalActor(actor);
                    localStorage.setItem("smartdata.localActor", actor);
                    setToast("本地演示身份已切换");
                    void query.refetch();
                  }}
                >
                  {LOCAL_ACTORS.map(([id, label]) => (
                    <option key={id} value={id}>
                      {label}
                    </option>
                  ))}
                </select>
              ) : (
                <strong>本地订单专员</strong>
              )}
              <small>本地演示 · 仅本机使用</small>
            </span>
            <ShieldCheck size={15} />
          </div>
        </div>
      </aside>
      <div className="sd-shell-content" inert={navOpen}>
        <header className="sd-topbar sd-liquid-host">
          <LiquidMaterial
            variant="toolbar"
            radius={16}
            strength={8}
            artRef={environmentArtRef}
          />
          <div>
            <button
              type="button"
              className="sd-icon sd-menu-button"
              aria-label={
                mobileViewport
                  ? navOpen
                    ? "关闭导航"
                    : "打开导航"
                  : navCollapsed
                    ? "展开导航"
                    : "收起导航"
              }
              aria-expanded={mobileViewport ? navOpen : !navCollapsed}
              aria-controls="sd-navigation"
              onClick={() =>
                window.innerWidth <= 760
                  ? setNavOpen(!navOpen)
                  : setNavCollapsed(!navCollapsed)
              }
            >
              <PanelLeft size={17} />
            </button>
            <span>
              {workspace?.businessBrief?.enterpriseName ?? "我的工作空间"}
            </span>
            <ChevronRight size={12} />
            <strong>{TITLES[view]}</strong>
          </div>
          <div>
            <button
              type="button"
              className="sd-command-button"
              onClick={() => setSearchOpen(true)}
              aria-label="搜索工作空间"
            >
              <Search size={14} />
              <span>搜索与命令</span>
              <kbd>⌘ K</kbd>
            </button>
            <span
              className={`sd-connection-state ${query.isError ? "is-offline" : ""}`}
            >
              <i />
              {query.isError
                ? "刷新失败"
                : workspace
                  ? "工作区服务已连接"
                  : "正在连接"}
            </span>
            <button
              type="button"
              className="sd-icon"
              onClick={() => query.refetch()}
              aria-label="刷新工作空间"
              disabled={query.isFetching}
            >
              <RefreshCw
                size={15}
                className={query.isFetching ? "sd-spinning" : ""}
              />
            </button>
            <span className="sd-topbar-divider" />
            <button
              type="button"
              className="sd-icon"
              onClick={() => setGuideOpen(true)}
              aria-label="使用指南"
            >
              <BookOpen size={16} />
            </button>
          </div>
        </header>
        <main className="sd-main" id="sd-main" tabIndex={-1}>
          {error && (
            <div className="sd-error-banner" role="alert">
              <span>{error}</span>
              <button
                type="button"
                onClick={() => setError("")}
                aria-label="关闭错误提示"
              >
                <X size={15} />
              </button>
            </div>
          )}
          {query.isLoading && (
            <div className="sd-loading" role="status">
              <BrandMark />
              <p>正在打开工作空间…</p>
              <span>读取已保存的来源、证据和协作记录</span>
            </div>
          )}
          {query.isError && !workspace && (
            <div className="sd-disconnected">
              <div className="sd-disconnected-symbol">
                <Database size={34} strokeWidth={1} />
              </div>
              <span className="sd-overline">连接工作空间</span>
              <h1>无法连接工作区服务</h1>
              <p>
                工作台暂时无法连接工作区服务。启动 SmartData
                后，已保存的数据来源和协作进展会出现在这里。
              </p>
              <code>
                .\gradlew.bat :runtime-api:bootRun
                --args="--spring.profiles.active=local"
              </code>
              <button
                className="sd-button sd-primary"
                type="button"
                onClick={() => query.refetch()}
              >
                <RefreshCw size={15} />
                重新连接
              </button>
            </div>
          )}
          {workspace && (
            <AnimatePresence mode="wait" initial={false}>
              <motion.div
                className={`sd-page ${view === "graph" ? "sd-page-graph" : ""}`}
                key={view}
                initial={reduce ? false : { opacity: 0, y: 9 }}
                animate={{ opacity: 1, y: 0 }}
                exit={reduce ? undefined : { opacity: 0, y: -4 }}
                transition={{
                  duration: reduce ? 0 : 0.2,
                  ease: [0.22, 1, 0.36, 1],
                }}
              >
                {view === "graph" && (
                  <Suspense
                    fallback={
                      <div className="sd-loading" role="status">
                        正在打开数据图谱…
                      </div>
                    }
                  >
                    <GraphView
                      artRef={environmentArtRef}
                      workspace={workspace}
                      canEdit={localActor === "admin"}
                      pending={pending}
                      onAsset={setAsset}
                      navigate={navigate}
                      onConnect={() => setSourceOpen(true)}
                      onDemo={() => {
                        void run("正在载入并检查演示数据", () =>
                          workspaceClient.bootstrap(workspace.stateVersion),
                        );
                      }}
                      onSave={(command) =>
                        run("正在保存业务映射", () =>
                          workspaceClient.saveLineageMappings(command),
                        )
                      }
                    />
                  </Suspense>
                )}
                {view === "overview" && (
                  <Overview
                    workspace={workspace}
                    pending={pending}
                    onConnect={() => setSourceOpen(true)}
                    onDemo={() =>
                      run("正在载入并检查演示数据", () =>
                        workspaceClient.bootstrap(workspace.stateVersion),
                      )
                    }
                    navigate={navigate}
                    onFinding={setFinding}
                    onExport={exportReport}
                  />
                )}
                {view === "sources" && (
                  <Sources
                    workspace={workspace}
                    pending={pending}
                    operation={operation}
                    onConnect={() => setSourceOpen(true)}
                    onScan={(id) =>
                      run("正在读取并检查数据", () =>
                        workspaceClient.scan(id, workspace.stateVersion),
                      )
                    }
                    onAssets={() => navigate("assets")}
                    onReplace={(source) => {
                      setReplaceSource(source);
                      setSourceOpen(true);
                    }}
                    onResetDemo={() => setResetOpen(true)}
                    onDisconnect={setDisconnectSource}
                  />
                )}
                {view === "assets" && (
                  <Assets
                    workspace={workspace}
                    onSelect={setAsset}
                    onConnect={() => setSourceOpen(true)}
                  />
                )}
                {view === "business" &&
                  ("businessBrief" in workspace ? (
                    <BusinessBriefPage
                      workspace={workspace}
                      pending={pending}
                      editor={businessEditor}
                      canEdit={localActor !== "auditor"}
                      canConfirm={
                        localActor === "admin" || localActor === "sales-owner"
                      }
                      onSave={(body) =>
                        run("正在保存业务约定", () =>
                          workspaceClient.saveBusinessBrief(body),
                        )
                      }
                      onConfirm={(id, version, metricVersion) =>
                        run("正在确认指标口径", () =>
                          workspaceClient.confirmMetric(
                            id,
                            workspace.stateVersion,
                            version,
                            metricVersion,
                          ),
                        )
                      }
                    />
                  ) : (
                    <Empty
                      icon={Target}
                      title="当前服务尚未启用业务约定"
                      body="请更新并重启本机服务，原有数据与协作功能仍可使用。"
                    />
                  ))}
                {view === "findings" && (
                  <Findings
                    workspace={workspace}
                    onSelect={setFinding}
                    onTeam={() => navigate("team")}
                  />
                )}
                {view === "team" && (
                  <Team
                    workspace={workspace}
                    evidenceFindings={storedWorkspace?.findings ?? []}
                    onFinding={setFinding}
                    pending={pending}
                    onProvision={() =>
                      run("正在组建服务团队", () =>
                        workspaceClient.provision(workspace.stateVersion),
                      )
                    }
                    onTask={setTask}
                    onAsk={(id, question) =>
                      run("正在整理有据可查的答复", () =>
                        workspaceClient.ask(
                          id,
                          workspace.stateVersion,
                          question,
                        ),
                      )
                    }
                  />
                )}
                {view === "tasks" && (
                  <Tasks
                    workspace={workspace}
                    onSelect={setTask}
                    onTeam={() => navigate("team")}
                  />
                )}
                {view === "settings" && (
                  <Settings
                    theme={theme}
                    setTheme={setTheme}
                    workspace={workspace}
                    onExport={exportReport}
                  />
                )}
              </motion.div>
            </AnimatePresence>
          )}
        </main>
        <footer className="sd-statusbar">
          <span>
            <ShieldCheck size={12} />
            来源只读 · 变更需另行审批
          </span>
          <span>
            {operation ? (
              <>
                <LoaderCircle size={12} className="sd-spinning" />
                {operation}
              </>
            ) : (
              <>
                SMARTDATA <span className="sd-status-dot" />{" "}
                {workspace ? `工作区 v${workspace.stateVersion}` : "等待连接"}
              </>
            )}
          </span>
        </footer>
      </div>
      <AnimatePresence>
        {toast && (
          <motion.div
            className="sd-toast"
            role="status"
            initial={reduce ? false : { opacity: 0, y: 14, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={reduce ? undefined : { opacity: 0, y: 4 }}
            transition={reduce ? { duration: 0 } : undefined}
          >
            <Check size={16} />
            {toast}
            <button
              type="button"
              aria-label="关闭通知"
              onClick={() => setToast("")}
            >
              <X size={14} />
            </button>
          </motion.div>
        )}
      </AnimatePresence>
      {sourceOpen && workspace && (
        <SourceDialog
          version={workspace.stateVersion}
          pending={pending}
          error={error}
          onClose={() => {
            setSourceOpen(false);
            setReplaceSource(undefined);
            setError("");
          }}
          onSubmit={addSource}
          replaceSource={replaceSource}
        />
      )}
      {searchOpen && (
        <SearchDialog
          workspace={workspace}
          onClose={() => setSearchOpen(false)}
          onNavigate={navigate}
          onAsset={setAsset}
          onFinding={setFinding}
        />
      )}
      {asset && (
        <AssetDialog
          asset={asset}
          workspace={workspace}
          onClose={() => setAsset(null)}
          onFinding={(item) => {
            setAsset(null);
            setFinding(item);
          }}
        />
      )}
      {currentFinding && (
        <FindingDialog
          finding={currentFinding}
          workspace={workspace}
          onClose={() => setFinding(null)}
          onTask={(item) => {
            setFinding(null);
            setTask(item);
          }}
          onTeam={() => {
            setFinding(null);
            navigate("team");
          }}
        />
      )}
      {currentTask && workspace && (
        <TaskDialog
          task={currentTask}
          workspace={storedWorkspace!}
          pending={pending}
          error={error}
          localActor={localActor}
          onActorChange={(actor) => {
            setLocalActor(actor);
            localStorage.setItem("smartdata.localActor", actor);
          }}
          onClose={() => {
            setTask(null);
            setError("");
          }}
          onTransition={(status, note, evidenceRefs) =>
            run("正在更新协作任务", () =>
              workspaceClient.transition(
                currentTask.id,
                workspace.stateVersion,
                status,
                note,
                evidenceRefs,
              ),
            )
          }
          onComment={(body) =>
            run("正在保存协作记录", () =>
              workspaceClient.comment(
                currentTask.id,
                workspace.stateVersion,
                body,
                currentTask.evidenceRefs,
              ),
            )
          }
        />
      )}
      {guideOpen && (
        <Guide
          onClose={() => setGuideOpen(false)}
          onConnect={() => {
            setGuideOpen(false);
            setSourceOpen(true);
          }}
        />
      )}
      {resetOpen && workspace && (
        <Dialog
          title="退出演示工作区？"
          subtitle="只清空合成演示数据和对应协作记录，之后即可接入你的业务来源。"
          onClose={() => setResetOpen(false)}
        >
          <div className="sd-inspector-body">
            <p className="sd-muted">
              演示任务、讨论和检查记录将一起移除。这项操作仅适用于纯演示工作区。
            </p>
            <footer className="sd-inspector-footer">
              <button
                type="button"
                className="sd-button sd-secondary"
                onClick={() => setResetOpen(false)}
              >
                保留演示
              </button>
              <button
                type="button"
                className="sd-button sd-primary"
                disabled={pending}
                onClick={async () => {
                  const result = await run("正在清空演示工作区", () =>
                    workspaceClient.resetDemo(workspace.stateVersion),
                  );
                  if (result) {
                    setResetOpen(false);
                    navigate("overview");
                  }
                }}
              >
                退出并开始接入
                <ArrowRight size={14} />
              </button>
            </footer>
          </div>
        </Dialog>
      )}
      {disconnectSource && workspace && (
        <Dialog
          title={`断开「${disconnectSource.name}」？`}
          subtitle="停止后续读取并移除这项来源的凭证引用。已有观测和协作记录会保留。"
          onClose={() => setDisconnectSource(null)}
        >
          <div className="sd-inspector-body">
            <p className="sd-muted">
              断开后，这项来源不能再盘点或用于任务核验。如需再次连接，请由管理员重新配置来源。
            </p>
            <footer className="sd-inspector-footer">
              <button
                className="sd-button sd-secondary"
                type="button"
                onClick={() => setDisconnectSource(null)}
              >
                保持连接
              </button>
              <button
                className="sd-button sd-primary"
                type="button"
                disabled={pending}
                onClick={async () => {
                  const result = await run("正在断开数据来源", () =>
                    workspaceClient.disconnect(
                      disconnectSource.id,
                      workspace.stateVersion,
                    ),
                  );
                  if (result) setDisconnectSource(null);
                }}
              >
                确认断开
                <CirclePause size={14} />
              </button>
            </footer>
          </div>
        </Dialog>
      )}
    </div>
  );
}

function BrandMark() {
  return <SmartDataMark className="sd-brandmark" />;
}
function PageTitle({
  eyebrow,
  title,
  description,
  children,
}: {
  eyebrow: string;
  title: string;
  description: string;
  children?: React.ReactNode;
}) {
  return (
    <header className="sd-page-title">
      <div>
        <span className="sd-sr-only">{eyebrow}</span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {children && <div className="sd-page-actions">{children}</div>}
    </header>
  );
}
function Empty({
  icon: Icon = Database,
  title,
  body,
  action,
  onAction,
}: {
  icon?: typeof Database;
  title: string;
  body: string;
  action?: string;
  onAction?: () => void;
}) {
  return (
    <div className="sd-empty">
      <span>
        <Icon size={27} strokeWidth={1.2} />
      </span>
      <h3>{title}</h3>
      <p>{body}</p>
      {action && (
        <button
          type="button"
          className="sd-button sd-secondary"
          onClick={onAction}
        >
          {action}
          <ArrowRight size={14} />
        </button>
      )}
    </div>
  );
}
function Provenance({ demo }: { demo: boolean }) {
  return (
    <span className={`sd-provenance ${demo ? "is-demo" : ""}`}>
      {demo ? <FlaskConical size={11} /> : <ShieldCheck size={11} />}{" "}
      {demo ? "演示样本" : "用户接入"}
    </span>
  );
}
function State({ value }: { value: string }) {
  return (
    <span className={`sd-state sd-state-${value.toLowerCase()}`}>
      <i />
      {STATUS[value] ?? value}
    </span>
  );
}

function Overview({
  workspace,
  pending,
  onConnect,
  onDemo,
  navigate,
  onFinding,
  onExport,
}: {
  workspace: WorkspaceState;
  pending: boolean;
  onConnect: () => void;
  onDemo: () => void;
  navigate: (view: WorkspaceViewId) => void;
  onFinding: (finding: WorkspaceFinding) => void;
  onExport: () => void;
}) {
  const currentTasks = workspace.tasks.filter(
    (item) => item.inCurrentScope !== false && item.status !== "VERIFIED",
  );
  const goals = [...(workspace.businessBrief?.goals ?? [])].sort(
    (a, b) =>
      ({ HIGH: 0, MEDIUM: 1, LOW: 2 })[a.priority] -
      { HIGH: 0, MEDIUM: 1, LOW: 2 }[b.priority],
  );
  const priorityFindings = workspace.businessBrief
    ? Array.from(
        new Map(
          currentTasks.flatMap((item) => {
            const finding = workspace.findings.find(
              (value) => value.id === (item.currentFindingId ?? item.findingId),
            );
            return finding ? [[finding.id, finding] as const] : [];
          }),
        ).values(),
      )
    : workspace.findings;
  return (
    <>
      <PageTitle
        eyebrow="工作清单"
        title="工作清单"
        description={
          workspace.businessBrief
            ? `按照约定 v${workspace.businessBrief.version} 的范围与目标安排。`
            : "本轮发现、待办与最近记录。尚未设置企业自己的目标。"
        }
      >
        <button
          className="sd-button sd-secondary"
          type="button"
          onClick={onExport}
          disabled={!workspace.sources.length}
        >
          <ArrowDownToLine size={14} />
          导出报告
        </button>
        <button
          className="sd-button sd-primary"
          type="button"
          onClick={onConnect}
        >
          <Plus size={14} />
          连接数据
        </button>
      </PageTitle>
      {!workspace.sources.length ? (
        <section className="sd-start-workspace">
          <div className="sd-start-symbol">
            <Database size={30} strokeWidth={1.2} />
          </div>
          <h2>连接第一个来源</h2>
          <p>导入JSON样本或连接只读接口。检查覆盖范围，再开始安排工作。</p>
          <div>
            <button
              className="sd-button sd-primary"
              type="button"
              onClick={onConnect}
            >
              <Plus size={14} />
              连接我的数据
            </button>
            <button
              className="sd-button sd-quiet"
              type="button"
              onClick={onDemo}
              disabled={pending}
            >
              <FlaskConical size={14} />
              先体验电商示例
            </button>
          </div>
        </section>
      ) : (
        <>
          <div className="sd-work-summary">
            <button type="button" onClick={() => navigate("graph")}>
              <GitBranch size={17} />
              <strong>{workspace.assets.length}</strong>
              <span>数据资产</span>
              <ArrowUpRight size={13} />
            </button>
            <button type="button" onClick={() => navigate("findings")}>
              <Fingerprint size={17} />
              <strong>{workspace.findings.length}</strong>
              <span>当前发现</span>
              <ArrowUpRight size={13} />
            </button>
            <button type="button" onClick={() => navigate("tasks")}>
              <ListChecks size={17} />
              <strong>{currentTasks.length}</strong>
              <span>本轮待办</span>
              <ArrowUpRight size={13} />
            </button>
          </div>
          {workspace.sources.every(
            (source) => source.provenance === "DEMO",
          ) && (
            <p className="sd-demo-notice">
              <FlaskConical size={13} />
              当前为合成演示，结果不代表企业真实数据。
            </p>
          )}
          <div className="sd-work-columns">
            <section className="sd-work-section">
              <header>
                <h2>优先关注</h2>
                <button
                  type="button"
                  className="sd-text-button"
                  onClick={() => navigate("findings")}
                >
                  全部发现
                  <ArrowRight size={13} />
                </button>
              </header>
              {priorityFindings.length ? (
                <FindingRows
                  findings={priorityFindings.slice(0, 5)}
                  workspace={workspace}
                  onSelect={onFinding}
                />
              ) : (
                <p className="sd-work-empty">
                  本轮暂无可安排的有效发现。请结合来源范围与最新盘点理解结果。
                </p>
              )}
            </section>
            <div className="sd-work-context">
              <section className="sd-work-section">
                <header>
                  <h2>本轮目标</h2>
                  <button
                    type="button"
                    className="sd-text-button"
                    onClick={() => navigate("business")}
                  >
                    目标与范围
                    <ArrowRight size={13} />
                  </button>
                </header>
                {goals.length ? (
                  <div className="sd-work-goals">
                    {goals.slice(0, 3).map((goal) => (
                      <div key={goal.id}>
                        <div>
                          <strong>{goal.title}</strong>
                          <span>
                            {
                              {
                                HIGH: "高优先级",
                                MEDIUM: "常规",
                                LOW: "后续关注",
                              }[goal.priority]
                            }
                          </span>
                        </div>
                        <p>{goal.expectedOutcome}</p>
                        <small>
                          {goal.sourceIds.length
                            ? `${goal.sourceIds.length} 个指定来源`
                            : `跟随本轮 ${workspace.businessBrief?.includedSourceIds.length ?? 0} 个来源`}{" "}
                          · 完成情况尚需验证
                        </small>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="sd-work-empty">
                    尚未登记目标。先约定本轮范围与期望结果，再安排检查。
                  </p>
                )}
              </section>
              <section className="sd-work-section sd-handoff-section">
                <header>
                  <h2>团队交接</h2>
                  <button
                    type="button"
                    className="sd-text-button"
                    onClick={() => navigate("tasks")}
                  >
                    打开任务
                    <ArrowRight size={13} />
                  </button>
                </header>
                <dl className="sd-handoff-ledger">
                  {[
                    {
                      label: "等待接手",
                      count: currentTasks.filter((task) =>
                        ["PROPOSED", "REOPENED"].includes(task.status),
                      ).length,
                      note: "需要安排负责人",
                    },
                    {
                      label: "正在处理",
                      count: currentTasks.filter(
                        (task) => task.status === "ACCEPTED",
                      ).length,
                      note: "负责人推进中",
                    },
                    {
                      label: "等待复核",
                      count: currentTasks.filter(
                        (task) => task.status === "COMPLETED",
                      ).length,
                      note: "需要另一人核对新证据",
                    },
                  ].map((item) => (
                    <div key={item.label}>
                      <dt>{item.label}</dt>
                      <dd>
                        {item.count}
                        <small>{item.note}</small>
                      </dd>
                    </div>
                  ))}
                </dl>
                <p className="sd-handoff-note">
                  只统计本轮任务。历史工作仍可从任务页查看，提交处理不等于核验通过。
                </p>
              </section>
            </div>
          </div>
          <section className="sd-work-section sd-recent-section">
            <header>
              <h2>最近记录</h2>
              <span className="sd-muted">工作区 v{workspace.stateVersion}</span>
            </header>
            {workspace.audit.length ? (
              workspace.audit
                .slice(-5)
                .reverse()
                .map((event, index) => (
                  <div className="sd-activity-row" key={event.id ?? index}>
                    <i />
                    <span title={event.event}>{auditLabel(event.event)}</span>
                    <small>{event.actorId}</small>
                    <time>{time(event.occurredAt ?? event.createdAt)}</time>
                  </div>
                ))
            ) : (
              <p className="sd-work-empty">暂无操作记录。</p>
            )}
          </section>
        </>
      )}
    </>
  );
}

function FindingRows({
  findings,
  workspace,
  onSelect,
}: {
  findings: WorkspaceFinding[];
  workspace: WorkspaceState;
  onSelect: (finding: WorkspaceFinding) => void;
}) {
  return (
    <div className="sd-finding-rows">
      {findings.map((finding, index) => (
        <button
          type="button"
          className="sd-finding-row"
          key={finding.id}
          onClick={() => onSelect(finding)}
        >
          <span className="sd-row-number">
            {String(index + 1).padStart(2, "0")}
          </span>
          <span
            className={`sd-severity-dot sd-severity-${finding.severity.toLowerCase()}`}
          />
          <span className="sd-finding-row-content">
            <strong>{finding.title}</strong>
            <small>
              {workspace.assets.find((asset) => asset.id === finding.assetId)
                ?.name ?? "数据资产"}
              <span>·</span>
              {ruleLabel(finding.ruleId)}
            </small>
          </span>
          <span className="sd-severity-label">
            {severity(finding.severity)}
          </span>
          <ArrowUpRight size={14} />
        </button>
      ))}
    </div>
  );
}

function Sources({
  workspace,
  pending,
  operation,
  onConnect,
  onScan,
  onAssets,
  onReplace,
  onResetDemo,
  onDisconnect,
}: {
  workspace: WorkspaceState;
  pending: boolean;
  operation: string;
  onConnect: () => void;
  onScan: (id: string) => void;
  onAssets: () => void;
  onReplace: (source: WorkspaceSource) => void;
  onResetDemo: () => void;
  onDisconnect: (source: WorkspaceSource) => void;
}) {
  return (
    <>
      <PageTitle
        eyebrow="连接 / CONNECTIONS"
        title="数据连接"
        description="只读取你授权的来源。凭证保留在服务端，盘点过程和读取范围都可查看。"
      >
        {workspace.sources.length > 0 &&
          workspace.sources.every((source) => source.provenance === "DEMO") && (
            <button
              type="button"
              className="sd-button sd-secondary"
              onClick={onResetDemo}
            >
              退出演示工作区
              <ArrowUpRight size={14} />
            </button>
          )}
        <button
          className="sd-button sd-primary"
          type="button"
          onClick={onConnect}
        >
          <Plus size={15} />
          添加数据来源
        </button>
      </PageTitle>
      <div className="sd-source-summary">
        <span>
          <b>{workspace.sources.length}</b>已配置来源
        </span>
        <span>
          <b>
            {
              workspace.sources.filter((source) => source.status === "SCANNED")
                .length
            }
          </b>
          已完成盘点
        </span>
        <span>
          <b>{workspace.assets.length}</b>项数据资产
        </span>
        <span className="sd-source-summary-note">
          <ShieldCheck size={16} />
          只读连接
        </span>
      </div>
      {workspace.sources.length ? (
        <div className="sd-source-list">
          {workspace.sources.map((source) => (
            <article key={source.id} className="sd-source-row">
              <span
                className={`sd-source-icon ${source.kind === "HTTP_JSON" ? "sd-source-icon-api" : ""}`}
              >
                {source.kind === "HTTP_JSON" ? (
                  <Globe2 size={24} strokeWidth={1.3} />
                ) : source.kind === "JSON_SNAPSHOT" ? (
                  <Braces size={24} strokeWidth={1.3} />
                ) : (
                  <Database size={24} strokeWidth={1.3} />
                )}
              </span>
              <div>
                <h2>
                  {source.name}
                  <Provenance demo={source.provenance === "DEMO"} />
                </h2>
                <p>
                  {sourceKindLabel[source.kind]}
                  {source.endpointLabel
                    ? ` · ${source.endpointLabel}`
                    : " · 本地数据样本"}
                </p>
                <small>
                  {source.assetCount} 项资产 · 最近盘点尝试{" "}
                  {time(source.lastScannedAt)}
                </small>
                {source.lastError && (
                  <p className="sd-source-error">{source.lastError}</p>
                )}
              </div>
              <State value={source.status} />
              {source.kind === "JSON_SNAPSHOT" &&
                source.provenance === "USER" && (
                  <button
                    type="button"
                    className="sd-text-button"
                    onClick={() => onReplace(source)}
                    disabled={pending || source.status === "DISCONNECTED"}
                  >
                    更新快照
                    <ArrowUpRight size={13} />
                  </button>
                )}
              <button
                type="button"
                className="sd-button sd-secondary"
                disabled={pending || source.status === "DISCONNECTED"}
                onClick={() => onScan(source.id)}
              >
                <RefreshCw size={14} className={pending ? "sd-spinning" : ""} />
                {pending ? operation : "重新盘点"}
              </button>
              {source.status !== "DISCONNECTED" && (
                <button
                  className="sd-icon"
                  type="button"
                  disabled={pending}
                  aria-label={`断开 ${source.name}`}
                  title="断开来源"
                  onClick={() => onDisconnect(source)}
                >
                  <CirclePause size={16} />
                </button>
              )}
            </article>
          ))}
        </div>
      ) : (
        <Empty
          title="从第一个来源开始"
          body="导入 JSON 数据，或添加经过授权的 API 与数据库连接。"
          action="添加数据来源"
          onAction={onConnect}
        />
      )}
      <div className="sd-section-title sd-section-spacing">
        <div>
          <span className="sd-section-number">读取记录</span>
          <h2>每次盘点，都留下记录</h2>
        </div>
        <button type="button" className="sd-text-button" onClick={onAssets}>
          查看已发现资产
          <ArrowRight size={13} />
        </button>
      </div>
      {workspace.scans.length ? (
        <div className="sd-scan-log">
          {workspace.scans
            .slice()
            .reverse()
            .slice(0, 12)
            .map((scan) => (
              <div key={scan.id}>
                <span className="sd-log-line">
                  <i />
                </span>
                <div>
                  <strong>
                    {workspace.sources.find(
                      (source) => source.id === scan.sourceId,
                    )?.name ?? "来源"}
                  </strong>
                  <small>
                    {scan.error ||
                      `${scan.assetIds.length} 项资产 · ${scan.findingIds.length} 项发现`}
                  </small>
                </div>
                <time>{time(scan.completedAt ?? scan.startedAt)}</time>
                <State value={scan.status} />
              </div>
            ))}
        </div>
      ) : (
        <p className="sd-muted">
          还没有运行记录。保存来源后即可开始第一次盘点。
        </p>
      )}
    </>
  );
}

function Assets({
  workspace,
  onSelect,
  onConnect,
}: {
  workspace: WorkspaceState;
  onSelect: (asset: WorkspaceAsset) => void;
  onConnect: () => void;
}) {
  const [search, setSearch] = useState("");
  const [scope, setScope] = useState("all");
  const assets = workspace.assets.filter(
    (asset) =>
      (scope === "all" || asset.sourceId === scope) &&
      `${asset.name} ${asset.columns.map((column) => column.name).join(" ")}`
        .toLowerCase()
        .includes(search.toLowerCase()),
  );
  return (
    <>
      <PageTitle
        eyebrow="目录 / DATA CATALOG"
        title="数据资产"
        description="这是一份从实际读取结果生成的数据目录。打开资产，可以查看字段、质量和观测范围。"
      >
        <button
          className="sd-button sd-primary"
          type="button"
          onClick={onConnect}
        >
          <Plus size={15} />
          连接数据
        </button>
      </PageTitle>
      <div className="sd-toolbar">
        <label className="sd-inline-search">
          <Search size={16} />
          <input
            aria-label="搜索数据资产"
            placeholder="搜索资产或字段…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </label>
        <select
          aria-label="筛选数据来源"
          value={scope}
          onChange={(e) => setScope(e.target.value)}
        >
          <option value="all">全部来源</option>
          {workspace.sources.map((source) => (
            <option value={source.id} key={source.id}>
              {source.name}
            </option>
          ))}
        </select>
        <span>{assets.length} 项资产</span>
      </div>
      {assets.length ? (
        <div className="sd-table-scroll">
          <table className="sd-table">
            <thead>
              <tr>
                <th>数据资产</th>
                <th>来源</th>
                <th>已读取记录</th>
                <th>字段</th>
                <th>最近观测</th>
                <th>检查发现</th>
              </tr>
            </thead>
            <tbody>
              {assets.map((asset) => (
                <tr key={asset.id} onClick={() => onSelect(asset)}>
                  <td>
                    <button
                      type="button"
                      className="sd-table-name"
                      onClick={(e) => {
                        e.stopPropagation();
                        onSelect(asset);
                      }}
                    >
                      <span>
                        <Database size={17} />
                      </span>
                      <div>
                        <strong>{asset.name}</strong>
                        <small>
                          {asset.sampled ? "限量采样" : "当前快照"}
                          <span>·</span>
                          <Provenance demo={asset.provenance === "DEMO"} />
                        </small>
                      </div>
                    </button>
                  </td>
                  <td>
                    {workspace.sources.find(
                      (source) => source.id === asset.sourceId,
                    )?.name ?? "—"}
                  </td>
                  <td className="sd-number">
                    {asset.rowCount.toLocaleString()}
                    {asset.totalRows != null &&
                      asset.totalRows !== asset.rowCount && (
                        <small> / {asset.totalRows.toLocaleString()}</small>
                      )}
                  </td>
                  <td className="sd-number">{asset.columns.length}</td>
                  <td>{time(asset.observedAt)}</td>
                  <td>
                    <span className="sd-table-count">
                      {
                        workspace.findings.filter(
                          (item) => item.assetId === asset.id,
                        ).length
                      }
                    </span>
                    <ArrowUpRight size={13} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty
          title={search ? "没有匹配的资产" : "还没有数据资产"}
          body={
            search
              ? "换一个关键词，或切换数据来源。"
              : "完成一次来源盘点后，数据资产会出现在这里。"
          }
          action="连接数据"
          onAction={onConnect}
        />
      )}
      <p className="sd-bottom-note">
        <Fingerprint size={14} />
        记录数描述本次读取范围。采样检查不能直接推断整个数据库都符合规则。
      </p>
    </>
  );
}

function Findings({
  workspace,
  onSelect,
  onTeam,
}: {
  workspace: WorkspaceState;
  onSelect: (finding: WorkspaceFinding) => void;
  onTeam: () => void;
}) {
  const [filter, setFilter] = useState("all");
  const findings = workspace.findings.filter(
    (item) => filter === "all" || item.severity === filter,
  );
  return (
    <>
      <PageTitle
        eyebrow="诊断 / DIAGNOSTICS"
        title="诊断发现"
        description="检查发现是需要核实的线索。每一项都附有实际观测、判断规则与下一步建议。"
      >
        <button
          className="sd-button sd-secondary"
          type="button"
          onClick={onTeam}
        >
          <UsersRound size={15} />
          交给服务团队
        </button>
      </PageTitle>
      <div className="sd-toolbar">
        <div className="sd-filter-tabs" aria-label="发现优先级">
          {[
            "all",
            ...new Set(workspace.findings.map((item) => item.severity)),
          ].map((value) => (
            <button
              type="button"
              key={value}
              onClick={() => setFilter(value)}
              className={filter === value ? "is-active" : ""}
            >
              {value === "all" ? "全部发现" : severity(value)}
            </button>
          ))}
        </div>
        <span>{findings.length} 项待核实</span>
      </div>
      {findings.length ? (
        <FindingRows
          findings={findings}
          workspace={workspace}
          onSelect={onSelect}
        />
      ) : (
        <Empty
          icon={Fingerprint}
          title="当前范围没有待核实发现"
          body="检查仅覆盖已配置规则、读取范围和观测时间。你可以补充来源或添加规则。"
        />
      )}
      <div className="sd-explanation">
        <Fingerprint size={19} />
        <div>
          <strong>为什么保留实际观测？</strong>
          <p>
            例如“唯一订单号出现重复”，比一个没有计算依据的健康分更有用。打开任一发现，都能查看样本范围、证据指纹和复查方法。
          </p>
        </div>
      </div>
    </>
  );
}

function Team({
  workspace,
  evidenceFindings,
  onFinding,
  pending,
  onProvision,
  onTask,
  onAsk,
}: {
  workspace: WorkspaceState;
  evidenceFindings: WorkspaceFinding[];
  onFinding: (finding: WorkspaceFinding) => void;
  pending: boolean;
  onProvision: () => void;
  onTask: (task: WorkspaceTask) => void;
  onAsk: (id: string, question: string) => Promise<WorkspaceState | undefined>;
}) {
  const [selectedRole, setSelectedRole] =
    useState<WorkspaceAssistant["role"]>("OPERATIONS");
  const [question, setQuestion] = useState("");
  const assistant = workspace.assistants.find(
    (item) => item.role === selectedRole,
  );
  async function ask(event: FormEvent) {
    event.preventDefault();
    if (assistant && question.trim()) {
      const next = await onAsk(assistant.id, question.trim());
      const added = next?.messages.find(
        (item) =>
          !workspace.messages.some((previous) => previous.id === item.id),
      );
      if (added && added.status !== "FAILED") setQuestion("");
    }
  }
  return (
    <>
      <PageTitle
        eyebrow="协作 / YOUR SERVICE TEAM"
        title="服务团队"
        description="运维关注稳定，分析核对数据，决策梳理行动。共享同一份依据，让分工真正接上业务。"
      >
        <button
          type="button"
          className="sd-button sd-primary"
          onClick={onProvision}
          disabled={pending || !workspace.findings.length}
        >
          <UsersRound size={15} />
          {workspace.assistants.length ? "按最新发现更新分工" : "组建服务团队"}
        </button>
      </PageTitle>
      <div className="sd-role-layout" role="tablist" aria-label="选择服务助手">
        {Object.entries(ROLE).map(([role, config], i) => {
          const item = workspace.assistants.find(
            (value) => value.role === role,
          );
          return (
            <button
              type="button"
              role="tab"
              key={role}
              id={`sd-assistant-${role}`}
              aria-controls="sd-assistant-panel"
              aria-selected={selectedRole === role}
              tabIndex={selectedRole === role ? 0 : -1}
              disabled={pending}
              onKeyDown={(event) => {
                const roles = Object.keys(ROLE) as WorkspaceAssistant["role"][];
                const index = roles.indexOf(selectedRole);
                const next =
                  event.key === "ArrowRight"
                    ? (index + 1) % roles.length
                    : event.key === "ArrowLeft"
                      ? (index + roles.length - 1) % roles.length
                      : event.key === "Home"
                        ? 0
                        : event.key === "End"
                          ? roles.length - 1
                          : -1;
                if (next < 0) return;
                const nextRole = roles[next];
                if (!nextRole) return;
                event.preventDefault();
                setSelectedRole(nextRole);
                document.getElementById(`sd-assistant-${nextRole}`)?.focus();
              }}
              onClick={() =>
                setSelectedRole(role as WorkspaceAssistant["role"])
              }
              className={`sd-role ${selectedRole === role ? "is-selected" : ""}`}
            >
              <span className="sd-role-top">
                <span className={`sd-assistant-mark sd-mark-${config.color}`}>
                  <config.icon size={24} strokeWidth={1.2} />
                </span>
                <span className="sd-role-index">0{i + 1}</span>
              </span>
              <h2>{config.label}</h2>
              <strong>{config.title}</strong>
              <p>
                {item?.responsibility ??
                  [
                    "监测更新、定位异常、整理复查步骤。",
                    "核对完整性、重复值与分析口径。",
                    "梳理影响、责任与可验证的下一步。",
                  ][i]}
              </p>
              <span className="sd-role-bottom">
                {item ? `${item.taskIds.length} 项协作任务` : "等待数据盘点"}
                <ArrowUpRight size={14} />
              </span>
            </button>
          );
        })}
      </div>
      <div
        className="sd-team-workbench"
        role="tabpanel"
        id="sd-assistant-panel"
        aria-labelledby={`sd-assistant-${selectedRole}`}
      >
        <section className="sd-team-conversation">
          {assistant?.scopeNotice && (
            <p className="sd-scope-note">{assistant.scopeNotice}</p>
          )}
          <div className="sd-section-title">
            <h2>和{ROLE[selectedRole].label}一起看</h2>
            <span className="sd-method-label">
              {assistantMethod(
                workspace.capabilities.assistantMethod ?? assistant?.method,
              )}{" "}
              · 可追溯
            </span>
          </div>
          <div className="sd-messages">
            {assistant ? (
              <>
                <div className="sd-assistant-intro">
                  <span
                    className={`sd-assistant-mark sd-mark-${ROLE[selectedRole].color}`}
                  >
                    <Sparkles size={18} />
                  </span>
                  <div>
                    <strong>先把依据放到桌面上。</strong>
                    <p>
                      我负责{assistant.responsibility.replace(/[。；;]+$/, "")}
                      。
                      你可以询问当前发现、证据或处理步骤；我的答复基于本工作区的检查结果。
                    </p>
                  </div>
                </div>
                {workspace.messages
                  .filter((item) => item.assistantId === assistant.id)
                  .map((item) => (
                    <div className="sd-message-pair" key={item.id}>
                      <div className="sd-user-message">
                        <span>你 · {time(item.createdAt)}</span>
                        <p>{item.question}</p>
                      </div>
                      <div
                        className={`sd-assistant-message ${item.status === "FAILED" ? "is-failed" : ""}`}
                      >
                        <span>
                          {ROLE[selectedRole].label} ·{" "}
                          {assistantMethod(item.method)} ·{" "}
                          {item.status === "FAILED"
                            ? "答复未完成"
                            : "依据答复时的工作区"}
                        </span>
                        <p>{item.answer}</p>
                        {!!item.uncertainty?.length && (
                          <div className="sd-answer-uncertainty">
                            <strong>仍需确认</strong>
                            <ul>
                              {item.uncertainty.map((value, index) => (
                                <li key={index}>{value}</li>
                              ))}
                            </ul>
                          </div>
                        )}
                        {!!item.evidenceRefs.length && (
                          <div
                            className="sd-answer-evidence"
                            aria-label="答复依据"
                          >
                            {item.evidenceRefs.map((id) => {
                              const evidence = evidenceFindings.find(
                                (value) => value.id === id,
                              );
                              return evidence ? (
                                <button
                                  className="sd-evidence-link"
                                  type="button"
                                  key={id}
                                  onClick={() => onFinding(evidence)}
                                >
                                  <Fingerprint size={12} />
                                  <span>
                                    {evidence.title}
                                    {!evidence.active ? " · 历史观测" : ""}
                                  </span>
                                  <ArrowUpRight size={12} />
                                </button>
                              ) : (
                                <small key={id}>证据引用：{id}</small>
                              );
                            })}
                          </div>
                        )}
                        {item.errorCode && (
                          <small className="sd-answer-error">
                            失败记录：{item.errorCode}
                          </small>
                        )}
                        {item.trace && (
                          <details className="sd-answer-trace">
                            <summary>查看模型与版本记录</summary>
                            <dl>
                              <dt>模型</dt>
                              <dd>
                                {item.trace.providerProfile} ·{" "}
                                {item.trace.modelId}
                              </dd>
                              <dt>模型版本</dt>
                              <dd>{item.trace.modelVersion}</dd>
                              <dt>提示词版本</dt>
                              <dd>{item.trace.promptVersion}</dd>
                              <dt>依据指纹</dt>
                              <dd>
                                <code>{item.trace.contextSha256}</code>
                              </dd>
                              <dt>用量</dt>
                              <dd>
                                输入 {item.trace.inputTokens ?? "未返回"} / 输出{" "}
                                {item.trace.outputTokens ?? "未返回"}{" "}
                                tokens（模型计量单位）
                              </dd>
                            </dl>
                          </details>
                        )}
                      </div>
                    </div>
                  ))}
              </>
            ) : (
              <Empty
                icon={UsersRound}
                title="先给团队一点业务背景"
                body="完成数据盘点后，按实际发现组建团队。每个助手会获得具体分工和证据。"
              />
            )}
          </div>
          <form className="sd-composer" onSubmit={ask}>
            <label className="sd-sr-only" htmlFor="team-question">
              向服务助手提问
            </label>
            <textarea
              id="team-question"
              placeholder={
                assistant
                  ? "例如：这个问题会影响哪些分析？我们先检查什么？"
                  : "组建团队后，在这里开始协作…"
              }
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              rows={2}
              maxLength={2000}
              disabled={!assistant || pending}
            />
            <footer>
              <span>
                <Fingerprint size={12} />
                {workspace.capabilities.assistantMethod ===
                "SPRING_AI_EVIDENCE_V1"
                  ? "提问与获准摘要会发送到已配置模型；勿粘贴原始记录或密钥"
                  : "只使用工作区证据"}
              </span>
              <button
                type="submit"
                aria-label="发送问题"
                disabled={!assistant || !question.trim() || pending}
              >
                {pending ? (
                  <LoaderCircle size={17} className="sd-spinning" />
                ) : (
                  <ArrowRight size={18} />
                )}
              </button>
            </footer>
          </form>
        </section>
        <aside className="sd-team-assignment">
          <div className="sd-section-title">
            <h2>负责的下一步</h2>
            <span>{assistant?.taskIds.length ?? 0}</span>
          </div>
          {assistant &&
          workspace.tasks.filter((item) => assistant.taskIds.includes(item.id))
            .length ? (
            workspace.tasks
              .filter((item) => assistant.taskIds.includes(item.id))
              .map((item) => (
                <button
                  type="button"
                  className="sd-assignment"
                  key={item.id}
                  onClick={() => onTask(item)}
                >
                  <State value={item.status} />
                  <strong>{item.title}</strong>
                  {selectedRole === "DECISION" && (
                    <small className="sd-assignment-owner">
                      负责：
                      {workspace.assistants.find(
                        (owner) => owner.id === item.assistantId,
                      )?.name ?? "待分工"}
                    </small>
                  )}
                  <p>{item.description}</p>
                  {workspace.businessBrief && item.priorityReason && (
                    <small className="sd-assignment-owner">
                      {item.priorityReason}
                    </small>
                  )}
                  <span>
                    打开协作记录
                    <ArrowUpRight size={13} />
                  </span>
                </button>
              ))
          ) : (
            <p className="sd-muted">与该助手职责相关的任务会出现在这里。</p>
          )}
        </aside>
      </div>
      <p className="sd-bottom-note">
        <CircleHelp size={14} />
        {workspace.capabilities.assistantMethod === "SPRING_AI_EVIDENCE_V1"
          ? "已启用模型协助理解。你的提问与获准发现摘要会发送到已配置模型，请勿粘贴原始记录或密钥。请核对引用、未知项与版本记录，再决定下一步。模型答复不会直接修改业务系统。"
          : "当前助手按固定检查规则整理证据与步骤，尚未调用语言模型。可选模型接入由管理员在服务端配置，启用后会在这里标明。"}
      </p>
    </>
  );
}

function Tasks({
  workspace,
  onSelect,
  onTeam,
}: {
  workspace: WorkspaceState;
  onSelect: (task: WorkspaceTask) => void;
  onTeam: () => void;
}) {
  const [tab, setTab] = useState("all");
  const tasks = workspace.tasks.filter(
    (task) =>
      tab === "all" ||
      (tab === "scope"
        ? task.inCurrentScope !== false
        : tab === "finished"
          ? task.status === "VERIFIED"
          : task.status !== "VERIFIED"),
  );
  return (
    <>
      <PageTitle
        eyebrow="行动 / COLLABORATION"
        title="协作任务"
        description="接手、讨论、处理和复核，都围绕同一项任务展开。完成处理后，再检查来源是否真的改善。"
      >
        <button
          className="sd-button sd-secondary"
          type="button"
          onClick={onTeam}
        >
          <UsersRound size={15} />
          查看团队分工
        </button>
      </PageTitle>
      <div className="sd-toolbar">
        <div className="sd-filter-tabs">
          {[
            ["all", "全部任务"],
            ...(workspace.businessBrief ? [["scope", "本轮范围"]] : []),
            ["active", "进行中"],
            ["finished", "已核验"],
          ].map(([id, label]) => (
            <button
              key={id}
              type="button"
              onClick={() => setTab(id!)}
              className={tab === id ? "is-active" : ""}
            >
              {label}
            </button>
          ))}
        </div>
        <span>{tasks.length} 项任务</span>
      </div>
      {tasks.length ? (
        <div className="sd-task-list">
          {tasks.map((task, i) => {
            const assistant = workspace.assistants.find(
              (item) => item.id === task.assistantId,
            );
            return (
              <button
                type="button"
                className="sd-task-row"
                key={task.id}
                onClick={() => onSelect(task)}
              >
                <span className="sd-row-number">
                  {String(i + 1).padStart(2, "0")}
                </span>
                <span className="sd-task-main">
                  <strong>{task.title}</strong>
                  <small>{task.description}</small>
                  {workspace.businessBrief && (
                    <small>
                      {task.inCurrentScope === false
                        ? "保留的历史工作 · "
                        : "本轮关注 · "}
                      {task.priorityReason ?? task.scopeReason}
                    </small>
                  )}
                </span>
                <span className="sd-task-role">
                  {assistant ? ROLE[assistant.role].label : "待分工"}
                </span>
                <State value={task.status} />
                <span className="sd-task-time">{time(task.updatedAt)}</span>
                <ArrowUpRight size={15} />
              </button>
            );
          })}
        </div>
      ) : (
        <Empty
          icon={ListChecks}
          title="把下一步安排起来"
          body="完成数据盘点后，服务团队可以按发现生成协作任务。"
          action="打开服务团队"
          onAction={onTeam}
        />
      )}
    </>
  );
}

function Settings({
  theme,
  setTheme,
  workspace,
  onExport,
}: {
  theme: string;
  setTheme: (theme: string) => void;
  workspace: WorkspaceState;
  onExport: () => void;
}) {
  return (
    <>
      <PageTitle
        eyebrow="偏好 / SPACE SETTINGS"
        title="空间设置"
        description="调整阅读氛围，了解工作空间的数据边界与当前能力。"
      />
      <section className="sd-settings-section">
        <div>
          <h2>工作空间外观</h2>
          <p>克制用色，把注意力留给数据与协作。</p>
        </div>
        <div className="sd-theme-picker">
          {[
            {
              id: "mineral",
              title: "矿白 · 普鲁士蓝",
              caption: "明净的矿物白与深蓝工作面",
            },
            {
              id: "titanium",
              title: "石墨 · 钛银",
              caption: "低亮度、清晰克制的深色环境",
            },
            {
              id: "muse",
              title: "水碧 · 月白",
              caption: "青绿点缀的明亮工作环境",
            },
            {
              id: "cinnabar",
              title: "朱砂 · 素纸",
              caption: "灰纸白、矿物朱红与极少赭金",
            },
          ].map((item) => (
            <button
              type="button"
              data-preview={item.id}
              key={item.id}
              onClick={() => setTheme(item.id)}
              aria-pressed={theme === item.id}
            >
              <span className="sd-theme-swatch">
                <i />
                <i />
                <i />
                {theme === item.id && <Check size={16} />}
              </span>
              <strong>{item.title}</strong>
              <small>{item.caption}</small>
            </button>
          ))}
        </div>
      </section>
      <section className="sd-settings-section">
        <div>
          <h2>身份连接</h2>
          <p>
            {localRolesEnabled()
              ? "当前工作区使用本机演示身份，可在左侧下方切换角色来验证交接。"
              : "当前使用本机服务默认的订单专员身份；此构建未启用角色演练。"}
          </p>
        </div>
        <p className="sd-bottom-note">
          <ShieldCheck size={14} />
          本地角色切换用于功能演练，不能证明不同人员完成审批。企业登录与真实双人复核仍需生产身份适配。
        </p>
      </section>
      <section className="sd-settings-section">
        <div>
          <h2>数据与能力边界</h2>
          <p>读懂工具现在能做什么，也知道哪些步骤仍需要人来判断。</p>
        </div>
        <dl className="sd-capability-list">
          <div>
            <dt>保存方式</dt>
            <dd>服务端文件保存 · 工作区 v{workspace.stateVersion}</dd>
          </div>
          <div>
            <dt>来源访问</dt>
            <dd>只读连接；API 和数据库目标需在服务端允许名单中</dd>
          </div>
          <div>
            <dt>诊断方式</dt>
            <dd>确定性规则 · 每项发现保留观测范围和证据指纹</dd>
          </div>
          <div>
            <dt>助手方式</dt>
            <dd>
              {assistantMethod(workspace.capabilities.assistantMethod)} ·
              按职责整理检查证据与处理步骤；答复保留方式、引用和未确认项
            </dd>
          </div>
          <div>
            <dt>外部修改</dt>
            <dd>通用诊断不写入业务系统；履约专项须审批并独立验证</dd>
          </div>
          <div>
            <dt>数据迁出</dt>
            <dd>
              <button
                type="button"
                className="sd-text-button"
                onClick={onExport}
              >
                导出当前工作空间报告
                <ArrowDownToLine size={13} />
              </button>
            </dd>
          </div>
        </dl>
      </section>
    </>
  );
}

function AssetDialog({
  asset,
  workspace,
  onClose,
  onFinding,
}: {
  asset: WorkspaceAsset;
  workspace?: WorkspaceState;
  onClose: () => void;
  onFinding: (finding: WorkspaceFinding) => void;
}) {
  const findings =
    workspace?.findings.filter((item) => item.assetId === asset.id) ?? [];
  return (
    <Dialog
      title={asset.name}
      subtitle="字段来自本次实际读取；不显示原始业务记录。"
      onClose={onClose}
      wide
    >
      <div className="sd-inspector-body">
        <div className="sd-inspector-meta">
          <Provenance demo={asset.provenance === "DEMO"} />
          <span>
            {asset.rowCount} 条已读取 · {asset.columns.length} 个字段
          </span>
          <span>{time(asset.observedAt)}</span>
        </div>
        <div className="sd-table-scroll">
          <table className="sd-table sd-column-table">
            <thead>
              <tr>
                <th>字段</th>
                <th>观测类型</th>
                <th>缺失记录</th>
                <th>不同值数量</th>
              </tr>
            </thead>
            <tbody>
              {asset.columns.map((column) => (
                <tr key={column.name}>
                  <td>
                    <code>{column.name}</code>
                  </td>
                  <td>{column.inferredType}</td>
                  <td>
                    {column.missingCount} / {asset.rowCount}
                  </td>
                  <td>{column.distinctCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <section>
          <h3>相关发现</h3>
          {findings.length && workspace ? (
            <FindingRows
              findings={findings}
              workspace={workspace}
              onSelect={onFinding}
            />
          ) : (
            <p className="sd-muted">当前检查规则未对这项资产产生发现。</p>
          )}
        </section>
        <div className="sd-evidence-stamp">
          <Fingerprint size={17} />
          <div>
            <span>证据指纹 SHA-256 · 用于确认本次观测未被替换</span>
            <code>{asset.evidenceSha256}</code>
            <small>
              {asset.sampled
                ? `采样上限 ${asset.sampleLimit} 条，结论只覆盖该样本。`
                : "基于当前导入快照；不代表来源系统的实时全量状态。"}
            </small>
          </div>
        </div>
      </div>
    </Dialog>
  );
}
function FindingDialog({
  finding,
  workspace,
  onClose,
  onTask,
  onTeam,
}: {
  finding: WorkspaceFinding;
  workspace?: WorkspaceState;
  onClose: () => void;
  onTask: (task: WorkspaceTask) => void;
  onTeam: () => void;
}) {
  const task = workspace?.tasks.find((item) => item.findingId === finding.id);
  const asset = workspace?.assets.find((item) => item.id === finding.assetId);
  return (
    <Dialog
      title={finding.title}
      subtitle="先核对依据，再决定怎样处理。"
      onClose={onClose}
      wide
    >
      <div className="sd-inspector-body">
        <div className="sd-inspector-meta">
          <span
            className={`sd-severity-text sd-severity-${finding.severity.toLowerCase()}`}
          >
            {severity(finding.severity)}
          </span>
          <span>{asset?.name ?? "数据资产"}</span>
          <code>{finding.ruleId}</code>
        </div>
        <dl className="sd-finding-details">
          <div>
            <dt>实际观测</dt>
            <dd>{finding.observed}</dd>
          </div>
          <div>
            <dt>检查约定</dt>
            <dd>{finding.expected}</dd>
          </div>
          <div>
            <dt>建议下一步</dt>
            <dd>{finding.suggestion}</dd>
          </div>
        </dl>
        <div className="sd-evidence-stamp">
          <Fingerprint size={17} />
          <div>
            <span>本次检查依据</span>
            <p>
              读取 {finding.evidence.sampledRows} 条记录 · 采样上限{" "}
              {finding.evidence.sampleLimit} 条 · 观测于{" "}
              {time(finding.evidence.observedAt)}
            </p>
            <code>{finding.evidence.sha256}</code>
            <small>
              指纹证明输入版本，不自动证明业务结论正确。请结合原始系统和业务口径复核。
            </small>
          </div>
        </div>
        <footer className="sd-inspector-footer">
          <span>{task ? "已关联协作任务" : "尚未安排协作任务"}</span>
          <button
            className="sd-button sd-primary"
            type="button"
            onClick={() => (task ? onTask(task) : onTeam())}
          >
            {task ? "打开协作任务" : "安排团队分工"}
            <ArrowRight size={14} />
          </button>
        </footer>
      </div>
    </Dialog>
  );
}

function TaskDialog({
  task,
  workspace,
  pending,
  error,
  onClose,
  onTransition,
  onComment,
  localActor,
  onActorChange,
}: {
  task: WorkspaceTask;
  workspace: WorkspaceState;
  pending: boolean;
  error: string;
  onClose: () => void;
  onTransition: (status: string, note: string, refs: string[]) => void;
  onComment: (body: string) => Promise<WorkspaceState | undefined>;
  localActor: string;
  onActorChange: (actor: string) => void;
}) {
  const [note, setNote] = useState("");
  const comments = workspace.comments.filter((item) => item.taskId === task.id);
  const finding = workspace.findings.find((item) => item.id === task.findingId);
  const latestScan = workspace.scans
    .filter((scan) => scan.sourceId === finding?.sourceId)
    .at(-1);
  const verificationRefs = [
    ...new Set([...task.evidenceRefs, ...(latestScan ? [latestScan.id] : [])]),
  ];
  return (
    <Dialog
      title={task.title}
      subtitle="与业务伙伴一起处理，保留每次判断和复查依据。"
      onClose={onClose}
      wide
    >
      <div className="sd-inspector-body">
        <div className="sd-inspector-meta">
          <State value={task.status} />
          <span>
            {
              workspace.assistants.find((item) => item.id === task.assistantId)
                ?.name
            }
          </span>
          <span>更新于 {time(task.updatedAt)}</span>
        </div>
        <p className="sd-task-description">{task.description}</p>
        {workspace.businessBrief && (
          <div className="sd-scope-note">
            <strong>
              {task.inCurrentScope === false
                ? "当前未安排，历史仍保留"
                : "按本轮业务目标安排"}
            </strong>
            <p>{task.scopeReason}</p>
            <p>{task.priorityReason}</p>
            <small>
              任务建立于约定{" "}
              {task.createdUnderBriefVersion == null
                ? "尚未设置时"
                : `v${task.createdUnderBriefVersion}`}{" "}
              · 当前约定 v{workspace.businessBrief.version}
              。范围变化不是修复证据。
            </small>
          </div>
        )}
        {finding && (
          <div className="sd-task-finding">
            <Fingerprint size={16} />
            <span>
              <strong>关联发现</strong>
              {finding.observed}
            </span>
          </div>
        )}
        <h3>协作记录</h3>
        <div className="sd-comment-thread">
          {comments.length ? (
            comments.map((comment) => (
              <article key={comment.id}>
                <span className="sd-comment-avatar">
                  {comment.actorId.substring(0, 1).toUpperCase()}
                </span>
                <div>
                  <header>
                    <strong>{comment.actorId}</strong>
                    <time>{time(comment.createdAt)}</time>
                  </header>
                  <p>{comment.body}</p>
                </div>
              </article>
            ))
          ) : (
            <p className="sd-muted">
              还没有讨论。写下你核实了什么、还需要谁协助。
            </p>
          )}
        </div>
        <label className="sd-note-label">
          处理说明或讨论内容
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="例如：已核对原始订单，重复来自同步任务的重试。准备请运维检查去重逻辑。"
            rows={3}
            maxLength={2000}
          />
        </label>
        {error && (
          <p className="sd-error" role="alert">
            {error}
          </p>
        )}
        {task.status === "COMPLETED" && (
          <div className="sd-review-controls">
            {localRolesEnabled() ? (
              <label>
                本地复核身份
                <select
                  value={localActor}
                  onChange={(event) => onActorChange(event.target.value)}
                  aria-label="本地复核身份"
                >
                  <option value="admin">本地管理员</option>
                  <option value="sales-owner">业务负责人</option>
                </select>
              </label>
            ) : (
              <p>
                当前控制台未启用演练身份切换，使用服务端默认的本地订单专员身份。
              </p>
            )}
            <p>
              本地演示可切换角色验证交接。实际部署须由企业登录系统识别不同审核人。
            </p>
            <span>
              {latestScan && latestScan.id !== finding?.scanId
                ? `将引用最新盘点：${time(latestScan.completedAt)}`
                : "还没有新的盘点。请先在数据连接中更新来源并重新盘点。"}
            </span>
          </div>
        )}
        <div className="sd-task-actions">
          <button
            className="sd-button sd-secondary"
            type="button"
            disabled={pending || !note.trim()}
            onClick={async () => {
              const next = await onComment(note.trim());
              if (next) setNote("");
            }}
          >
            <MessageSquare size={14} />
            保存讨论
          </button>
          {!["ACCEPTED", "COMPLETED", "VERIFIED"].includes(task.status) && (
            <button
              className="sd-button sd-primary"
              type="button"
              disabled={pending || !note.trim()}
              onClick={() =>
                onTransition("ACCEPTED", note.trim(), task.evidenceRefs)
              }
            >
              接手处理
              <ArrowRight size={14} />
            </button>
          )}
          {task.status === "ACCEPTED" && (
            <button
              className="sd-button sd-primary"
              type="button"
              disabled={pending || !note.trim()}
              onClick={() =>
                onTransition("COMPLETED", note.trim(), task.evidenceRefs)
              }
            >
              提交处理结果
              <Check size={14} />
            </button>
          )}
          {task.status === "COMPLETED" && (
            <button
              className="sd-button sd-primary"
              type="button"
              disabled={pending || !note.trim()}
              onClick={() =>
                onTransition("VERIFIED", note.trim(), verificationRefs)
              }
            >
              核验最新观测
              <FileCheck2 size={14} />
            </button>
          )}
          {task.status === "VERIFIED" && (
            <button
              className="sd-button sd-secondary"
              type="button"
              disabled={pending || !note.trim()}
              onClick={() =>
                onTransition("REOPENED", note.trim(), task.evidenceRefs)
              }
            >
              重新打开
            </button>
          )}
        </div>
        <p className="sd-bottom-note">
          <ShieldCheck size={13} />
          任务完成不等于问题已消失。先重新盘点来源，再由另一位审核人核验最新结果。
        </p>
      </div>
    </Dialog>
  );
}

function SearchDialog({
  workspace,
  onClose,
  onNavigate,
  onAsset,
  onFinding,
}: {
  workspace?: WorkspaceState;
  onClose: () => void;
  onNavigate: (view: WorkspaceViewId) => void;
  onAsset: (asset: WorkspaceAsset) => void;
  onFinding: (finding: WorkspaceFinding) => void;
}) {
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const results = useMemo(
    () =>
      [
        ...NAV.map((item) => ({
          id: item.id,
          title: item.label,
          type: "页面",
          open: () => onNavigate(item.id),
        })),
        ...(workspace?.assets ?? []).map((asset) => ({
          id: asset.id,
          title: asset.name,
          type: "数据资产",
          open: () => onAsset(asset),
        })),
        ...(workspace?.findings ?? []).map((finding) => ({
          id: finding.id,
          title: finding.title,
          type: "诊断发现",
          open: () => onFinding(finding),
        })),
      ]
        .filter((item) =>
          item.title.toLowerCase().includes(query.toLowerCase()),
        )
        .slice(0, 12),
    [query, workspace, onNavigate, onAsset, onFinding],
  );
  function open(index: number) {
    results[index]?.open();
    onClose();
  }
  return (
    <Dialog title="搜索工作空间" onClose={onClose}>
      <div className="sd-command">
        <label>
          <Search size={18} />
          <input
            autoFocus
            data-autofocus
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setActive(0);
            }}
            placeholder="查找资产、发现，或前往页面…"
            aria-label="搜索"
            role="combobox"
            aria-expanded="true"
            aria-controls="search-results"
            aria-activedescendant={
              results[active] ? `result-${results[active].id}` : undefined
            }
            onKeyDown={(e) => {
              if (e.nativeEvent.isComposing || e.keyCode === 229) return;
              if (e.key === "ArrowDown") {
                e.preventDefault();
                setActive((value) => Math.min(value + 1, results.length - 1));
              }
              if (e.key === "ArrowUp") {
                e.preventDefault();
                setActive((value) => Math.max(value - 1, 0));
              }
              if (e.key === "Enter") {
                e.preventDefault();
                open(active);
              }
            }}
          />
          <kbd>ESC</kbd>
        </label>
        <div role="listbox" id="search-results">
          {results.map((result, index) => (
            <button
              type="button"
              role="option"
              id={`result-${result.id}`}
              aria-selected={active === index}
              className={active === index ? "is-active" : ""}
              key={result.id}
              onClick={() => open(index)}
            >
              <span>{result.title}</span>
              <small>{result.type}</small>
              <ArrowUpRight size={13} />
            </button>
          ))}
          {!results.length && <p>没有匹配结果。换一个关键词试试。</p>}
        </div>
        <footer>
          <span>↑↓ 选择</span>
          <span>↵ 打开</span>
        </footer>
      </div>
    </Dialog>
  );
}

function Guide({
  onClose,
  onConnect,
}: {
  onClose: () => void;
  onConnect: () => void;
}) {
  const reduce = useReducedMotion();
  const [step, setStep] = useState(0);
  const steps = [
    {
      title: "连接你授权的数据",
      body: "导入 JSON 样本，或连接业务 API、PostgreSQL。先选定读取范围，再进行只读盘点。",
      icon: Link2,
    },
    {
      title: "把本轮要做好什么说清楚",
      body: "在业务约定里说明目标、选择来源范围，并记录共同使用的指标定义。助手据此安排工作；定义确认后仍需另行计算与核对结果。",
      icon: Target,
    },
    {
      title: "打开一项发现，看看依据",
      body: "查看实际观测、检查约定、读取范围和证据指纹。发现是核实线索，不能代替业务判断。",
      icon: Fingerprint,
    },
    {
      title: "让合适的人和助手接着做",
      body: "组建运维、分析和决策助手。每个任务都可以接手、讨论、提交结果，再用新观测核验。",
      icon: UsersRound,
    },
  ];
  const current = steps[step]!;
  return (
    <Dialog title="四步，开始协作" onClose={onClose}>
      <div className="sd-guide">
        <div className="sd-guide-steps" aria-label="学习步骤">
          {steps.map((item, i) => (
            <button
              type="button"
              key={item.title}
              aria-label={`第${i + 1}步：${item.title}`}
              aria-current={step === i ? "step" : undefined}
              onClick={() => setStep(i)}
              className={step === i ? "is-active" : ""}
            >
              {String(i + 1).padStart(2, "0")}
            </button>
          ))}
        </div>
        <AnimatePresence mode="wait">
          <motion.div
            key={step}
            initial={reduce ? false : { opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={reduce ? undefined : { opacity: 0, y: -4 }}
            transition={reduce ? { duration: 0 } : { duration: 0.2 }}
          >
            <span className="sd-guide-icon">
              <current.icon size={42} strokeWidth={1} />
            </span>
            <h3>{current.title}</h3>
            <p>{current.body}</p>
          </motion.div>
        </AnimatePresence>
        <footer>
          <span>
            {step + 1} / {steps.length}
          </span>
          <button
            className="sd-button sd-primary"
            type="button"
            onClick={() =>
              step < steps.length - 1 ? setStep(step + 1) : onConnect()
            }
          >
            {step < steps.length - 1 ? "下一步" : "连接我的数据"}
            <ArrowRight size={14} />
          </button>
        </footer>
      </div>
    </Dialog>
  );
}

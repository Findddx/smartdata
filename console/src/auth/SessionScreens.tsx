import { useState, type ReactNode } from "react";
import {
  ArrowRight,
  Check,
  Copy,
  LoaderCircle,
  LogOut,
  RefreshCw,
  ShieldCheck,
} from "lucide-react";
import { SmartDataMark } from "../brand/SmartDataMark";
import { ROLE_LABELS, type InvitationView } from "./types";
import "./auth.css";

/** Authentication screens never infer an organization or a role from browser storage. */
export function SessionScreen({ children }: { children: ReactNode }) {
  const savedTheme = localStorage.getItem("smartdata.theme");
  const theme =
    savedTheme &&
    ["mineral", "titanium", "muse", "cinnabar"].includes(savedTheme)
      ? savedTheme
      : "mineral";
  return (
    <main className="sd-workspace sd-desktop sd-auth-shell" data-theme={theme}>
      <div className="sd-auth-environment" aria-hidden="true">
        <img src="/art/strata-study-v1.webp" alt="" decoding="async" />
      </div>
      <section className="sd-auth-card">
        <div className="sd-auth-brand">
          <SmartDataMark width={30} height={30} />
          <span>
            SmartData<small>智能数据基座</small>
          </span>
        </div>
        {children}
      </section>
    </main>
  );
}

export function SessionLoading() {
  return (
    <SessionScreen>
      <div className="sd-auth-status" role="status">
        <LoaderCircle size={20} className="sd-spinning" />
        <h1>正在确认身份</h1>
        <p>读取登录状态与企业访问权限。</p>
      </div>
    </SessionScreen>
  );
}

export function SessionUnavailable({
  error,
  onRetry,
  pending,
}: {
  error: string;
  onRetry: () => void;
  pending: boolean;
}) {
  return (
    <SessionScreen>
      <h1>暂时无法确认身份</h1>
      <p>工作台需要先确认你的企业与访问权限，恢复连接后可以继续。</p>
      <p className="sd-auth-error" role="alert">
        {error}
      </p>
      <button
        type="button"
        className="sd-button sd-primary"
        onClick={onRetry}
        disabled={pending}
      >
        <RefreshCw size={15} />
        重新连接
      </button>
    </SessionScreen>
  );
}

export function LoginScreen({
  onLogin,
  pending,
  error,
}: {
  onLogin: () => void;
  pending: boolean;
  error?: string;
}) {
  return (
    <SessionScreen>
      <div className="sd-auth-kicker">
        <ShieldCheck size={14} />
        企业工作空间
      </div>
      <h1>登录企业工作区</h1>
      <p>使用企业账号登录，查看获准的数据、问题与协作进度。</p>
      {error && (
        <p className="sd-auth-error" role="alert">
          {error}
        </p>
      )}
      <button
        type="button"
        className="sd-button sd-primary sd-auth-login"
        onClick={onLogin}
        disabled={pending}
      >
        {pending ? (
          <LoaderCircle size={16} className="sd-spinning" />
        ) : (
          <ShieldCheck size={16} />
        )}
        使用企业账号登录
        <ArrowRight size={15} />
      </button>
      <small className="sd-auth-footnote">
        账号由企业身份服务核实；进入工作区后，权限由负责人授予。
      </small>
    </SessionScreen>
  );
}

export function AwaitingMembership({
  accountId,
  displayName,
  onAccept,
  onRefresh,
  onLogout,
  pending,
  error,
  suspended,
  invitations,
}: {
  accountId: string;
  displayName: string;
  onAccept: (invitationId: string) => Promise<void>;
  onRefresh: () => void;
  onLogout: () => void;
  pending: boolean;
  error?: string;
  suspended: boolean;
  invitations: InvitationView[];
}) {
  const [copyStatus, setCopyStatus] = useState("");
  async function copyAccount() {
    try {
      await navigator.clipboard.writeText(accountId);
      setCopyStatus("账号编号已复制");
    } catch {
      setCopyStatus("请选中上方账号编号后手动复制");
    }
  }
  return (
    <SessionScreen>
      <div className="sd-auth-kicker">
        <Check size={14} />
        已登录 · {displayName}
      </div>
      <h1>{suspended ? "企业访问已暂停" : "还差一步，加入企业工作区。"}</h1>
      <p>
        {suspended
          ? "当前企业成员资格已暂停。请联系负责人恢复访问，历史工作记录仍然保留。"
          : "将账号编号交给负责人，取得指定给你的邀请。登录成功本身不会授予企业数据权限。"}
      </p>
      <label className="sd-auth-account">
        你的账号编号
        <div>
          <input
            aria-label="我的账号编号"
            value={accountId}
            readOnly
            onFocus={(event) => event.target.select()}
          />
          <button
            type="button"
            className="sd-icon"
            aria-label="复制账号编号"
            title="复制账号编号"
            onClick={copyAccount}
          >
            <Copy size={16} />
          </button>
        </div>
      </label>
      {copyStatus && (
        <p className="sd-auth-copy-status" role="status">
          {copyStatus}
        </p>
      )}
      <div className="sd-auth-invitation">
        {error && (
          <p className="sd-auth-error" role="alert">
            {error}
          </p>
        )}
        {!suspended &&
          invitations
            .filter((invite) => invite.status === "PENDING")
            .map((invite) => (
              <article className="sd-auth-pending-invite" key={invite.id}>
                <strong>负责人邀请你加入</strong>
                <p>
                  {invite.roles.map((role) => ROLE_LABELS[role]).join(" · ")}
                  <br />
                  有效至{" "}
                  {new Date(invite.expiresAt).toLocaleString("zh-CN", {
                    hour12: false,
                  })}
                </p>
                <button
                  type="button"
                  className="sd-button sd-primary"
                  disabled={pending}
                  onClick={() => {
                    void onAccept(invite.id);
                  }}
                >
                  {pending ? (
                    <LoaderCircle size={15} className="sd-spinning" />
                  ) : (
                    <ArrowRight size={15} />
                  )}
                  接受指定邀请
                </button>
              </article>
            ))}
        {!suspended &&
          !invitations.some((invite) => invite.status === "PENDING") && (
            <p>
              暂时没有指定给你的待接受邀请。负责人登记后，可以重新检查加入状态。
            </p>
          )}
      </div>
      <div className="sd-auth-actions">
        <button
          type="button"
          className="sd-text-button"
          onClick={onRefresh}
          disabled={pending}
        >
          <RefreshCw size={13} />
          重新检查加入状态
        </button>
        <button
          type="button"
          className="sd-text-button"
          onClick={onLogout}
          disabled={pending}
        >
          <LogOut size={13} />
          退出账号
        </button>
      </div>
    </SessionScreen>
  );
}

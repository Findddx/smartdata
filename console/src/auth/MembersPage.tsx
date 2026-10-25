import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Check,
  Pause,
  Play,
  Plus,
  RefreshCw,
  ShieldCheck,
  UserRound,
  X,
} from "lucide-react";
import { Dialog } from "../workspace/Dialog";
import { identityClient } from "./client";
import { sessionGeneration } from "./transport";
import {
  ROLE_LABELS,
  workspacePermissions,
  type BusinessRole,
  type InvitationView,
  type MeView,
  type MemberDirectory,
  type MemberView,
} from "./types";

const roleText = (roles: BusinessRole[]) =>
  roles.map((role) => ROLE_LABELS[role]).join(" · ");
const time = (value: string) =>
  new Date(value).toLocaleString("zh-CN", { hour12: false });
const STATUS = {
  PENDING: "待接受",
  ACCEPTED: "已接受",
  REVOKED: "已撤销",
  EXPIRED: "已过期",
};
function RolePicker({
  value,
  onChange,
  disabled,
}: {
  value: BusinessRole[];
  onChange: (next: BusinessRole[]) => void;
  disabled: boolean;
}) {
  return (
    <fieldset className="sd-member-role-picker" disabled={disabled}>
      <legend>业务角色</legend>
      {(Object.keys(ROLE_LABELS) as BusinessRole[]).map((role) => (
        <label key={role}>
          <input
            type="checkbox"
            checked={value.includes(role)}
            onChange={(event) =>
              onChange(
                event.target.checked
                  ? [...value, role]
                  : value.filter((item) => item !== role),
              )
            }
          />
          {ROLE_LABELS[role]}
        </label>
      ))}
    </fieldset>
  );
}
type Edit =
  | {
      kind: "roles" | "suspend" | "activate";
      member: MemberView;
      roles: BusinessRole[];
      version: number;
    }
  | { kind: "revoke"; invitation: InvitationView; version: number };

export function MembersPage({
  identity,
  onIdentityRefresh,
}: {
  identity: MeView;
  onIdentityRefresh: () => Promise<unknown>;
}) {
  const allowed = workspacePermissions(identity).manageMembers;
  const client = useQueryClient();
  const live = useRef(true);
  const mountedGeneration = useRef(sessionGeneration()).current;
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  const query = useQuery({
    queryKey: ["enterprise-members"],
    queryFn: identityClient.members,
    enabled: allowed,
    retry: false,
  });
  const [accountId, setAccountId] = useState("");
  const [roles, setRoles] = useState<BusinessRole[]>(["ORDER_SPECIALIST"]);
  const [draftVersion, setDraftVersion] = useState<number | null>(null);
  const [edit, setEdit] = useState<Edit | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  useEffect(() => {
    if (query.data && draftVersion === null)
      setDraftVersion(query.data.stateVersion);
  }, [query.data, draftVersion]);
  if (!allowed)
    return (
      <section className="sd-members-section">
        <h1>企业成员</h1>
        <p className="sd-member-note">
          当前账号没有管理成员的权限。企业管理员可在这里邀请和管理已确认的真实账号。
        </p>
      </section>
    );
  const directory = query.data;
  const stale = !!directory && draftVersion !== directory.stateVersion;
  const editStale = !!edit && directory?.stateVersion !== edit.version;
  async function execute(
    operation: () => Promise<MemberDirectory>,
    done: (next: MemberDirectory) => void,
  ) {
    if (pending || !live.current || mountedGeneration !== sessionGeneration())
      return;
    setPending(true);
    setError("");
    setNotice("");
    const deliverable = () =>
      live.current && mountedGeneration === sessionGeneration();
    try {
      const next = await operation();
      if (!deliverable()) return;
      client.setQueryData<MemberDirectory>(["enterprise-members"], (current) =>
        current && current.stateVersion > next.stateVersion ? current : next,
      );
      done(next);
      await onIdentityRefresh();
    } catch (failure) {
      if (!deliverable()) return;
      setError(
        failure instanceof Error
          ? failure.message
          : "成员操作未完成，请刷新后核对。",
      );
      await query.refetch();
    } finally {
      if (deliverable()) setPending(false);
    }
  }
  function submitEdit() {
    if (!edit || editStale) return;
    const snapshot = edit;
    void execute(
      () =>
        snapshot.kind === "revoke"
          ? identityClient.revoke(snapshot.invitation.id, snapshot.version)
          : snapshot.kind === "roles"
            ? identityClient.setRoles(
                snapshot.member.accountId,
                snapshot.version,
                snapshot.member.stateVersion,
                snapshot.roles,
              )
            : identityClient.memberStatus(
                snapshot.member.accountId,
                snapshot.kind,
                snapshot.version,
                snapshot.member.stateVersion,
              ),
      (next) => {
        setEdit(null);
        setDraftVersion(next.stateVersion);
        setNotice("成员目录已更新，权限以服务端当前记录为准。");
      },
    );
  }
  return (
    <>
      <header className="sd-page-title">
        <div>
          <h1>企业成员</h1>
          <p>
            {identity.tenant?.displayName} · 邀请真实账号，明确每个人能做什么。
          </p>
        </div>
        <button
          type="button"
          className="sd-button sd-secondary"
          onClick={() => query.refetch()}
          disabled={pending || query.isFetching}
        >
          <RefreshCw size={14} />
          刷新目录
        </button>
      </header>
      {query.isPending && (
        <p role="status" className="sd-member-note">
          正在读取成员目录…
        </p>
      )}
      {query.isError && (
        <p role="alert" className="sd-auth-error">
          {query.error.message} 已保存的邀请草稿仍保留，请恢复连接后核对。
        </p>
      )}
      {notice && (
        <p role="status" className="sd-member-note">
          {notice}
        </p>
      )}
      {error && !edit && (
        <p role="alert" className="sd-auth-error">
          {error}
        </p>
      )}
      {directory && (
        <div className="sd-members-section">
          <header>
            <h2>邀请同事</h2>
            <p>目录 v{directory.stateVersion}</p>
          </header>
          <form
            className="sd-member-invite-form"
            onSubmit={(event) => {
              event.preventDefault();
              if (
                !stale &&
                draftVersion !== null &&
                /^acct_[0-9a-f]{64}$/.test(accountId.trim()) &&
                roles.length
              )
                void execute(
                  () =>
                    identityClient.invite(
                      draftVersion,
                      accountId.trim(),
                      roles,
                    ),
                  (next) => {
                    setAccountId("");
                    setRoles(["ORDER_SPECIALIST"]);
                    setDraftVersion(next.stateVersion);
                    setNotice(
                      "邀请已登记，请同事登录并接受指定给自己的邀请。有效期为七天。",
                    );
                  },
                );
            }}
          >
            <p>
              请同事先登录，复制其账号编号交给你。编号精确匹配，不以姓名或邮箱猜测身份。
            </p>
            <div className="sd-member-fields">
              <label>
                同事的账号编号
                <input
                  aria-label="邀请账号编号"
                  value={accountId}
                  onChange={(event) => setAccountId(event.target.value)}
                  placeholder="acct_…"
                  autoComplete="off"
                  maxLength={69}
                  required
                  pattern="acct_[0-9a-f]{64}"
                />
              </label>
            </div>
            <RolePicker value={roles} onChange={setRoles} disabled={pending} />
            {roles.includes("ADMIN") && (
              <p className="sd-member-note">
                <ShieldCheck size={13} />{" "}
                企业管理员可以管理成员与来源授权，请确认这是同事应承担的职责。
              </p>
            )}
            {stale && (
              <p className="sd-member-note" role="status">
                成员目录已变化，邀请内容仍保留。
                <button
                  className="sd-text-button"
                  type="button"
                  onClick={() => setDraftVersion(directory.stateVersion)}
                >
                  核对后采用最新目录版本
                </button>
              </p>
            )}
            <button
              type="submit"
              className="sd-button sd-primary"
              disabled={
                pending ||
                query.isError ||
                stale ||
                !roles.length ||
                !/^acct_[0-9a-f]{64}$/.test(accountId.trim())
              }
            >
              <Plus size={14} />
              登记指定邀请
            </button>
          </form>
          <header>
            <h2>成员</h2>
            <p>{directory.members.length} 人</p>
          </header>
          {directory.members.map((member) => (
            <article className="sd-member-row" key={member.accountId}>
              <div>
                <strong>
                  <UserRound size={13} /> {member.displayName || "企业成员"}
                  {member.accountId === identity.accountId && "（你）"}
                </strong>
                <span className="sd-member-status">
                  {member.status === "ACTIVE" ? "可协作" : "已暂停"}
                </span>
                <small>{member.accountId}</small>
              </div>
              <div>
                <span>{roleText(member.roles)}</span>
                <small>
                  成员 v{member.stateVersion} · {time(member.updatedAt)}
                </small>
              </div>
              <div>
                <button
                  type="button"
                  className="sd-button sd-secondary"
                  disabled={pending || query.isError}
                  onClick={() => {
                    setError("");
                    setEdit({
                      kind: "roles",
                      member,
                      roles: [...member.roles],
                      version: directory.stateVersion,
                    });
                  }}
                >
                  设置角色
                </button>
                <button
                  type="button"
                  className="sd-button sd-quiet"
                  disabled={pending || query.isError}
                  onClick={() => {
                    setError("");
                    setEdit({
                      kind: member.status === "ACTIVE" ? "suspend" : "activate",
                      member,
                      roles: [...member.roles],
                      version: directory.stateVersion,
                    });
                  }}
                >
                  {member.status === "ACTIVE" ? (
                    <Pause size={13} />
                  ) : (
                    <Play size={13} />
                  )}
                  {member.status === "ACTIVE" ? "暂停" : "恢复"}
                </button>
              </div>
            </article>
          ))}
          <header>
            <h2>邀请记录</h2>
            <p>接受后才会成为成员</p>
          </header>
          {directory.invitations.length ? (
            directory.invitations.map((invite) => (
              <article className="sd-member-row" key={invite.id}>
                <div>
                  <strong>{STATUS[invite.status]}</strong>
                  <small>{invite.accountId}</small>
                </div>
                <div>
                  <span>{roleText(invite.roles)}</span>
                  <small>
                    有效至 {time(invite.expiresAt)} · 邀请 v
                    {invite.stateVersion}
                  </small>
                </div>
                <div>
                  {invite.status === "PENDING" && (
                    <button
                      type="button"
                      className="sd-button sd-quiet"
                      disabled={pending || query.isError}
                      onClick={() => {
                        setError("");
                        setEdit({
                          kind: "revoke",
                          invitation: invite,
                          version: directory.stateVersion,
                        });
                      }}
                    >
                      <X size={13} />
                      撤销邀请
                    </button>
                  )}
                </div>
              </article>
            ))
          ) : (
            <p className="sd-member-note">还没有邀请记录。</p>
          )}
          <p className="sd-member-note">
            暂停成员会停止其后续访问，保留历史工作记录；恢复成员不会恢复旧邀请或过去的批准。角色变更由服务端再次检查，不能通过修改页面授予权限。
          </p>
        </div>
      )}
      {edit && (
        <Dialog
          title={
            edit.kind === "roles"
              ? "设置成员角色"
              : edit.kind === "suspend"
                ? "暂停成员访问"
                : edit.kind === "activate"
                  ? "恢复成员访问"
                  : "撤销指定邀请"
          }
          subtitle="核对目标账号和当前记录后，再确认这次变更。"
          onClose={() => {
            if (!pending) {
              setEdit(null);
              setError("");
            }
          }}
        >
          <p className="sd-member-note">
            目标账号：
            {edit.kind === "revoke"
              ? edit.invitation.accountId
              : edit.member.accountId}
          </p>
          {edit.kind === "roles" && (
            <RolePicker
              value={edit.roles}
              disabled={pending}
              onChange={(next) => setEdit({ ...edit, roles: next })}
            />
          )}
          {edit.kind === "suspend" && (
            <p className="sd-member-note">
              该成员将无法继续访问企业工作区，历史记录仍保留。系统会阻止暂停最后一名管理员等不允许的操作。
            </p>
          )}
          {edit.kind === "activate" && (
            <p className="sd-member-note">
              恢复此成员的当前角色与访问资格，不恢复历史邀请或先前的批准。
            </p>
          )}
          {edit.kind === "revoke" && (
            <p className="sd-member-note">
              此邀请将不能再被接受，已经存在的成员资格不会因此改变。
            </p>
          )}
          {editStale && (
            <p role="status" className="sd-member-note">
              目录已变化，请先核对最新记录。本次草稿保留。
              <button
                type="button"
                className="sd-text-button"
                onClick={() => {
                  const latest =
                    edit.kind === "revoke"
                      ? directory?.invitations.find(
                          (item) => item.id === edit.invitation.id,
                        )
                      : directory?.members.find(
                          (item) => item.accountId === edit.member.accountId,
                        );
                  if (!latest || !directory) return;
                  setEdit(
                    edit.kind === "revoke"
                      ? {
                          ...edit,
                          invitation: latest as InvitationView,
                          version: directory.stateVersion,
                        }
                      : {
                          ...edit,
                          member: latest as MemberView,
                          roles: [...(latest as MemberView).roles],
                          version: directory.stateVersion,
                        },
                  );
                }}
              >
                重新载入当前记录（替换草稿）
              </button>
            </p>
          )}
          {error && (
            <p role="alert" className="sd-auth-error">
              {error}
            </p>
          )}
          <div className="sd-auth-actions">
            <button
              type="button"
              className="sd-button sd-secondary"
              disabled={pending}
              onClick={() => setEdit(null)}
            >
              取消
            </button>
            <button
              type="button"
              className="sd-button sd-primary"
              onClick={submitEdit}
              disabled={
                pending ||
                query.isError ||
                editStale ||
                (edit.kind === "roles" && !edit.roles.length)
              }
            >
              <Check size={14} />
              确认变更
            </button>
          </div>
        </Dialog>
      )}
    </>
  );
}

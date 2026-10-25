import {
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import "../workspace/workspace.css";
import "../workspace/desktop.css";
import "../workspace/islands.css";
const Workspace = lazy(() =>
  import("../workspace/Workspace").then((module) => ({
    default: module.Workspace,
  })),
);
import { identityClient, startEnterpriseLogin } from "./client";
import {
  AUTHORITY_CHANGED,
  configureSession,
  sessionGeneration,
} from "./transport";
import { identityScope, type MeView } from "./types";
import {
  AwaitingMembership,
  LoginScreen,
  SessionLoading,
  SessionUnavailable,
} from "./SessionScreens";

export function AuthGate() {
  const client = useQueryClient();
  const adoptedScope = useRef<string | null>(null);
  const [barrier, setBarrier] = useState(false);
  const [error, setError] = useState("");
  const [acceptingGeneration, setAcceptingGeneration] = useState<number | null>(
    null,
  );
  const accepting =
    acceptingGeneration !== null && acceptingGeneration === sessionGeneration();
  const [fatal, setFatal] = useState(false);
  const scoped = useCallback(async () => {
    await client.cancelQueries({
      predicate: (query) => query.queryKey[0] !== "me",
    });
    client.removeQueries({ predicate: (query) => query.queryKey[0] !== "me" });
  }, [client]);
  const adopt = useCallback(
    async (next: MeView, valid: () => boolean = () => true) => {
      if (!valid())
        throw new DOMException("Identity check cancelled", "AbortError");
      const scope = identityScope(next);
      if (scope !== adoptedScope.current) await scoped();
      if (!valid())
        throw new DOMException("Identity check cancelled", "AbortError");
      adoptedScope.current = scope;
      configureSession(next);
      return next;
    },
    [scoped],
  );
  const query = useQuery({
    queryKey: ["me"],
    queryFn: async ({ signal }) => {
      try {
        return await adopt(
          await identityClient.me(undefined, signal),
          () => !signal.aborted,
        );
      } catch (failure) {
        if (signal.aborted) throw failure;
        configureSession(null);
        await scoped();
        throw failure;
      }
    },
    retry: false,
    staleTime: 0,
    refetchOnWindowFocus: true,
  });
  const refresh = useCallback(async () => {
    setBarrier(true);
    setError("");
    setFatal(false);
    try {
      return await query.refetch();
    } finally {
      setBarrier(false);
    }
  }, [query.refetch]);
  useEffect(() => {
    const changed = () => {
      configureSession(null);
      setBarrier(true);
      void scoped().then(refresh);
    };
    window.addEventListener(AUTHORITY_CHANGED, changed);
    return () => window.removeEventListener(AUTHORITY_CHANGED, changed);
  }, [scoped, refresh]);
  async function switchLocalActor(actor: string) {
    if (
      query.data?.mode !== "LOCAL_DEMO" ||
      !query.data.capabilities.localRoleSwitch
    )
      return;
    localStorage.setItem("smartdata.localActor", actor);
    await refresh();
  }
  async function logout() {
    const snapshot = query.data;
    setBarrier(true);
    setError("");
    configureSession(null);
    await client.cancelQueries({ queryKey: ["me"] });
    await scoped();
    try {
      await identityClient.logout(snapshot);
      configureSession(null);
      await query.refetch();
      setBarrier(false);
    } catch (failure) {
      configureSession(null);
      setFatal(true);
      setError(
        failure instanceof Error
          ? failure.message
          : "退出结果尚不能确认，请重新检查身份。",
      );
      setBarrier(false);
    }
  }
  async function accept(id: string) {
    const invitation = query.data?.invitations.find(
      (item) =>
        item.id === id &&
        item.accountId === query.data.accountId &&
        item.status === "PENDING",
    );
    if (
      !invitation ||
      query.data?.membership?.status === "SUSPENDED" ||
      accepting
    )
      return;
    const generation = sessionGeneration();
    setAcceptingGeneration(generation);
    setError("");
    try {
      const next = await adopt(
        await identityClient.accept(id, invitation.stateVersion),
        () => generation === sessionGeneration(),
      );
      client.setQueryData(["me"], next);
    } catch (failure) {
      if (generation !== sessionGeneration()) return;
      setError(
        failure instanceof Error ? failure.message : "邀请未接受，请重新核对。",
      );
      await query.refetch();
    } finally {
      setAcceptingGeneration((current) =>
        current === generation ? null : current,
      );
    }
  }
  if (query.isPending || barrier) return <SessionLoading />;
  if (
    query.isError ||
    fatal ||
    !query.data ||
    (error && query.data.capabilities.canUseWorkspace)
  )
    return (
      <SessionUnavailable
        error={error || query.error?.message || "未取得可信身份信息。"}
        pending={query.isFetching}
        onRetry={() => {
          void refresh();
        }}
      />
    );
  const identity = query.data;
  if (!identity.authenticated)
    return (
      <LoginScreen
        pending={false}
        error={error}
        onLogin={() => {
          try {
            startEnterpriseLogin(identity);
          } catch (failure) {
            setError(
              failure instanceof Error ? failure.message : "登录入口不可用。",
            );
          }
        }}
      />
    );
  if (!identity.capabilities.canUseWorkspace)
    return (
      <AwaitingMembership
        accountId={identity.accountId!}
        displayName={identity.displayName || "企业账号"}
        suspended={identity.membership?.status === "SUSPENDED"}
        invitations={identity.invitations}
        pending={accepting || query.isFetching}
        error={error}
        onAccept={accept}
        onRefresh={() => {
          void refresh();
        }}
        onLogout={() => {
          void logout();
        }}
      />
    );
  return (
    <Suspense fallback={<SessionLoading />}>
      <Workspace
        key={identityScope(identity)}
        identity={identity}
        onIdentityRefresh={refresh}
        onLocalActorChange={switchLocalActor}
        onLogout={logout}
      />
    </Suspense>
  );
}

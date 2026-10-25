import { currentSession } from "../auth/transport";

// Rehearsal is enabled only by the current server identity response.
export function localRolesEnabled() {
  const me = currentSession();
  return me?.mode === "LOCAL_DEMO" && me.capabilities.localRoleSwitch;
}

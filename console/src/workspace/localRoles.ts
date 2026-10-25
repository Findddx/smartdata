// This flag enables local role rehearsal, not enterprise authentication.
export function localRolesEnabled() {
  return import.meta.env.DEV || import.meta.env.VITE_DEMO_AUTH === "true";
}

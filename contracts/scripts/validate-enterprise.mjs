import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const schema = JSON.parse(await readFile(resolve(root, "enterprise/enterprise-v1.schema.json"), "utf8"));
const ajv = new Ajv2020({ strict: true, allErrors: true });
addFormats(ajv); ajv.addSchema(schema);
const check = name => { const validate = ajv.getSchema(`${schema.$id}#/$defs/${name}`); assert.ok(validate); return validate; };
for (const name of Object.keys(schema.$defs)) check(name);

if (process.argv[2] === "--response") {
  const [path, name] = process.argv.slice(3);
  const validate = check(name);
  assert.ok(validate(JSON.parse(await readFile(path, "utf8"))), JSON.stringify(validate.errors));
  console.log(`Enterprise ${name} response passed`);
} else {
  const accountId = `acct_${"a".repeat(64)}`;
  const owner = `acct_${"b".repeat(64)}`;
  const membership = { status: "ACTIVE", stateVersion: 1 };
  const invitation = { id: "invite-1", accountId, roles: ["ORDER_SPECIALIST"], status: "PENDING", stateVersion: 1, createdBy: owner, expiresAt: "2026-10-26T12:00:00Z" };
  const me = { mode: "ENTERPRISE", authenticated: true, accountId, displayName: "合成员工", tenant: { id: "test-enterprise", displayName: "合成企业" }, membership,
    roles: ["ORDER_SPECIALIST"], capabilities: { canUseWorkspace: true, canManageMembers: false, localRoleSwitch: false }, loginUrl: "/oauth2/authorization/enterprise", logoutUrl: "/logout",
    csrf: { headerName: "X-CSRF-TOKEN", token: "test-only-csrf" }, invitations: [] };
  const accept = (name, value) => { const validate = check(name); assert.ok(validate(value), `${name}: ${JSON.stringify(validate.errors)}`); };
  let rejected = 0;
  const reject = (name, value, reason) => { assert.equal(check(name)(value), false, reason); rejected++; };
  accept("Me", me);
  accept("Me", { ...me, authenticated: false, accountId: null, displayName: null, membership: null, roles: [], capabilities: { ...me.capabilities, canUseWorkspace: false } });
  accept("Me", { ...me, membership: { ...membership, status: "SUSPENDED" }, capabilities: { ...me.capabilities, canUseWorkspace: false } });
  accept("Me", { ...me, mode: "LOCAL_DEMO", accountId: "admin", membership: null, roles: ["ADMIN"], capabilities: { ...me.capabilities, localRoleSwitch: true }, loginUrl: null, logoutUrl: null, csrf: null });
  const directory = { stateVersion: 2, members: [{ accountId: owner, displayName: "合成负责人", roles: ["ADMIN"], status: "ACTIVE", stateVersion: 1, updatedAt: "2026-10-25T12:00:00Z" }], invitations: [invitation] };
  accept("Directory", directory);
  const invite = { expectedStateVersion: 2, accountId, roles: ["ORDER_SPECIALIST"] };
  accept("InviteCommand", invite);
  accept("MemberRolesCommand", { expectedStateVersion: 2, expectedMembershipVersion: 1, roles: ["AUDITOR"] });
  accept("MemberStateCommand", { expectedStateVersion: 2, expectedMembershipVersion: 1 });
  accept("VersionCommand", { expectedStateVersion: 1 });
  reject("Me", { ...me, accessToken: "forbidden-in-browser-response" }, "token leaks");
  reject("Me", { ...me, issuer: "browser-selected" }, "uncontracted issuer");
  reject("Me", { ...me, roles: ["SUPERUSER"] }, "unknown role");
  reject("Me", { ...me, roles: ["ADMIN", "ADMIN"] }, "duplicate roles");
  reject("Me", { ...me, accountId: "subject-only" }, "unscoped enterprise account");
  reject("Me", { ...me, csrf: { ...me.csrf, headerName: "X-Tenant-Id" } }, "csrf forges tenancy header");
  reject("Me", { ...me, loginUrl: "https://external.invalid/login" }, "external login redirect");
  reject("Me", { ...me, logoutUrl: "//external.invalid/logout" }, "external logout redirect");
  reject("Me", { ...me, authenticated: false }, "anonymous access");
  reject("Me", { ...me, membership: null }, "workspace without membership");
  reject("Me", { ...me, roles: [] }, "workspace without roles");
  reject("Me", { ...me, tenant: null }, "workspace without enterprise");
  reject("Me", { ...me, membership: { ...membership, status: "SUSPENDED" } }, "suspended member retains workspace");
  reject("Me", { ...me, capabilities: { ...me.capabilities, localRoleSwitch: true } }, "enterprise demo switch");
  reject("Me", { ...me, capabilities: { ...me.capabilities, canManageMembers: true } }, "non-admin member manager");
  reject("InviteCommand", { ...invite, tenantId: "other-enterprise" }, "client chooses tenant");
  reject("InviteCommand", { ...invite, issuer: "other-issuer" }, "client chooses identity provider");
  reject("InviteCommand", { ...invite, roles: [] }, "empty invite roles");
  reject("InviteCommand", { ...invite, roles: ["ROOT"] }, "invented invite role");
  reject("InviteCommand", { ...invite, expectedStateVersion: -1 }, "negative CAS");
  reject("InviteCommand", { accountId, roles: ["ADMIN"] }, "missing directory CAS");
  reject("InviteCommand", { ...invite, accountId: "employee@example.invalid" }, "email substitutes account identity");
  reject("MemberRolesCommand", { expectedStateVersion: 2, roles: ["ADMIN"] }, "missing membership CAS");
  reject("MemberStateCommand", { expectedStateVersion: 2, expectedMembershipVersion: 1, status: "ACTIVE" }, "arbitrary member status field");
  reject("Directory", { ...directory, credential: "forbidden" }, "directory leaks credential");
  reject("Invitation", { ...invitation, status: "APPROVED" }, "unknown invitation lifecycle");
  reject("VersionCommand", { expectedStateVersion: 1, actorId: owner }, "client selects acting account");
  console.log(`Enterprise schema compiled ${Object.keys(schema.$defs).length} definitions; ${rejected} unsafe fixtures rejected.`);
}

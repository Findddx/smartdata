-- External accounts are registered only after OIDC validation. The server's
-- configured tenant supplies the RLS context; no HTTP tenant/role header does.
CREATE TABLE enterprise_account (
    tenant_id text NOT NULL REFERENCES tenant(id),
    account_id text NOT NULL CHECK (account_id ~ '^acct_[0-9a-f]{64}$'),
    issuer text NOT NULL,
    subject text NOT NULL,
    display_name text NOT NULL,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,account_id),
    UNIQUE (tenant_id,issuer,subject)
);
CREATE TABLE enterprise_directory (
    tenant_id text PRIMARY KEY REFERENCES tenant(id),
    state_version bigint NOT NULL DEFAULT 0 CHECK (state_version>=0)
);
CREATE TABLE enterprise_member (
    tenant_id text NOT NULL,
    account_id text NOT NULL,
    roles text[] NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED')),
    state_version bigint NOT NULL CHECK (state_version>=1),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,account_id),
    FOREIGN KEY (tenant_id,account_id) REFERENCES enterprise_account(tenant_id,account_id),
    CHECK (cardinality(roles) BETWEEN 1 AND 5),
    CHECK (roles <@ ARRAY['ORDER_SPECIALIST','WAREHOUSE_APPROVER','SALES_OWNER','AUDITOR','ADMIN']::text[])
);
CREATE TABLE enterprise_invitation (
    tenant_id text NOT NULL REFERENCES tenant(id),
    id uuid NOT NULL,
    account_id text NOT NULL CHECK (account_id ~ '^acct_[0-9a-f]{64}$'),
    roles text[] NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING','ACCEPTED','REVOKED','EXPIRED')),
    state_version bigint NOT NULL CHECK (state_version>=1),
    created_by text NOT NULL,
    inviter_membership_version bigint NOT NULL CHECK (inviter_membership_version>=1),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES enterprise_member(tenant_id,account_id),
    CHECK (cardinality(roles) BETWEEN 1 AND 5),
    CHECK (roles <@ ARRAY['ORDER_SPECIALIST','WAREHOUSE_APPROVER','SALES_OWNER','AUDITOR','ADMIN']::text[])
);
CREATE UNIQUE INDEX enterprise_invitation_one_pending ON enterprise_invitation(tenant_id,account_id) WHERE status='PENDING';
CREATE TABLE enterprise_command (
    tenant_id text NOT NULL REFERENCES tenant(id),
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
    account_id text NOT NULL,
    fingerprint text NOT NULL CHECK (fingerprint ~ '^v2:[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,idempotency_key)
);
CREATE TRIGGER enterprise_command_immutable BEFORE UPDATE OR DELETE ON enterprise_command
    FOR EACH ROW EXECUTE FUNCTION smartdata_reject_fact_mutation();
DO $enterprise_rls$
DECLARE name text;
BEGIN
    FOREACH name IN ARRAY ARRAY['enterprise_account','enterprise_directory','enterprise_member','enterprise_invitation','enterprise_command'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_setting(''app.tenant_id'',true)) WITH CHECK (tenant_id = current_setting(''app.tenant_id'',true))',name);
    END LOOP;
END $enterprise_rls$;

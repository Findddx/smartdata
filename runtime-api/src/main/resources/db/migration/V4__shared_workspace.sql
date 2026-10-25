-- Additive enterprise workspace storage. Existing V1/V2 and local files are untouched.
-- One bounded workspace per verified tenant. Payload is a separately protected,
-- <=1 MiB immutable snapshot, not a whole-workspace blob or an object-store claim.

CREATE TABLE workspace_head (
    tenant_id text PRIMARY KEY REFERENCES tenant(id),
    state_version bigint NOT NULL DEFAULT 0 CHECK(state_version>=0),
    format_version integer NOT NULL DEFAULT 1 CHECK(format_version=1)
);

CREATE TABLE workspace_snapshot_payload (
    tenant_id text NOT NULL REFERENCES tenant(id),
    sha256 char(64) NOT NULL,
    canonical_json text NOT NULL CHECK(octet_length(canonical_json)<=1048576 AND jsonb_typeof(canonical_json::jsonb)='array'),
    PRIMARY KEY(tenant_id,sha256)
);

CREATE TABLE workspace_source (
    tenant_id text NOT NULL REFERENCES tenant(id),
    id text NOT NULL,
    current_revision bigint NOT NULL,
    ordinal integer NOT NULL,
    PRIMARY KEY(tenant_id,id)
);

CREATE TABLE workspace_source_revision (
    tenant_id text NOT NULL,
    source_id text NOT NULL,
    revision bigint NOT NULL,
    name text NOT NULL,
    kind text NOT NULL,
    provenance text NOT NULL CHECK(provenance='USER'),
    endpoint text,
    credential_env_ref text,
    username_env_ref text,
    payload_sha256 char(64) NOT NULL,
    observed_at text,
    primary_key_column text,
    freshness_column text,
    freshness_max_hours bigint NOT NULL,
    source_schema text NOT NULL,
    status text NOT NULL,
    last_scanned_at text,
    last_error text,
    PRIMARY KEY(tenant_id,source_id,revision),
    FOREIGN KEY(tenant_id,source_id) REFERENCES workspace_source(tenant_id,id),
    FOREIGN KEY(tenant_id,payload_sha256) REFERENCES workspace_snapshot_payload(tenant_id,sha256)
);

CREATE TABLE workspace_scan (
    tenant_id text NOT NULL,
    id text NOT NULL,
    source_id text NOT NULL,
    ordinal integer NOT NULL,
    provenance text NOT NULL,
    status text NOT NULL,
    started_at text NOT NULL,
    completed_at text NOT NULL,
    asset_ids text[] NOT NULL,
    finding_ids text[] NOT NULL,
    method text NOT NULL,
    error text,
    PRIMARY KEY(tenant_id,id),
    FOREIGN KEY(tenant_id,source_id) REFERENCES workspace_source(tenant_id,id)
);

CREATE TABLE workspace_asset_revision (
    tenant_id text NOT NULL,
    id text NOT NULL,
    scan_id text NOT NULL,
    source_id text NOT NULL,
    name text NOT NULL,
    provenance text NOT NULL,
    row_count integer NOT NULL,
    total_rows bigint,
    sample_limit integer NOT NULL,
    sampled boolean NOT NULL,
    observed_at text NOT NULL,
    evidence_sha256 char(64) NOT NULL,
    sampling text NOT NULL,
    content_fingerprint char(64) NOT NULL,
    PRIMARY KEY(tenant_id,id,scan_id),
    FOREIGN KEY(tenant_id,scan_id) REFERENCES workspace_scan(tenant_id,id),
    FOREIGN KEY(tenant_id,source_id) REFERENCES workspace_source(tenant_id,id)
);

CREATE TABLE workspace_asset_column (
    tenant_id text NOT NULL,
    asset_id text NOT NULL,
    scan_id text NOT NULL,
    ordinal integer NOT NULL,
    name text NOT NULL,
    inferred_type text NOT NULL,
    missing_count integer NOT NULL,
    distinct_count integer NOT NULL,
    PRIMARY KEY(tenant_id,asset_id,scan_id,ordinal),
    FOREIGN KEY(tenant_id,asset_id,scan_id) REFERENCES workspace_asset_revision(tenant_id,id,scan_id)
);

CREATE TABLE workspace_asset (
    tenant_id text NOT NULL,
    id text NOT NULL,
    scan_id text NOT NULL,
    ordinal integer NOT NULL,
    PRIMARY KEY(tenant_id,id),
    FOREIGN KEY(tenant_id,id,scan_id) REFERENCES workspace_asset_revision(tenant_id,id,scan_id)
);

CREATE TABLE workspace_finding (
    tenant_id text NOT NULL,
    id text NOT NULL,
    source_id text NOT NULL,
    asset_id text NOT NULL,
    scan_id text NOT NULL,
    ordinal integer NOT NULL,
    provenance text NOT NULL,
    rule_id text NOT NULL,
    title text NOT NULL,
    severity text NOT NULL,
    column_name text,
    observed text NOT NULL,
    expected text NOT NULL,
    evidence_json jsonb NOT NULL,
    suggestion text NOT NULL,
    active boolean NOT NULL,
    PRIMARY KEY(tenant_id,id),
    FOREIGN KEY(tenant_id,asset_id,scan_id) REFERENCES workspace_asset_revision(tenant_id,id,scan_id),
    FOREIGN KEY(tenant_id,source_id) REFERENCES workspace_source(tenant_id,id)
);

CREATE TABLE workspace_assistant (
    tenant_id text NOT NULL REFERENCES tenant(id),
    id text NOT NULL,
    ordinal integer NOT NULL,
    role text NOT NULL,
    name text NOT NULL,
    responsibility text NOT NULL,
    status text NOT NULL,
    method text NOT NULL,
    finding_ids text[] NOT NULL,
    task_ids text[] NOT NULL,
    focus_goal_ids text[] NOT NULL,
    scope_source_ids text[] NOT NULL,
    scope_notice text NOT NULL,
    PRIMARY KEY(tenant_id,id)
);

CREATE TABLE workspace_task (
    tenant_id text NOT NULL REFERENCES tenant(id),
    id text NOT NULL,
    current_revision bigint NOT NULL,
    ordinal integer NOT NULL,
    PRIMARY KEY(tenant_id,id)
);

CREATE TABLE workspace_task_revision (
    tenant_id text NOT NULL,
    task_id text NOT NULL,
    commit_version bigint NOT NULL,
    assistant_id text NOT NULL,
    finding_id text NOT NULL,
    title text NOT NULL,
    description text NOT NULL,
    status text NOT NULL,
    owner_id text,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    completion_note text,
    evidence_refs text[] NOT NULL,
    state_version bigint NOT NULL,
    verified_by text,
    goal_ids text[] NOT NULL,
    in_current_scope boolean NOT NULL,
    scope_reason text NOT NULL,
    business_brief_version bigint,
    created_under_brief_version bigint,
    priority_rank integer NOT NULL,
    priority_reason text NOT NULL,
    current_finding_id text,
    CHECK(status <> 'VERIFIED' OR (owner_id IS NOT NULL AND verified_by IS NOT NULL AND owner_id <> verified_by)),
    PRIMARY KEY(tenant_id,task_id,commit_version),
    FOREIGN KEY(tenant_id,task_id) REFERENCES workspace_task(tenant_id,id),
    FOREIGN KEY(tenant_id,assistant_id) REFERENCES workspace_assistant(tenant_id,id),
    FOREIGN KEY(tenant_id,finding_id) REFERENCES workspace_finding(tenant_id,id)
);

CREATE TABLE workspace_comment (
    tenant_id text NOT NULL,
    id text NOT NULL,
    ordinal integer NOT NULL,
    task_id text NOT NULL,
    actor_id text NOT NULL,
    body text NOT NULL,
    evidence_refs text[] NOT NULL,
    created_at text NOT NULL,
    PRIMARY KEY(tenant_id,id),
    FOREIGN KEY(tenant_id,task_id) REFERENCES workspace_task(tenant_id,id)
);

CREATE TABLE workspace_message (
    tenant_id text NOT NULL,
    id text NOT NULL,
    ordinal integer NOT NULL,
    assistant_id text NOT NULL,
    actor_id text NOT NULL,
    question text NOT NULL,
    answer text NOT NULL,
    evidence_refs text[] NOT NULL,
    created_at text NOT NULL,
    method text NOT NULL,
    status text NOT NULL,
    uncertainty text[] NOT NULL,
    error_code text,
    trace_json jsonb,
    PRIMARY KEY(tenant_id,id),
    FOREIGN KEY(tenant_id,assistant_id) REFERENCES workspace_assistant(tenant_id,id)
);

CREATE TABLE workspace_audit (
    tenant_id text NOT NULL REFERENCES tenant(id),
    id text NOT NULL,
    ordinal integer NOT NULL,
    actor_id text NOT NULL,
    event text NOT NULL,
    resource_id text NOT NULL,
    occurred_at text NOT NULL,
    state_version bigint NOT NULL,
    PRIMARY KEY(tenant_id,id)
);

CREATE TABLE workspace_command (
    tenant_id text NOT NULL REFERENCES tenant(id),
    command_id char(64) NOT NULL,
    fingerprint text NOT NULL,
    result_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,command_id)
);

CREATE TABLE workspace_operation (
    tenant_id text NOT NULL REFERENCES tenant(id),
    id uuid NOT NULL,
    command_id char(64) NOT NULL,
    actor_id text NOT NULL,
    authorization_version bigint NOT NULL,
    fingerprint text NOT NULL,
    kind text NOT NULL CHECK(kind IN ('SOURCE_SCAN','MODEL_REASONING')),
    status text NOT NULL CHECK(status IN ('RUNNING','COMPLETED','UNKNOWN')),
    lease_token uuid NOT NULL,
    lease_until timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,id),
    UNIQUE(tenant_id,command_id)
);

CREATE TABLE workspace_brief_revision (
    tenant_id text NOT NULL REFERENCES tenant(id),
    version bigint NOT NULL,
    enterprise_name text NOT NULL,
    business_description text NOT NULL,
    included_source_ids text[] NOT NULL,
    updated_at text NOT NULL,
    updated_by text NOT NULL,
    model_context_export_enabled boolean NOT NULL DEFAULT false CHECK(NOT model_context_export_enabled),
    PRIMARY KEY(tenant_id,version)
);

CREATE TABLE workspace_goal (
    tenant_id text NOT NULL,
    brief_version bigint NOT NULL,
    id text NOT NULL,
    ordinal integer NOT NULL,
    title text NOT NULL,
    type text NOT NULL,
    expected_outcome text NOT NULL,
    priority text NOT NULL,
    source_ids text[] NOT NULL,
    PRIMARY KEY(tenant_id,brief_version,id),
    FOREIGN KEY(tenant_id,brief_version) REFERENCES workspace_brief_revision(tenant_id,version)
);

CREATE TABLE workspace_metric (
    tenant_id text NOT NULL,
    brief_version bigint NOT NULL,
    id text NOT NULL,
    ordinal integer NOT NULL,
    name text NOT NULL,
    business_definition text NOT NULL,
    owner text NOT NULL,
    time_zone text NOT NULL,
    currency text,
    formula_description text NOT NULL,
    source_ids text[] NOT NULL,
    status text NOT NULL,
    state_version bigint NOT NULL,
    confirmed_by text,
    confirmed_at text,
    calculation_status text NOT NULL CHECK(calculation_status='NOT_COMPUTED'),
    PRIMARY KEY(tenant_id,brief_version,id),
    FOREIGN KEY(tenant_id,brief_version) REFERENCES workspace_brief_revision(tenant_id,version)
);

CREATE TABLE workspace_mapping_revision (
    tenant_id text NOT NULL REFERENCES tenant(id),
    version bigint NOT NULL,
    updated_by text,
    updated_at text,
    PRIMARY KEY(tenant_id,version)
);

CREATE TABLE workspace_entity_mapping (
    tenant_id text NOT NULL,
    mapping_version bigint NOT NULL,
    id text NOT NULL,
    ordinal integer NOT NULL,
    name text NOT NULL,
    entity_type text NOT NULL,
    transformation_description text NOT NULL,
    PRIMARY KEY(tenant_id,mapping_version,id),
    FOREIGN KEY(tenant_id,mapping_version) REFERENCES workspace_mapping_revision(tenant_id,version)
);

CREATE TABLE workspace_metric_mapping (
    tenant_id text NOT NULL,
    mapping_version bigint NOT NULL,
    metric_id text NOT NULL,
    ordinal integer NOT NULL,
    definition_version bigint NOT NULL,
    input_entity_ids text[] NOT NULL,
    transformation_description text NOT NULL,
    PRIMARY KEY(tenant_id,mapping_version,metric_id),
    FOREIGN KEY(tenant_id,mapping_version) REFERENCES workspace_mapping_revision(tenant_id,version)
);

CREATE TABLE workspace_decision_mapping (
    tenant_id text NOT NULL,
    mapping_version bigint NOT NULL,
    goal_id text NOT NULL,
    ordinal integer NOT NULL,
    input_metric_ids text[] NOT NULL,
    decision_question text NOT NULL,
    PRIMARY KEY(tenant_id,mapping_version,goal_id),
    FOREIGN KEY(tenant_id,mapping_version) REFERENCES workspace_mapping_revision(tenant_id,version)
);

CREATE TABLE workspace_mapping_field (
    tenant_id text NOT NULL,
    mapping_version bigint NOT NULL,
    owner_type text NOT NULL CHECK(owner_type IN ('ENTITY','METRIC')),
    owner_id text NOT NULL,
    ordinal integer NOT NULL,
    source_id text NOT NULL,
    asset_id text NOT NULL,
    field_name text NOT NULL,
    PRIMARY KEY(tenant_id,mapping_version,owner_type,owner_id,ordinal),
    FOREIGN KEY(tenant_id,mapping_version) REFERENCES workspace_mapping_revision(tenant_id,version)
);

ALTER TABLE workspace_source ADD CONSTRAINT source_current_revision_fk FOREIGN KEY(tenant_id,id,current_revision)
    REFERENCES workspace_source_revision(tenant_id,source_id,revision) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE workspace_task ADD CONSTRAINT task_current_revision_fk FOREIGN KEY(tenant_id,id,current_revision)
    REFERENCES workspace_task_revision(tenant_id,task_id,commit_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE workspace_mapping_field ADD CONSTRAINT mapping_source_fk FOREIGN KEY(tenant_id,source_id)
    REFERENCES workspace_source(tenant_id,id);
ALTER TABLE workspace_scan ADD CONSTRAINT workspace_scan_source_identity UNIQUE(tenant_id,id,source_id);
ALTER TABLE workspace_asset_revision ADD CONSTRAINT workspace_asset_scan_source_fk FOREIGN KEY(tenant_id,scan_id,source_id)
    REFERENCES workspace_scan(tenant_id,id,source_id);
ALTER TABLE workspace_asset_revision ADD CONSTRAINT workspace_asset_source_identity UNIQUE(tenant_id,id,scan_id,source_id);
ALTER TABLE workspace_finding ADD CONSTRAINT workspace_finding_source_identity_fk FOREIGN KEY(tenant_id,asset_id,scan_id,source_id)
    REFERENCES workspace_asset_revision(tenant_id,id,scan_id,source_id);

CREATE INDEX workspace_scan_source_idx ON workspace_scan(tenant_id,source_id,ordinal);
CREATE INDEX workspace_operation_pending_idx ON workspace_operation(tenant_id,status,lease_until);

ALTER TABLE workspace_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_head FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_head USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_snapshot_payload ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_snapshot_payload FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_snapshot_payload USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_source ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_source FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_source USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_source_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_source_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_source_revision USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_scan ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_scan FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_scan USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_asset_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_asset_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_asset_revision USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_asset_column ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_asset_column FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_asset_column USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_asset ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_asset FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_asset USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_finding ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_finding FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_finding USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_assistant ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_assistant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_assistant USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_task FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_task USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_task_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_task_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_task_revision USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_comment ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_comment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_comment USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_message FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_message USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_audit USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_command ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_command FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_command USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_operation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_operation USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_brief_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_brief_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_brief_revision USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_goal ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_goal FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_goal USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_metric ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_metric FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_metric USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_mapping_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_mapping_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_mapping_revision USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_entity_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_entity_mapping FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_entity_mapping USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_metric_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_metric_mapping FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_metric_mapping USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_decision_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_decision_mapping FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_decision_mapping USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

ALTER TABLE workspace_mapping_field ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_mapping_field FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_mapping_field USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));

CREATE FUNCTION workspace_reject_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Workspace history is immutable' USING ERRCODE='23514'; END $$;
CREATE FUNCTION workspace_protect_finding_fact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Finding history is immutable' USING ERRCODE='23514'; END IF;
    IF (to_jsonb(NEW)-'active') IS DISTINCT FROM (to_jsonb(OLD)-'active') THEN
        RAISE EXCEPTION 'Only the finding active projection can change' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_finding_fact BEFORE UPDATE OR DELETE ON workspace_finding FOR EACH ROW EXECUTE FUNCTION workspace_protect_finding_fact();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_snapshot_payload FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_source_revision FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_scan FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_asset_revision FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_asset_column FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_task_revision FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_comment FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_message FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_audit FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_command FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_brief_revision FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_goal FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_metric FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_mapping_revision FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_entity_mapping FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_metric_mapping FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_decision_mapping FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON workspace_mapping_field FOR EACH ROW EXECUTE FUNCTION workspace_reject_history_change();

-- Expand only: retain every V1 UUID primary/foreign key and legacy column.
-- Public/domain IDs may be non-UUID strings. Old writers get id::text from a
-- trigger; new writers explicitly supply business_id. No table/type rewrite.
-- The controlled migration principal must see legacy rows behind FORCE RLS;
-- the runtime principal is separately required to have neither privilege.
DO $migration_role$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls)) THEN
        RAISE EXCEPTION 'V2 requires a dedicated privileged migration principal; never use the runtime principal';
    END IF;
END
$migration_role$;

CREATE FUNCTION smartdata_fill_business_id() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.business_id IS DISTINCT FROM NEW.business_id THEN
        RAISE EXCEPTION 'business_id is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.business_id IS NULL THEN NEW.business_id := NEW.id::text; END IF;
    RETURN NEW;
END
$body$;

DO $expand$
DECLARE name text;
BEGIN
    FOREACH name IN ARRAY ARRAY['evidence_ref','claim','exception_case','knowledge_gap',
        'recommendation','action_request','approval_decision','action_receipt',
        'verification_result','memory_asset','audit_event'] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN business_id text', name);
        EXECUTE format('UPDATE %I SET business_id = id::text', name);
        EXECUTE format('ALTER TABLE %I ALTER COLUMN business_id SET NOT NULL', name);
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (tenant_id, business_id)', name, name || '_business_id_unique');
        EXECUTE format('CREATE TRIGGER fill_business_id BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION smartdata_fill_business_id()', name);
    END LOOP;
END
$expand$;

-- V1 did not retain the case-to-claim relation. Never guess this association
-- from order numbers. Existing cases stay fail-closed until explicitly imported
-- with their evidence context; all their original rows remain intact.
ALTER TABLE exception_case ADD COLUMN context_complete boolean NOT NULL DEFAULT false;

-- The V1 four-decimal projection must never round 0.94999 up to the kernel's
-- 0.95 trust threshold. Preserve the exact domain score in an expanded column.
ALTER TABLE claim ADD COLUMN confidence_exact double precision;
UPDATE claim SET confidence_exact=confidence::double precision;
ALTER TABLE claim ALTER COLUMN confidence_exact SET NOT NULL;
ALTER TABLE claim ADD CONSTRAINT claim_confidence_exact_range CHECK(confidence_exact BETWEEN 0 AND 1);
CREATE FUNCTION smartdata_fill_confidence() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
    IF NEW.confidence_exact IS NULL OR (TG_OP='UPDATE' AND NEW.confidence IS DISTINCT FROM OLD.confidence AND NEW.confidence_exact IS NOT DISTINCT FROM OLD.confidence_exact) THEN
        NEW.confidence_exact:=NEW.confidence::double precision;
    END IF;
    RETURN NEW;
END
$body$;
CREATE TRIGGER fill_confidence BEFORE INSERT OR UPDATE ON claim FOR EACH ROW EXECUTE FUNCTION smartdata_fill_confidence();
ALTER TABLE claim ADD COLUMN latest_revision bigint NOT NULL DEFAULT 0 CHECK(latest_revision>=0);

CREATE TABLE case_claim_link (
    tenant_id text NOT NULL,
    case_id uuid NOT NULL,
    case_state_version bigint NOT NULL CHECK (case_state_version >= 0),
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    claim_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, case_id, case_state_version, ordinal),
    UNIQUE (tenant_id, case_id, case_state_version, claim_id),
    FOREIGN KEY (tenant_id, case_id) REFERENCES exception_case(tenant_id,id),
    FOREIGN KEY (tenant_id, claim_id) REFERENCES claim(tenant_id,id)
);
CREATE TABLE case_gap_link (
    tenant_id text NOT NULL,
    case_id uuid NOT NULL,
    case_state_version bigint NOT NULL CHECK (case_state_version >= 0),
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    gap_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, case_id, case_state_version, ordinal),
    UNIQUE (tenant_id, case_id, case_state_version, gap_id),
    FOREIGN KEY (tenant_id, case_id) REFERENCES exception_case(tenant_id,id),
    FOREIGN KEY (tenant_id, gap_id) REFERENCES knowledge_gap(tenant_id,id)
);
CREATE TABLE claim_evidence_link (
    tenant_id text NOT NULL,
    claim_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    evidence_id uuid NOT NULL,
    PRIMARY KEY (tenant_id,claim_id,ordinal),
    UNIQUE (tenant_id,claim_id,evidence_id),
    FOREIGN KEY (tenant_id,claim_id) REFERENCES claim(tenant_id,id),
    FOREIGN KEY (tenant_id,evidence_id) REFERENCES evidence_ref(tenant_id,id)
);
CREATE TABLE claim_revision (
    tenant_id text NOT NULL, claim_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>=0),
    subject_type text NOT NULL, subject_id text NOT NULL, predicate text NOT NULL, value_json jsonb NOT NULL,
    status text NOT NULL CHECK(status IN ('ASSERTED','INFERRED','CONFLICTED','REVOKED')),
    confidence_exact double precision NOT NULL CHECK(confidence_exact BETWEEN 0 AND 1),
    valid_from timestamptz NOT NULL, valid_to timestamptz, recorded_at timestamptz NOT NULL, expires_at timestamptz,
    classification text NOT NULL CHECK(classification IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
    acl_ref text NOT NULL, derivation_version text, model_version text,
    PRIMARY KEY(tenant_id,claim_id,revision),
    FOREIGN KEY(tenant_id,claim_id) REFERENCES claim(tenant_id,id),
    CHECK(valid_to IS NULL OR valid_to>=valid_from)
);
CREATE TABLE claim_revision_evidence_link (
    tenant_id text NOT NULL, claim_id uuid NOT NULL, revision bigint NOT NULL,
    ordinal integer NOT NULL CHECK(ordinal>=0), evidence_id uuid NOT NULL,
    PRIMARY KEY(tenant_id,claim_id,revision,ordinal),
    UNIQUE(tenant_id,claim_id,revision,evidence_id),
    FOREIGN KEY(tenant_id,claim_id,revision) REFERENCES claim_revision(tenant_id,claim_id,revision),
    FOREIGN KEY(tenant_id,evidence_id) REFERENCES evidence_ref(tenant_id,id)
);
ALTER TABLE case_claim_link ADD COLUMN claim_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE case_claim_link ADD FOREIGN KEY(tenant_id,claim_id,claim_revision) REFERENCES claim_revision(tenant_id,claim_id,revision);
CREATE TABLE recommendation_evidence_link (
    tenant_id text NOT NULL, recommendation_id uuid NOT NULL, evidence_id uuid NOT NULL,
    PRIMARY KEY(tenant_id,recommendation_id,evidence_id),
    FOREIGN KEY(tenant_id,recommendation_id) REFERENCES recommendation(tenant_id,id),
    FOREIGN KEY(tenant_id,evidence_id) REFERENCES evidence_ref(tenant_id,id)
);
CREATE TABLE action_evidence_link (
    tenant_id text NOT NULL, action_request_id uuid NOT NULL, evidence_id uuid NOT NULL,
    PRIMARY KEY(tenant_id,action_request_id,evidence_id),
    FOREIGN KEY(tenant_id,action_request_id) REFERENCES action_request(tenant_id,id),
    FOREIGN KEY(tenant_id,evidence_id) REFERENCES evidence_ref(tenant_id,id)
);
CREATE TABLE receipt_attempt (
    tenant_id text NOT NULL, receipt_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    started_at timestamptz NOT NULL, completed_at timestamptz NOT NULL,
    outcome text NOT NULL CHECK(outcome IN ('SUCCEEDED','FAILED','UNKNOWN','COMPENSATED')),
    message_zh text NOT NULL,
    PRIMARY KEY(tenant_id,receipt_id,ordinal),
    FOREIGN KEY(tenant_id,receipt_id) REFERENCES action_receipt(tenant_id,id),
    CHECK(completed_at >= started_at)
);
ALTER TABLE verification_result ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK(revision >= 0);
-- The V1 result remains the latest projection. Repeated reconciliation retains
-- every independent observation, including an earlier INCONCLUSIVE outcome.
CREATE TABLE verification_revision (
    tenant_id text NOT NULL, verification_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision >= 0),
    verifier_id text NOT NULL, independent_source text NOT NULL,
    outcome text NOT NULL CHECK(outcome IN ('PASSED','FAILED','INCONCLUSIVE')),
    observed_from timestamptz NOT NULL, observed_until timestamptz NOT NULL, created_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,verification_id,revision),
    FOREIGN KEY(tenant_id,verification_id) REFERENCES verification_result(tenant_id,id),
    CHECK(observed_until >= observed_from)
);
CREATE TABLE verification_assertion (
    tenant_id text NOT NULL, verification_id uuid NOT NULL, revision bigint NOT NULL,
    ordinal integer NOT NULL CHECK(ordinal >= 0), name text NOT NULL,
    expected text NOT NULL, observed text NOT NULL, passed boolean NOT NULL,
    PRIMARY KEY(tenant_id,verification_id,revision,ordinal),
    FOREIGN KEY(tenant_id,verification_id,revision) REFERENCES verification_revision(tenant_id,verification_id,revision)
);
CREATE TABLE verification_evidence_link (
    tenant_id text NOT NULL, verification_id uuid NOT NULL, revision bigint NOT NULL,
    ordinal integer NOT NULL CHECK(ordinal >= 0), evidence_id uuid NOT NULL,
    PRIMARY KEY(tenant_id,verification_id,revision,ordinal),
    FOREIGN KEY(tenant_id,verification_id,revision) REFERENCES verification_revision(tenant_id,verification_id,revision),
    FOREIGN KEY(tenant_id,evidence_id) REFERENCES evidence_ref(tenant_id,id)
);
CREATE TABLE verification_gap_link (
    tenant_id text NOT NULL, verification_id uuid NOT NULL, revision bigint NOT NULL, gap_id uuid NOT NULL,
    PRIMARY KEY(tenant_id,verification_id,revision,gap_id),
    FOREIGN KEY(tenant_id,verification_id,revision) REFERENCES verification_revision(tenant_id,verification_id,revision),
    FOREIGN KEY(tenant_id,gap_id) REFERENCES knowledge_gap(tenant_id,id)
);
CREATE TABLE verification_metric (
    tenant_id text NOT NULL, verification_id uuid NOT NULL, revision bigint NOT NULL,
    name text NOT NULL, value double precision NOT NULL,
    PRIMARY KEY(tenant_id,verification_id,revision,name),
    FOREIGN KEY(tenant_id,verification_id,revision) REFERENCES verification_revision(tenant_id,verification_id,revision),
    CHECK(value NOT IN ('Infinity'::float8,'-Infinity'::float8,'NaN'::float8))
);
CREATE TABLE memory_case_link (
    tenant_id text NOT NULL, memory_id uuid NOT NULL, case_id uuid NOT NULL,
    PRIMARY KEY(tenant_id,memory_id,case_id),
    FOREIGN KEY(tenant_id,memory_id) REFERENCES memory_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,case_id) REFERENCES exception_case(tenant_id,id)
);
CREATE TABLE memory_evidence_link (
    tenant_id text NOT NULL, memory_id uuid NOT NULL, evidence_id uuid NOT NULL,
    PRIMARY KEY(tenant_id,memory_id,evidence_id),
    FOREIGN KEY(tenant_id,memory_id) REFERENCES memory_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,evidence_id) REFERENCES evidence_ref(tenant_id,id)
);

-- Preserve all unambiguous V1 array/document relationships as normalized rows.
-- Invalid legacy references fail migration instead of silently losing evidence.
INSERT INTO claim_evidence_link
SELECT c.tenant_id,c.id,(u.ordinality-1)::integer,u.id FROM claim c
CROSS JOIN LATERAL unnest(c.evidence_ids) WITH ORDINALITY AS u(id,ordinality);
INSERT INTO claim_revision
SELECT tenant_id,id,0,subject_type,subject_id,predicate,value_json,status,confidence_exact,
       valid_from,valid_to,recorded_at,expires_at,classification,acl_ref,derivation_version,model_version FROM claim;
INSERT INTO claim_revision_evidence_link SELECT tenant_id,claim_id,0,ordinal,evidence_id FROM claim_evidence_link;
INSERT INTO case_gap_link
SELECT g.tenant_id,g.case_id,c.state_version,
       (row_number() OVER(PARTITION BY g.tenant_id,g.case_id ORDER BY g.id)-1)::integer,g.id
FROM knowledge_gap g JOIN exception_case c ON c.tenant_id=g.tenant_id AND c.id=g.case_id;
INSERT INTO recommendation_evidence_link SELECT r.tenant_id,r.id,u.id FROM recommendation r CROSS JOIN LATERAL unnest(r.evidence_ids) AS u(id);
INSERT INTO action_evidence_link SELECT a.tenant_id,a.id,u.id FROM action_request a CROSS JOIN LATERAL unnest(a.evidence_ids) AS u(id);
INSERT INTO memory_case_link SELECT m.tenant_id,m.id,u.id FROM memory_asset m CROSS JOIN LATERAL unnest(m.source_case_ids) AS u(id);
INSERT INTO memory_evidence_link SELECT m.tenant_id,m.id,u.id FROM memory_asset m CROSS JOIN LATERAL unnest(m.source_evidence_ids) AS u(id);
INSERT INTO receipt_attempt
SELECT r.tenant_id,r.id,(u.ordinality-1)::integer,(u.item->>'number')::integer,
       (u.item->>'startedAt')::timestamptz,(u.item->>'completedAt')::timestamptz,
       u.item->>'outcome',u.item->>'messageZh'
FROM action_receipt r CROSS JOIN LATERAL jsonb_array_elements(r.attempts_json) WITH ORDINALITY AS u(item,ordinality);
INSERT INTO verification_revision
SELECT tenant_id,id,0,verifier_id,independent_source,outcome,observed_from,observed_until,created_at FROM verification_result;
INSERT INTO verification_assertion
SELECT v.tenant_id,v.id,0,(u.ordinality-1)::integer,u.item->>'name',u.item->>'expected',u.item->>'observed',(u.item->>'passed')::boolean
FROM verification_result v CROSS JOIN LATERAL jsonb_array_elements(v.assertions_json) WITH ORDINALITY AS u(item,ordinality);
INSERT INTO verification_evidence_link
SELECT v.tenant_id,v.id,0,(u.ordinality-1)::integer,u.id FROM verification_result v CROSS JOIN LATERAL unnest(v.evidence_ids) WITH ORDINALITY AS u(id,ordinality);
INSERT INTO verification_gap_link SELECT v.tenant_id,v.id,0,u.id FROM verification_result v CROSS JOIN LATERAL unnest(v.resolved_gap_ids) AS u(id);
INSERT INTO verification_metric SELECT v.tenant_id,v.id,0,u.key,u.value::float8 FROM verification_result v CROSS JOIN LATERAL jsonb_each_text(v.anti_gaming_metrics) AS u;

ALTER TABLE audit_event ADD COLUMN case_business_id text;
UPDATE audit_event SET case_business_id=details_json->>'caseId';
CREATE INDEX audit_case_timeline_idx ON audit_event(tenant_id,case_business_id,occurred_at,id);
ALTER TABLE audit_event ADD COLUMN sequence_no bigint;
WITH ordered AS (
    SELECT tenant_id,id,row_number() OVER(PARTITION BY tenant_id,aggregate_type,aggregate_id ORDER BY occurred_at,id) AS n FROM audit_event
) UPDATE audit_event a SET sequence_no=o.n FROM ordered o WHERE a.tenant_id=o.tenant_id AND a.id=o.id;
CREATE UNIQUE INDEX audit_chain_sequence_idx ON audit_event(tenant_id,aggregate_type,aggregate_id,sequence_no);

ALTER TABLE outbox_event ADD COLUMN lease_owner text;
ALTER TABLE outbox_event ADD COLUMN lease_token uuid;
ALTER TABLE outbox_event ADD COLUMN lease_until timestamptz;
CREATE INDEX outbox_tenant_pending_idx ON outbox_event(tenant_id,occurred_at,event_id) WHERE published_at IS NULL;

-- Keep the V1 digest column for N-1 writers; the version is independent data,
-- not an overlong prefix silently truncated by char(64).
ALTER TABLE command_deduplication ADD COLUMN fingerprint_version text NOT NULL DEFAULT 'LEGACY_V1'
    CHECK(fingerprint_version IN ('LEGACY_V1','HTTP_V2'));

DO $rls$
DECLARE name text;
BEGIN
    FOREACH name IN ARRAY ARRAY['case_claim_link','case_gap_link','claim_evidence_link','claim_revision','claim_revision_evidence_link',
        'recommendation_evidence_link','action_evidence_link','receipt_attempt',
        'verification_revision','verification_assertion','verification_evidence_link',
        'verification_gap_link','verification_metric','memory_case_link','memory_evidence_link'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=current_setting(''app.tenant_id'',true)) WITH CHECK (tenant_id=current_setting(''app.tenant_id'',true))',name);
    END LOOP;
END
$rls$;

-- Both N and N-1 writers get immutable historical facts. The claim table is
-- only the latest projection; case versions reference a specific revision.
CREATE FUNCTION smartdata_prepare_claim_revision() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
    IF TG_OP='INSERT' THEN NEW.latest_revision:=0;
    ELSIF ROW(NEW.subject_type,NEW.subject_id,NEW.predicate,NEW.value_json,NEW.status,NEW.confidence_exact,
        NEW.valid_from,NEW.valid_to,NEW.recorded_at,NEW.expires_at,NEW.classification,NEW.acl_ref,NEW.derivation_version,NEW.model_version,NEW.evidence_ids)
        IS DISTINCT FROM ROW(OLD.subject_type,OLD.subject_id,OLD.predicate,OLD.value_json,OLD.status,OLD.confidence_exact,
        OLD.valid_from,OLD.valid_to,OLD.recorded_at,OLD.expires_at,OLD.classification,OLD.acl_ref,OLD.derivation_version,OLD.model_version,OLD.evidence_ids)
        THEN NEW.latest_revision:=OLD.latest_revision+1;
    ELSE NEW.latest_revision:=OLD.latest_revision;
    END IF;
    RETURN NEW;
END
$body$;
CREATE TRIGGER zz_prepare_claim_revision BEFORE INSERT OR UPDATE ON claim FOR EACH ROW EXECUTE FUNCTION smartdata_prepare_claim_revision();
CREATE FUNCTION smartdata_capture_claim_revision() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
    IF TG_OP='INSERT' OR NEW.latest_revision<>OLD.latest_revision THEN
        INSERT INTO claim_revision VALUES(NEW.tenant_id,NEW.id,NEW.latest_revision,NEW.subject_type,NEW.subject_id,NEW.predicate,NEW.value_json,
            NEW.status,NEW.confidence_exact,NEW.valid_from,NEW.valid_to,NEW.recorded_at,NEW.expires_at,NEW.classification,NEW.acl_ref,NEW.derivation_version,NEW.model_version);
        INSERT INTO claim_revision_evidence_link SELECT NEW.tenant_id,NEW.id,NEW.latest_revision,(u.ordinality-1)::integer,u.id
            FROM unnest(NEW.evidence_ids) WITH ORDINALITY AS u(id,ordinality);
    END IF;
    DELETE FROM claim_evidence_link WHERE tenant_id=NEW.tenant_id AND claim_id=NEW.id;
    INSERT INTO claim_evidence_link SELECT NEW.tenant_id,NEW.id,(u.ordinality-1)::integer,u.id FROM unnest(NEW.evidence_ids) WITH ORDINALITY AS u(id,ordinality);
    RETURN NEW;
END
$body$;
CREATE TRIGGER capture_claim_revision AFTER INSERT OR UPDATE ON claim FOR EACH ROW EXECUTE FUNCTION smartdata_capture_claim_revision();

CREATE FUNCTION smartdata_reject_fact_mutation() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
    RAISE EXCEPTION 'historical facts are immutable' USING ERRCODE='23514';
END
$body$;
CREATE TRIGGER evidence_immutable BEFORE UPDATE OR DELETE ON evidence_ref FOR EACH ROW EXECUTE FUNCTION smartdata_reject_fact_mutation();
CREATE TRIGGER claim_revision_immutable BEFORE UPDATE OR DELETE ON claim_revision FOR EACH ROW EXECUTE FUNCTION smartdata_reject_fact_mutation();
CREATE TRIGGER claim_revision_evidence_immutable BEFORE UPDATE OR DELETE ON claim_revision_evidence_link FOR EACH ROW EXECUTE FUNCTION smartdata_reject_fact_mutation();
CREATE TRIGGER receipt_attempt_immutable BEFORE UPDATE OR DELETE ON receipt_attempt FOR EACH ROW EXECUTE FUNCTION smartdata_reject_fact_mutation();

CREATE FUNCTION smartdata_reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only' USING ERRCODE='23514';
END
$body$;
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit_event FOR EACH ROW EXECUTE FUNCTION smartdata_reject_audit_mutation();

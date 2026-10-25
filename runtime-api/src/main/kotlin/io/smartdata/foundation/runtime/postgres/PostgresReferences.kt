package io.smartdata.foundation.runtime.postgres

import io.smartdata.foundation.domain.*
import io.smartdata.foundation.service.BusinessValidationException
import io.smartdata.foundation.service.ResourceNotFoundException
import org.jooq.DSLContext
import org.jooq.Record
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Shared immutable facts; all queries are inside a tenant-bound UnitOfWork. */
internal data class StoredClaimRevision(val id:UUID,val revision:Long)

internal class PostgresReferences {
    fun guardIdentity(db:DSLContext,table:String,tenant:String,businessId:String,internal:UUID) {
        require(table in TABLES)
        val existing=db.fetchOne("select business_id from $table where tenant_id=? and id=?",tenant,internal)
        if(existing!=null && existing.text("business_id")!=businessId) throw BusinessValidationException("业务编号与已占用的内部编号冲突，拒绝合并或覆盖")
    }
    fun id(db: DSLContext, table: String, tenant: String, businessId: String): UUID {
        require(table in TABLES)
        PostgresValues.identifier(businessId)
        return db.fetchOne("select id from $table where tenant_id=? and business_id=?",tenant,businessId)?.uuid("id")
            ?: throw ResourceNotFoundException(table,businessId)
    }

    fun evidence(db: DSLContext, value: EvidenceRef): UUID {
        val normalized = value.copy(observedAt=value.observedAt.truncatedTo(ChronoUnit.MICROS),recordedAt=value.recordedAt.truncatedTo(ChronoUnit.MICROS))
        val prior=db.fetchOne("select * from evidence_ref where tenant_id=? and business_id=?",value.tenantId,value.id)
        if(prior!=null) {
            if(evidence(prior)!=normalized) throw BusinessValidationException("已保存的证据不可覆写；更正必须使用新证据标识")
            return prior.uuid("id")
        }
        val internal = PostgresValues.storageId(value.tenantId,"evidence",value.id)
        guardIdentity(db,"evidence_ref",value.tenantId,value.id,internal)
        db.execute("""insert into evidence_ref(tenant_id,id,business_id,source_system,source_record_id,content_hash,object_uri,observed_at,recorded_at,classification,acl_ref)
            values(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?) on conflict do nothing""",value.tenantId,internal,value.id,value.sourceSystem,value.sourceRecordId,
            value.contentHash,value.uri,PostgresValues.timestamp(value.observedAt),PostgresValues.timestamp(value.recordedAt),value.classification.name,value.aclRef)
        val stored = db.fetchOne("select * from evidence_ref where tenant_id=? and business_id=?",value.tenantId,value.id)
            ?: throw BusinessValidationException("证据内部标识冲突")
        if (evidence(stored) != normalized) throw BusinessValidationException("已保存的证据不可覆写；更正必须使用新证据标识")
        return stored.uuid("id")
    }

    fun evidence(row: Record) = EvidenceRef(row.text("business_id"),row.text("tenant_id"),row.text("source_system"),
        row.text("source_record_id"),row.text("content_hash").trim(),row.instant("observed_at"),row.instant("recorded_at"),
        row.optionalText("object_uri"),DataClassification.valueOf(row.text("classification")),row.text("acl_ref"))

    fun claim(db: DSLContext, value: Claim): StoredClaimRevision {
        PostgresValues.bounded(value.evidenceRefs.size)
        require(value.evidenceRefs.map { it.id }.distinct().size == value.evidenceRefs.size) { "主张的证据引用不能重复" }
        val evidenceById=value.evidenceRefs.sortedBy { it.id }.associate { it.id to evidence(db,it) }
        val evidence=value.evidenceRefs.map { requireNotNull(evidenceById[it.id]) }
        val prior=db.fetchOne("select id from claim where tenant_id=? and business_id=?",value.tenantId,value.id)
        val internal=prior?.uuid("id")?:PostgresValues.storageId(value.tenantId,"claim",value.id)
        if(prior==null) guardIdentity(db,"claim",value.tenantId,value.id,internal)
        db.execute("""insert into claim(tenant_id,id,business_id,subject_type,subject_id,predicate,value_json,status,confidence,confidence_exact,
            valid_from,valid_to,recorded_at,expires_at,classification,acl_ref,derivation_version,model_version,evidence_ids)
            values(?,?,?,?,?,?,?::jsonb,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,?,?,?,?,?::uuid[]) on conflict do nothing""",value.tenantId,internal,value.id,
            value.subjectType,value.subjectId,value.predicate,PostgresValues.json(value.value),value.status.name,value.confidence,value.confidence,
            PostgresValues.timestamp(value.validFrom),value.validTo?.let(PostgresValues::timestamp),PostgresValues.timestamp(value.recordedAt),
            value.expiresAt?.let(PostgresValues::timestamp),value.classification.name,value.aclRef,value.derivationVersion,value.modelVersion,PostgresValues.uuidArray(evidence))
        val row=db.fetchOne("select * from claim where tenant_id=? and business_id=? for update",value.tenantId,value.id)
            ?: throw BusinessValidationException("主张内部标识冲突")
        val candidates=db.fetch("""select c.id,c.business_id,r.* from claim_revision r join claim c on c.tenant_id=r.tenant_id and c.id=r.claim_id
            where r.tenant_id=? and r.claim_id=? and r.recorded_at=?::timestamptz and r.status=? and r.value_json=?::jsonb order by r.revision desc limit 1001""",value.tenantId,row.uuid("id"),PostgresValues.timestamp(value.recordedAt),value.status.name,PostgresValues.json(value.value))
        PostgresValues.overflow(candidates.size)
        // Reusing an old case snapshot must not regress the global latest
        // projection. Point at the matching historical revision instead.
        for(candidate in candidates) {
            val storedEvidence=db.fetch("""select e.* from claim_revision_evidence_link l join evidence_ref e on e.tenant_id=l.tenant_id and e.id=l.evidence_id
                where l.tenant_id=? and l.claim_id=? and l.revision=? order by l.ordinal""",value.tenantId,row.uuid("id"),candidate.number("revision")).map(::evidence)
            val expected=value.copy(evidenceRefs=storedEvidence,validFrom=value.validFrom.truncatedTo(ChronoUnit.MICROS),validTo=value.validTo?.truncatedTo(ChronoUnit.MICROS),recordedAt=value.recordedAt.truncatedTo(ChronoUnit.MICROS),expiresAt=value.expiresAt?.truncatedTo(ChronoUnit.MICROS))
            if(claim(candidate,storedEvidence)==expected && storedEvidence.map { it.id }==value.evidenceRefs.map { it.id })
                return StoredClaimRevision(row.uuid("id"),candidate.number("revision"))
        }
        val updated=requireNotNull(db.fetchOne("""update claim set subject_type=?,subject_id=?,predicate=?,value_json=?::jsonb,status=?,confidence=?,confidence_exact=?,valid_from=?::timestamptz,valid_to=?::timestamptz,recorded_at=?::timestamptz,expires_at=?::timestamptz,classification=?,acl_ref=?,derivation_version=?,model_version=?,evidence_ids=?::uuid[]
            where tenant_id=? and id=? returning latest_revision""",value.subjectType,value.subjectId,value.predicate,PostgresValues.json(value.value),value.status.name,value.confidence,value.confidence,
            PostgresValues.timestamp(value.validFrom),value.validTo?.let(PostgresValues::timestamp),PostgresValues.timestamp(value.recordedAt),value.expiresAt?.let(PostgresValues::timestamp),value.classification.name,value.aclRef,value.derivationVersion,value.modelVersion,PostgresValues.uuidArray(evidence),value.tenantId,row.uuid("id")))
        return StoredClaimRevision(row.uuid("id"),updated.number("latest_revision"))
    }

    fun claim(row: Record, evidence: List<EvidenceRef>): Claim {
        val node=PostgresValues.mapper.readTree(row.text("value_json"))
        return Claim(row.text("business_id"),row.text("tenant_id"),row.text("subject_type"),row.text("subject_id"),row.text("predicate"),
            if(node.isString) node.asString() else node.toString(),evidence,row.instant("valid_from"),row.optionalInstant("valid_to"),
            row.instant("recorded_at"),row.optionalInstant("expires_at"),DataClassification.valueOf(row.text("classification")),row.text("acl_ref"),
            row.optionalText("derivation_version"),row.optionalText("model_version"),requireNotNull(row.get("confidence_exact",Double::class.java)),ClaimStatus.valueOf(row.text("status")))
    }

    fun gap(db:DSLContext,value:KnowledgeGap,caseId:UUID):UUID {
        val internal=PostgresValues.storageId(value.tenantId,"gap",value.id)
        guardIdentity(db,"knowledge_gap",value.tenantId,value.id,internal)
        db.execute("""insert into knowledge_gap(tenant_id,id,business_id,case_id,kind,code,subject_id,description_zh,affected_decision,required_observation,due_at,classification,acl_ref)
            values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?) on conflict do nothing""",value.tenantId,internal,value.id,caseId,value.kind.name,value.code,value.subjectId,
            value.descriptionZh,value.affectedDecision,value.requiredObservation,value.dueAt?.let(PostgresValues::timestamp),value.classification.name,value.aclRef)
        val row=db.fetchOne("select * from knowledge_gap where tenant_id=? and business_id=?",value.tenantId,value.id)
            ?: throw BusinessValidationException("知识缺口内部标识冲突")
        if(gap(row)!=value.copy(dueAt=value.dueAt?.truncatedTo(ChronoUnit.MICROS)) || row.uuid("case_id")!=caseId)
            throw BusinessValidationException("知识缺口不可跨案件复用或覆写")
        return row.uuid("id")
    }
    fun gap(row:Record)=KnowledgeGap(row.text("business_id"),row.text("tenant_id"),KnowledgeGapKind.valueOf(row.text("kind")),row.text("code"),
        row.text("subject_id"),row.text("description_zh"),row.text("affected_decision"),row.text("required_observation"),row.optionalInstant("due_at"),
        DataClassification.valueOf(row.text("classification")),row.text("acl_ref"))

    companion object { val TABLES=setOf("exception_case","claim","evidence_ref","knowledge_gap","recommendation","action_request","action_receipt","verification_result","memory_asset") }
}

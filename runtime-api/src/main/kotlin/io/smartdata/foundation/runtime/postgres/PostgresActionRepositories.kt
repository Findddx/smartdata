package io.smartdata.foundation.runtime.postgres

import io.smartdata.foundation.domain.*
import io.smartdata.foundation.repository.ActionRequestRepository
import io.smartdata.foundation.repository.RecommendationRepository
import io.smartdata.foundation.service.BusinessValidationException
import io.smartdata.foundation.service.IdempotencyConflictException
import io.smartdata.foundation.service.OptimisticStateException
import org.jooq.DSLContext
import org.jooq.Record
import java.time.temporal.ChronoUnit

class PostgresRecommendationRepository internal constructor(private val transactions:PostgresUnitOfWork,private val references:PostgresReferences):RecommendationRepository {
    override fun findById(tenantId:String,id:String):Recommendation?=transactions.query(tenantId) { db ->
        db.fetchOne("select r.*,c.business_id as case_business_id from recommendation r join exception_case c on c.tenant_id=r.tenant_id and c.id=r.case_id where r.tenant_id=? and r.business_id=?",tenantId,id)?.let { hydrate(db,it) }
    }
    override fun save(recommendation:Recommendation):Recommendation=transactions.query(recommendation.tenantId,true) { db ->
        val value=recommendation
        PostgresValues.bounded(value.evidenceIds.size)
        val existing=findById(value.tenantId,value.id)
        if(existing!=null) {
            if(existing!=value.copy(createdAt=existing.createdAt,actionInput=existing.actionInput) || !PostgresValues.sameInput(existing.actionInput,value.actionInput)) throw BusinessValidationException("建议记录不可覆写")
            return@query existing
        }
        val caseId=references.id(db,"exception_case",value.tenantId,value.caseId)
        val ids=value.evidenceIds.sorted().map { references.id(db,"evidence_ref",value.tenantId,it) }
        val internal=PostgresValues.storageId(value.tenantId,"recommendation",value.id)
        references.guardIdentity(db,"recommendation",value.tenantId,value.id,internal)
        val inserted=db.execute("""insert into recommendation(tenant_id,id,business_id,case_id,case_state_version,action_definition_id,input_json,rationale_zh,evidence_ids,domain_version,policy_version,model_version,created_at)
            values(?,?,?,?,?,?,?::jsonb,?,?::uuid[],?,?,?,?::timestamptz) on conflict do nothing""",value.tenantId,internal,value.id,caseId,value.caseStateVersion,value.actionDefinitionId,
            PostgresValues.json(value.actionInput),value.rationaleZh,PostgresValues.uuidArray(ids),value.domainVersion,value.policyVersion,value.modelVersion,PostgresValues.timestamp(value.createdAt))
        if(inserted==1) ids.forEach { db.execute("insert into recommendation_evidence_link values(?,?,?)",value.tenantId,internal,it) }
        val stored=findById(value.tenantId,value.id)?:throw BusinessValidationException("建议内部标识冲突")
        if(stored!=value.copy(createdAt=stored.createdAt,actionInput=stored.actionInput) || !PostgresValues.sameInput(stored.actionInput,value.actionInput)) throw BusinessValidationException("建议记录标识冲突")
        stored
    }
    private fun hydrate(db:DSLContext,row:Record):Recommendation {
        val evidence=db.fetch("select e.business_id from recommendation_evidence_link l join evidence_ref e on e.tenant_id=l.tenant_id and e.id=l.evidence_id where l.tenant_id=? and l.recommendation_id=? order by e.business_id",row.text("tenant_id"),row.uuid("id")).map { it.text("business_id") }.toSet()
        return Recommendation(row.text("business_id"),row.text("tenant_id"),row.text("case_business_id"),row.number("case_state_version"),row.text("action_definition_id"),
            PostgresValues.objectMap(row.text("input_json")),row.text("rationale_zh"),evidence,row.instant("created_at"),row.text("domain_version"),row.text("policy_version"),row.text("model_version"))
    }
}

class PostgresActionRequestRepository internal constructor(private val transactions:PostgresUnitOfWork,private val references:PostgresReferences):ActionRequestRepository {
    override fun findById(tenantId:String,id:String):ActionRequest?=transactions.query(tenantId) { db -> select(db,tenantId,"a.business_id",id) }
    override fun findByIdempotencyKey(tenantId:String,key:String):ActionRequest?=transactions.query(tenantId) { db -> select(db,tenantId,"a.idempotency_key",key) }
    private fun select(db:DSLContext,tenant:String,column:String,key:String):ActionRequest? {
        require(column in setOf("a.business_id","a.idempotency_key"))
        return db.fetchOne("""select a.*,c.business_id as case_business_id,r.business_id as recommendation_business_id from action_request a
            join exception_case c on c.tenant_id=a.tenant_id and c.id=a.case_id
            join recommendation r on r.tenant_id=a.tenant_id and r.id=a.recommendation_id where a.tenant_id=? and $column=?""",tenant,key)?.let { hydrate(db,it) }
    }
    override fun save(request:ActionRequest,expectedStateVersion:Long?):ActionRequest=transactions.query(request.tenantId,true) { db ->
        val value=request
        require(value.idempotencyKey.length in 8..128 && value.requestFingerprint.matches(Regex("[a-f0-9]{64}")))
        PostgresValues.bounded(value.evidenceIds.size)
        val current=db.fetchOne("select * from action_request where tenant_id=? and business_id=? for update",value.tenantId,value.id)
        if(expectedStateVersion==null && current!=null) {
            val existing=requireNotNull(findById(value.tenantId,value.id))
            if(existing==normalize(value).copy(input=existing.input) && PostgresValues.sameInput(existing.input,value.input)) return@query existing
            if(existing.idempotencyKey==value.idempotencyKey && (existing.requestFingerprint!=value.requestFingerprint || !sameIntent(existing,value))) throw IdempotencyConflictException()
            throw OptimisticStateException("actionRequest",value.id,value.stateVersion,current.number("state_version"))
        }
        if(expectedStateVersion!=null && current?.number("state_version")!=expectedStateVersion)
            throw OptimisticStateException("actionRequest",value.id,expectedStateVersion,current?.number("state_version"))
        if(expectedStateVersion!=null) require(value.stateVersion==expectedStateVersion+1) { "动作更新必须递增一个版本" }
        val caseId=references.id(db,"exception_case",value.tenantId,value.caseId)
        val recommendationId=references.id(db,"recommendation",value.tenantId,value.recommendationId)
        require(db.fetchOne("select case_id from recommendation where tenant_id=? and id=?",value.tenantId,recommendationId)?.uuid("case_id")==caseId) { "动作建议不属于该案件" }
        val evidence=value.evidenceIds.sorted().map { references.id(db,"evidence_ref",value.tenantId,it) }
        val internal=current?.uuid("id")?:PostgresValues.storageId(value.tenantId,"action",value.id)
        if(current==null) references.guardIdentity(db,"action_request",value.tenantId,value.id,internal)
        if(current==null) {
            val inserted=db.execute("""insert into action_request(tenant_id,id,business_id,case_id,recommendation_id,definition_id,definition_version,input_json,evidence_ids,requested_by,approved_by,idempotency_key,request_fingerprint,
                expected_case_version,bound_case_version,verification_bound_case_version,domain_version,policy_version,state,state_version,created_at,approval_expires_at,updated_at)
                values(?,?,?,?,?,?,?,?::jsonb,?::uuid[],?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz) on conflict do nothing""",value.tenantId,internal,value.id,caseId,recommendationId,value.definitionId,value.definitionVersion,
                PostgresValues.json(value.input),PostgresValues.uuidArray(evidence),value.requestedBy,value.approvedBy,value.idempotencyKey,value.requestFingerprint,value.expectedCaseStateVersion,value.boundCaseStateVersion,
                value.verificationBoundCaseStateVersion,value.domainVersion,value.policyVersion,value.state.name,value.stateVersion,PostgresValues.timestamp(value.createdAt),PostgresValues.timestamp(value.approvalExpiresAt),PostgresValues.timestamp(value.updatedAt))
            if(inserted!=1) {
                val duplicate=findByIdempotencyKey(value.tenantId,value.idempotencyKey)
                if(duplicate!=null && duplicate.requestFingerprint==value.requestFingerprint && duplicate.id==value.id && sameIntent(duplicate,value)) return@query duplicate
                throw IdempotencyConflictException()
            }
            evidence.forEach { db.execute("insert into action_evidence_link values(?,?,?)",value.tenantId,internal,it) }
        } else {
            val old=requireNotNull(findById(value.tenantId,value.id))
            require(old.caseId==value.caseId && old.recommendationId==value.recommendationId && old.definitionId==value.definitionId &&
                old.definitionVersion==value.definitionVersion && PostgresValues.sameInput(old.input,value.input) && old.evidenceIds==value.evidenceIds &&
                old.requestedBy==value.requestedBy && old.idempotencyKey==value.idempotencyKey && old.requestFingerprint==value.requestFingerprint &&
                old.domainVersion==value.domainVersion && old.policyVersion==value.policyVersion && old.expectedCaseStateVersion==value.expectedCaseStateVersion &&
                old.createdAt==value.createdAt.truncatedTo(ChronoUnit.MICROS) && old.approvalExpiresAt==value.approvalExpiresAt.truncatedTo(ChronoUnit.MICROS)) { "已创建动作的输入、证据和版本绑定不可替换" }
            val changed=db.execute("""update action_request set approved_by=?,bound_case_version=?,verification_bound_case_version=?,state=?,state_version=?,updated_at=?::timestamptz
                where tenant_id=? and id=? and state_version=?""",value.approvedBy,value.boundCaseStateVersion,value.verificationBoundCaseStateVersion,value.state.name,value.stateVersion,PostgresValues.timestamp(value.updatedAt),value.tenantId,internal,expectedStateVersion)
            if(changed!=1) throw OptimisticStateException("actionRequest",value.id,expectedStateVersion!!,old.stateVersion)
        }
        if(value.state==ActionState.APPROVED) recordApproval(db,value,internal,evidence)
        requireNotNull(findById(value.tenantId,value.id))
    }
    private fun recordApproval(db:DSLContext,value:ActionRequest,internal:java.util.UUID,evidence:List<java.util.UUID>) {
        val approvalId=java.util.UUID.fromString(StableIds.from("approval",value.tenantId,value.id,value.stateVersion))
        val hashes=evidence.map { id -> requireNotNull(db.fetchOne("select business_id,content_hash from evidence_ref where tenant_id=? and id=?",value.tenantId,id)).let { "${it.text("business_id")}:${it.text("content_hash").trim()}" } }.sorted()
        val frozenInput=PostgresValues.objectMap(requireNotNull(db.fetchOne("select input_json from action_request where tenant_id=? and id=?",value.tenantId,internal)).text("input_json"))
        val inputHash=PostgresValues.sha256(PostgresValues.json(frozenInput.toSortedMap()))
        val evidenceHash=PostgresValues.sha256(PostgresValues.json(hashes))
        db.execute("""insert into approval_decision(tenant_id,id,business_id,action_request_id,decision,decided_by,action_state_version,case_state_version,domain_version,policy_version,definition_version,input_sha256,evidence_bundle_sha256,decided_at,expires_at)
            values(?,?,?,?,'APPROVED',?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz) on conflict do nothing""",value.tenantId,approvalId,approvalId.toString(),internal,requireNotNull(value.approvedBy),value.stateVersion,value.boundCaseStateVersion,
            value.domainVersion,value.policyVersion,value.definitionVersion,inputHash,evidenceHash,PostgresValues.timestamp(value.updatedAt),PostgresValues.timestamp(value.approvalExpiresAt))
        val stored=requireNotNull(db.fetchOne("select * from approval_decision where tenant_id=? and id=?",value.tenantId,approvalId))
        require(stored.text("business_id")==approvalId.toString() && stored.uuid("action_request_id")==internal && stored.text("decision")=="APPROVED" &&
            stored.number("action_state_version")==value.stateVersion && stored.number("case_state_version")==value.boundCaseStateVersion &&
            stored.text("decided_by")==value.approvedBy && stored.text("input_sha256").trim()==inputHash && stored.text("evidence_bundle_sha256").trim()==evidenceHash &&
            stored.text("domain_version")==value.domainVersion && stored.text("policy_version")==value.policyVersion && stored.text("definition_version")==value.definitionVersion &&
            stored.instant("expires_at")==value.approvalExpiresAt.truncatedTo(ChronoUnit.MICROS) && stored.instant("decided_at")==value.updatedAt.truncatedTo(ChronoUnit.MICROS)) { "审批记录内部标识冲突，拒绝复用不同绑定" }
    }
    private fun hydrate(db:DSLContext,row:Record):ActionRequest {
        val evidence=db.fetch("select e.business_id from action_evidence_link l join evidence_ref e on e.tenant_id=l.tenant_id and e.id=l.evidence_id where l.tenant_id=? and l.action_request_id=? order by e.business_id",row.text("tenant_id"),row.uuid("id")).map { it.text("business_id") }.toSet()
        return ActionRequest(row.text("business_id"),row.text("tenant_id"),row.text("case_business_id"),row.text("recommendation_business_id"),row.text("definition_id"),row.text("definition_version"),
            PostgresValues.objectMap(row.text("input_json")),evidence,row.text("requested_by"),row.optionalText("approved_by"),row.text("idempotency_key"),row.text("request_fingerprint").trim(),
            row.number("expected_case_version"),row.number("bound_case_version"),row.optionalNumber("verification_bound_case_version"),row.text("domain_version"),row.text("policy_version"),
            ActionState.valueOf(row.text("state")),row.number("state_version"),row.instant("created_at"),row.instant("approval_expires_at"),row.instant("updated_at"))
    }
    private fun normalize(value:ActionRequest)=value.copy(createdAt=value.createdAt.truncatedTo(ChronoUnit.MICROS),approvalExpiresAt=value.approvalExpiresAt.truncatedTo(ChronoUnit.MICROS),updatedAt=value.updatedAt.truncatedTo(ChronoUnit.MICROS))
    /** A digest is a diagnostic binding, never proof that the actual business intent is equal. */
    private fun sameIntent(stored:ActionRequest,incoming:ActionRequest):Boolean =
        stored.tenantId==incoming.tenantId && stored.idempotencyKey==incoming.idempotencyKey && stored.caseId==incoming.caseId &&
            stored.recommendationId==incoming.recommendationId && stored.definitionId==incoming.definitionId &&
            stored.definitionVersion==incoming.definitionVersion && PostgresValues.sameInput(stored.input,incoming.input) &&
            stored.evidenceIds==incoming.evidenceIds && stored.requestedBy==incoming.requestedBy &&
            stored.expectedCaseStateVersion==incoming.expectedCaseStateVersion && stored.domainVersion==incoming.domainVersion &&
            stored.policyVersion==incoming.policyVersion
}

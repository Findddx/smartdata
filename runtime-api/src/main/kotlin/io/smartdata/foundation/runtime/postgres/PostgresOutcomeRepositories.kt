package io.smartdata.foundation.runtime.postgres

import io.smartdata.foundation.domain.*
import io.smartdata.foundation.repository.ActionReceiptRepository
import io.smartdata.foundation.repository.VerificationRepository
import io.smartdata.foundation.service.BusinessValidationException
import org.jooq.DSLContext
import org.jooq.Record
import java.time.temporal.ChronoUnit
import java.util.UUID

class PostgresActionReceiptRepository internal constructor(private val transactions:PostgresUnitOfWork,private val references:PostgresReferences):ActionReceiptRepository {
    override fun findByActionRequestId(tenantId:String,actionRequestId:String):ActionReceipt?=transactions.query(tenantId) { db ->
        db.fetchOne("select r.*,a.business_id as action_business_id from action_receipt r join action_request a on a.tenant_id=r.tenant_id and a.id=r.action_request_id where r.tenant_id=? and a.business_id=?",tenantId,actionRequestId)?.let { hydrate(db,it) }
    }
    override fun save(receipt:ActionReceipt):ActionReceipt=transactions.query(receipt.tenantId,true) { db ->
        val value=receipt.copy(attempts=receipt.attempts.map { it.copy(startedAt=it.startedAt.truncatedTo(ChronoUnit.MICROS),completedAt=it.completedAt.truncatedTo(ChronoUnit.MICROS)) })
        PostgresValues.bounded(value.attempts.size,10)
        if(value.attempts.isEmpty()) throw BusinessValidationException("回执必须包含至少一次执行尝试")
        val action=references.id(db,"action_request",value.tenantId,value.actionRequestId)
        // Serializes same-action receipt updates. Aggregate CAS in the caller's
        // UnitOfWork supplies the ownership/version boundary for the whole commit.
        val old=db.fetchOne("select * from action_receipt where tenant_id=? and action_request_id=? for update",value.tenantId,action)
        val internal=old?.uuid("id")?:PostgresValues.storageId(value.tenantId,"receipt",value.id)
        if(old==null) references.guardIdentity(db,"action_receipt",value.tenantId,value.id,internal)
        require(old==null || old.text("business_id")==value.id) { "动作已有不同的回执标识" }
        if(old!=null) {
            if(old.text("external_request_id")!=value.externalRequestId || old.instant("created_at")!=value.createdAt.truncatedTo(ChronoUnit.MICROS))
                throw BusinessValidationException("已保存回执的外部请求标识和创建时间不可替换")
            if(value.updatedAt.truncatedTo(ChronoUnit.MICROS).isBefore(old.instant("updated_at")))
                throw BusinessValidationException("不能用旧回执覆盖新结果")
        }
        val oldAttempts=if(old==null) emptyList() else db.fetch("select * from receipt_attempt where tenant_id=? and receipt_id=? order by ordinal",value.tenantId,internal).map(::attempt)
        if(value.attempts.size<oldAttempts.size || value.attempts.take(oldAttempts.size)!=oldAttempts)
            throw BusinessValidationException("历史执行尝试不可改写或删除，只能追加新的尝试")
        fun resolveVerification(id:String,purpose:VerificationPurpose):UUID {
            val verificationId=references.id(db,"verification_result",value.tenantId,id)
            val binding=requireNotNull(db.fetchOne("select action_request_id,purpose from verification_result where tenant_id=? and id=?",value.tenantId,verificationId))
            if(binding.uuid("action_request_id")!=action || binding.text("purpose")!=purpose.name)
                throw BusinessValidationException("验证引用必须属于当前动作，并区分执行验证与补偿验证")
            return verificationId
        }
        val verification=value.verificationId?.let { resolveVerification(it,VerificationPurpose.ACTION) }
        val compensation=value.compensationVerificationId?.let { resolveVerification(it,VerificationPurpose.COMPENSATION) }
        if(old==null) db.execute("""insert into action_receipt(tenant_id,id,business_id,action_request_id,external_request_id,external_receipt_id,outcome,attempts_json,compensation_receipt_id,compensation_verification_id,verification_id,created_at,updated_at)
            values(?,?,?,?,?,?,?,?::jsonb,?,?,?,?::timestamptz,?::timestamptz)""",value.tenantId,internal,value.id,action,value.externalRequestId,value.externalReceiptId,value.outcome.name,PostgresValues.json(value.attempts),value.compensationReceiptId,compensation,verification,PostgresValues.timestamp(value.createdAt),PostgresValues.timestamp(value.updatedAt))
        else db.execute("""update action_receipt set external_receipt_id=?,outcome=?,attempts_json=?::jsonb,compensation_receipt_id=?,compensation_verification_id=?,verification_id=?,updated_at=?::timestamptz where tenant_id=? and id=?""",
            value.externalReceiptId,value.outcome.name,PostgresValues.json(value.attempts),value.compensationReceiptId,compensation,verification,PostgresValues.timestamp(value.updatedAt),value.tenantId,internal)
        value.attempts.drop(oldAttempts.size).forEachIndexed { additionalOrdinal,attempt ->
            val ordinal=oldAttempts.size+additionalOrdinal
            db.execute("insert into receipt_attempt values(?,?,?,?,?::timestamptz,?::timestamptz,?,?)",value.tenantId,internal,ordinal,attempt.number,PostgresValues.timestamp(attempt.startedAt),PostgresValues.timestamp(attempt.completedAt),attempt.outcome.name,attempt.messageZh)
        }
        requireNotNull(findByActionRequestId(value.tenantId,value.actionRequestId))
    }
    private fun hydrate(db:DSLContext,row:Record):ActionReceipt {
        val tenant=row.text("tenant_id")
        val attempts=db.fetch("select * from receipt_attempt where tenant_id=? and receipt_id=? order by ordinal",tenant,row.uuid("id")).map(::attempt)
        fun verification(column:String):String?=row.get(column,UUID::class.java)?.let { id -> db.fetchOne("select business_id from verification_result where tenant_id=? and id=?",tenant,id)?.text("business_id") }
        return ActionReceipt(row.text("business_id"),tenant,row.text("action_business_id"),row.text("external_request_id"),row.optionalText("external_receipt_id"),ReceiptOutcome.valueOf(row.text("outcome")),
            attempts,row.optionalText("compensation_receipt_id"),verification("compensation_verification_id"),verification("verification_id"),row.instant("created_at"),row.instant("updated_at"))
    }
    private fun attempt(row:Record)=ActionAttempt(row.number("attempt_number").toInt(),row.instant("started_at"),row.instant("completed_at"),ReceiptOutcome.valueOf(row.text("outcome")),row.text("message_zh"))
}

class PostgresVerificationRepository internal constructor(private val transactions:PostgresUnitOfWork,private val references:PostgresReferences):VerificationRepository {
    override fun findByActionRequestId(tenantId:String,actionRequestId:String,purpose:VerificationPurpose):VerificationResult?=transactions.query(tenantId) { db ->
        db.fetchOne("select v.*,a.business_id as action_business_id from verification_result v join action_request a on a.tenant_id=v.tenant_id and a.id=v.action_request_id where v.tenant_id=? and a.business_id=? and v.purpose=?",tenantId,actionRequestId,purpose.name)?.let { hydrate(db,it) }
    }
    override fun save(result:VerificationResult):VerificationResult=transactions.query(result.tenantId,true) { db ->
        val value=result
        PostgresValues.bounded(value.assertions.size); PostgresValues.bounded(value.evidenceRefs.size); PostgresValues.bounded(value.resolvedKnowledgeGapIds.size)
        require(value.antiGamingMetrics.size<=100 && value.antiGamingMetrics.values.all { it.isFinite() })
        val action=references.id(db,"action_request",value.tenantId,value.actionRequestId)
        val old=db.fetchOne("select * from verification_result where tenant_id=? and action_request_id=? and purpose=? for update",value.tenantId,action,value.purpose.name)
        if(old!=null) {
            require(old.text("business_id")==value.id && old.text("verifier_id")==value.verifierId) { "验证器或验证标识不可替换" }
            val existing=requireNotNull(findByActionRequestId(value.tenantId,value.actionRequestId,value.purpose))
            if(PostgresValues.json(existing)==PostgresValues.json(value)) return@query existing
            if(value.createdAt.isBefore(old.instant("created_at"))) throw BusinessValidationException("不能用旧观测覆盖新验证")
        }
        val internal=old?.uuid("id")?:PostgresValues.storageId(value.tenantId,"verification",value.id)
        if(old==null) references.guardIdentity(db,"verification_result",value.tenantId,value.id,internal)
        val revision=old?.number("revision")?.plus(1)?:0
        val evidence=value.evidenceRefs.map { references.evidence(db,it) }
        val gaps=value.resolvedKnowledgeGapIds.sorted().map { references.id(db,"knowledge_gap",value.tenantId,it) }
        if(old==null) db.execute("""insert into verification_result(tenant_id,id,business_id,action_request_id,verifier_id,purpose,independent_source,outcome,assertions_json,evidence_ids,resolved_gap_ids,anti_gaming_metrics,observed_from,observed_until,created_at,revision)
            values(?,?,?,?,?,?,?,?,?::jsonb,?::uuid[],?::uuid[],?::jsonb,?::timestamptz,?::timestamptz,?::timestamptz,?)""",value.tenantId,internal,value.id,action,value.verifierId,value.purpose.name,value.independentSource,value.outcome.name,
            PostgresValues.json(value.assertions),PostgresValues.uuidArray(evidence),PostgresValues.uuidArray(gaps),PostgresValues.json(value.antiGamingMetrics),PostgresValues.timestamp(value.observedFrom),PostgresValues.timestamp(value.observedUntil),PostgresValues.timestamp(value.createdAt),revision)
        else db.execute("""update verification_result set independent_source=?,outcome=?,assertions_json=?::jsonb,evidence_ids=?::uuid[],resolved_gap_ids=?::uuid[],anti_gaming_metrics=?::jsonb,observed_from=?::timestamptz,observed_until=?::timestamptz,created_at=?::timestamptz,revision=? where tenant_id=? and id=?""",
            value.independentSource,value.outcome.name,PostgresValues.json(value.assertions),PostgresValues.uuidArray(evidence),PostgresValues.uuidArray(gaps),PostgresValues.json(value.antiGamingMetrics),PostgresValues.timestamp(value.observedFrom),PostgresValues.timestamp(value.observedUntil),PostgresValues.timestamp(value.createdAt),revision,value.tenantId,internal)
        db.execute("insert into verification_revision values(?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",value.tenantId,internal,revision,value.verifierId,value.independentSource,value.outcome.name,PostgresValues.timestamp(value.observedFrom),PostgresValues.timestamp(value.observedUntil),PostgresValues.timestamp(value.createdAt))
        value.assertions.forEachIndexed { ordinal,assertion -> db.execute("insert into verification_assertion values(?,?,?,?,?,?,?,?)",value.tenantId,internal,revision,ordinal,assertion.name,assertion.expected,assertion.observed,assertion.passed) }
        evidence.forEachIndexed { ordinal,id -> db.execute("insert into verification_evidence_link values(?,?,?,?,?)",value.tenantId,internal,revision,ordinal,id) }
        gaps.forEach { db.execute("insert into verification_gap_link values(?,?,?,?)",value.tenantId,internal,revision,it) }
        value.antiGamingMetrics.forEach { (name,number) -> db.execute("insert into verification_metric values(?,?,?,?,?)",value.tenantId,internal,revision,name,number) }
        requireNotNull(findByActionRequestId(value.tenantId,value.actionRequestId,value.purpose))
    }
    private fun hydrate(db:DSLContext,row:Record):VerificationResult {
        val tenant=row.text("tenant_id"); val internal=row.uuid("id"); val revision=row.number("revision")
        val assertions=db.fetch("select * from verification_assertion where tenant_id=? and verification_id=? and revision=? order by ordinal",tenant,internal,revision).map {
            VerificationAssertion(it.text("name"),it.text("expected"),it.text("observed"),requireNotNull(it.get("passed",Boolean::class.java)))
        }
        val evidence=db.fetch("select e.* from verification_evidence_link l join evidence_ref e on e.tenant_id=l.tenant_id and e.id=l.evidence_id where l.tenant_id=? and l.verification_id=? and l.revision=? order by l.ordinal",tenant,internal,revision).map(references::evidence)
        val gaps=db.fetch("select g.business_id from verification_gap_link l join knowledge_gap g on g.tenant_id=l.tenant_id and g.id=l.gap_id where l.tenant_id=? and l.verification_id=? and l.revision=? order by g.business_id",tenant,internal,revision).map { it.text("business_id") }.toSet()
        val metrics=db.fetch("select name,value from verification_metric where tenant_id=? and verification_id=? and revision=?",tenant,internal,revision).associate { it.text("name") to requireNotNull(it.get("value",Double::class.java)) }
        return VerificationResult(row.text("business_id"),tenant,row.text("action_business_id"),row.text("verifier_id"),VerificationPurpose.valueOf(row.text("purpose")),row.text("independent_source"),
            VerificationOutcome.valueOf(row.text("outcome")),assertions,evidence,gaps,row.instant("observed_from"),row.instant("observed_until"),metrics,row.instant("created_at"))
    }
}

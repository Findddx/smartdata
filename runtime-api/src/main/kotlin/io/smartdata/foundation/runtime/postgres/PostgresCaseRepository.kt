package io.smartdata.foundation.runtime.postgres

import io.smartdata.foundation.domain.*
import io.smartdata.foundation.repository.CaseRepository
import io.smartdata.foundation.service.BusinessValidationException
import io.smartdata.foundation.service.OptimisticStateException
import org.jooq.DSLContext
import org.jooq.Record
import java.time.temporal.ChronoUnit

class PostgresCaseRepository internal constructor(private val transactions:PostgresUnitOfWork,private val references:PostgresReferences):CaseRepository {
    override fun findAll(tenantId:String):List<ExceptionCase> = transactions.query(tenantId) { db ->
        val rows=db.fetch("select * from exception_case where tenant_id=? order by case priority when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,created_at,business_id limit 1001",tenantId)
        PostgresValues.overflow(rows.size)
        rows.map { hydrate(db,it) }
    }
    /** Seek pagination avoids scanning/returning an entire tenant to fetch one page. */
    fun findPage(tenantId:String,limit:Int=100,afterId:String?=null):List<ExceptionCase> = transactions.query(tenantId) { db ->
        require(limit in 1..1000)
        db.fetch("select * from exception_case where tenant_id=? and (?::text is null or business_id>?) order by business_id limit ?",tenantId,afterId,afterId,limit).map { hydrate(db,it) }
    }
    override fun findById(tenantId:String,id:String):ExceptionCase? = transactions.query(tenantId) { db ->
        db.fetchOne("select * from exception_case where tenant_id=? and business_id=?",tenantId,id)?.let { hydrate(db,it) }
    }
    override fun lockById(tenantId:String,id:String):ExceptionCase? {
        transactions.requireWriteTransaction(tenantId)
        return transactions.query(tenantId,true) { db -> db.fetchOne("select * from exception_case where tenant_id=? and business_id=? for update",tenantId,id)?.let { hydrate(db,it) } }
    }
    override fun save(case:ExceptionCase,expectedStateVersion:Long?):ExceptionCase = transactions.query(case.tenantId,true) { db ->
        PostgresValues.bounded(case.claims.size); PostgresValues.bounded(case.knowledgeGaps.size)
        require(case.claims.map { it.id }.distinct().size==case.claims.size && case.knowledgeGaps.map { it.id }.distinct().size==case.knowledgeGaps.size)
        val old=db.fetchOne("select * from exception_case where tenant_id=? and business_id=? for update",case.tenantId,case.id)
        if(expectedStateVersion==null && old!=null) {
            if(old.get("context_complete",Boolean::class.java)==true && hydrate(db,old)==normalize(case)) return@query hydrate(db,old)
            throw OptimisticStateException("case",case.id,case.stateVersion,old.number("state_version"))
        }
        if(expectedStateVersion!=null && old?.number("state_version")!=expectedStateVersion)
            throw OptimisticStateException("case",case.id,expectedStateVersion,old?.number("state_version"))
        if(expectedStateVersion!=null) require(case.stateVersion==expectedStateVersion+1) { "案件更新必须递增一个版本" }
        val internal=old?.uuid("id")?:PostgresValues.storageId(case.tenantId,"case",case.id)
        if(old==null) references.guardIdentity(db,"exception_case",case.tenantId,case.id,internal)
        if(old==null) db.execute("""insert into exception_case(tenant_id,id,business_id,order_id,title_zh,priority,status,scenario_kinds,domain_version,policy_version,state_version,created_at,updated_at,context_complete)
            values(?,?,?,?,?,?,?,?::text[],?,?,?,?::timestamptz,?::timestamptz,true)""",case.tenantId,internal,case.id,case.orderId,case.titleZh,case.priority.name,case.status.name,case.scenarioKinds.toTypedArray(),case.domainVersion,case.policyVersion,case.stateVersion,PostgresValues.timestamp(case.createdAt),PostgresValues.timestamp(case.updatedAt))
        else {
            val changed=db.execute("""update exception_case set order_id=?,title_zh=?,priority=?,status=?,scenario_kinds=?::text[],domain_version=?,policy_version=?,state_version=?,updated_at=?::timestamptz,context_complete=true
                where tenant_id=? and id=? and state_version=?""",case.orderId,case.titleZh,case.priority.name,case.status.name,case.scenarioKinds.toTypedArray(),case.domainVersion,case.policyVersion,case.stateVersion,PostgresValues.timestamp(case.updatedAt),case.tenantId,internal,expectedStateVersion)
            if(changed!=1) throw OptimisticStateException("case",case.id,expectedStateVersion!!,old.number("state_version"))
        }
        val revisions=case.claims.sortedBy { it.id }.associate { it.id to references.claim(db,it) }
        case.claims.forEachIndexed { ordinal,claim ->
            val revision=requireNotNull(revisions[claim.id])
            db.execute("insert into case_claim_link(tenant_id,case_id,case_state_version,ordinal,claim_id,claim_revision) values(?,?,?,?,?,?)",case.tenantId,internal,case.stateVersion,ordinal,revision.id,revision.revision)
        }
        case.knowledgeGaps.forEachIndexed { ordinal,gap ->
            val gapId=references.gap(db,gap,internal)
            db.execute("insert into case_gap_link values(?,?,?,?,?)",case.tenantId,internal,case.stateVersion,ordinal,gapId)
        }
        requireNotNull(db.fetchOne("select * from exception_case where tenant_id=? and id=?",case.tenantId,internal)).let { hydrate(db,it) }
    }
    private fun hydrate(db:DSLContext,row:Record):ExceptionCase {
        if(row.get("context_complete",Boolean::class.java)!=true) throw BusinessValidationException("旧案件缺少明确的主张关联；必须显式迁移证据上下文，不能根据订单号猜测")
        val tenant=row.text("tenant_id"); val id=row.uuid("id"); val version=row.number("state_version")
        val claims=db.fetch("""select c.id,c.business_id,r.* from case_claim_link l join claim c on c.tenant_id=l.tenant_id and c.id=l.claim_id
            join claim_revision r on r.tenant_id=l.tenant_id and r.claim_id=l.claim_id and r.revision=l.claim_revision
            where l.tenant_id=? and l.case_id=? and l.case_state_version=? order by l.ordinal""",tenant,id,version)
        val evidence=db.fetch("""select ce.claim_id,ce.revision as claim_revision,e.* from case_claim_link l join claim_revision_evidence_link ce on ce.tenant_id=l.tenant_id and ce.claim_id=l.claim_id and ce.revision=l.claim_revision
            join evidence_ref e on e.tenant_id=ce.tenant_id and e.id=ce.evidence_id where l.tenant_id=? and l.case_id=? and l.case_state_version=? order by l.ordinal,ce.ordinal""",tenant,id,version).groupBy { it.uuid("claim_id") to it.number("claim_revision") }
        val gaps=db.fetch("select g.* from case_gap_link l join knowledge_gap g on g.tenant_id=l.tenant_id and g.id=l.gap_id where l.tenant_id=? and l.case_id=? and l.case_state_version=? order by l.ordinal",tenant,id,version)
        return ExceptionCase(row.text("business_id"),tenant,row.text("order_id"),row.text("title_zh"),CasePriority.valueOf(row.text("priority")),CaseStatus.valueOf(row.text("status")),
            row.get("scenario_kinds",Array<String>::class.java)?.toSet().orEmpty(),claims.map { references.claim(it,evidence[it.uuid("id") to it.number("revision")].orEmpty().map(references::evidence)) },gaps.map(references::gap),
            version,row.text("domain_version"),row.text("policy_version"),row.instant("created_at"),row.instant("updated_at"))
    }
    private fun normalize(value:ExceptionCase)=value.copy(
        createdAt=value.createdAt.truncatedTo(ChronoUnit.MICROS),updatedAt=value.updatedAt.truncatedTo(ChronoUnit.MICROS),
        claims=value.claims.map { claim -> claim.copy(
            validFrom=claim.validFrom.truncatedTo(ChronoUnit.MICROS),validTo=claim.validTo?.truncatedTo(ChronoUnit.MICROS),
            recordedAt=claim.recordedAt.truncatedTo(ChronoUnit.MICROS),expiresAt=claim.expiresAt?.truncatedTo(ChronoUnit.MICROS),
            evidenceRefs=claim.evidenceRefs.map { it.copy(observedAt=it.observedAt.truncatedTo(ChronoUnit.MICROS),recordedAt=it.recordedAt.truncatedTo(ChronoUnit.MICROS)) },
        ) },
        knowledgeGaps=value.knowledgeGaps.map { it.copy(dueAt=it.dueAt?.truncatedTo(ChronoUnit.MICROS)) },
    )
}

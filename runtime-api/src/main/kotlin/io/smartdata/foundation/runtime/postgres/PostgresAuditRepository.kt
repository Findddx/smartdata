package io.smartdata.foundation.runtime.postgres

import io.smartdata.foundation.domain.AuditFact
import io.smartdata.foundation.repository.AuditRepository
import java.time.temporal.ChronoUnit
import java.util.UUID
import io.smartdata.foundation.service.BusinessValidationException

/** Audit and its outbox record are one database commit, with a per-aggregate hash chain. */
class PostgresAuditRepository internal constructor(private val transactions:PostgresUnitOfWork):AuditRepository {
    override fun append(fact:AuditFact) { transactions.query<Unit>(fact.tenantId,true) { db ->
        val value=fact.copy(occurredAt=fact.occurredAt.truncatedTo(ChronoUnit.MICROS),details=fact.details.toSortedMap())
        PostgresValues.identifier(value.aggregateId)
        val details=PostgresValues.json(value.details)
        val identity=PostgresValues.json(linkedMapOf("tenantId" to value.tenantId,"aggregateType" to value.aggregateType,"aggregateId" to value.aggregateId,
            "eventType" to value.eventType,"actorId" to value.actorId,"occurredAt" to value.occurredAt.toString(),"details" to value.details))
        val internal=UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))
        // Database advisory lock serializes only this tenant/aggregate's chain;
        // hash collisions reduce concurrency rather than weakening isolation.
        db.fetch("select pg_advisory_xact_lock(hashtextextended(?,0))","${value.tenantId}\u001f${value.aggregateType}\u001f${value.aggregateId}")
        db.fetchOne("select * from audit_event where tenant_id=? and id=?",value.tenantId,internal)?.let { stored ->
            if(hydrate(stored)!=value) throw BusinessValidationException("审计标识冲突，拒绝忽略不同事实")
            return@query
        }
        val previous=db.fetchOne("select event_hash,sequence_no from audit_event where tenant_id=? and aggregate_type=? and aggregate_id=? order by sequence_no desc nulls last limit 1",value.tenantId,value.aggregateType,value.aggregateId)
        val previousHash=previous?.text("event_hash")?.trim()
        val sequence=(previous?.optionalNumber("sequence_no")?:0L)+1
        val payload=linkedMapOf<String,Any>("auditId" to internal.toString(),"tenantId" to value.tenantId,"aggregateType" to value.aggregateType,
            "aggregateId" to value.aggregateId,"eventType" to value.eventType,"actorId" to value.actorId,"occurredAt" to value.occurredAt.toString(),"details" to value.details)
        val hash=PostgresValues.sha256("${previousHash.orEmpty()}\n${PostgresValues.json(payload)}")
        db.execute("""insert into audit_event(tenant_id,id,business_id,aggregate_type,aggregate_id,event_type,actor_id,details_json,previous_hash,event_hash,occurred_at,case_business_id,sequence_no)
            values(?,?,?,?,?,?,?,?::jsonb,?,?,?::timestamptz,?,?)""",value.tenantId,internal,internal.toString(),value.aggregateType,value.aggregateId,value.eventType,value.actorId,details,previousHash,hash,PostgresValues.timestamp(value.occurredAt),value.details["caseId"],sequence)
        db.execute("""insert into outbox_event(tenant_id,event_id,aggregate_type,aggregate_id,event_type,event_version,payload_json,headers_json,occurred_at)
            values(?,?,?,?,?,1,?::jsonb,?::jsonb,?::timestamptz)""",value.tenantId,internal,value.aggregateType,value.aggregateId,value.eventType,PostgresValues.json(payload),
            PostgresValues.json(mapOf("specversion" to "1.0","source" to "smartdata/runtime","datacontenttype" to "application/json")),PostgresValues.timestamp(value.occurredAt))
    } }
    override fun findByAggregate(tenantId:String,aggregateId:String):List<AuditFact> = transactions.query(tenantId) { db ->
        val rows=db.fetch("select * from audit_event where tenant_id=? and aggregate_id=? order by occurred_at,sequence_no,id limit 1001",tenantId,aggregateId)
        PostgresValues.overflow(rows.size); rows.map(::hydrate)
    }
    override fun findByCase(tenantId:String,caseId:String):List<AuditFact> = transactions.query(tenantId) { db ->
        val rows=db.fetch("select * from audit_event where tenant_id=? and (aggregate_id=? or case_business_id=?) order by occurred_at,sequence_no,id limit 1001",tenantId,caseId,caseId)
        PostgresValues.overflow(rows.size); rows.map(::hydrate)
    }
    private fun hydrate(row:org.jooq.Record)=AuditFact(row.text("tenant_id"),row.text("aggregate_type"),row.text("aggregate_id"),row.text("event_type"),row.text("actor_id"),row.instant("occurred_at"),PostgresValues.stringMap(row.text("details_json")))
}

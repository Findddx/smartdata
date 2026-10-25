package io.smartdata.foundation.runtime.postgres

import java.time.Duration
import java.time.Instant
import java.util.UUID

data class ClaimedOutboxEvent(
    val tenantId:String,val eventId:String,val aggregateType:String,val aggregateId:String,val eventType:String,
    val eventVersion:Int,val payload:Map<String,Any>,val occurredAt:Instant,
    val leaseToken:String,val leaseUntil:Instant,val attempts:Int,
)

/** Leases and publish acknowledgements are durable; delivery remains at-least-once. */
class PostgresOutbox internal constructor(private val transactions:PostgresUnitOfWork) {
    fun claimBatch(tenantId:String,workerId:String,limit:Int=50,lease:Duration=Duration.ofSeconds(30)):List<ClaimedOutboxEvent> = transactions.query(tenantId,true) { db ->
        require(workerId.isNotBlank() && workerId.length<=128 && limit in 1..100 && lease.toMillis() in 1000..300000)
        val rows=db.fetch("""select * from outbox_event where tenant_id=? and published_at is null and (lease_until is null or lease_until<clock_timestamp())
            order by occurred_at,event_id limit ? for update skip locked""",tenantId,limit)
        rows.map { row ->
            val token=UUID.randomUUID()
            val claimed=requireNotNull(db.fetchOne("""update outbox_event set lease_owner=?,lease_token=?,lease_until=clock_timestamp()+make_interval(secs=>?),attempts=attempts+1
                where tenant_id=? and event_id=? returning *""",workerId,token,lease.toMillis()/1000.0,tenantId,row.uuid("event_id")))
            ClaimedOutboxEvent(tenantId,claimed.uuid("event_id").toString(),claimed.text("aggregate_type"),claimed.text("aggregate_id"),claimed.text("event_type"),claimed.number("event_version").toInt(),
                PostgresValues.objectMap(claimed.text("payload_json")),claimed.instant("occurred_at"),token.toString(),claimed.instant("lease_until"),claimed.number("attempts").toInt())
        }
    }
    /** Call after broker acknowledgement, in a new short database transaction. */
    fun markPublished(tenantId:String,eventId:String,leaseToken:String):Boolean=transactions.query(tenantId,true) { db ->
        db.execute("""update outbox_event set published_at=clock_timestamp(),lease_owner=null,lease_token=null,lease_until=null
            where tenant_id=? and event_id=? and lease_token=? and lease_until>clock_timestamp() and published_at is null""",tenantId,UUID.fromString(eventId),UUID.fromString(leaseToken))==1
    }
    fun pendingCount(tenantId:String):Long=transactions.query(tenantId) { db -> requireNotNull(db.fetchOne("select count(*) as count from outbox_event where tenant_id=? and published_at is null",tenantId)).number("count") }
}

package io.smartdata.foundation.runtime.postgres

import io.smartdata.foundation.service.IdempotencyConflictException
import java.time.Instant

/** Replay stores a typed resource reference, never a privileged serialized HTTP response. */
data class StoredCommand(
    val tenantId:String,val idempotencyKey:String,val commandType:String,val requestFingerprint:String,
    val authorizationContextSha256:String,val resultResourceType:String,val resultResourceId:String,
    val resultStateVersion:Long?,val createdAt:Instant,val expiresAt:Instant,
)

class PostgresCommandJournal internal constructor(private val transactions:PostgresUnitOfWork) {
    fun find(tenantId:String,key:String):StoredCommand?=transactions.query(tenantId) { db ->
        db.fetchOne("select * from command_deduplication where tenant_id=? and idempotency_key=?",tenantId,key)?.let(::hydrate)
    }
    /** Must join the same explicit transaction as aggregate, audit and outbox updates. */
    fun complete(value:StoredCommand):StoredCommand {
        transactions.requireWriteTransaction(value.tenantId)
        val fingerprint=when {
            value.requestFingerprint.matches(Regex("[a-f0-9]{64}")) -> "LEGACY_V1" to value.requestFingerprint
            value.requestFingerprint.matches(Regex("v2:[a-f0-9]{64}")) -> "HTTP_V2" to value.requestFingerprint.removePrefix("v2:")
            else -> throw IllegalArgumentException("不支持的请求摘要格式或版本")
        }
        require(value.idempotencyKey.length in 8..128 && value.authorizationContextSha256.matches(Regex("[a-f0-9]{64}")))
        require(value.commandType.length in 1..100 && value.resultResourceType.length in 1..100 && value.expiresAt.isAfter(value.createdAt))
        PostgresValues.identifier(value.resultResourceId)
        return transactions.query(value.tenantId,true) { db ->
            db.execute("""insert into command_deduplication(tenant_id,idempotency_key,command_type,request_fingerprint,fingerprint_version,authorization_context_sha256,result_resource_type,result_resource_id,result_state_version,created_at,expires_at)
                values(?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz) on conflict do nothing""",value.tenantId,value.idempotencyKey,value.commandType,fingerprint.second,fingerprint.first,value.authorizationContextSha256,value.resultResourceType,value.resultResourceId,value.resultStateVersion,PostgresValues.timestamp(value.createdAt),PostgresValues.timestamp(value.expiresAt))
            val stored=requireNotNull(find(value.tenantId,value.idempotencyKey))
            if(stored.commandType!=value.commandType || stored.requestFingerprint!=value.requestFingerprint || stored.authorizationContextSha256!=value.authorizationContextSha256 ||
                stored.resultResourceType!=value.resultResourceType || stored.resultResourceId!=value.resultResourceId || stored.resultStateVersion!=value.resultStateVersion) throw IdempotencyConflictException()
            stored
        }
    }
    private fun hydrate(row:org.jooq.Record)=StoredCommand(row.text("tenant_id"),row.text("idempotency_key"),row.text("command_type"),
        when(row.text("fingerprint_version")) {
            "LEGACY_V1" -> row.text("request_fingerprint").trim()
            "HTTP_V2" -> "v2:"+row.text("request_fingerprint").trim()
            else -> throw IllegalStateException("未知的持久化请求摘要版本")
        },row.text("authorization_context_sha256").trim(),
        row.text("result_resource_type"),row.text("result_resource_id"),row.optionalNumber("result_state_version"),row.instant("created_at"),row.instant("expires_at"))
}

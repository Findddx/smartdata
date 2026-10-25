package io.smartdata.foundation.runtime.postgres

import io.smartdata.foundation.domain.*
import io.smartdata.foundation.repository.MemoryRepository
import io.smartdata.foundation.service.OptimisticStateException
import org.jooq.DSLContext
import org.jooq.Record
import java.time.temporal.ChronoUnit

class PostgresMemoryRepository internal constructor(private val transactions:PostgresUnitOfWork,private val references:PostgresReferences):MemoryRepository {
    override fun findAll(tenantId:String):List<MemoryAsset> = transactions.query(tenantId) { db ->
        val rows=db.fetch("select * from memory_asset where tenant_id=? order by created_at desc,business_id limit 1001",tenantId)
        PostgresValues.overflow(rows.size); rows.map { hydrate(db,it) }
    }
    fun findPage(tenantId:String,limit:Int=100,afterId:String?=null,status:MemoryStatus?=null):List<MemoryAsset> = transactions.query(tenantId) { db ->
        require(limit in 1..1000)
        db.fetch("select * from memory_asset where tenant_id=? and (?::text is null or business_id>?) and (?::text is null or status=?) order by business_id limit ?",tenantId,afterId,afterId,status?.name,status?.name,limit).map { hydrate(db,it) }
    }
    override fun findById(tenantId:String,id:String):MemoryAsset? = transactions.query(tenantId) { db ->
        db.fetchOne("select * from memory_asset where tenant_id=? and business_id=?",tenantId,id)?.let { hydrate(db,it) }
    }
    override fun save(asset:MemoryAsset,expectedStateVersion:Long?):MemoryAsset = transactions.query(asset.tenantId,true) { db ->
        val value=asset
        require(value.titleZh.length<=1000 && value.contentZh.length<=16000)
        PostgresValues.bounded(value.sourceCaseIds.size);PostgresValues.bounded(value.sourceEvidenceIds.size)
        val old=db.fetchOne("select * from memory_asset where tenant_id=? and business_id=? for update",value.tenantId,value.id)
        if(expectedStateVersion==null && old!=null) {
            val existing=hydrate(db,old)
            if(existing==value.copy(validFrom=value.validFrom.truncatedTo(ChronoUnit.MICROS),validTo=value.validTo?.truncatedTo(ChronoUnit.MICROS),
                expiresAt=value.expiresAt?.truncatedTo(ChronoUnit.MICROS),createdAt=existing.createdAt,updatedAt=existing.updatedAt)) return@query existing
            throw OptimisticStateException("memoryAsset",value.id,value.stateVersion,old.number("state_version"))
        }
        if(expectedStateVersion!=null && old?.number("state_version")!=expectedStateVersion)
            throw OptimisticStateException("memoryAsset",value.id,expectedStateVersion,old?.number("state_version"))
        if(expectedStateVersion!=null) require(value.stateVersion==expectedStateVersion+1) { "记忆更新必须递增一个版本" }
        val internal=old?.uuid("id")?:PostgresValues.storageId(value.tenantId,"memory",value.id)
        if(old==null) references.guardIdentity(db,"memory_asset",value.tenantId,value.id,internal)
        val cases=value.sourceCaseIds.sorted().map { references.id(db,"exception_case",value.tenantId,it) }
        val evidence=value.sourceEvidenceIds.sorted().map { references.id(db,"evidence_ref",value.tenantId,it) }
        val supersedes=value.supersedesId?.let { references.id(db,"memory_asset",value.tenantId,it) }
        if(old==null) db.execute("""insert into memory_asset(tenant_id,id,business_id,level,status,title_zh,content_zh,source_case_ids,source_evidence_ids,valid_from,valid_to,expires_at,owner_id,acl_ref,model_version,embedding_version,supersedes_id,state_version,created_at,updated_at)
            values(?,?,?,?,?,?,?,?::uuid[],?::uuid[],?::timestamptz,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?::timestamptz,?::timestamptz)""",value.tenantId,internal,value.id,value.level.name,value.status.name,value.titleZh,value.contentZh,PostgresValues.uuidArray(cases),PostgresValues.uuidArray(evidence),PostgresValues.timestamp(value.validFrom),
            value.validTo?.let(PostgresValues::timestamp),value.expiresAt?.let(PostgresValues::timestamp),value.ownerId,value.aclRef,value.modelVersion,value.embeddingVersion,supersedes,value.stateVersion,PostgresValues.timestamp(value.createdAt),PostgresValues.timestamp(value.updatedAt))
        else {
            val changed=db.execute("""update memory_asset set level=?,status=?,title_zh=?,content_zh=?,source_case_ids=?::uuid[],source_evidence_ids=?::uuid[],valid_from=?::timestamptz,valid_to=?::timestamptz,expires_at=?::timestamptz,owner_id=?,acl_ref=?,model_version=?,embedding_version=?,supersedes_id=?,state_version=?,updated_at=?::timestamptz where tenant_id=? and id=? and state_version=?""",
                value.level.name,value.status.name,value.titleZh,value.contentZh,PostgresValues.uuidArray(cases),PostgresValues.uuidArray(evidence),PostgresValues.timestamp(value.validFrom),value.validTo?.let(PostgresValues::timestamp),value.expiresAt?.let(PostgresValues::timestamp),value.ownerId,value.aclRef,value.modelVersion,value.embeddingVersion,supersedes,value.stateVersion,PostgresValues.timestamp(value.updatedAt),value.tenantId,internal,expectedStateVersion)
            if(changed!=1) throw OptimisticStateException("memoryAsset",value.id,expectedStateVersion!!,old.number("state_version"))
            db.execute("delete from memory_case_link where tenant_id=? and memory_id=?",value.tenantId,internal)
            db.execute("delete from memory_evidence_link where tenant_id=? and memory_id=?",value.tenantId,internal)
        }
        cases.forEach { db.execute("insert into memory_case_link values(?,?,?)",value.tenantId,internal,it) }
        evidence.forEach { db.execute("insert into memory_evidence_link values(?,?,?)",value.tenantId,internal,it) }
        requireNotNull(findById(value.tenantId,value.id))
    }
    private fun hydrate(db:DSLContext,row:Record):MemoryAsset {
        val tenant=row.text("tenant_id");val id=row.uuid("id")
        val cases=db.fetch("select c.business_id from memory_case_link l join exception_case c on c.tenant_id=l.tenant_id and c.id=l.case_id where l.tenant_id=? and l.memory_id=? order by c.business_id",tenant,id).map { it.text("business_id") }.toSet()
        val evidence=db.fetch("select e.business_id from memory_evidence_link l join evidence_ref e on e.tenant_id=l.tenant_id and e.id=l.evidence_id where l.tenant_id=? and l.memory_id=? order by e.business_id",tenant,id).map { it.text("business_id") }.toSet()
        val supersedes=row.get("supersedes_id",java.util.UUID::class.java)?.let { db.fetchOne("select business_id from memory_asset where tenant_id=? and id=?",tenant,it)?.text("business_id") }
        return MemoryAsset(row.text("business_id"),tenant,MemoryLevel.valueOf(row.text("level")),MemoryStatus.valueOf(row.text("status")),row.text("title_zh"),row.text("content_zh"),cases,evidence,
            row.instant("valid_from"),row.optionalInstant("valid_to"),row.optionalInstant("expires_at"),row.text("owner_id"),row.text("acl_ref"),row.text("model_version"),row.optionalText("embedding_version"),supersedes,row.number("state_version"),row.instant("created_at"),row.instant("updated_at"))
    }
}

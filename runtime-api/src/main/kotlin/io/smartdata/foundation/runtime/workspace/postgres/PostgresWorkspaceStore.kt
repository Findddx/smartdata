package io.smartdata.foundation.runtime.workspace.postgres

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.AuditFact
import io.smartdata.foundation.runtime.postgres.PostgresAuditRepository
import io.smartdata.foundation.runtime.postgres.PostgresUnitOfWork
import io.smartdata.foundation.runtime.workspace.*
import io.smartdata.foundation.service.BusinessValidationException
import io.smartdata.foundation.service.IdempotencyConflictException
import io.smartdata.foundation.service.OptimisticStateException
import org.jooq.DSLContext
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Bounded shared workspace, normalized relations, explicit current-member admission.
 * It deliberately preserves v1's conservative whole-workspace CAS; unrelated edits
 * can conflict. This is not an unbounded analytics store or an object-store adapter.
 */
class PostgresWorkspaceStore(
    private val transactions: PostgresUnitOfWork,
    private val access: WorkspaceTransactionAccessPort,
    private val clock: Clock = Clock.systemUTC(),
) : WorkspaceStorePort {
    override val mode = "POSTGRES_SHARED"
    private val audits = PostgresAuditRepository(transactions)

    override fun read(tenantId: String): PersistedWorkspace = throw BusinessValidationException("数据库工作区读取必须提供当前已认证成员")
    override fun mutate(tenantId: String, expectedVersion: Long, key: String, fingerprint: String, mutation: (PersistedWorkspace) -> Unit): PersistedWorkspace =
        throw BusinessValidationException("数据库工作区修改必须提供当前已认证成员")

    override fun readAuthorized(actor: Actor): PersistedWorkspace = transactions.read(actor.tenantId) {
        access.checkCurrent(actor,false)
        transactions.query(actor.tenantId) { db ->
            val version=db.fetchOne("select state_version from workspace_head where tenant_id=?",actor.tenantId)?.long("state_version")?:0
            WorkspaceContentRepository(db,actor.tenantId).load(version)
        }
    }

    override fun mutateAuthorized(actor: Actor, expectedVersion: Long, key: String, fingerprint: String,
        lease: WorkspaceOperationLease?, mutation: (PersistedWorkspace) -> Unit): PersistedWorkspace = transactions.write(actor.tenantId) {
        access.checkCurrent(actor,true)
        require(expectedVersion>=0)
        validateCommand(key,fingerprint)
        transactions.query(actor.tenantId,true) { db ->
            val version=lockHead(db,actor.tenantId)
            val command=commandId(actor.tenantId,key)
            val content=WorkspaceContentRepository(db,actor.tenantId)
            db.fetchOne("select fingerprint from workspace_command where tenant_id=? and command_id=?",actor.tenantId,command)?.let {
                if(it.text("fingerprint")!=fingerprint) throw IdempotencyConflictException()
                return@query content.load(version)
            }
            if(version!=expectedVersion) throw OptimisticStateException("workspace",actor.tenantId,expectedVersion,version)
            checkOperation(db,actor,command,fingerprint,lease)
            val state=content.load(version)
            require(state.commands.size<10000) { "有界工作区命令账本已达10000条，请先完成受控归档扩展" }
            val before=copyState(state)
            mutation(state)
            require(state.tenantId==actor.tenantId && state.stateVersion==version && state.formatVersion==1)
            state.stateVersion=Math.addExact(version,1)
            content.save(before,state)
            val changed=db.execute("update workspace_head set state_version=? where tenant_id=? and state_version=?",state.stateVersion,actor.tenantId,version)
            if(changed!=1) throw OptimisticStateException("workspace",actor.tenantId,version,version+1)
            db.execute("insert into workspace_command(tenant_id,command_id,fingerprint,result_version) values(?,?,?,?)",actor.tenantId,command,fingerprint,state.stateVersion)
            if(lease!=null) {
                val finished=db.execute("update workspace_operation set status='COMPLETED' where tenant_id=? and id=?::uuid and lease_token=?::uuid and status='RUNNING' and lease_until>clock_timestamp()",
                    actor.tenantId,lease.operationId,lease.token)
                require(finished==1) { "外部操作租约已失效，拒绝过期结果提交" }
            }
            state.audit.drop(before.audit.size).forEach { event ->
                audits.append(AuditFact(actor.tenantId,"Workspace","workspace",event.event,actor.id,Instant.parse(event.occurredAt),
                    mapOf("workspaceAuditId" to event.id,"resourceId" to event.resourceId,"stateVersion" to event.stateVersion.toString())))
            }
            state.commands[command]=fingerprint
            state
        }
    }

    override fun reserveExternalOperation(actor:Actor,expectedVersion:Long,key:String,fingerprint:String,kind:String):WorkspaceOperationReservation =
        transactions.write(actor.tenantId) {
            access.checkCurrent(actor,true)
            validateCommand(key,fingerprint)
            require(kind in setOf("SOURCE_SCAN","MODEL_REASONING"))
            transactions.query(actor.tenantId,true) { db ->
                val version=lockHead(db,actor.tenantId)
                val command=commandId(actor.tenantId,key)
                db.fetchOne("select fingerprint from workspace_command where tenant_id=? and command_id=?",actor.tenantId,command)?.let {
                    if(it.text("fingerprint")!=fingerprint) throw IdempotencyConflictException()
                    return@query WorkspaceOperationReservation(WorkspaceOperationStatus.COMPLETED)
                }
                val old=db.fetchOne("select *,lease_until>clock_timestamp() as live from workspace_operation where tenant_id=? and command_id=? for update",actor.tenantId,command)
                if(old!=null) {
                    if(old.text("fingerprint")!=fingerprint || old.text("actor_id")!=actor.id || old.text("kind")!=kind) throw IdempotencyConflictException()
                    if(old.text("status")=="RUNNING" && old.bool("live")) return@query WorkspaceOperationReservation(WorkspaceOperationStatus.IN_PROGRESS)
                    if(old.text("status")!="COMPLETED") db.execute("update workspace_operation set status='UNKNOWN' where tenant_id=? and command_id=?",actor.tenantId,command)
                    return@query WorkspaceOperationReservation(WorkspaceOperationStatus.UNKNOWN)
                }
                if(version!=expectedVersion) throw OptimisticStateException("workspace",actor.tenantId,expectedVersion,version)
                require((db.fetchOne("select count(*) as n from workspace_operation where tenant_id=?",actor.tenantId)?.long("n")?:0)<10000) { "有界工作区运行账本已达上限" }
                val id=UUID.randomUUID(); val token=UUID.randomUUID()
                db.execute("insert into workspace_operation(tenant_id,id,command_id,actor_id,authorization_version,fingerprint,kind,status,lease_token,lease_until) values(?,?,?,?,?,?,?,'RUNNING',?,clock_timestamp()+interval '60 seconds')",
                    actor.tenantId,id,command,actor.id,requireNotNull(actor.authorizationVersion),fingerprint,kind,token)
                WorkspaceOperationReservation(WorkspaceOperationStatus.ACQUIRED,WorkspaceOperationLease(id.toString(),token.toString()))
            }
        }

    override fun markOperationUnknown(actor:Actor,lease:WorkspaceOperationLease) {
        transactions.write(actor.tenantId) {
            access.checkCurrent(actor,true)
            transactions.query(actor.tenantId,true) { db ->
                db.execute("update workspace_operation set status='UNKNOWN' where tenant_id=? and id=?::uuid and lease_token=?::uuid and actor_id=? and status='RUNNING'",
                    actor.tenantId,lease.operationId,lease.token,actor.id)
            }
        }
    }

    private fun checkOperation(db:DSLContext,actor:Actor,command:String,fingerprint:String,lease:WorkspaceOperationLease?) {
        val operation=db.fetchOne("select *,lease_until>clock_timestamp() as live from workspace_operation where tenant_id=? and command_id=? for update",actor.tenantId,command)
        if(lease==null) {
            require(operation==null) { "该命令已有持久外部操作，不能绕过租约提交" }
        } else {
            require(operation!=null && operation.text("id")==lease.operationId && operation.text("lease_token")==lease.token &&
                operation.text("actor_id")==actor.id && operation.long("authorization_version")==actor.authorizationVersion &&
                operation.text("fingerprint")==fingerprint && operation.text("status")=="RUNNING" && operation.bool("live")) {
                "外部操作身份、命令或租约已失效，结果不能标记完成"
            }
        }
    }

    private fun lockHead(db:DSLContext,tenant:String):Long {
        db.execute("insert into workspace_head(tenant_id) values(?) on conflict do nothing",tenant)
        return requireNotNull(db.fetchOne("select state_version from workspace_head where tenant_id=? for update",tenant)).long("state_version")
    }

    private fun validateCommand(key:String,fingerprint:String) {
        require(key.length in 8..128 && fingerprint.matches(Regex("(v2:)?[a-f0-9]{64}"))) { "命令键或版本化摘要无效" }
    }
    private fun commandId(tenant:String,key:String)=digest(tenant+"\u0000"+key)

    private fun copyState(s:PersistedWorkspace)=s.copy(sources=s.sources.toMutableList(),assets=s.assets.toMutableList(),scans=s.scans.toMutableList(),
        findings=s.findings.toMutableList(),assistants=s.assistants.toMutableList(),tasks=s.tasks.toMutableList(),comments=s.comments.toMutableList(),
        messages=s.messages.toMutableList(),audit=s.audit.toMutableList(),commands=s.commands.toMutableMap(),modelBudgetReservations=s.modelBudgetReservations.toMutableList(),
        businessBriefHistory=s.businessBriefHistory.toMutableList(),lineageMappingHistory=s.lineageMappingHistory.toMutableList())

    companion object {
        val OWNED_TABLES=setOf(
            "workspace_head",
            "workspace_snapshot_payload",
            "workspace_source",
            "workspace_source_revision",
            "workspace_scan",
            "workspace_asset_revision",
            "workspace_asset_column",
            "workspace_asset",
            "workspace_finding",
            "workspace_assistant",
            "workspace_task",
            "workspace_task_revision",
            "workspace_comment",
            "workspace_message",
            "workspace_audit",
            "workspace_command",
            "workspace_operation",
            "workspace_brief_revision",
            "workspace_goal",
            "workspace_metric",
            "workspace_mapping_revision",
            "workspace_entity_mapping",
            "workspace_metric_mapping",
            "workspace_decision_mapping",
            "workspace_mapping_field"
        )
    }
}

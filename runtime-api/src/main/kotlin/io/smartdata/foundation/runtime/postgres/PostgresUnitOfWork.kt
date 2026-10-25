package io.smartdata.foundation.runtime.postgres

import io.smartdata.foundation.repository.RepositoryUnitOfWork
import io.smartdata.foundation.service.BusinessValidationException
import io.smartdata.foundation.service.TenantBoundaryException
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import java.sql.Connection
import javax.sql.DataSource
import java.util.concurrent.atomic.AtomicInteger

/** Explicit JDBC transactions; a tenant setting can never escape its connection transaction. */
class PostgresUnitOfWork(private val dataSource: DataSource, private val queryTimeoutSeconds: Int = 5) : RepositoryUnitOfWork {
    private data class Frame(val tenantId: String, val writable: Boolean, val database: DSLContext, var rollbackOnly: Boolean = false)
    private val frame = ThreadLocal<Frame>()
    private val activeTransactions = AtomicInteger()
    val activeTransactionCount: Int get() = activeTransactions.get()
    val transactionActiveOnCurrentThread: Boolean get() = frame.get() != null
    private val settings = Settings().withExecuteLogging(false).withExecuteLoggingSQLExceptions(false)

    init {
        require(queryTimeoutSeconds in 1..30)
        assertRuntimeRole()
    }

    override fun <T> read(tenantId: String, block: () -> T): T = transact(tenantId, false, block)
    override fun <T> write(tenantId: String, block: () -> T): T = transact(tenantId, true, block)

    internal fun <T> query(tenantId: String, write: Boolean = false, block: (DSLContext) -> T): T =
        transact(tenantId, write) { block(requireNotNull(frame.get()).database) }

    internal fun requireWriteTransaction(tenantId:String) {
        val active=frame.get()?:throw BusinessValidationException("锁定聚合必须位于显式写事务内")
        if(active.tenantId!=tenantId) { active.rollbackOnly=true;throw TenantBoundaryException() }
        if(!active.writable) { active.rollbackOnly=true;throw BusinessValidationException("只读事务不能锁定写入聚合") }
    }

    private fun <T> transact(tenantId: String, write: Boolean, block: () -> T): T {
        require(tenantId.isNotBlank() && tenantId.length <= 128) { "租户标识无效" }
        frame.get()?.let { existing ->
            try {
                if (existing.tenantId != tenantId) throw TenantBoundaryException()
                if (write && !existing.writable) throw BusinessValidationException("只读事务中禁止写入")
                return block()
            } catch (failure: Throwable) {
                existing.rollbackOnly = true
                throw failure
            }
        }
        dataSource.connection.use { connection ->
            connection.setNetworkTimeout({ task -> task.run() },queryTimeoutSeconds*1000)
            connection.isReadOnly = !write
            connection.transactionIsolation = if (write) Connection.TRANSACTION_READ_COMMITTED else Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            val database = DSL.using(connection, SQLDialect.POSTGRES, settings)
            val current = Frame(tenantId, write, database)
            try {
                database.fetch("select pg_catalog.set_config('search_path','pg_catalog,public,pg_temp',true), pg_catalog.set_config('app.tenant_id',?,true), pg_catalog.set_config('statement_timeout',?,true)",
                    tenantId, (queryTimeoutSeconds * 1000).toString())
                frame.set(current)
                activeTransactions.incrementAndGet()
                val result = block()
                if (current.rollbackOnly) throw BusinessValidationException("嵌套操作失败，整个事务已取消")
                connection.commit()
                return result
            } catch (failure: Throwable) {
                try { connection.rollback() } catch (rollbackFailure: Exception) { failure.addSuppressed(rollbackFailure) }
                throw failure
            } finally {
                if (frame.get() != null) activeTransactions.decrementAndGet()
                frame.remove()
            }
        }
    }

    /** Privileged migration and low-privilege application connections must be different principals. */
    private fun assertRuntimeRole() {
        dataSource.connection.use { connection ->
            connection.setNetworkTimeout({ task -> task.run() },queryTimeoutSeconds*1000)
            connection.autoCommit=false
            val database = DSL.using(connection, SQLDialect.POSTGRES, settings)
            database.fetch("select pg_catalog.set_config('search_path','pg_catalog,public,pg_temp',true)")
            val role = requireNotNull(database.fetchOne("""
                select current_setting('server_version_num')::integer as server_version, r.rolsuper, r.rolbypassrls,r.rolcreaterole,r.rolcreatedb,
                    has_schema_privilege(current_user,'public','CREATE') as can_create,
                    exists(select 1 from pg_roles p where pg_has_role(p.oid,'MEMBER') and
                        (p.rolsuper or p.rolbypassrls or p.rolcreaterole or p.rolcreatedb or has_schema_privilege(p.oid,'public','CREATE'))) as can_escalate
                from pg_roles r where r.rolname=current_user
            """.trimIndent()))
            require(requireNotNull(role.get("server_version", Int::class.java)) in 180000..189999) { "持久化适配器需要 PostgreSQL 18，其他主版本须单独验收" }
            require(role.get("rolsuper", Boolean::class.java) == false && role.get("rolbypassrls", Boolean::class.java) == false &&
                role.get("can_create", Boolean::class.java) == false && role.get("can_escalate",Boolean::class.java)==false &&
                role.get("rolcreaterole",Boolean::class.java)==false && role.get("rolcreatedb",Boolean::class.java)==false) { "运行账号及其可切换角色不得为超级用户、绕过RLS或拥有public建表/角色管理权限" }
            val tables = database.fetch("""
                select c.relname,c.relrowsecurity,c.relforcerowsecurity,pg_has_role(c.relowner,'MEMBER') as owner_member,
                    (has_table_privilege(current_user,c.oid,'TRUNCATE') or
                        exists(select 1 from pg_roles p where pg_has_role(p.oid,'MEMBER') and has_table_privilege(p.oid,c.oid,'TRUNCATE'))) as can_truncate
                from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='public' and c.relkind='r' and c.relname in (${OWNED_TABLES.joinToString(",") { "'$it'" }})
            """.trimIndent())
            require(tables.size == OWNED_TABLES.size && tables.all {
                it.get("relrowsecurity", Boolean::class.java) == true && it.get("relforcerowsecurity", Boolean::class.java) == true &&
                    it.get("owner_member", Boolean::class.java) == false && it.get("can_truncate",Boolean::class.java)==false
            }) { "表迁移、强制租户隔离或运行账号所有权检查未通过；运行账号及其可切换角色不得有TRUNCATE清表权限" }
            connection.commit()
        }
    }

    companion object {
        val OWNED_TABLES = setOf("tenant","identity_link","evidence_ref","claim","exception_case","knowledge_gap",
            "recommendation","action_request","approval_decision","connector_execution_grant","action_receipt","verification_result",
            "memory_asset","outbox_event","inbox_event","audit_event","command_deduplication",
            "case_claim_link","case_gap_link","claim_evidence_link","claim_revision","claim_revision_evidence_link","recommendation_evidence_link","action_evidence_link","receipt_attempt",
            "verification_revision","verification_assertion","verification_evidence_link","verification_gap_link","verification_metric",
            "memory_case_link","memory_evidence_link")
    }
}

package io.smartdata.foundation.runtime.operations

import io.smartdata.foundation.runtime.config.EnterpriseWorkspaceConfiguration
import io.smartdata.foundation.runtime.postgres.PostgresUnitOfWork
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager

/** An operator-only process. Migration credentials never enter the running web application. */
object EnterpriseDatabaseSetup {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty()) { "配置只从专用环境变量读取，不把凭据放在命令参数中" }
        fun required(name: String) = requireNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) { "缺少环境变量 $name" }
        val url = required("SMARTDATA_SETUP_DB_URL")
        EnterpriseWorkspaceConfiguration().validateDatabaseUrl(url)
        val migrationUser = required("SMARTDATA_SETUP_DB_USER")
        val migrationPassword = required("SMARTDATA_SETUP_DB_PASSWORD")
        val business = required("SMARTDATA_DB_USER")
        val session = required("SMARTDATA_SESSION_DB_USER")
        require(migrationUser !in setOf(business, session)) { "迁移账号不能作为业务或会话运行账号" }
        Flyway.configure().dataSource(url, migrationUser, migrationPassword).defaultSchema("public")
            .locations("classpath:db/migration").createSchemas(false).cleanDisabled(true).load().also { it.migrate(); it.validate() }
        DriverManager.getConnection(url, migrationUser, migrationPassword).use { connection ->
            prepare(connection, required("SMARTDATA_ENTERPRISE_TENANT_ID"), required("SMARTDATA_ENTERPRISE_NAME"), business, session)
        }
        println("ENTERPRISE_DATABASE_PREPARED: schema validated, enterprise registered, dedicated least-privilege roles granted; application readiness must be checked separately.")
    }

    /** Retry-safe setup for two already-created, dedicated login roles. No role passwords are read here. */
    fun prepare(connection: Connection, tenant: String, displayName: String, business: String, session: String) {
        require(tenant.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,63}"))) { "企业编号无效" }
        require(displayName.isNotBlank() && displayName.length <= 100) { "企业名称无效" }
        require(business != session && listOf(business, session).all { it.matches(Regex("[a-z_][a-z0-9_]{0,62}")) }) { "需要两个不同的专用小写数据库运行角色" }
        require(connection.autoCommit) { "数据库准备需要独立连接" }
        connection.autoCommit = false
        try {
            connection.createStatement().use {
                it.execute("select pg_catalog.set_config('search_path','pg_catalog,public,pg_temp',true)")
                it.execute("revoke create on schema public from public")
            }
            validateRole(connection, business)
            validateRole(connection, session)
            val businessRights = PostgresUnitOfWork.OWNED_TABLES.associateWith { table ->
                when {
                    table == "tenant" -> setOf("SELECT")
                    table in setOf("audit_event", "outbox_event") -> setOf("SELECT", "INSERT")
                    table in setOf("enterprise_account", "enterprise_directory", "enterprise_member", "enterprise_invitation") -> setOf("SELECT", "INSERT", "UPDATE")
                    table == "enterprise_command" -> setOf("SELECT", "INSERT")
                    table in setOf("workspace_head", "workspace_source", "workspace_assistant", "workspace_task", "workspace_operation", "workspace_finding") -> setOf("SELECT", "INSERT", "UPDATE")
                    table == "workspace_asset" -> setOf("SELECT", "INSERT", "UPDATE", "DELETE")
                    table.startsWith("workspace_") -> setOf("SELECT", "INSERT")
                    else -> emptySet()
                }
            }
            val sessionRights = mapOf("smartdata_session.spring_session" to setOf("SELECT", "INSERT", "UPDATE", "DELETE"),
                "smartdata_session.spring_session_attributes" to setOf("SELECT", "INSERT", "UPDATE", "DELETE"))
            val all = businessRights.mapKeys { "public.${it.key}" } + sessionRights
            assertNoExtraRights(connection, business, all.mapValues { (table, _) -> if (table.startsWith("public.")) businessRights[table.substringAfter('.')].orEmpty() else emptySet() })
            assertNoExtraRights(connection, session, all.mapValues { (table, _) -> sessionRights[table].orEmpty() })
            connection.prepareStatement("select pg_catalog.set_config('app.tenant_id',?,true),pg_catalog.set_config('search_path','pg_catalog,public,pg_temp',true)").use {
                it.setString(1, tenant); it.execute()
            }
            connection.prepareStatement("insert into public.tenant(id,display_name,status,created_at) values(?,?,'ACTIVE',current_timestamp) on conflict do nothing").use {
                it.setString(1, tenant); it.setString(2, displayName); it.executeUpdate()
            }
            connection.prepareStatement("select display_name,status from public.tenant where id=?").use { sql ->
                sql.setString(1, tenant)
                sql.executeQuery().use { row -> require(row.next() && row.getString(1) == displayName && row.getString(2) == "ACTIVE") { "已存在企业的名称或状态不同，准备过程不会覆盖它" } }
            }
            connection.createStatement().use { sql ->
                val catalog = requireNotNull(connection.catalog).replace("\"", "\"\"")
                sql.execute("grant connect on database \"$catalog\" to \"$business\",\"$session\"")
                sql.execute("grant usage on schema public to \"$business\"")
                sql.execute("grant usage on schema smartdata_session to \"$session\"")
                businessRights.filterValues { it.isNotEmpty() }.forEach { (table, rights) -> sql.execute("grant ${rights.joinToString()} on table public.\"$table\" to \"$business\"") }
                sessionRights.forEach { (table, rights) -> sql.execute("grant ${rights.joinToString()} on table $table to \"$session\"") }
            }
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback(); throw failure
        } finally { connection.autoCommit = true }
    }

    private fun validateRole(connection: Connection, role: String) {
        connection.prepareStatement("""
            select r.rolcanlogin and not(r.rolsuper or r.rolbypassrls or r.rolcreatedb or r.rolcreaterole),
              exists(select 1 from pg_roles p where pg_has_role(r.oid,p.oid,'MEMBER') and
                (p.rolsuper or p.rolbypassrls or p.rolcreatedb or p.rolcreaterole or has_schema_privilege(p.oid,'public','CREATE')))
            from pg_roles r where r.rolname=?
        """.trimIndent()).use { sql ->
            sql.setString(1, role)
            sql.executeQuery().use { row -> require(row.next() && row.getBoolean(1) && !row.getBoolean(2)) { "运行角色不存在、不是独立低权限登录角色或可以提升权限；请新建专用角色" } }
        }
    }

    private fun assertNoExtraRights(connection: Connection, role: String, tables: Map<String, Set<String>>) {
        connection.prepareStatement("select pg_catalog.has_table_privilege(?::name,?::text,?::text)").use { tableSql ->
          connection.prepareStatement("select pg_catalog.has_any_column_privilege(?::name,?::text,?::text)").use { columnSql ->
            for ((table, allowed) in tables) for (right in listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "MAINTAIN")) {
                if (right in allowed) continue
                val sql = if (right in setOf("SELECT", "INSERT", "UPDATE", "REFERENCES")) columnSql else tableSql
                sql.setString(1, role); sql.setString(2, table); sql.setString(3, right)
                sql.executeQuery().use { row -> require(row.next() && !row.getBoolean(1)) { "运行角色已有超出本企业工作区所需的表权限；请使用专用角色，准备过程不会撤销其他服务权限" } }
            }
          }
        }
    }
}

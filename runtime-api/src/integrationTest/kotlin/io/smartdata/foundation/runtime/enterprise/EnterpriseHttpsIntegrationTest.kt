package io.smartdata.foundation.runtime.enterprise

import io.smartdata.foundation.runtime.RuntimeApiApplication
import io.smartdata.foundation.runtime.operations.EnterpriseDatabaseSetup
import io.smartdata.foundation.runtime.persistence.PostgresIntegrationDatabase
import io.smartdata.foundation.runtime.persistence.PostgresFixture
import io.smartdata.foundation.runtime.security.enterprise.EnterpriseAccounts
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Full TLS/OIDC/HTTP/JDBC path. No mock principal, H2, local actor or in-memory session. */
class EnterpriseHttpsIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val json = JsonMapper.builder().build()

    @Test fun `two verified accounts collaborate across replicas and restart with immediate membership revocation`() {
        PostgresIntegrationDatabase.start()
        try {
            PostgresIntegrationDatabase.fixture().use { database ->
                val tenant = "enterprise-${UUID.randomUUID().toString().take(8)}"
                database.seedTenant(tenant)
                val sessionRole = "session_${UUID.randomUUID().toString().replace("-", "").take(16)}"
                val sessionPassword = UUID.randomUUID().toString()
                val businessRole = "business_${UUID.randomUUID().toString().replace("-", "").take(16)}"
                val businessPassword = UUID.randomUUID().toString()
                database.admin { connection -> connection.createStatement().use { sql ->
                    val catalog = requireNotNull(connection.catalog).replace("\"", "\"\"")
                    sql.execute("revoke connect on database \"$catalog\" from public")
                    sql.execute("create role $sessionRole login nosuperuser nobypassrls nocreatedb nocreaterole password '$sessionPassword'")
                    sql.execute("create role $businessRole login nosuperuser nobypassrls nocreatedb nocreaterole password '$businessPassword'")
                } }
                database.admin { EnterpriseDatabaseSetup.prepare(it, tenant, "合成集成测试企业", businessRole, sessionRole) }
                database.admin { EnterpriseDatabaseSetup.prepare(it, tenant, "合成集成测试企业", businessRole, sessionRole) }
                TestOidcProvider(temporary).use { provider ->
                    val ownerId = EnterpriseAccounts.identity(provider.issuer, "owner", "Owner").accountId
                    val workerId = EnterpriseAccounts.identity(provider.issuer, "worker", "Worker").accountId
                    val key = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                    val port = freePort()
                    val base = "https://localhost:$port"
                    provider.registeredRedirect = "$base/login/oauth2/code/enterprise"
                    val properties = properties(database, provider, tenant, ownerId, key, sessionRole, sessionPassword, port) + mapOf(
                        "spring.datasource.username" to businessRole, "spring.datasource.password" to businessPassword)
                    var app: ConfigurableApplicationContext? = null
                    var replica: ConfigurableApplicationContext? = null
                    try {
                        app = start(properties)
                        val owner = Browser(provider)
                        val worker = Browser(provider)
                        val outsider = Browser(provider)
                        assertEquals(401, owner.request("$base/api/v1/workspace").statusCode())
                        assertFalse(owner.get("$base/api/v1/me")["authenticated"].asBoolean())
                        owner.login(base, "owner")
                        worker.login(base, "worker")
                        outsider.login(base, "outsider")
                        val ownerMe = owner.get("$base/api/v1/me")
                        assertEquals(ownerId, ownerMe["accountId"].asString())
                        assertTrue(ownerMe["capabilities"]["canManageMembers"].asBoolean())
                        assertEquals(404, owner.request("$base/api/v1/cases").statusCode(), "Unassembled fulfillment APIs must stay unavailable")
                        database.admin { connection -> connection.createStatement().use { sql ->
                            sql.executeQuery("select attribute_bytes from smartdata_session.spring_session_attributes").use { rows ->
                                var count = 0
                                while (rows.next()) {
                                    val bytes = rows.getBytes(1)
                                    assertArrayEquals("SDJ1".toByteArray(), bytes.copyOfRange(0, 4))
                                    assertFalse(bytes.toString(Charsets.UTF_8).contains("code_verifier"))
                                    assertFalse(bytes.toString(Charsets.UTF_8).contains(provider.clientSecret))
                                    count++
                                }
                                assertTrue(count > 0, "OIDC/session attributes must actually be persisted and encrypted")
                            }
                        } }
                        assertFalse(worker.get("$base/api/v1/me")["capabilities"]["canUseWorkspace"].asBoolean())
                        assertEquals(403, owner.post("$base/api/v1/workspace/sources", emptyMap<String, String>(), csrf = false).statusCode())
                        assertEquals(400, owner.request("$base/api/v1/workspace", headers = mapOf("X-Roles" to "ADMIN")).statusCode())

                        var directory = owner.get("$base/api/v1/enterprise/members")
                        directory = owner.command(base, "/enterprise/invitations", mapOf("expectedStateVersion" to version(directory), "accountId" to workerId, "roles" to listOf("ORDER_SPECIALIST")))
                        val invite = directory["invitations"].first { it["accountId"].asString() == workerId }
                        val accept = mapOf("expectedStateVersion" to version(invite))
                        assertEquals(403, outsider.post("$base/api/v1/enterprise/invitations/${invite["id"].asString()}/accept", accept).statusCode())
                        worker.command(base, "/enterprise/invitations/${invite["id"].asString()}/accept", accept)
                        assertTrue(worker.get("$base/api/v1/me")["capabilities"]["canUseWorkspace"].asBoolean())
                        val workerAccessToken = provider.resourceToken("worker")
                        assertEquals(200, outsider.request("$base/api/v1/workspace", headers = mapOf("Authorization" to "Bearer $workerAccessToken")).statusCode())
                        assertEquals(403, outsider.request("$base/api/v1/enterprise/members", headers = mapOf("Authorization" to "Bearer $workerAccessToken")).statusCode(), "JWT roles must never override current membership")
                        for (fault in listOf("SUBJECT", "CLIENT", "AUDIENCE", "ID_TOKEN_TYPE", "EXPIRED")) {
                            assertEquals(401, owner.request("$base/api/v1/workspace", headers = mapOf("Authorization" to "Bearer ${provider.resourceToken("worker", fault)}")).statusCode(),
                                "Invalid $fault bearer token must not fall back to a valid browser session")
                        }

                        var workspace = owner.get("$base/api/v1/workspace")
                        assertEquals("POSTGRES_SHARED", workspace["mode"].asString())
                        val originalAt = Instant.now().minusSeconds(120).toString()
                        val sourceBody = mapOf("expectedStateVersion" to version(workspace), "name" to "合成完整订单", "kind" to "JSON_SNAPSHOT", "primaryKey" to "order_id",
                            "snapshotRows" to listOf(mapOf("order_id" to "one", "amount" to 10), mapOf("order_id" to "one", "amount" to 20)), "observedAt" to originalAt)
                        val replayKey = UUID.randomUUID().toString()
                        workspace = owner.command(base, "/workspace/sources", sourceBody, replayKey)
                        val source = workspace["sources"].first()["id"].asString()
                        val replay = owner.command(base, "/workspace/sources", sourceBody, replayKey)
                        assertEquals(version(workspace), version(replay))
                        assertEquals(1, replay["sources"].size())
                        workspace = owner.command(base, "/workspace/sources/$source/scan", mapOf("expectedStateVersion" to version(workspace)))
                        workspace = owner.command(base, "/workspace/team/provision", mapOf("expectedStateVersion" to version(workspace)))
                        val task = workspace["tasks"].first()
                        val taskId = task["id"].asString()
                        val finding = task["findingId"].asString()
                        workspace = worker.command(base, "/workspace/tasks/$taskId/transition", mapOf("expectedStateVersion" to version(workspace), "status" to "ACCEPTED", "note" to "已领取核对任务"))
                        val fixed = mapOf("expectedStateVersion" to version(workspace), "snapshotRows" to listOf(mapOf("order_id" to "one", "amount" to 10), mapOf("order_id" to "two", "amount" to 20)),
                            "observedAt" to Instant.now().minusSeconds(60).toString())
                        workspace = owner.command(base, "/workspace/sources/$source/snapshot", fixed)
                        workspace = owner.command(base, "/workspace/sources/$source/scan", mapOf("expectedStateVersion" to version(workspace)))
                        val latestScan = workspace["scans"].last()["id"].asString()
                        workspace = worker.command(base, "/workspace/tasks/$taskId/transition", mapOf("expectedStateVersion" to version(workspace), "status" to "COMPLETED", "note" to "已提交不同的新完整观察", "evidenceRefs" to listOf(finding, latestScan)))

                        // Changing role must never turn the same human into an independent checker.
                        directory = owner.get("$base/api/v1/enterprise/members")
                        var member = directory["members"].first { it["accountId"].asString() == workerId }
                        directory = owner.command(base, "/enterprise/members/$workerId/roles", mapOf("expectedStateVersion" to version(directory), "expectedMembershipVersion" to version(member), "roles" to listOf("ORDER_SPECIALIST", "SALES_OWNER")))
                        val verify = mapOf("expectedStateVersion" to version(workspace), "status" to "VERIFIED", "note" to "独立核对新观察", "evidenceRefs" to listOf(latestScan))
                        assertEquals(400, worker.post("$base/api/v1/workspace/tasks/$taskId/transition", verify).statusCode())
                        workspace = owner.command(base, "/workspace/tasks/$taskId/transition", verify)
                        assertEquals(ownerId, workspace["tasks"].first()["verifiedBy"].asString())
                        assertFalse(workspace["sources"].first().has("snapshotRows"))
                        assertEquals(400, owner.post("$base/api/v1/workspace/demo/bootstrap", mapOf("expectedStateVersion" to version(workspace))).statusCode())

                        val replicaPort = freePort()
                        replica = start(properties + ("server.port" to replicaPort.toString()))
                        val replicaBase = "https://localhost:$replicaPort"
                        assertEquals(workerId, worker.get("$replicaBase/api/v1/me")["accountId"].asString())
                        assertEquals(version(workspace), worker.get("$replicaBase/api/v1/workspace")["stateVersion"].asLong())
                        member = directory["members"].first { it["accountId"].asString() == workerId }
                        owner.command(base, "/enterprise/members/$workerId/suspend", mapOf("expectedStateVersion" to version(directory), "expectedMembershipVersion" to version(member)))
                        assertEquals(403, worker.request("$replicaBase/api/v1/workspace").statusCode())
                        assertEquals(403, outsider.request("$replicaBase/api/v1/workspace", headers = mapOf("Authorization" to "Bearer $workerAccessToken")).statusCode())
                        assertFalse(worker.get("$replicaBase/api/v1/me")["capabilities"]["canUseWorkspace"].asBoolean())

                        app.close(); app = null
                        app = start(properties)
                        assertTrue(owner.get("$base/api/v1/me")["authenticated"].asBoolean())
                        assertEquals(version(workspace), owner.get("$base/api/v1/workspace")["stateVersion"].asLong())
                        assertEquals(403, worker.request("$base/api/v1/workspace").statusCode())
                        assertEquals(204, owner.post("$base/logout", null).statusCode())
                        assertFalse(owner.get("$replicaBase/api/v1/me")["authenticated"].asBoolean())
                        owner.login(base, "owner")
                        database.admin { connection -> connection.createStatement().use { sql ->
                            sql.executeUpdate("update smartdata_session.spring_session set expiry_time=0,last_access_time=0")
                        } }
                        assertFalse(owner.get("$base/api/v1/me")["authenticated"].asBoolean(), "Expired shared session must not authenticate")
                        for (fault in listOf("NONCE", "ISSUER", "AUDIENCE", "EXPIRED", "SIGNATURE")) {
                            provider.nextTokenFault.set(fault)
                            val rejected = Browser(provider)
                            rejected.login(base, "outsider", expectAuthenticated = false)
                            assertFalse(rejected.get("$base/api/v1/me")["authenticated"].asBoolean(), "$fault token must not authenticate")
                        }
                        assertEquals(provider.tokenExchanges.get(), provider.pkceChecks.get())
                        assertTrue(provider.pkceChecks.get() >= 7)
                        println("ENTERPRISE_HTTPS_OIDC_PKCE_SHARED_SESSION_RESTART_MEMBER_REVOCATION=PASS")
                    } finally { replica?.close(); app?.close() }
                }
            }
        } finally { PostgresIntegrationDatabase.stop() }
    }

    private fun version(node: JsonNode) = node["stateVersion"].asLong()
    private fun freePort() = ServerSocket(0).use { it.localPort }
    private fun start(properties: Map<String, String>) = SpringApplicationBuilder(RuntimeApiApplication::class.java)
        .run(*properties.map { "--${it.key}=${it.value}" }.toTypedArray())

    private fun properties(database: PostgresFixture, idp: TestOidcProvider, tenant: String, owner: String, key: String, sessionRole: String, sessionPassword: String, port: Int) = mapOf(
        "spring.profiles.active" to "enterprise,postgres", "spring.main.banner-mode" to "off", "spring.flyway.enabled" to "false",
        "server.address" to "127.0.0.1", "server.port" to port.toString(), "server.ssl.enabled" to "true", "server.ssl.key-store" to idp.keyStorePath.toUri().toString(),
        "server.ssl.key-store-password" to idp.keyStorePassword, "server.ssl.key-store-type" to "PKCS12", "server.ssl.key-alias" to "localhost",
        "logging.level.root" to "WARN", "smartdata.persistence.enabled" to "true", "smartdata.enterprise.enabled" to "true",
        "smartdata.enterprise.tenant-id" to tenant, "smartdata.enterprise.tenant-display-name" to "合成企业协作验收", "smartdata.enterprise.issuer-uri" to idp.issuer,
        "smartdata.enterprise.client-id" to idp.clientId, "smartdata.enterprise.bootstrap-owners[0]" to owner, "smartdata.enterprise.external-base-url" to "https://localhost:$port",
        "spring.datasource.url" to database.jdbcUrl, "spring.datasource.username" to database.runtimeDataSource.username, "spring.datasource.password" to database.runtimeDataSource.password,
        "smartdata.session.datasource.url" to database.jdbcUrl, "smartdata.session.datasource.username" to sessionRole, "smartdata.session.datasource.password" to sessionPassword,
        "smartdata.session.encryption-key" to key, "spring.session.jdbc.initialize-schema" to "never",
        "spring.security.oauth2.client.registration.enterprise.client-id" to idp.clientId, "spring.security.oauth2.client.registration.enterprise.client-secret" to idp.clientSecret,
        "spring.security.oauth2.client.registration.enterprise.authorization-grant-type" to "authorization_code", "spring.security.oauth2.client.registration.enterprise.scope" to "openid,profile",
        "spring.security.oauth2.client.provider.enterprise.issuer-uri" to idp.issuer,
    )

    private inner class Browser(private val idp: TestOidcProvider) {
        private val client = HttpClient.newBuilder().sslContext(idp.sslContext).cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()
        fun request(url: String, body: Any? = null, post: Boolean = false, csrf: Boolean = true, key: String = UUID.randomUUID().toString(), headers: Map<String, String> = emptyMap()): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
            headers.forEach(builder::header)
            if (post) {
                if (csrf) {
                    val origin = URI(url).let { "${it.scheme}://${it.authority}" }
                    val me = get("$origin/api/v1/me")
                    if (!me["csrf"].isNull) builder.header(me["csrf"]["headerName"].asString(), me["csrf"]["token"].asString())
                }
                builder.header("Content-Type", "application/json").header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(if (body == null) "" else json.writeValueAsString(body)))
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        fun get(url: String): JsonNode {
            val response = request(url)
            assertEquals(200, response.statusCode(), "GET ${URI(url).path}: ${response.body().take(300)}")
            return json.readTree(response.body())
        }
        fun post(url: String, body: Any?, csrf: Boolean = true) = request(url, body, post = true, csrf = csrf)
        fun command(base: String, path: String, body: Any, key: String = UUID.randomUUID().toString()): JsonNode {
            val response = request("$base/api/v1$path", body, post = true, key = key)
            assertEquals(200, response.statusCode(), "POST $path: ${response.body().take(400)}")
            return json.readTree(response.body())
        }
        fun login(base: String, subject: String, expectAuthenticated: Boolean = true) {
            val fixtureLogin = HttpRequest.newBuilder(URI("${idp.issuer}/fixture/login")).POST(HttpRequest.BodyPublishers.ofString("subject=$subject")).build()
            assertEquals(200, client.send(fixtureLogin, HttpResponse.BodyHandlers.discarding()).statusCode())
            var current = "$base/oauth2/authorization/enterprise"
            val hops = mutableListOf<String>()
            repeat(6) {
                val response = request(current)
                val stage = if (response.statusCode() == 401) {
                    val detail = runCatching { json.readTree(response.body())["detail"].asString() }.getOrNull()
                    when (detail) {
                        "企业会话已到期，请重新登录" -> "SESSION_EXPIRED"
                        "企业会话无效，请重新登录" -> "SESSION_INVALID"
                        "请先登录企业账号" -> "AUTH_REQUIRED"
                        else -> "OTHER"
                    }
                } else "none"
                hops += "${URI(current).path}:${response.statusCode()}:$stage:${response.headers().firstValue("X-Audit-Id").orElse("none")}"
                if (response.statusCode() !in 300..399) {
                    if (expectAuthenticated) {
                        val me = get("$base/api/v1/me")
                        assertTrue(me["authenticated"].asBoolean(), "Login for $subject did not complete; hops=$hops; provider authorizations=${idp.authorizations.get()}, exchanges=${idp.tokenExchanges.get()}, PKCE=${idp.pkceChecks.get()}, userInfo=${idp.userInfoRequests.get()}, rejected=${idp.rejectedRequests}")
                    }
                    return
                }
                current = URI(current).resolve(response.headers().firstValue("Location").orElseThrow()).toString()
                require(URI(current).host == "localhost") { "Test login redirected outside its owned fixtures" }
            }
            fail<Unit>("Login redirected too many times")
        }
    }
}

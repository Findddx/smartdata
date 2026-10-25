package io.smartdata.foundation.runtime.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import org.awaitility.Awaitility.await
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import tools.jackson.databind.json.JsonMapper

/** A real, disposable database. There is deliberately no Docker-absent skip. */
internal object PostgresIntegrationDatabase {
    // Build this exact delivery recipe before running integrationTest. A missing
    // local image must fail; never pull an unrelated image with the same tag.
    const val IMAGE = "localhost/smartdata-postgres:18.6-alpine3.24-r1"
    private val password = UUID.randomUUID().toString()
    private val ownershipLabel = UUID.randomUUID().toString()
    private val portable: PortablePostgres? by lazy { System.getProperty("smartdata.integration.portableMetadata")?.let(::readPortable) }
    val jdbcUrl: String get() = portable?.let { "jdbc:postgresql://127.0.0.1:${it.port}/postgres" } ?: run {
        // A Docker restart may assign another random host port. Read current
        // inspection instead of Testcontainers' cached initial port mapping.
        val inspection = container.currentContainerInfo
        check(inspection.config.labels?.get("io.smartdata.integration.owner") == ownershipLabel)
        val port = requireNotNull(inspection.networkSettings.ports.bindings[ExposedPort.tcp(5432)]?.firstOrNull()?.hostPortSpec).toInt()
        val host = container.host.let { if (':' in it) "[$it]" else it }
        "jdbc:postgresql://$host:$port/${container.databaseName}"
    }
    val username: String get() = portable?.username ?: container.username
    val adminPassword: String get() = portable?.password ?: container.password
    val container: PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
        .withImagePullPolicy { _ -> false }
        .withCreateContainerCmdModifier { command ->
            requireNotNull(command.hostConfig)
                .withMemory(2L * 1024 * 1024 * 1024)
                .withCpuPeriod(100_000L).withCpuQuota(200_000L)
                .withPortBindings(PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432)))
        }
        .withDatabaseName("smartdata_integration")
        .withUsername("migration_admin")
        .withPassword(password)
        .withLabel("io.smartdata.integration.owner", ownershipLabel)
        .withEnv("TZ", "UTC")
        .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on", "-c", "full_page_writes=on", "-c", "max_connections=50")
        .withStartupTimeout(Duration.ofSeconds(90))

    fun start() {
        if (portable == null) {
            container.start()
            val imageId = requireNotNull(container.currentContainerInfo.imageId)
            val expectedImageId = System.getenv("SMARTDATA_TEST_POSTGRES_IMAGE_ID")
            if (expectedImageId != null) {
                check(expectedImageId.matches(Regex("sha256:[a-f0-9]{64}")) && imageId == expectedImageId) { "Integration container is not the delivery image built by this job" }
            }
            val labels = requireNotNull(requireNotNull(container.dockerClient.inspectImageCmd(imageId).exec().config).labels)
            check(labels["io.smartdata.postgres.base"] == "postgres:18.6-alpine3.24@sha256:898c60cb390d1085bc97bdd6a1ae968757421df394b071848b52b1c09c89d6a5" &&
                labels["io.smartdata.gosu.source-commit"] == "6456aaa0f3c854d199d0f037f068eb97515b7513" &&
                labels["io.smartdata.gosu.go-version"] == "1.27.3" && labels["io.smartdata.brotli.version"] == "1.2.1-r0") { "Integration image is missing the frozen PostgreSQL security patch bindings" }
            println("POSTGRESQL_INTEGRATION_PROVIDER=TESTCONTAINERS_POSTGRES_18_6")
            println("POSTGRESQL_INTEGRATION_DISTRIBUTION=SMARTDATA_ALPINE_3_24_R1")
            println("POSTGRESQL_INTEGRATION_IMAGE_ID=$imageId")
        } else {
            check(!System.getenv("CI").equals("true", ignoreCase = true)) { "CI must use real Testcontainers; portable mode is local development only" }
            assertOwnedContainer()
            println("POSTGRESQL_INTEGRATION_PROVIDER=PORTABLE_POSTGRES_18_6")
        }
        admin { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select current_setting('server_version_num')::integer, current_setting('fsync'), current_setting('synchronous_commit'), current_setting('full_page_writes')").use { row ->
                    assertTrue(row.next())
                    assertEquals(180006, row.getInt(1), "the pinned image must run PostgreSQL 18.6")
                    assertEquals("on", row.getString(2), "durability tests must not use Testcontainers' fsync=off default")
                    assertEquals("on", row.getString(3))
                    assertEquals("on", row.getString(4))
                }
            }
        }
    }

    fun stop() {
        if (portable == null && container.isRunning) container.stop()
    }

    fun assertOwnedContainer() {
        portable?.let { local ->
            val pid = Files.readAllLines(local.dataDir.resolve("postmaster.pid")).first().trim().toLong()
            check(pid == local.pid) { "Portable PostgreSQL PID no longer matches the owned startup instance" }
            val process = ProcessHandle.of(pid).orElseThrow { IllegalStateException("Owned portable PostgreSQL process is not running") }
            // Windows ProcessHandle does not reliably expose another process's
            // arguments. Match its actual executable, PID/start, SQL PGDATA and
            // cluster identifier instead of treating absent arguments as proof.
            val executable = Path.of(process.info().command().orElseThrow { IllegalStateException("Cannot inspect owned PostgreSQL executable") }).toRealPath()
            check(executable == local.bin.resolve("postgres.exe").toRealPath()) { "Portable PostgreSQL executable does not match the verified toolchain" }
            check(Duration.between(process.info().startInstant().orElseThrow(), local.processStartedAt).abs() < Duration.ofSeconds(1)) { "Portable PostgreSQL process start does not match" }
            admin { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("select system_identifier::text, current_setting('data_directory') from pg_control_system()").use { row ->
                    check(row.next() && row.getString(1) == local.systemIdentifier) { "Portable database cluster identity changed" }
                    check(Path.of(row.getString(2)).toRealPath() == local.dataDir) { "Connected PostgreSQL is not the owned PGDATA directory" }
                }
            } }
            return
        }
        assertEquals(ownershipLabel, container.currentContainerInfo.config.labels?.get("io.smartdata.integration.owner"))
    }

    fun crashAndRestart() {
        assertOwnedContainer()
        portable?.let { local ->
            fun control(timeoutSeconds: Long, vararg arguments: String) {
                val process = ProcessBuilder(listOf(local.bin.resolve("pg_ctl.exe").toString(), "-D", local.dataDir.toString()) + arguments)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(local.dataDir.parent.resolve("test-pg-ctl.log").toFile())).start()
                check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS) && process.exitValue() == 0) { "Owned portable PostgreSQL control failed" }
            }
            control(20, "-m", "immediate", "-w", "-t", "15", "stop")
            control(95, "-l", local.dataDir.parent.resolve("server.log").toString(), "-o", "-h 127.0.0.1 -p ${local.port} -c fsync=on -c synchronous_commit=on -c full_page_writes=on", "-w", "-t", "90", "start")
            local.pid = Files.readAllLines(local.dataDir.resolve("postmaster.pid")).first().trim().toLong()
            local.processStartedAt = ProcessHandle.of(local.pid).orElseThrow().info().startInstant().orElseThrow()
            assertOwnedContainer()
            val updated = JsonMapper.builder().build().createObjectNode()
                .put("pid", local.pid).put("bin", local.bin.toString()).put("dataDir", local.dataDir.toString())
                .put("port", local.port).put("host", "127.0.0.1").put("username", local.username).put("password", local.password)
                .put("systemIdentifier", local.systemIdentifier).put("processStartedAt", local.processStartedAt.toString())
                .put("sourceArchiveSha256", "e2246ba91d22345bc3d017586c09ede52d9df180b1eeb480f050445f1cad84e2")
            Files.writeString(local.metadataPath, updated.toString())
            return
        }
        container.dockerClient.killContainerCmd(container.containerId).withSignal("KILL").exec()
        await().atMost(Duration.ofSeconds(15)).until { container.currentContainerInfo.state.running == false }
        assertOwnedContainer()
        container.dockerClient.startContainerCmd(container.containerId).exec()
        await().atMost(Duration.ofSeconds(15)).until { container.currentContainerInfo.state.running == true }
        assertOwnedContainer()
    }

    fun <T> admin(block: (Connection) -> T): T =
        DriverManager.getConnection(jdbcUrl, username, adminPassword).use(block)

    fun fixture(target: String? = null): PostgresFixture {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val database = "it_$suffix"
        val runtimeRole = "runtime_$suffix"
        val runtimePassword = UUID.randomUUID().toString()
        admin { connection ->
            connection.createStatement().use { statement ->
                statement.execute("create database \"$database\"")
                statement.execute("create role \"$runtimeRole\" login nosuperuser nobypassrls nocreatedb nocreaterole password '$runtimePassword'")
            }
        }
        val originalUrl = jdbcUrl
        val query = originalUrl.substringAfter('?', "")
        val jdbcUrl = originalUrl.substringBefore('?').substringBeforeLast('/') + "/$database" + if (query.isEmpty()) "" else "?$query"
        val fixture = PostgresFixture("public", jdbcUrl, runtimeRole, runtimePassword)
        fixture.admin { connection -> connection.createStatement().use { statement ->
            statement.execute("revoke create on schema public from public")
            statement.execute("grant usage on schema public to \"$runtimeRole\"")
        } }
        fixture.migrate(target)
        fixture.grantRuntimeAccess()
        return fixture
    }

    private data class PortablePostgres(val metadataPath: Path, val bin: Path, val dataDir: Path, val port: Int,
        val username: String, val password: String, val systemIdentifier: String, var pid: Long, var processStartedAt: Instant)
    private fun readPortable(text: String): PortablePostgres {
        check(!System.getenv("CI").equals("true", ignoreCase = true)) { "CI cannot use portable PostgreSQL" }
        val metadataPath = Path.of(text).toRealPath()
        val root = metadataPath.parent.parent.toRealPath()
        val ownedRoot = Path.of(requireNotNull(System.getProperty("smartdata.integration.checkoutRoot"))).resolve(".local").toRealPath()
        check(root == ownedRoot) { "Portable PostgreSQL must belong to this exact test checkout" }
        check(root.fileName.toString() == ".local" && metadataPath.parent.fileName.toString() == "pg-integration-dev" && metadataPath.fileName.toString() == "server.json") { "Only this round's owned .local/pg-integration-dev metadata is supported" }
        val metadata = JsonMapper.builder().build().readTree(Files.readString(metadataPath))
        check(metadata["host"].asString() == "127.0.0.1" && metadata["sourceArchiveSha256"].asString() == "e2246ba91d22345bc3d017586c09ede52d9df180b1eeb480f050445f1cad84e2") { "Only the explicitly verified local PostgreSQL 18.6-5 archive is supported" }
        val bin = Path.of(metadata["bin"].asString()).toRealPath()
        val data = Path.of(metadata["dataDir"].asString()).toRealPath()
        check(bin == root.resolve("toolchains/postgresql-18.6-5/pgsql/bin").toRealPath() && data == metadataPath.parent.resolve("data").toRealPath()) { "Portable PostgreSQL paths do not match this round's exact owned directories" }
        val port = metadata["port"].asInt()
        check(port == 18432)
        return PortablePostgres(metadataPath, bin, data, port, metadata["username"].asString(), metadata["password"].asString(),
            metadata["systemIdentifier"].asString(), metadata["pid"].asLong(), Instant.parse(metadata["processStartedAt"].asString()))
    }
}

internal class PostgresFixture(
    val schema: String,
    private val originalJdbcUrl: String,
    val runtimeRole: String,
    private val runtimePassword: String,
) : AutoCloseable {
    val jdbcUrl: String get() = PostgresIntegrationDatabase.jdbcUrl.substringBefore('?').substringBeforeLast('/') + "/" +
        originalJdbcUrl.substringBefore('?').substringAfterLast('/') + originalJdbcUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
    val runtimeDataSource: HikariDataSource = dataSource(runtimeRole, runtimePassword)

    fun migrate(target: String? = null) {
        val configuration = Flyway.configure()
            .dataSource(jdbcUrl, PostgresIntegrationDatabase.username, PostgresIntegrationDatabase.adminPassword)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .createSchemas(false)
            .cleanDisabled(true)
        if (target != null) configuration.target(target)
        configuration.load().also { it.migrate(); it.validate() }
    }

    fun grantRuntimeAccess() = admin { connection ->
        val tables = connection.prepareStatement("select tablename from pg_tables where schemaname = ? and tablename <> 'flyway_schema_history'").use { statement ->
            statement.setString(1, schema)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        connection.createStatement().use { statement ->
            tables.forEach { table ->
                val privileges = when (table) {
                    "tenant" -> "select"
                    "audit_event", "approval_decision" -> "select, insert"
                    else -> "select, insert, update, delete"
                }
                statement.execute("grant $privileges on table \"$schema\".\"$table\" to \"$runtimeRole\"")
            }
        }
    }

    fun seedTenant(tenantId: String) = admin { connection ->
        connection.prepareStatement("insert into tenant(id, display_name, status, created_at) values (?, ?, 'ACTIVE', current_timestamp)").use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, "合成集成测试企业")
            statement.executeUpdate()
        }
    }

    fun <T> admin(block: (Connection) -> T): T = DriverManager.getConnection(jdbcUrl,
        PostgresIntegrationDatabase.username, PostgresIntegrationDatabase.adminPassword).use(block)

    fun dataSource(username: String = runtimeRole, password: String = runtimePassword): HikariDataSource =
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = this@PostgresFixture.jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 4
            minimumIdle = 0
            connectionTimeout = 5_000
            validationTimeout = 2_000
            initializationFailTimeout = 10_000
            poolName = "pg-it-${UUID.randomUUID().toString().take(8)}"
        })

    override fun close() = runtimeDataSource.close()
}

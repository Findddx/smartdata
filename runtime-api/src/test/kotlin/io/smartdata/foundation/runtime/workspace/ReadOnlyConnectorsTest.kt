package io.smartdata.foundation.runtime.workspace

import com.sun.net.httpserver.HttpServer
import io.smartdata.foundation.service.BusinessValidationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.net.InetSocketAddress
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

class ReadOnlyConnectorsTest {
    private fun binding(kind: SourceKind, endpoint: String, reference: String) = CredentialBinding(
        "tenant-a", kind, canonicalCredentialEndpoint(endpoint, kind),
        if (kind == SourceKind.JDBC_POSTGRES_READONLY) "public" else "-", reference,
    )
    private fun source(kind: SourceKind, endpoint: String, secret: String? = null, user: String? = null) = SourceRecord(
        "source-test", "真实测试源", kind, Provenance.USER, endpoint, secret, user, emptyList(), null, "id", null, 24, "public", tenantId = "tenant-a")

    @Test
    fun `HTTP GET uses env credentials without copying secrets into response and refuses redirects`() {
        val redirected = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/records") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("Bearer private-secret", exchange.requestHeaders.getFirst("Authorization"))
            val bytes = "[{\"id\":\"actual-1\",\"amount\":25}]".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/sensitive")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/sensitive") { exchange -> redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            val connector = HttpJsonSource(setOf("127.0.0.1"), setOf(binding(SourceKind.HTTP_JSON,
                "http://127.0.0.1:${server.address.port}/records", "SMARTDATA_CONNECTOR_TEST_TOKEN"))) { name -> if (name == "SMARTDATA_CONNECTOR_TEST_TOKEN") "private-secret" else null }
            val result = connector.observe(source(SourceKind.HTTP_JSON, "http://127.0.0.1:${server.address.port}/records", "SMARTDATA_CONNECTOR_TEST_TOKEN"))
            assertEquals("actual-1", result.single().rows.single()["id"])
            assertNull(result.single().totalRows, "a JSON API response cannot prove total source size")
            assertEquals("API_RESPONSE_ONLY_TOTAL_UNKNOWN", result.single().sampling)
            assertThrows(BusinessValidationException::class.java) {
                connector.observe(source(SourceKind.HTTP_JSON, "http://127.0.0.1:${server.address.port}/redirect"))
            }
            assertEquals(0, redirected.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `HTTP denied host embedded credentials query and wrong environment reference are rejected`() {
        val connector = HttpJsonSource(setOf("127.0.0.1"))
        listOf("http://localhost/records", "http://user:pass@127.0.0.1/records", "http://127.0.0.1/records?token=secret").forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) { connector.validate(source(SourceKind.HTTP_JSON, endpoint)) }
        }
        assertThrows(IllegalArgumentException::class.java) { connector.validate(source(SourceKind.HTTP_JSON, "http://127.0.0.1/records", "AWS_SECRET_ACCESS_KEY")) }
        assertThrows(IllegalArgumentException::class.java) { HttpJsonSource(emptySet()).validate(source(SourceKind.HTTP_JSON, "http://127.0.0.1/records")) }
    }

    @Test
    fun `HTTP response byte and object boundaries are enforced`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/oversized") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, (MAX_SOURCE_BYTES + 1).toLong())
            exchange.close()
        }
        server.createContext("/nested") { exchange ->
            val bytes = "[{\"id\":1,\"payload\":{\"secret\":1}}]".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val connector = HttpJsonSource(setOf("127.0.0.1"))
            assertThrows(BusinessValidationException::class.java) { connector.observe(source(SourceKind.HTTP_JSON, "http://127.0.0.1:${server.address.port}/oversized")) }
            assertThrows(IllegalArgumentException::class.java) { connector.observe(source(SourceKind.HTTP_JSON, "http://127.0.0.1:${server.address.port}/nested")) }
        } finally { server.stop(0) }
    }

    @Test
    fun `Postgres adapter uses read only transaction bounded fixed SQL and quotes metadata identifiers`() {
        val connection = Mockito.mock(Connection::class.java)
        val metadata = Mockito.mock(DatabaseMetaData::class.java)
        val tables = Mockito.mock(ResultSet::class.java)
        val sourceColumns = Mockito.mock(ResultSet::class.java)
        val statement = Mockito.mock(Statement::class.java)
        val rows = Mockito.mock(ResultSet::class.java)
        val columns = Mockito.mock(ResultSetMetaData::class.java)
        Mockito.`when`(connection.metaData).thenReturn(metadata)
        Mockito.`when`(metadata.getTables(null, "public", "%", arrayOf("TABLE"))).thenReturn(tables)
        Mockito.`when`(tables.next()).thenReturn(true, false)
        Mockito.`when`(tables.getString("TABLE_NAME")).thenReturn("orders\";DROP TABLE accounts;--")
        Mockito.`when`(metadata.getColumns(null, "public", "orders\";DROP TABLE accounts;--", "%")).thenReturn(sourceColumns)
        Mockito.`when`(sourceColumns.next()).thenReturn(true, false)
        Mockito.`when`(sourceColumns.getString("COLUMN_NAME")).thenReturn("id")
        Mockito.`when`(sourceColumns.getInt("DATA_TYPE")).thenReturn(java.sql.Types.INTEGER)
        Mockito.`when`(connection.createStatement()).thenReturn(statement)
        val expectedSql = "SELECT \"id\" FROM \"public\".\"orders\"\";DROP TABLE accounts;--\" LIMIT 1001"
        Mockito.`when`(statement.executeQuery(expectedSql)).thenReturn(rows)
        Mockito.`when`(rows.metaData).thenReturn(columns)
        Mockito.`when`(columns.columnCount).thenReturn(1)
        Mockito.`when`(columns.getColumnLabel(1)).thenReturn("id")
        Mockito.`when`(rows.next()).thenReturn(true, false)
        Mockito.`when`(rows.getObject(1)).thenReturn(42)
        var receivedProperties: Properties? = null
        val driver = object : Driver {
            override fun acceptsURL(url: String?) = url == "jdbc:postgresql://127.0.0.1:5432/adapter_test"
            override fun connect(url: String?, info: Properties?): Connection? {
                if (!acceptsURL(url)) return null
                receivedProperties = info
                return connection
            }
            override fun getPropertyInfo(url: String?, info: Properties?) = emptyArray<DriverPropertyInfo>()
            override fun getMajorVersion() = 1
            override fun getMinorVersion() = 0
            override fun jdbcCompliant() = false
            override fun getParentLogger(): Logger = Logger.getLogger("test")
        }
        DriverManager.registerDriver(driver)
        try {
            val connector = PostgresReadOnlySource(setOf("127.0.0.1"), setOf(
                binding(SourceKind.JDBC_POSTGRES_READONLY, "jdbc:postgresql://127.0.0.1:5432/adapter_test", "SMARTDATA_CONNECTOR_DB_USER"),
                binding(SourceKind.JDBC_POSTGRES_READONLY, "jdbc:postgresql://127.0.0.1:5432/adapter_test", "SMARTDATA_CONNECTOR_DB_PASSWORD"),
            )) { name -> when (name) {
                "SMARTDATA_CONNECTOR_DB_USER" -> "readonly"
                "SMARTDATA_CONNECTOR_DB_PASSWORD" -> "test-password"
                else -> null
            } }
            val result = connector.observe(source(SourceKind.JDBC_POSTGRES_READONLY, "jdbc:postgresql://127.0.0.1:5432/adapter_test",
                "SMARTDATA_CONNECTOR_DB_PASSWORD", "SMARTDATA_CONNECTOR_DB_USER"))
            assertEquals(42, result.single().rows.single()["id"])
            Mockito.verify(connection).setReadOnly(true)
            Mockito.verify(connection).setAutoCommit(false)
            Mockito.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
            Mockito.verify(connection).rollback()
            Mockito.verify(statement).setQueryTimeout(5)
            Mockito.verify(statement).setMaxRows(1001)
            Mockito.verify(statement).setFetchSize(32)
            Mockito.verify(statement).executeQuery(expectedSql)
            assertEquals("always", receivedProperties?.getProperty("readOnlyMode"))
            assertTrue(receivedProperties?.getProperty("options").orEmpty().contains("default_transaction_read_only=on"))
        } finally { DriverManager.deregisterDriver(driver) }
    }

    @Test
    fun `Postgres rejects arbitrary JDBC drivers and source controlled connection properties`() {
        val connector = PostgresReadOnlySource(setOf("127.0.0.1"))
        listOf("jdbc:h2:mem:test", "jdbc:postgresql://127.0.0.1/business?options=-c%20default_transaction_read_only=off").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { connector.validate(source(SourceKind.JDBC_POSTGRES_READONLY, url,
                "SMARTDATA_CONNECTOR_DB_PASSWORD", "SMARTDATA_CONNECTOR_DB_USER")) }
        }
        assertEquals("\"a\"\"b\"", quoteIdentifier("a\"b"))
    }

    @Test
    fun `credential binding cannot be reused for another route port tenant connector database or schema`() {
        val token = "SMARTDATA_CONNECTOR_ROUTE_TOKEN"
        val http = HttpJsonSource(setOf("127.0.0.1"), setOf(binding(SourceKind.HTTP_JSON,
            "http://127.0.0.1:8087/orders", token)))
        http.validate(source(SourceKind.HTTP_JSON, "http://127.0.0.1:8087/orders", token))
        listOf("http://127.0.0.1:8087/admin", "http://127.0.0.1:8088/orders", "https://127.0.0.1:8087/orders").forEach { target ->
            assertThrows(IllegalArgumentException::class.java) { http.validate(source(SourceKind.HTTP_JSON, target, token)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            http.validate(source(SourceKind.HTTP_JSON, "http://127.0.0.1:8087/orders", token).copy(tenantId = "tenant-b"))
        }
        val endpoint = "jdbc:postgresql://127.0.0.1:5432/adapter_test"
        val password = "SMARTDATA_CONNECTOR_DB_PASSWORD"
        val user = "SMARTDATA_CONNECTOR_DB_USER"
        val postgres = PostgresReadOnlySource(setOf("127.0.0.1"), setOf(binding(SourceKind.JDBC_POSTGRES_READONLY, endpoint, password),
            binding(SourceKind.JDBC_POSTGRES_READONLY, endpoint, user)))
        postgres.validate(source(SourceKind.JDBC_POSTGRES_READONLY, endpoint, password, user))
        assertThrows(IllegalArgumentException::class.java) { postgres.validate(source(SourceKind.JDBC_POSTGRES_READONLY, endpoint.replace("adapter_test", "payroll"), password, user)) }
        assertThrows(IllegalArgumentException::class.java) { postgres.validate(source(SourceKind.JDBC_POSTGRES_READONLY, endpoint, password, user).copy(schema = "payroll")) }
        assertThrows(IllegalArgumentException::class.java) {
            HttpJsonSource(setOf("127.0.0.1"), setOf(binding(SourceKind.JDBC_POSTGRES_READONLY, endpoint, password)))
                .validate(source(SourceKind.HTTP_JSON, "http://127.0.0.1:5432/adapter_test", password))
        }
        assertEquals("https://example.org:443/records", canonicalCredentialEndpoint("https://EXAMPLE.org/records", SourceKind.HTTP_JSON))
    }

    @Test
    fun `only a numeric loopback literal can disable transport TLS`() {
        assertTrue(isLiteralLoopback("127.0.0.1"))
        listOf("localhost", "127.0.0.2", "loopback.example.org", "10.0.0.1").forEach { assertFalse(isLiteralLoopback(it)) }
        assertThrows(BusinessValidationException::class.java) {
            HttpJsonSource(setOf("localhost")).validate(source(SourceKind.HTTP_JSON, "http://localhost/records"))
        }
    }

    @Test
    fun `Postgres timezone aware timestamps are normalized without guessing zones for local timestamps`() {
        val connection = Mockito.mock(Connection::class.java)
        val metadata = Mockito.mock(DatabaseMetaData::class.java)
        val tables = Mockito.mock(ResultSet::class.java)
        val sourceColumns = Mockito.mock(ResultSet::class.java)
        val statement = Mockito.mock(Statement::class.java)
        val rows = Mockito.mock(ResultSet::class.java)
        val columns = Mockito.mock(ResultSetMetaData::class.java)
        Mockito.`when`(connection.metaData).thenReturn(metadata)
        Mockito.`when`(metadata.getTables(null, "public", "%", arrayOf("TABLE"))).thenReturn(tables)
        Mockito.`when`(tables.next()).thenReturn(true, false)
        Mockito.`when`(tables.getString("TABLE_NAME")).thenReturn("events")
        Mockito.`when`(metadata.getColumns(null, "public", "events", "%")).thenReturn(sourceColumns)
        Mockito.`when`(sourceColumns.next()).thenReturn(true, true, false)
        Mockito.`when`(sourceColumns.getString("COLUMN_NAME")).thenReturn("aware_at", "local_at")
        Mockito.`when`(sourceColumns.getInt("DATA_TYPE")).thenReturn(java.sql.Types.TIMESTAMP_WITH_TIMEZONE, java.sql.Types.TIMESTAMP)
        Mockito.`when`(connection.createStatement()).thenReturn(statement)
        Mockito.`when`(statement.executeQuery("SELECT \"aware_at\",\"local_at\" FROM \"public\".\"events\" LIMIT 1001")).thenReturn(rows)
        Mockito.`when`(rows.metaData).thenReturn(columns)
        Mockito.`when`(columns.columnCount).thenReturn(2)
        Mockito.`when`(columns.getColumnLabel(1)).thenReturn("aware_at")
        Mockito.`when`(columns.getColumnLabel(2)).thenReturn("local_at")
        Mockito.`when`(columns.getColumnType(1)).thenReturn(java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        Mockito.`when`(columns.getColumnType(2)).thenReturn(java.sql.Types.TIMESTAMP)
        Mockito.`when`(columns.getColumnTypeName(1)).thenReturn("timestamptz")
        Mockito.`when`(columns.getColumnTypeName(2)).thenReturn("timestamp")
        Mockito.`when`(rows.next()).thenReturn(true, false)
        Mockito.`when`(rows.getObject(1, java.time.OffsetDateTime::class.java)).thenReturn(java.time.OffsetDateTime.parse("2026-10-25T16:00:00+08:00"))
        Mockito.`when`(rows.getObject(2, java.time.LocalDateTime::class.java)).thenReturn(java.time.LocalDateTime.parse("2026-10-25T08:00:00"))
        val endpoint = "jdbc:postgresql://127.0.0.1:5432/time_test"
        val driver = object : Driver {
            override fun acceptsURL(url: String?) = url == endpoint
            override fun connect(url: String?, info: Properties?): Connection? = if (acceptsURL(url)) connection else null
            override fun getPropertyInfo(url: String?, info: Properties?) = emptyArray<DriverPropertyInfo>()
            override fun getMajorVersion() = 1
            override fun getMinorVersion() = 0
            override fun jdbcCompliant() = false
            override fun getParentLogger(): Logger = Logger.getLogger("test-time")
        }
        DriverManager.registerDriver(driver)
        try {
            val user = "SMARTDATA_CONNECTOR_DB_USER"
            val password = "SMARTDATA_CONNECTOR_DB_PASSWORD"
            val adapter = PostgresReadOnlySource(setOf("127.0.0.1"), setOf(binding(SourceKind.JDBC_POSTGRES_READONLY, endpoint, user),
                binding(SourceKind.JDBC_POSTGRES_READONLY, endpoint, password))) { "test-value" }
            val observations = adapter.observe(source(SourceKind.JDBC_POSTGRES_READONLY, endpoint, password, user))
            assertEquals("2026-10-25T08:00:00Z", observations.single().rows.single()["aware_at"])
            assertEquals("2026-10-25T08:00", observations.single().rows.single()["local_at"])
            val clock = java.time.Clock.fixed(java.time.Instant.now(), java.time.ZoneOffset.UTC)
            val awareProfile = DeterministicAssetProfiler(clock).profile(source(SourceKind.JDBC_POSTGRES_READONLY, endpoint, password, user)
                .copy(freshnessColumn = "aware_at"), observations.single(), "aware-scan")
            assertFalse(awareProfile.findings.any { it.ruleId == "DQ.INVALID_TIMESTAMP.v1" })
            val localProfile = DeterministicAssetProfiler(clock).profile(source(SourceKind.JDBC_POSTGRES_READONLY, endpoint, password, user)
                .copy(freshnessColumn = "local_at"), observations.single(), "local-scan")
            assertTrue(localProfile.findings.any { it.ruleId == "DQ.INVALID_TIMESTAMP.v1" })
        } finally { DriverManager.deregisterDriver(driver) }
    }
}

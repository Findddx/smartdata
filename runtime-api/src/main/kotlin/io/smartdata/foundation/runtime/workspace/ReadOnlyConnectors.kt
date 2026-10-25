package io.smartdata.foundation.runtime.workspace

import io.smartdata.foundation.service.BusinessValidationException
import tools.jackson.databind.json.JsonMapper
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.sql.DriverManager
import java.sql.Types
import java.time.Instant
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ObservedAsset(
    val name: String,
    val rows: List<Map<String, Any?>>,
    val observedAt: String,
    val totalRows: Long?,
    val sampling: String,
)

/** An extension can add a source adapter, but receives no model-generated SQL or script. */
interface ReadOnlySourcePort {
    val kind: SourceKind
    fun validate(source: SourceRecord)
    fun observe(source: SourceRecord): List<ObservedAsset>
}

/** Each secret is granted to one tenant, connector capability and exact target. */
data class CredentialBinding(
    val tenantId: String,
    val kind: SourceKind,
    val endpoint: String,
    val schema: String,
    val environmentReference: String,
)

class JsonSnapshotSource : ReadOnlySourcePort {
    override val kind = SourceKind.JSON_SNAPSHOT
    override fun validate(source: SourceRecord) {
        require(source.endpoint == null && source.credentialEnvRef == null && source.usernameEnvRef == null) { "快照无需端点或凭据" }
        validateRows(source.snapshotRows)
        requireNotNull(source.observedAt) { "请提供快照实际采集时间 observedAt" }
        Instant.parse(source.observedAt)
    }

    override fun observe(source: SourceRecord): List<ObservedAsset> = listOf(ObservedAsset(
        source.name, source.snapshotRows, requireNotNull(source.observedAt), source.snapshotRows.size.toLong(), "UPLOADED_SNAPSHOT_COMPLETE",
    ))
}

/** Exact host names are configured by the server operator, never supplied as an access grant by a client. */
class HttpJsonSource(
    private val allowedHosts: Set<String>,
    private val credentialBindings: Set<CredentialBinding> = emptySet(),
    private val environment: (String) -> String? = System::getenv,
) : ReadOnlySourcePort {
    override val kind = SourceKind.HTTP_JSON
    private val mapper = JsonMapper.builder().build()

    override fun validate(source: SourceRecord) {
        val uri = checkedEndpoint(source.endpoint, allowedHosts, setOf("https", "http"))
        if (uri.scheme == "http" && !isLiteralLoopback(uri.host)) {
            throw BusinessValidationException("HTTP_JSON 远程源必须使用 HTTPS；本地回环测试可使用 HTTP")
        }
        validateEnvRef(source.credentialEnvRef)
        requireCredentialBindings(source, credentialBindings)
        require(source.usernameEnvRef == null && source.snapshotRows.isEmpty()) { "HTTP_JSON 不接受用户名或快照混合配置" }
    }

    override fun observe(source: SourceRecord): List<ObservedAsset> {
        validate(source)
        val connection = URI.create(requireNotNull(source.endpoint)).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        source.credentialEnvRef?.let { reference ->
            val secret = environment(reference)?.takeIf { it.isNotBlank() && !it.contains('\r') && !it.contains('\n') }
                ?: throw BusinessValidationException("连接器凭据环境变量未配置")
            connection.setRequestProperty("Authorization", "Bearer $secret")
        }
        // A total deadline also covers a peer that trickles bytes below the socket timeout.
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val future = executor.submit<List<ObservedAsset>> {
            try {
                val status = connection.responseCode
                if (status != 200) throw BusinessValidationException("只读源返回非成功状态（$status）；未跟随重定向")
                if (connection.contentLengthLong > MAX_SOURCE_BYTES) throw BusinessValidationException("只读源响应超过 1 MiB 限制")
                val contentType = connection.contentType.orEmpty().substringBefore(';').lowercase()
                require(contentType == "application/json" || contentType.endsWith("+json")) { "只读源必须返回 JSON 内容类型" }
                val bytes = connection.inputStream.use { stream -> stream.readNBytes(MAX_SOURCE_BYTES + 1) }
                require(bytes.size <= MAX_SOURCE_BYTES) { "只读源响应超过 1 MiB 限制" }
                val root = mapper.readTree(bytes)
                val array = if (root.isArray) root else root.get("rows")
                require(array != null && array.isArray) { "JSON 必须为对象行数组，或包含 rows 数组" }
                require(array.size() <= MAX_SAMPLE_ROWS) { "源返回超过 1000 行，请在源 API 配置有界分页或提供快照" }
                val rows: List<Map<String, Any?>> = array.values().map { row ->
                    require(row.isObject) { "每行必须是对象" }
                    @Suppress("UNCHECKED_CAST")
                    (mapper.convertValue(row, Map::class.java) as Map<String, Any?>)
                }
                validateRows(rows)
                listOf(ObservedAsset(source.name, rows, Instant.now().toString(), null, "API_RESPONSE_ONLY_TOTAL_UNKNOWN"))
            } finally {
                connection.disconnect()
            }
        }
        try {
            return future.get(12, TimeUnit.SECONDS)
        } catch (failure: java.util.concurrent.ExecutionException) {
            if (failure.cause is java.io.IOException) throw BusinessValidationException("只读来源传输中断，响应未完整读取；请重新核对来源")
            throw failure.cause ?: failure
        } catch (_: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            connection.disconnect()
            throw BusinessValidationException("只读源超过 12 秒总时限；本次扫描未完成")
        } finally {
            executor.shutdownNow()
        }
    }
}

/** PostgreSQL metadata + bounded SELECT only. No model or API client can submit SQL. */
class PostgresReadOnlySource(
    private val allowedHosts: Set<String>,
    private val credentialBindings: Set<CredentialBinding> = emptySet(),
    private val environment: (String) -> String? = System::getenv,
) : ReadOnlySourcePort {
    override val kind = SourceKind.JDBC_POSTGRES_READONLY

    override fun validate(source: SourceRecord) {
        val raw = requireNotNull(source.endpoint) { "PostgreSQL 需要 JDBC 端点" }
        require(raw.startsWith("jdbc:postgresql://")) { "仅支持 PostgreSQL JDBC 网络端点" }
        val uri = checkedEndpoint(raw.removePrefix("jdbc:"), allowedHosts, setOf("postgresql"))
        require(uri.path.matches(Regex("/[A-Za-z0-9_][A-Za-z0-9_.-]{0,62}"))) { "数据库名格式无效" }
        validateEnvRef(source.usernameEnvRef)
        validateEnvRef(source.credentialEnvRef)
        require(source.usernameEnvRef != null && source.credentialEnvRef != null) { "PostgreSQL 用户名和密码必须使用环境变量引用" }
        requireCredentialBindings(source, credentialBindings)
        require(source.snapshotRows.isEmpty()) { "PostgreSQL 不接受混合快照" }
        require(source.schema.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,62}")) && source.schema !in setOf("pg_catalog", "information_schema")) { "请指定业务 schema" }
    }

    override fun observe(source: SourceRecord): List<ObservedAsset> {
        validate(source)
        val uri = URI.create(requireNotNull(source.endpoint).removePrefix("jdbc:"))
        // Never downgrade TLS based on DNS answers: a name can resolve to a
        // different peer between validation and the JDBC driver's connection.
        val local = isLiteralLoopback(uri.host)
        val properties = Properties().apply {
            setProperty("user", environment(requireNotNull(source.usernameEnvRef))?.takeIf(String::isNotBlank)
                ?: throw BusinessValidationException("数据库用户环境变量未配置"))
            setProperty("password", environment(requireNotNull(source.credentialEnvRef))?.takeIf(String::isNotBlank)
                ?: throw BusinessValidationException("数据库密码环境变量未配置"))
            setProperty("connectTimeout", "5")
            setProperty("socketTimeout", "5")
            setProperty("sslmode", if (local) "disable" else "verify-full")
            setProperty("readOnlyMode", "always")
            setProperty("options", "-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=2000")
            setProperty("ApplicationName", "smartdata-readonly-inventory")
        }
        try {
            DriverManager.getConnection(source.endpoint, properties).use { connection ->
                connection.isReadOnly = true
                connection.autoCommit = false
                connection.transactionIsolation = java.sql.Connection.TRANSACTION_REPEATABLE_READ
                try {
                    val tables = mutableListOf<String>()
                    connection.metaData.getTables(null, source.schema, "%", arrayOf("TABLE")).use { result ->
                        while (result.next()) {
                            require(tables.size < 20) { "单次最多扫描 20 个表，请缩小业务 schema" }
                            tables += result.getString("TABLE_NAME")
                        }
                    }
                    val observedAt = Instant.now().toString()
                    val assets = tables.sorted().map { table ->
                        val projection = mutableListOf<String>()
                        var valuesLimited = false
                        connection.metaData.getColumns(null, source.schema, table, "%").use { columns ->
                            while (columns.next()) {
                                require(projection.size < 128) { "单表超过 128 列，请提供业务只读视图" }
                                val name = columns.getString("COLUMN_NAME")
                                val quoted = quoteIdentifier(name)
                                projection += when (columns.getInt("DATA_TYPE")) {
                                    Types.BOOLEAN, Types.BIT, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                                    Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> quoted
                                    Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> quoted
                                    Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                                        valuesLimited = true
                                        "CASE WHEN $quoted IS NULL THEN NULL ELSE '[binary]' END AS $quoted"
                                    }
                                    else -> {
                                        valuesLimited = true
                                        "LEFT(CAST($quoted AS text),4096) AS $quoted"
                                    }
                                }
                            }
                        }
                        require(projection.isNotEmpty()) { "没有可读取的业务列" }
                        val rows = mutableListOf<Map<String, Any?>>()
                        val sql = "SELECT ${projection.joinToString(",")} FROM ${quoteIdentifier(source.schema)}.${quoteIdentifier(table)} LIMIT ${MAX_SAMPLE_ROWS + 1}"
                        var sampledBytes = 0
                        connection.createStatement().use { statement ->
                            statement.queryTimeout = 5
                            statement.maxRows = MAX_SAMPLE_ROWS + 1
                            statement.fetchSize = 32
                            statement.executeQuery(sql).use { result ->
                                val metadata = result.metaData
                                require(metadata.columnCount <= 128) { "单表超过 128 列，请提供业务只读视图" }
                                while (result.next()) {
                                    val row = linkedMapOf<String, Any?>()
                                    for (index in 1..metadata.columnCount) {
                                        val columnType = metadata.getColumnType(index)
                                        val columnTypeName = metadata.getColumnTypeName(index).orEmpty()
                                        val value = if (columnType == Types.TIMESTAMP_WITH_TIMEZONE || columnTypeName == "timestamptz") {
                                            // PostgreSQL's text cast uses a space and driver-specific
                                            // offset spelling. Read its actual timezone-aware type.
                                            result.getObject(index, java.time.OffsetDateTime::class.java)?.toInstant()?.toString()
                                        } else if (columnType == Types.TIMESTAMP) {
                                            // No zone is attached: preserve that uncertainty instead
                                            // of silently interpreting a local timestamp as UTC.
                                            result.getObject(index, java.time.LocalDateTime::class.java)?.toString()
                                        } else result.getObject(index)
                                        row[metadata.getColumnLabel(index)] = when (value) {
                                            null, is Number, is Boolean -> value
                                            is ByteArray -> "[binary:${value.size}]"
                                            is java.sql.Date -> value.toLocalDate().toString()
                                            is java.sql.Time -> value.toLocalTime().toString()
                                            else -> value.toString().take(4096)
                                        }
                                        sampledBytes += metadata.getColumnLabel(index).toByteArray(Charsets.UTF_8).size + (row[metadata.getColumnLabel(index)]?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0)
                                        require(sampledBytes <= MAX_SOURCE_BYTES) { "单表采样超过 1 MiB，请提供列更少的业务只读视图" }
                                    }
                                    rows += row
                                }
                            }
                        }
                        val truncated = rows.size > MAX_SAMPLE_ROWS
                        val sampling = if (truncated) "UNORDERED_LIMIT_1000_TOTAL_UNKNOWN" else "TABLE_ROWS_COMPLETE_UP_TO_1000"
                        ObservedAsset("${source.schema}.$table", rows.take(MAX_SAMPLE_ROWS), observedAt,
                            if (truncated) null else rows.size.toLong(), sampling + if (valuesLimited) "_VALUES_CAPPED_OR_MASKED" else "")
                    }
                    connection.rollback()
                    return assets
                } catch (failure: Throwable) {
                    connection.rollback()
                    throw failure
                }
            }
        } catch (_: java.sql.SQLException) {
            // Driver messages can include database URLs and user names: never relay them.
            throw BusinessValidationException("PostgreSQL 只读连接或采样失败；请检查服务端主机名单、环境变量、TLS 与只读账号授权")
        }
    }
}

internal fun quoteIdentifier(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

internal fun checkedEndpoint(raw: String?, allowedHosts: Set<String>, schemes: Set<String>): URI {
    val uri = try { URI.create(requireNotNull(raw)) } catch (_: Exception) { throw IllegalArgumentException("端点格式无效") }
    require(uri.scheme in schemes && uri.host != null && uri.userInfo == null && uri.fragment == null && uri.rawQuery == null) {
        "端点仅允许指定协议、主机和路径，禁止内嵌凭据、查询参数或片段"
    }
    require(uri.host.lowercase() in allowedHosts.map(String::lowercase)) { "端点主机未获服务端允许" }
    require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
    require(InetAddress.getAllByName(uri.host).none { it.isLinkLocalAddress || it.isMulticastAddress || it.isAnyLocalAddress }) {
        "禁止连接链路本地、组播或未指定地址"
    }
    return uri
}

internal fun validateEnvRef(reference: String?) {
    require(reference == null || reference.matches(Regex("SMARTDATA_CONNECTOR_[A-Z][A-Z0-9_]{0,79}"))) {
        "凭据只能引用 SMARTDATA_CONNECTOR_ 前缀的环境变量"
    }
}

internal fun canonicalCredentialEndpoint(endpoint: String, kind: SourceKind): String {
    val uri = URI.create(endpoint.removePrefix("jdbc:"))
    val port = if (uri.port >= 0) uri.port else when (uri.scheme) {
        "http" -> 80
        "https" -> 443
        "postgresql" -> 5432
        else -> throw IllegalArgumentException("不支持的凭据目标协议")
    }
    require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null)
    val prefix = if (kind == SourceKind.JDBC_POSTGRES_READONLY) "jdbc:" else ""
    // Exact raw path keeps escaped slashes and alternate routes from sharing a
    // credential grant. Missing HTTP slash normalizes to the root resource.
    val path = uri.rawPath?.takeIf(String::isNotEmpty) ?: "/"
    return "$prefix${uri.scheme.lowercase()}://${uri.host.lowercase()}:$port$path"
}

internal fun isLiteralLoopback(host: String): Boolean = host == "127.0.0.1"

private fun requireCredentialBindings(source: SourceRecord, bindings: Set<CredentialBinding>) {
    val target = canonicalCredentialEndpoint(requireNotNull(source.endpoint), source.kind)
    val scope = if (source.kind == SourceKind.JDBC_POSTGRES_READONLY) source.schema else "-"
    listOfNotNull(source.credentialEnvRef, source.usernameEnvRef).forEach { reference ->
        require(CredentialBinding(source.tenantId, source.kind, target, scope, reference) in bindings) {
            "该租户、连接器类型、完整端点、数据范围和凭据引用尚未获服务端绑定授权"
        }
    }
}

internal fun validateRows(rows: List<Map<String, Any?>>) {
    require(rows.size <= MAX_SAMPLE_ROWS) { "单个资产最多接收 1000 行" }
    rows.forEach { nullableRow: Map<String, Any?>? ->
        val row = requireNotNull(nullableRow) { "数据行不能为 null" }
        require(row.size <= 128 && row.keys.all { it.isNotBlank() && it.length <= 128 && !it.any(Char::isISOControl) }) { "列名或列数超出限制" }
        row.values.forEach { value ->
            require(value == null || value is String || value is Number || value is Boolean) { "仅接受标量列；请先展平嵌套对象" }
            require(value !is String || value.length <= 4096) { "单元格文本最多 4096 字符" }
        }
    }
}

internal const val MAX_SAMPLE_ROWS = 1000
internal const val MAX_SOURCE_BYTES = 1048576

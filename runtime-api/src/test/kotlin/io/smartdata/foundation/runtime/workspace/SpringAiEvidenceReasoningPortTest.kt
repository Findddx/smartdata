package io.smartdata.foundation.runtime.workspace

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Real loopback HTTP + actual Spring AI/official SDK. These tests do not certify a cloud provider. */
class SpringAiEvidenceReasoningPortTest {
    @TempDir lateinit var directory: Path
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "model-stub").apply { isDaemon = true } }
    private val mapper = JsonMapper.builder().build()
    private val requests = CopyOnWriteArrayList<String>()
    private val authorizations = CopyOnWriteArrayList<String>()
    private var responseText = ""
    private var responseId = "chatcmpl-contract-stub"
    private var statusCode = 200
    private var delayMillis = 0L
    private var extraMessage: Map<String, Any?> = emptyMap()
    private var finishReason = "stop"
    private val clock = Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), ZoneOffset.UTC)
    private val secret = "fixture-only-not-a-real-cloud-credential"

    @BeforeEach
    fun start() {
        responseText = validContent()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            requests += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            authorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val message = mapOf("role" to "assistant", "content" to responseText) + extraMessage
            val payload = if (statusCode == 200) mapper.writeValueAsBytes(mapOf(
                "id" to responseId, "object" to "chat.completion", "created" to 1792915200,
                "model" to "stub-v1", "choices" to listOf(mapOf("index" to 0, "message" to message, "finish_reason" to finishReason)),
                "usage" to mapOf("prompt_tokens" to 120, "completion_tokens" to 40, "total_tokens" to 160),
            )) else mapper.writeValueAsBytes(mapOf("error" to mapOf("message" to "provider private detail", "type" to "server_error")))
            try {
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(statusCode, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } catch (_: java.io.IOException) { /* Expected when the client deadline cancels a slow fixture. */ }
            finally { exchange.close() }
        }
        server.start()
    }

    @AfterEach
    fun stop() { server.stop(0); executor.shutdownNow() }

    private fun settings() = WorkspaceModelProperties(
        mode = WorkspaceReasoningMode.SPRING_AI_EVIDENCE_V1,
        endpoint = "http://127.0.0.1:${server.address.port}/v1", modelId = "stub-v1", modelVersion = "fixture-2026-10-25",
        credentialEnvRef = "SMARTDATA_MODEL_STUB", exportApproved = true,
        allowedTenants = setOf("tenant-a"), allowedSourceIds = setOf("source-a"),
    )

    private fun context() = AssistantReasoningContext("tenant-a", "assistant-analysis", AssistantRole.ANALYSIS,
        "解释已观察的问题", "请说明重复记录怎样核对", 3, listOf(FindingView("finding-1", "source-a", "asset-a", "scan-a",
            Provenance.USER, "DQ.DUPLICATE_PRIMARY_KEY.v1", "业务唯一键出现重复", FindingSeverity.HIGH, "order_id",
            "观察到额外重复 1 次（仅样本）", "已声明唯一键在观察范围不重复",
            FindingEvidence("a".repeat(64), 2, 2, 1000, clock.instant().toString(), "UPLOADED_SNAPSHOT_COMPLETE"),
            "请业务人员核对唯一键与采集规则")))

    private fun adapter(config: WorkspaceModelProperties = settings(), budget: ModelBudgetPort = ModelBudgetPort { _, _, _, _ -> true }) =
        SpringAiEvidenceReasoningPort(config, budget, clock) { if (it == "SMARTDATA_MODEL_STUB") secret else null }

    private fun validContent(refs: List<String> = listOf("finding-1")) = mapper.writeValueAsString(mapOf(
        "answer" to "当前样本出现重复键。先核对业务键定义，再比较源记录；不要直接删除订单。",
        "evidenceRefs" to refs, "uncertainty" to listOf("只观察到两行，不能推断全库重复比例。"),
    ))

    @Test
    fun `Spring AI calls compatible completion endpoint with evidence only and no tool catalog`() {
        val result = adapter().respond(context())
        assertEquals("SPRING_AI_EVIDENCE_V1", result.method)
        assertEquals(listOf("finding-1"), result.evidenceRefs)
        assertTrue(result.uncertainty.isNotEmpty())
        assertEquals("stub-v1", result.trace!!.modelId)
        assertEquals("fixture-2026-10-25", result.trace!!.modelVersion)
        assertEquals(120L, result.trace!!.inputTokens)
        assertEquals(40L, result.trace!!.outputTokens)
        assertEquals("chatcmpl-contract-stub", result.trace!!.responseId)
        assertTrue(result.trace!!.contextSha256.matches(Regex("[a-f0-9]{64}")))
        assertEquals(1, requests.size)
        assertEquals("Bearer $secret", authorizations.single())
        val request = mapper.readTree(requests.single())
        assertEquals("stub-v1", request["model"].asString())
        assertEquals("none", request["tool_choice"].asString())
        assertFalse(request.has("tools"))
        assertFalse(request.has("functions"))
        assertEquals("json_schema", request["response_format"]["type"].asString())
        assertFalse(request["store"].asBoolean())
        val sent = requests.single()
        listOf("snapshotRows", "credentialEnvRef", "tenant-a", "source-a", secret, "SELECT ", "private@example.org").forEach { assertFalse(sent.contains(it), it) }
        assertTrue(sent.contains("finding-1"))
    }

    @Test
    fun `unknown and empty evidence references are rejected`() {
        responseText = validContent(listOf("foreign-tenant-evidence"))
        assertEquals("INVALID_MODEL_RESPONSE", assertThrows(AssistantReasoningException::class.java) { adapter().respond(context()) }.code)
        responseText = validContent(emptyList())
        assertEquals("INVALID_MODEL_RESPONSE", assertThrows(AssistantReasoningException::class.java) { adapter().respond(context()) }.code)
        assertEquals(2, requests.size)
    }

    @Test
    fun `duplicate JSON keys additional fields empty uncertainty and oversized answers fail closed`() {
        val outputs = listOf(
            """{"answer":"first","answer":"second","evidenceRefs":["finding-1"],"uncertainty":["sample"]}""",
            validContent().dropLast(1) + ",\"execute\":\"drop database\"}",
            """{"answer":"ok","evidenceRefs":["finding-1"],"uncertainty":[]}""",
            mapper.writeValueAsString(mapOf("answer" to "x".repeat(12001), "evidenceRefs" to listOf("finding-1"), "uncertainty" to listOf("sample"))),
        )
        outputs.forEach { text ->
            responseText = text
            assertEquals("INVALID_MODEL_RESPONSE", assertThrows(AssistantReasoningException::class.java) { adapter().respond(context()) }.code)
        }
    }

    @Test
    fun `tool call and truncated result cannot become a successful explanation`() {
        extraMessage = mapOf("tool_calls" to listOf(mapOf("id" to "call-1", "type" to "function", "function" to mapOf("name" to "execute", "arguments" to "{}"))))
        assertThrows(AssistantReasoningException::class.java) { adapter().respond(context()) }
        extraMessage = emptyMap()
        finishReason = "length"
        assertThrows(AssistantReasoningException::class.java) { adapter().respond(context()) }
    }

    @Test
    fun `scope approval and evidence admission happen before network`() {
        val cases = listOf(settings().copy(exportApproved = false), settings().copy(allowedTenants = setOf("other")),
            settings().copy(allowedSourceIds = setOf("other")))
        cases.forEach { config -> assertEquals("EXPORT_DENIED", assertThrows(AssistantReasoningException::class.java) { adapter(config).respond(context()) }.code) }
        assertEquals("NO_EVIDENCE", assertThrows(AssistantReasoningException::class.java) { adapter().respond(context().copy(findings = emptyList())) }.code)
        assertEquals("EXPORT_DENIED", assertThrows(AssistantReasoningException::class.java) { adapter().respond(context().copy(question = secret)) }.code)
        assertTrue(requests.isEmpty())
        val deniedWithSecret = assertThrows(AssistantReasoningException::class.java) {
            adapter(settings().copy(exportApproved = false)).respond(context().copy(question = secret))
        }
        assertTrue(deniedWithSecret.redactQuestion)
        val absentWithSecret = assertThrows(AssistantReasoningException::class.java) {
            adapter().respond(context().copy(question = secret, findings = emptyList()))
        }
        assertTrue(absentWithSecret.redactQuestion)
    }

    @Test
    fun `budget survives adapter restart and failed provider call consumes the reserved slot`() {
        val config = settings().copy(maxCallsPerDay = 1)
        statusCode = 500
        val first = adapter(config, LocalModelBudget(directory.resolve("quota"), clock))
        val failure = assertThrows(AssistantReasoningException::class.java) { first.respond(context()) }
        assertEquals("PROVIDER_FAILURE", failure.code)
        assertFalse(failure.message.orEmpty().contains("provider private detail"))
        assertEquals(1, requests.size, "SDK and transport must not retry 500 responses")
        statusCode = 200
        val restarted = adapter(config, LocalModelBudget(directory.resolve("quota"), clock))
        assertEquals("BUDGET_EXCEEDED", assertThrows(AssistantReasoningException::class.java) { restarted.respond(context()) }.code)
        assertEquals(1, requests.size)
    }

    @Test
    fun `total provider deadline is bounded and does not trigger retry`() {
        delayMillis = 2500
        val started = System.nanoTime()
        val failure = assertThrows(AssistantReasoningException::class.java) { adapter(settings().copy(timeoutSeconds = 1)).respond(context()) }
        assertEquals("PROVIDER_TIMEOUT", failure.code)
        assertTrue((System.nanoTime() - started) / 1000000 < 2300)
        assertEquals(1, requests.size)
    }

    @Test
    fun `plaintext DNS names credentials in URLs and wildcard grants are rejected at configuration`() {
        listOf(settings().copy(endpoint = "http://localhost:${server.address.port}/v1"),
            settings().copy(endpoint = "https://user:password@example.org/v1"),
            settings().copy(endpoint = "https://example.org/v1?api_key=secret"),
            settings().copy(allowedTenants = setOf("*"))).forEach { config ->
                assertThrows(IllegalArgumentException::class.java) { adapter(config) }
            }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `redirect response is never followed with prompts or credentials`() {
        var leaked = false
        server.createContext("/leak") { exchange -> leaked = true; exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.removeContext("/v1/chat/completions")
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readAllBytes()
            exchange.responseHeaders.set("Location", "http://127.0.0.1:${server.address.port}/leak")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        assertThrows(AssistantReasoningException::class.java) { adapter().respond(context()) }
        assertFalse(leaked)
    }

    @Test
    fun `oversized provider body and echoed service credential never enter a successful response`() {
        responseText = "x".repeat(140000)
        assertThrows(AssistantReasoningException::class.java) { adapter().respond(context()) }
        responseText = mapper.writeValueAsString(mapOf("answer" to secret, "evidenceRefs" to listOf("finding-1"), "uncertainty" to listOf("sample")))
        val failure = assertThrows(AssistantReasoningException::class.java) { adapter().respond(context()) }
        assertEquals("INVALID_MODEL_RESPONSE", failure.code)
        assertFalse(failure.message.orEmpty().contains(secret))
        assertEquals(2, requests.size)
    }

    @Test
    fun `missing credential input limit and unavailable budget prevent requests`() {
        val missing = SpringAiEvidenceReasoningPort(settings(), ModelBudgetPort { _, _, _, _ -> true }, clock) { null }
        assertEquals("CREDENTIAL_UNAVAILABLE", assertThrows(AssistantReasoningException::class.java) { missing.respond(context()) }.code)
        assertEquals("INPUT_LIMIT", assertThrows(AssistantReasoningException::class.java) {
            adapter(settings().copy(maxInputBytes = 2048)).respond(context().copy(question = "输入".repeat(1000)))
        }.code)
        assertEquals("BUDGET_UNAVAILABLE", assertThrows(AssistantReasoningException::class.java) {
            adapter(budget = ModelBudgetPort { _, _, _, _ -> throw IllegalStateException("unavailable") }).respond(context())
        }.code)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `cloud profiles cannot resolve to loopback or private model destinations`() {
        val cloud = settings().copy(profile = ModelProviderProfile.BAILIAN,
            endpoint = "https://127.0.0.1:${server.address.port}/v1")
        assertEquals("PROVIDER_FAILURE", assertThrows(AssistantReasoningException::class.java) { adapter(cloud).respond(context()) }.code)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `provider response id echoing credential is rejected before it can enter a failed trace`() {
        responseId = secret
        val failure = assertThrows(AssistantReasoningException::class.java) { adapter().respond(context()) }
        assertEquals("INVALID_MODEL_RESPONSE", failure.code)
        assertNull(failure.trace.responseId)
        assertFalse(mapper.writeValueAsString(failure.trace).contains(secret))
    }

    @Test
    fun `JSON escaped credentials are rejected after decoding both answer and uncertainty`() {
        val escaped = "\\u" + "%04x".format(secret.first().code) + secret.drop(1)
        listOf(
            """{"answer":"$escaped","evidenceRefs":["finding-1"],"uncertainty":["sample"]}""",
            """{"answer":"safe","evidenceRefs":["finding-1"],"uncertainty":["$escaped"]}""",
        ).forEach { text ->
            assertFalse(text.contains(secret))
            responseText = text
            val failure = assertThrows(AssistantReasoningException::class.java) { adapter().respond(context()) }
            assertEquals("INVALID_MODEL_RESPONSE", failure.code)
            assertFalse(failure.userMessage.contains(secret))
        }
    }
}

package io.smartdata.foundation.runtime.api

import com.jayway.jsonpath.JsonPath
import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.repository.InMemoryActionReceiptRepository
import io.smartdata.foundation.repository.InMemoryActionRequestRepository
import io.smartdata.foundation.repository.InMemoryAuditRepository
import io.smartdata.foundation.repository.InMemoryCaseRepository
import io.smartdata.foundation.repository.InMemoryMemoryRepository
import io.smartdata.foundation.repository.InMemoryRecommendationRepository
import io.smartdata.foundation.repository.InMemoryVerificationRepository
import io.smartdata.foundation.runtime.security.BoundaryFilter
import io.smartdata.foundation.runtime.security.LocalActorContextResolver
import io.smartdata.foundation.service.DeterministicRecommendationEngine
import io.smartdata.foundation.service.DeterministicApprovalPolicy
import io.smartdata.foundation.service.DeterministicDataAccessPolicy
import io.smartdata.foundation.service.FoundationKernel
import io.smartdata.foundation.simulation.DeterministicFulfillmentSimulator
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FoundationControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-07T08:00:00Z"), ZoneOffset.UTC)
    private lateinit var mockMvc: MockMvc
    private lateinit var kernel: FoundationKernel

    @BeforeEach
    fun setUp() {
        val simulator = DeterministicFulfillmentSimulator(clock)
        kernel = FoundationKernel(
            InMemoryCaseRepository(simulator.generateCases(500, "demo-cn-001")),
            InMemoryRecommendationRepository(),
            InMemoryActionRequestRepository(),
            InMemoryActionReceiptRepository(),
            InMemoryVerificationRepository(),
            InMemoryMemoryRepository(),
            InMemoryAuditRepository(),
            simulator,
            DeterministicRecommendationEngine(clock),
            DeterministicApprovalPolicy(),
            DeterministicDataAccessPolicy(),
            clock,
        )
        val validator = LocalValidatorFactoryBean().also { it.afterPropertiesSet() }
        val builder = MockMvcBuilders.standaloneSetup(
            FoundationController(
                kernel,
                LocalActorContextResolver(),
                InMemoryRunEventBus(clock),
                clock,
                InMemoryCommandIdempotencyGate(),
            ),
        )
            .setControllerAdvice(GlobalErrorHandler())
            .setValidator(validator)
        builder.addFilters<StandaloneMockMvcBuilder>(BoundaryFilter())
        mockMvc = builder.build()
    }

    @Test
    fun `serves paged Chinese exception cases with secure headers`() {
        mockMvc.perform(get("/api/v1/cases").param("limit", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(500))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(content().string(containsString("订单履约异常")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    }

    @Test
    fun `completes API approval execution verification flow`() {
        val requester = Actor("order-specialist", "订单专员", "demo-cn-001", setOf(BusinessRole.ORDER_SPECIALIST))
        val case = kernel.listCases(requester).first {
            it.scenarioKinds == setOf("SUPPLIER_DELAY")
        }
        val recommendationJson = mockMvc.perform(
            post("/api/v1/cases/{caseId}/recommendations", case.id)
                .header("X-Demo-Actor", "order-specialist")
                .header("Idempotency-Key", "recommend-happy-0001"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val recommendationId: String = JsonPath.read(recommendationJson, "$.id")

        val actionJson = mockMvc.perform(
            post("/api/v1/action-requests")
                .header("X-Demo-Actor", "order-specialist")
                .header("Idempotency-Key", "api-happy-path-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"recommendationId":"$recommendationId","expectedStateVersion":${case.stateVersion}}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.state").value("AWAITING_APPROVAL"))
            .andReturn().response.contentAsString
        val actionId: String = JsonPath.read(actionJson, "$.id")
        val awaitingVersion: Int = JsonPath.read(actionJson, "$.stateVersion")

        val approvedJson = mockMvc.perform(
            post("/api/v1/approvals/{actionRequestId}", actionId)
                .header("X-Demo-Actor", "warehouse-approver")
                .header("Idempotency-Key", "approve-happy-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"expectedStateVersion":$awaitingVersion}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("APPROVED"))
            .andReturn().response.contentAsString
        val approvedVersion: Int = JsonPath.read(approvedJson, "$.stateVersion")

        val executedJson = mockMvc.perform(
            post("/api/v1/action-requests/{actionRequestId}/execute", actionId)
                .header("X-Demo-Actor", "warehouse-approver")
                .header("Idempotency-Key", "execute-happy-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"expectedStateVersion":$approvedVersion}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.request.state").value("EXECUTED_UNVERIFIED"))
            .andReturn().response.contentAsString
        val executedActionVersion: Int = JsonPath.read(executedJson, "$.request.stateVersion")
        val verificationCaseVersion = kernel.getCase(requester, case.id).stateVersion

        mockMvc.perform(
            post("/api/v1/verifications/{actionRequestId}", actionId)
                .header("X-Demo-Actor", "warehouse-approver")
                .header("Idempotency-Key", "verify-happy-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"expectedActionStateVersion":$executedActionVersion,"expectedCaseStateVersion":$verificationCaseVersion}""",
                ),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.request.state").value("VERIFIED_SUCCEEDED"))
            .andExpect(jsonPath("$.verification.independentSource").value("WMS_READ_MODEL"))
            .andExpect(jsonPath("$.memoryCandidate.status").value("PROPOSED"))

        mockMvc.perform(get("/api/v1/cases/{caseId}/timeline", case.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.eventType == 'action.approved')]").exists())
            .andExpect(jsonPath("$[?(@.eventType == 'verification.passed')]").exists())
    }

    @Test
    fun `rejects client supplied tenant and role identity`() {
        mockMvc.perform(
            get("/api/v1/cases")
                .header("X-Tenant-Id", "other-tenant")
                .header("X-Roles", "ADMIN"),
        ).andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(content().string(containsString("租户和角色只能由服务端认证上下文解析")))
    }

    @Test
    fun `rejects unknown demo actor and oversized body`() {
        mockMvc.perform(get("/api/v1/cases").header("X-Demo-Actor", "intruder"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.title").value("操作被拒绝"))

        mockMvc.perform(
            post("/api/v1/action-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(256 * 1024 + 1)),
        ).andExpect(status().`is`(413))
    }

    @Test
    fun `maps malformed JSON and invalid enum parameters to stable bad requests`() {
        mockMvc.perform(
            post("/api/v1/action-requests")
                .header("Idempotency-Key", "malformed-json-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recommendationId\":}"),
        ).andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))

        mockMvc.perform(
            get("/api/v1/verifications/missing-action")
                .param("purpose", "AGENT_SELF_REPORT"),
        ).andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
    }

    @Test
    fun `evaluation health names independent success authority`() {
        mockMvc.perform(get("/api/v1/evaluations/health").header("X-Demo-Actor", "auditor"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deterministicCases").value(500))
            .andExpect(jsonPath("$.scenarioTemplates").value(32))
            .andExpect(jsonPath("$.successAuthority").value("INDEPENDENT_CONNECTOR_READBACK"))
            .andExpect(jsonPath("$.acceptsAgentSelfReportedSuccess").value(false))

        mockMvc.perform(get("/api/v1/domain-packs").header("X-Demo-Actor", "order-specialist"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `requires and safely replays command idempotency keys`() {
        val actor = Actor("order-specialist", "订单专员", "demo-cn-001", setOf(BusinessRole.ORDER_SPECIALIST))
        val cases = kernel.listCases(actor).filter {
            it.scenarioKinds.any { kind ->
                kind in setOf("STALE_ERP_STATUS", "INVENTORY_MISMATCH", "SUPPLIER_DELAY")
            }
        }.take(2)

        mockMvc.perform(
            post("/api/v1/cases/{caseId}/recommendations", cases[0].id)
                .header("X-Demo-Actor", "order-specialist"),
        ).andExpect(status().isBadRequest)

        val first = mockMvc.perform(
            post("/api/v1/cases/{caseId}/recommendations", cases[0].id)
                .header("X-Demo-Actor", "order-specialist")
                .header("Idempotency-Key", "recommend-replay-0001"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val replay = mockMvc.perform(
            post("/api/v1/cases/{caseId}/recommendations", cases[0].id)
                .header("X-Demo-Actor", "order-specialist")
                .header("Idempotency-Key", "recommend-replay-0001"),
        ).andExpect(status().isOk).andReturn().response.contentAsString

        org.assertj.core.api.Assertions.assertThat(replay).isEqualTo(first)
        org.assertj.core.api.Assertions.assertThat(kernel.caseTimeline(actor, cases[0].id))
            .filteredOn { it.eventType == "recommendation.created" }
            .hasSize(1)

        mockMvc.perform(
            post("/api/v1/cases/{caseId}/recommendations", cases[1].id)
                .header("X-Demo-Actor", "order-specialist")
                .header("Idempotency-Key", "recommend-replay-0001"),
        ).andExpect(status().isConflict)
    }
}

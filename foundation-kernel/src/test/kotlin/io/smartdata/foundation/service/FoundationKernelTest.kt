package io.smartdata.foundation.service

import io.smartdata.foundation.domain.ActionState
import io.smartdata.foundation.domain.ActionStateMachine
import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.domain.CaseStatus
import io.smartdata.foundation.domain.MemoryStatus
import io.smartdata.foundation.domain.ReceiptOutcome
import io.smartdata.foundation.domain.VerificationOutcome
import io.smartdata.foundation.domain.VerificationPurpose
import io.smartdata.foundation.port.ConnectorPort
import io.smartdata.foundation.port.ModelPort
import io.smartdata.foundation.repository.InMemoryActionReceiptRepository
import io.smartdata.foundation.repository.InMemoryActionRequestRepository
import io.smartdata.foundation.repository.InMemoryAuditRepository
import io.smartdata.foundation.repository.InMemoryCaseRepository
import io.smartdata.foundation.repository.InMemoryMemoryRepository
import io.smartdata.foundation.repository.InMemoryRecommendationRepository
import io.smartdata.foundation.repository.InMemoryVerificationRepository
import io.smartdata.foundation.simulation.DeterministicFulfillmentSimulator
import io.smartdata.foundation.simulation.ScenarioKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FoundationKernelTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-07T08:00:00Z"), ZoneOffset.UTC)
    private val tenantId = "tenant-test"
    private lateinit var simulator: DeterministicFulfillmentSimulator
    private lateinit var kernel: FoundationKernel
    private lateinit var orderSpecialist: Actor
    private lateinit var approver: Actor
    private lateinit var salesOwner: Actor

    @BeforeEach
    fun setUp() {
        simulator = DeterministicFulfillmentSimulator(clock)
        val generated = simulator.generateCases(500, tenantId)
        kernel = FoundationKernel(
            InMemoryCaseRepository(generated),
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
        orderSpecialist = Actor("requester", "订单专员", tenantId, setOf(BusinessRole.ORDER_SPECIALIST))
        approver = Actor("approver", "仓库审批人", tenantId, setOf(BusinessRole.WAREHOUSE_APPROVER))
        salesOwner = Actor("owner", "销售负责人", tenantId, setOf(BusinessRole.SALES_OWNER))
    }

    @Test
    fun `verified action closes case and creates human-review memory candidate`() {
        val case = kernel.listCases(orderSpecialist).first {
            it.scenarioKinds == setOf("SUPPLIER_DELAY")
        }
        val recommendation = kernel.recommend(orderSpecialist, case.id)
        assertThat(recommendation.actionDefinitionId).isEqualTo(FulfillmentActions.RESERVE_SUBSTITUTE_INVENTORY)

        var action = kernel.createAction(orderSpecialist, CreateActionCommand(
            recommendation.id, case.stateVersion, "recount-${case.id}",
        ))
        assertThat(action.state).isEqualTo(ActionState.AWAITING_APPROVAL)
        assertThat(kernel.getCase(orderSpecialist, case.id).status).isEqualTo(CaseStatus.AWAITING_APPROVAL)

        action = kernel.approve(approver, action.id, action.stateVersion)
        action = kernel.execute(approver, action.id, action.stateVersion).request
        assertThat(action.state).isEqualTo(ActionState.EXECUTED_UNVERIFIED)

        val verified = kernel.verify(
            approver, action.id, action.stateVersion, kernel.getCase(approver, case.id).stateVersion,
        )
        assertThat(verified.request.state).isEqualTo(ActionState.VERIFIED_SUCCEEDED)
        assertThat(verified.verification.independentSource).isEqualTo("WMS_READ_MODEL")
        assertThat(kernel.getCase(orderSpecialist, case.id).status).isEqualTo(CaseStatus.VERIFIED_CLOSED)
        assertThat(verified.memoryCandidate?.status).isEqualTo(MemoryStatus.PROPOSED)

        val published = kernel.reviewMemory(
            salesOwner,
            verified.memoryCandidate!!.id,
            MemoryReviewDecision.APPROVE,
            verified.memoryCandidate.stateVersion,
        )
        assertThat(published.status).isEqualTo(MemoryStatus.APPROVED)
    }

    @Test
    fun `verified recount task keeps business exception open for the recount observation`() {
        val case = kernel.listCases(orderSpecialist).first {
            "INVENTORY_MISMATCH" in it.scenarioKinds && "AMBIGUOUS_TIMEOUT" !in it.scenarioKinds
        }
        val recommendation = kernel.recommend(orderSpecialist, case.id)
        var action = kernel.createAction(orderSpecialist, CreateActionCommand(
            recommendation.id, case.stateVersion, "recount-observation-${case.id}",
        ))
        action = kernel.approve(approver, action.id, action.stateVersion)
        action = kernel.execute(approver, action.id, action.stateVersion).request

        val verified = kernel.verify(
            approver, action.id, action.stateVersion, kernel.getCase(approver, case.id).stateVersion,
        )
        assertThat(verified.request.state).isEqualTo(ActionState.VERIFIED_SUCCEEDED)
        assertThat(verified.memoryCandidate).isNull()
        assertThat(kernel.getCase(orderSpecialist, case.id).status).isEqualTo(CaseStatus.IN_PROGRESS)
    }

    @Test
    fun `a verified action does not close a multi-gap case when other blockers remain`() {
        val case = kernel.listCases(orderSpecialist).first {
            it.scenarioKinds == setOf("SUPPLIER_DELAY", "MISSING_QUALITY_INSPECTION")
        }
        val recommendation = kernel.recommend(orderSpecialist, case.id)
        assertThat(recommendation.actionDefinitionId).isEqualTo(FulfillmentActions.RESERVE_SUBSTITUTE_INVENTORY)
        var action = kernel.createAction(orderSpecialist, CreateActionCommand(
            recommendation.id, case.stateVersion, "multi-gap-${case.id}",
        ))
        action = kernel.approve(approver, action.id, action.stateVersion)
        action = kernel.execute(approver, action.id, action.stateVersion).request

        val verified = kernel.verify(
            approver, action.id, action.stateVersion, kernel.getCase(approver, case.id).stateVersion,
        )
        assertThat(verified.request.state).isEqualTo(ActionState.VERIFIED_SUCCEEDED)
        assertThat(verified.memoryCandidate).isNull()
        assertThat(kernel.getCase(orderSpecialist, case.id).status).isEqualTo(CaseStatus.IN_PROGRESS)
        assertThat(kernel.getCase(orderSpecialist, case.id).knowledgeGaps).isNotEmpty()
    }

    @Test
    fun `same idempotency key returns same request and conflicting use is rejected`() {
        val firstCase = kernel.listCases(orderSpecialist).first(::hasExecutableV1Action)
        val firstRecommendation = kernel.recommend(orderSpecialist, firstCase.id)
        val command = CreateActionCommand(firstRecommendation.id, firstCase.stateVersion, "shared-idempotency-key")
        val first = kernel.createAction(orderSpecialist, command)
        assertThat(kernel.createAction(orderSpecialist, command)).isEqualTo(first)

        val anotherCase = kernel.listCases(orderSpecialist).first {
            it.id != firstCase.id && it.scenarioKinds.any { kind ->
                kind in setOf("STALE_ERP_STATUS", "INVENTORY_MISMATCH", "SUPPLIER_DELAY")
            }
        }
        val anotherRecommendation = kernel.recommend(orderSpecialist, anotherCase.id)
        assertThatThrownBy {
            kernel.createAction(orderSpecialist, CreateActionCommand(
                anotherRecommendation.id, anotherCase.stateVersion, "shared-idempotency-key",
            ))
        }.isInstanceOf(IdempotencyConflictException::class.java)
    }

    @Test
    fun `requester cannot approve own action even when requester is admin`() {
        val admin = Actor("admin", "管理员", tenantId, setOf(BusinessRole.ADMIN))
        val case = kernel.listCases(admin).first(::hasExecutableV1Action)
        val recommendation = kernel.recommend(admin, case.id)
        val action = kernel.createAction(admin, CreateActionCommand(
            recommendation.id, case.stateVersion, "admin-self-approval",
        ))
        assertThatThrownBy { kernel.approve(admin, action.id, action.stateVersion) }
            .isInstanceOf(AuthorizationDeniedException::class.java)
            .hasMessageContaining("不同人员")
    }

    @Test
    fun `ambiguous timeout is not retried and requires reconciliation`() {
        val case = kernel.listCases(orderSpecialist).first {
            it.scenarioKinds == setOf(
                ScenarioKind.INVENTORY_MISMATCH.name,
                ScenarioKind.AMBIGUOUS_TIMEOUT.name,
            )
        }
        val recommendation = kernel.recommend(orderSpecialist, case.id)
        var action = kernel.createAction(orderSpecialist, CreateActionCommand(
            recommendation.id, case.stateVersion, "ambiguous-timeout-key",
        ))
        action = kernel.approve(approver, action.id, action.stateVersion)
        val firstExecution = kernel.execute(approver, action.id, action.stateVersion)
        assertThat(firstExecution.request.state).isEqualTo(ActionState.UNKNOWN_REQUIRES_RECONCILIATION)
        assertThat(firstExecution.receipt.attempts).hasSize(1)

        val replay = kernel.execute(approver, action.id, firstExecution.request.stateVersion)
        assertThat(replay.receipt).isEqualTo(firstExecution.receipt)
        assertThat(replay.receipt.attempts).hasSize(1)

        val reconciled = kernel.verify(
            approver,
            action.id,
            firstExecution.request.stateVersion,
            kernel.getCase(approver, case.id).stateVersion,
        )
        assertThat(reconciled.request.state).isEqualTo(ActionState.VERIFIED_SUCCEEDED)
    }

    @Test
    fun `unsupported anomaly never falls through to an unrelated write action`() {
        val case = kernel.listCases(orderSpecialist).first {
            it.scenarioKinds == setOf(ScenarioKind.MISSING_QUALITY_INSPECTION.name)
        }

        assertThatThrownBy { kernel.recommend(orderSpecialist, case.id) }
            .isInstanceOf(BusinessValidationException::class.java)
            .hasMessageContaining("没有首版可安全执行")
    }

    @Test
    fun `stale case version and unauthorized role fail closed`() {
        val case = kernel.listCases(orderSpecialist).first(::hasExecutableV1Action)
        val recommendation = kernel.recommend(orderSpecialist, case.id)
        assertThatThrownBy {
            kernel.createAction(orderSpecialist, CreateActionCommand(
                recommendation.id, case.stateVersion + 1, "stale-version-key",
            ))
        }.isInstanceOf(OptimisticStateException::class.java)

        val auditor = Actor("auditor", "审计员", tenantId, setOf(BusinessRole.AUDITOR))
        assertThatThrownBy {
            kernel.createAction(auditor, CreateActionCommand(
                recommendation.id, case.stateVersion, "unauthorized-key",
            ))
        }.isInstanceOf(AuthorizationDeniedException::class.java)
    }

    @Test
    fun `unavailable policy fails before mutating the case`() {
        val isolatedSimulator = DeterministicFulfillmentSimulator(clock)
        val caseRepository = InMemoryCaseRepository(isolatedSimulator.generateCases(1, tenantId))
        val blockedKernel = FoundationKernel(
            caseRepository,
            InMemoryRecommendationRepository(),
            InMemoryActionRequestRepository(),
            InMemoryActionReceiptRepository(),
            InMemoryVerificationRepository(),
            InMemoryMemoryRepository(),
            InMemoryAuditRepository(),
            isolatedSimulator,
            DeterministicRecommendationEngine(clock),
            FailClosedPolicyDecisionPort(),
            DeterministicDataAccessPolicy(),
            clock,
        )
        val case = blockedKernel.listCases(orderSpecialist).single()
        val recommendation = blockedKernel.recommend(orderSpecialist, case.id)
        assertThatThrownBy {
            blockedKernel.createAction(orderSpecialist, CreateActionCommand(
                recommendation.id, case.stateVersion, "policy-unavailable-key",
            ))
        }.isInstanceOf(PolicyUnavailableException::class.java)
        assertThat(blockedKernel.getCase(orderSpecialist, case.id).stateVersion).isEqualTo(case.stateVersion)
        assertThat(blockedKernel.getCase(orderSpecialist, case.id).status).isEqualTo(CaseStatus.OPEN)
    }

    @Test
    fun `rejects model output that crosses tenant boundary or violates typed input`() {
        val generated = simulator.generateCases(500, tenantId)
        val baseModel = DeterministicRecommendationEngine(clock)
        fun kernelWith(model: ModelPort) = FoundationKernel(
            InMemoryCaseRepository(generated),
            InMemoryRecommendationRepository(),
            InMemoryActionRequestRepository(),
            InMemoryActionReceiptRepository(),
            InMemoryVerificationRepository(),
            InMemoryMemoryRepository(),
            InMemoryAuditRepository(),
            simulator,
            model,
            DeterministicApprovalPolicy(),
            DeterministicDataAccessPolicy(),
            clock,
        )

        val tenantPoison = object : ModelPort {
            override val modelVersion = baseModel.modelVersion
            override fun recommend(
                case: io.smartdata.foundation.domain.ExceptionCase,
                definitions: Collection<io.smartdata.foundation.domain.ActionDefinition>,
            ) = baseModel.recommend(case, definitions).copy(tenantId = "other-tenant")
        }
        val firstKernel = kernelWith(tenantPoison)
        val firstCase = firstKernel.listCases(orderSpecialist).first(::hasExecutableV1Action)
        assertThatThrownBy { firstKernel.recommend(orderSpecialist, firstCase.id) }
            .isInstanceOf(BusinessValidationException::class.java)
            .hasMessageContaining("租户边界")

        val fractionalQuantity = object : ModelPort {
            override val modelVersion = baseModel.modelVersion
            override fun recommend(
                case: io.smartdata.foundation.domain.ExceptionCase,
                definitions: Collection<io.smartdata.foundation.domain.ActionDefinition>,
            ) = baseModel.recommend(case, definitions).let { recommendation ->
                recommendation.copy(actionInput = recommendation.actionInput + ("quantity" to 1.9))
            }
        }
        val secondKernel = kernelWith(fractionalQuantity)
        val reserveCase = secondKernel.listCases(orderSpecialist).first {
            it.scenarioKinds == setOf("SUPPLIER_DELAY")
        }
        assertThatThrownBy { secondKernel.recommend(orderSpecialist, reserveCase.id) }
            .isInstanceOf(BusinessValidationException::class.java)
            .hasMessageContaining("动作输入")
    }

    @Test
    fun `untrusted agent self verification moves action to manual intervention`() {
        val poisonedConnector = object : ConnectorPort by simulator {
            override fun verify(
                definition: io.smartdata.foundation.domain.ActionDefinition,
                request: io.smartdata.foundation.domain.ActionRequest,
                receipt: io.smartdata.foundation.domain.ActionReceipt,
            ) = simulator.verify(definition, request, receipt).copy(
                independentSource = "AGENT_SELF_REPORT",
                antiGamingMetrics = mapOf("agent_self_report" to 1.0),
            )
        }
        val isolated = FoundationKernel(
            InMemoryCaseRepository(simulator.generateCases(500, tenantId)),
            InMemoryRecommendationRepository(),
            InMemoryActionRequestRepository(),
            InMemoryActionReceiptRepository(),
            InMemoryVerificationRepository(),
            InMemoryMemoryRepository(),
            InMemoryAuditRepository(),
            poisonedConnector,
            DeterministicRecommendationEngine(clock),
            DeterministicApprovalPolicy(),
            DeterministicDataAccessPolicy(),
            clock,
        )
        val case = isolated.listCases(orderSpecialist).first {
            "AMBIGUOUS_TIMEOUT" !in it.scenarioKinds && hasExecutableV1Action(it)
        }
        val recommendation = isolated.recommend(orderSpecialist, case.id)
        var action = isolated.createAction(orderSpecialist, CreateActionCommand(
            recommendation.id, case.stateVersion, "poisoned-verifier-key",
        ))
        action = isolated.approve(approver, action.id, action.stateVersion)
        action = isolated.execute(approver, action.id, action.stateVersion).request

        assertThatThrownBy {
            isolated.verify(
                approver,
                action.id,
                action.stateVersion,
                isolated.getCase(approver, case.id).stateVersion,
            )
        }
            .isInstanceOf(BusinessValidationException::class.java)
            .hasMessageContaining("自述")
        assertThat(isolated.getAction(approver, action.id).state).isEqualTo(ActionState.MANUAL_INTERVENTION)
        assertThat(isolated.getCase(approver, case.id).status).isEqualTo(CaseStatus.MANUAL_INTERVENTION)
    }

    @Test
    fun `failed action is compensated only after a separate compensation verification`() {
        val failingActionVerifier = object : ConnectorPort by simulator {
            override fun verify(
                definition: io.smartdata.foundation.domain.ActionDefinition,
                request: io.smartdata.foundation.domain.ActionRequest,
                receipt: io.smartdata.foundation.domain.ActionReceipt,
            ) = simulator.verify(definition, request, receipt).let { result ->
                result.copy(
                    outcome = VerificationOutcome.FAILED,
                    assertions = result.assertions.mapIndexed { index, assertion ->
                        if (index == 0) assertion.copy(observed = "mismatched", passed = false) else assertion
                    },
                    resolvedKnowledgeGapIds = emptySet(),
                )
            }
        }
        val isolated = FoundationKernel(
            InMemoryCaseRepository(simulator.generateCases(500, tenantId)),
            InMemoryRecommendationRepository(),
            InMemoryActionRequestRepository(),
            InMemoryActionReceiptRepository(),
            InMemoryVerificationRepository(),
            InMemoryMemoryRepository(),
            InMemoryAuditRepository(),
            failingActionVerifier,
            DeterministicRecommendationEngine(clock),
            DeterministicApprovalPolicy(),
            DeterministicDataAccessPolicy(),
            clock,
        )
        val case = isolated.listCases(orderSpecialist).first { it.scenarioKinds == setOf("SUPPLIER_DELAY") }
        val recommendation = isolated.recommend(orderSpecialist, case.id)
        var action = isolated.createAction(orderSpecialist, CreateActionCommand(
            recommendation.id, case.stateVersion, "verified-compensation-key",
        ))
        action = isolated.approve(approver, action.id, action.stateVersion)
        action = isolated.execute(approver, action.id, action.stateVersion).request

        val result = isolated.verify(
            approver,
            action.id,
            action.stateVersion,
            isolated.getCase(approver, case.id).stateVersion,
        )

        assertThat(result.request.state).isEqualTo(ActionState.COMPENSATED)
        assertThat(result.receipt.compensationReceiptId).isNotBlank()
        assertThat(result.receipt.compensationVerificationId).isNotBlank()
        assertThat(isolated.getVerification(approver, action.id, VerificationPurpose.COMPENSATION)?.outcome)
            .isEqualTo(VerificationOutcome.PASSED)
        assertThat(isolated.getCase(approver, case.id).status).isEqualTo(CaseStatus.MANUAL_INTERVENTION)
    }

    @Test
    fun `case mutation after execution invalidates the verification binding`() {
        val caseRepository = InMemoryCaseRepository(simulator.generateCases(500, tenantId))
        val isolated = FoundationKernel(
            caseRepository,
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
        val case = isolated.listCases(orderSpecialist).first { it.scenarioKinds == setOf("SUPPLIER_DELAY") }
        val recommendation = isolated.recommend(orderSpecialist, case.id)
        var action = isolated.createAction(orderSpecialist, CreateActionCommand(
            recommendation.id, case.stateVersion, "stale-verification-binding",
        ))
        action = isolated.approve(approver, action.id, action.stateVersion)
        action = isolated.execute(approver, action.id, action.stateVersion).request
        val executedCase = isolated.getCase(approver, case.id)
        caseRepository.save(
            executedCase.copy(stateVersion = executedCase.stateVersion + 1),
            executedCase.stateVersion,
        )

        assertThatThrownBy {
            isolated.verify(
                approver,
                action.id,
                action.stateVersion,
                executedCase.stateVersion + 1,
            )
        }.isInstanceOf(BusinessValidationException::class.java)
            .hasMessageContaining("案件版本已变化")
        assertThat(isolated.getAction(approver, action.id).state).isEqualTo(ActionState.MANUAL_INTERVENTION)
    }

    @Test
    fun `interrupted executing state reconciles without replaying external write`() {
        var executeCalls = 0
        val countingConnector = object : ConnectorPort by simulator {
            override fun execute(
                definition: io.smartdata.foundation.domain.ActionDefinition,
                request: io.smartdata.foundation.domain.ActionRequest,
            ): io.smartdata.foundation.port.ConnectorExecutionResult {
                executeCalls += 1
                return simulator.execute(definition, request)
            }
        }
        val actionRepository = InMemoryActionRequestRepository()
        val isolated = FoundationKernel(
            InMemoryCaseRepository(simulator.generateCases(500, tenantId)),
            InMemoryRecommendationRepository(),
            actionRepository,
            InMemoryActionReceiptRepository(),
            InMemoryVerificationRepository(),
            InMemoryMemoryRepository(),
            InMemoryAuditRepository(),
            countingConnector,
            DeterministicRecommendationEngine(clock),
            DeterministicApprovalPolicy(),
            DeterministicDataAccessPolicy(),
            clock,
        )
        val case = isolated.listCases(orderSpecialist).first(::hasExecutableV1Action)
        val recommendation = isolated.recommend(orderSpecialist, case.id)
        var action = isolated.createAction(orderSpecialist, CreateActionCommand(
            recommendation.id, case.stateVersion, "interrupted-execute-key",
        ))
        action = isolated.approve(approver, action.id, action.stateVersion)
        val recentExecution = ActionStateMachine.transition(action, ActionState.EXECUTING, clock.instant())
        actionRepository.save(recentExecution, action.stateVersion)

        assertThatThrownBy { isolated.execute(approver, recentExecution.id, recentExecution.stateVersion) }
            .isInstanceOf(BusinessValidationException::class.java)
            .hasMessageContaining("执行租约")
        assertThat(executeCalls).isZero()
        assertThat(isolated.getAction(approver, recentExecution.id).state).isEqualTo(ActionState.EXECUTING)

        val stranded = recentExecution.copy(updatedAt = clock.instant().minusSeconds(60))
        actionRepository.save(stranded, recentExecution.stateVersion)

        val recovered = isolated.execute(approver, stranded.id, stranded.stateVersion)
        assertThat(executeCalls).isZero()
        assertThat(recovered.request.state).isEqualTo(ActionState.UNKNOWN_REQUIRES_RECONCILIATION)
        assertThat(recovered.receipt.outcome).isEqualTo(ReceiptOutcome.UNKNOWN)
        assertThat(recovered.receipt.attempts).hasSize(1)
    }

    @Test
    fun `concurrent execute callers join one in-flight source-system write`() {
        val enteredConnector = CountDownLatch(1)
        val releaseConnector = CountDownLatch(1)
        val executeCalls = AtomicInteger()
        val blockingConnector = object : ConnectorPort by simulator {
            override fun execute(
                definition: io.smartdata.foundation.domain.ActionDefinition,
                request: io.smartdata.foundation.domain.ActionRequest,
            ): io.smartdata.foundation.port.ConnectorExecutionResult {
                executeCalls.incrementAndGet()
                enteredConnector.countDown()
                check(releaseConnector.await(2, TimeUnit.SECONDS))
                return simulator.execute(definition, request)
            }
        }
        val isolated = FoundationKernel(
            InMemoryCaseRepository(simulator.generateCases(500, tenantId)),
            InMemoryRecommendationRepository(),
            InMemoryActionRequestRepository(),
            InMemoryActionReceiptRepository(),
            InMemoryVerificationRepository(),
            InMemoryMemoryRepository(),
            InMemoryAuditRepository(),
            blockingConnector,
            DeterministicRecommendationEngine(clock),
            DeterministicApprovalPolicy(),
            DeterministicDataAccessPolicy(),
            clock,
        )
        val case = isolated.listCases(orderSpecialist).first { it.scenarioKinds == setOf("SUPPLIER_DELAY") }
        val recommendation = isolated.recommend(orderSpecialist, case.id)
        var action = isolated.createAction(orderSpecialist, CreateActionCommand(
            recommendation.id, case.stateVersion, "concurrent-execution-key",
        ))
        action = isolated.approve(approver, action.id, action.stateVersion)

        Executors.newFixedThreadPool(2).use { pool ->
            val first = pool.submit<ExecutionView> { isolated.execute(approver, action.id, action.stateVersion) }
            assertThat(enteredConnector.await(1, TimeUnit.SECONDS)).isTrue()
            val secondStarted = CountDownLatch(1)
            val second = pool.submit<ExecutionView> {
                secondStarted.countDown()
                isolated.execute(approver, action.id, action.stateVersion)
            }
            assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue()
            releaseConnector.countDown()

            val firstResult = first.get(2, TimeUnit.SECONDS)
            val secondResult = second.get(2, TimeUnit.SECONDS)
            assertThat(secondResult).isEqualTo(firstResult)
        }
        assertThat(executeCalls).hasValue(1)
    }

    @Test
    fun `claim and evidence ACLs are enforced before case data reaches a caller`() {
        val source = simulator.generateCases(1, tenantId).single()
        val protectedClaim = source.claims.first().let { claim ->
            claim.copy(
                aclRef = "role:ADMIN",
                evidenceRefs = claim.evidenceRefs.map { it.copy(aclRef = "role:ADMIN") },
            )
        }
        val protectedCase = source.copy(claims = listOf(protectedClaim) + source.claims.drop(1))
        val isolated = FoundationKernel(
            InMemoryCaseRepository(listOf(protectedCase)),
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
        val admin = Actor("acl-admin", "管理员", tenantId, setOf(BusinessRole.ADMIN))

        assertThat(isolated.getCase(orderSpecialist, source.id).claims)
            .noneMatch { it.id == protectedClaim.id }
        assertThat(isolated.getEvidence(orderSpecialist, source.id).map { it.id })
            .doesNotContainAnyElementsOf(protectedClaim.evidenceRefs.map { it.id })
        assertThat(isolated.getCase(admin, source.id).claims)
            .anyMatch { it.id == protectedClaim.id }
        val adminRecommendation = isolated.recommend(admin, source.id)
        assertThatThrownBy { isolated.getRecommendation(orderSpecialist, adminRecommendation.id) }
            .isInstanceOf(ResourceNotFoundException::class.java)
    }

    @Test
    fun `expired approval window is persisted and audited`() {
        val mutableClock = MutableClock(Instant.parse("2026-09-07T08:00:00Z"))
        val localSimulator = DeterministicFulfillmentSimulator(mutableClock)
        val isolated = FoundationKernel(
            InMemoryCaseRepository(localSimulator.generateCases(500, tenantId)),
            InMemoryRecommendationRepository(),
            InMemoryActionRequestRepository(),
            InMemoryActionReceiptRepository(),
            InMemoryVerificationRepository(),
            InMemoryMemoryRepository(),
            InMemoryAuditRepository(),
            localSimulator,
            DeterministicRecommendationEngine(mutableClock),
            DeterministicApprovalPolicy(),
            DeterministicDataAccessPolicy(),
            mutableClock,
        )
        val case = isolated.listCases(orderSpecialist).first(::hasExecutableV1Action)
        val recommendation = isolated.recommend(orderSpecialist, case.id)
        val action = isolated.createAction(orderSpecialist, CreateActionCommand(
            recommendation.id, case.stateVersion, "approval-expiry-key",
        ))
        mutableClock.current = mutableClock.current.plus(Duration.ofHours(25))

        assertThatThrownBy { isolated.approve(approver, action.id, action.stateVersion) }
            .isInstanceOf(BusinessValidationException::class.java)
            .hasMessageContaining("过期")
        assertThat(isolated.getAction(approver, action.id).state).isEqualTo(ActionState.EXPIRED)
        assertThat(isolated.caseTimeline(approver, case.id).map { it.eventType }).contains("action.expired")
    }

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
    }

    private fun hasExecutableV1Action(case: io.smartdata.foundation.domain.ExceptionCase): Boolean =
        case.scenarioKinds.any { it in setOf("STALE_ERP_STATUS", "INVENTORY_MISMATCH", "SUPPLIER_DELAY") }
}

package io.smartdata.foundation.service

import io.smartdata.foundation.domain.*
import io.smartdata.foundation.port.*
import io.smartdata.foundation.repository.*
import io.smartdata.foundation.simulation.DeterministicFulfillmentSimulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Proves service boundaries. Physical rollback/isolation are separately tested against PostgreSQL. */
class FoundationKernelTransactionTest {
    private class MutableClock(@Volatile var now: Instant = Instant.parse("2026-09-07T08:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private class Probe : RepositoryUnitOfWork {
        data class Frame(val write: Boolean, val operations: MutableList<String> = mutableListOf())
        private val frame = ThreadLocal<Frame>()
        private val lock = ReentrantLock()
        val active = AtomicInteger()
        val commits = CopyOnWriteArrayList<List<String>>()
        val externalCalls = CopyOnWriteArrayList<String>()
        val violations = CopyOnWriteArrayList<String>()
        var rollbacks = 0

        override fun <T> read(tenantId: String, block: () -> T): T = run(false, block)
        override fun <T> write(tenantId: String, block: () -> T): T = run(true, block)
        private fun <T> run(write: Boolean, block: () -> T): T = lock.withLock {
            frame.get()?.let { current ->
                check(!write || current.write)
                return@withLock block()
            }
            val current = Frame(write)
            frame.set(current)
            active.incrementAndGet()
            try {
                block().also { if (write) commits += current.operations.toList() }
            } catch (failure: Throwable) {
                if (write) rollbacks++
                throw failure
            } finally { frame.remove(); active.decrementAndGet() }
        }

        fun repository(type: String, method: String) {
            val current = frame.get()
            check(current != null) { "$type.$method accessed without repository snapshot/commit" }
            if (method in setOf("save", "append", "lockById")) check(current.write) { "$type.$method used a read transaction" }
            current.operations += "$type.$method"
        }

        fun external(name: String) {
            externalCalls += name
            if (active.get() != 0 || frame.get() != null) {
                violations += "$name held a repository transaction, including on another thread"
                throw IllegalStateException(violations.last())
            }
        }
    }

    private inline fun <reified T : Any> checked(delegate: T, probe: Probe): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, arguments ->
        if (method.name !in setOf("toString", "hashCode", "equals")) probe.repository(T::class.java.simpleName, method.name)
        try { method.invoke(delegate, *(arguments ?: emptyArray())) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    } as T

    private inner class Fixture {
        val clock = MutableClock()
        val probe = Probe()
        val simulator = DeterministicFulfillmentSimulator(clock)
        val tenant = "transaction-tenant"
        val rawCases = InMemoryCaseRepository(simulator.generateCases(40, tenant))
        val rawRecommendations = InMemoryRecommendationRepository()
        val rawActions = InMemoryActionRequestRepository()
        val rawReceipts = InMemoryActionReceiptRepository()
        val rawVerifications = InMemoryVerificationRepository()
        val rawMemories = InMemoryMemoryRepository()
        val rawAudits = InMemoryAuditRepository()
        val requester = Actor("requester", "订单专员", tenant, setOf(BusinessRole.ORDER_SPECIALIST))
        val approver = Actor("approver", "仓库审批人", tenant, setOf(BusinessRole.WAREHOUSE_APPROVER))
        val owner = Actor("owner", "业务负责人", tenant, setOf(BusinessRole.SALES_OWNER))
        var modelHook: (() -> Unit)? = null
        var policyHook: (() -> Unit)? = null
        var executeHook: ((ActionRequest, ConnectorExecutionResult) -> Unit)? = null
        var compensationHook: ((ActionRequest, ActionReceipt) -> Unit)? = null
        var forceFailure = false
        var untrustedVerification = false
        var modelVersionOverride: String? = null
        var hideBoundRecommendation = false
        val executeCalls = AtomicInteger()
        val compensationCalls = AtomicInteger()
        val connector = object : ConnectorPort by simulator {
            override fun execute(definition: ActionDefinition, request: ActionRequest): ConnectorExecutionResult {
                probe.external("connector.execute")
                executeCalls.incrementAndGet()
                return simulator.execute(definition, request).also { executeHook?.invoke(request, it) }
            }
            override fun verify(definition: ActionDefinition, request: ActionRequest, receipt: ActionReceipt): VerificationResult {
                probe.external("connector.verify")
                val result = simulator.verify(definition, request, receipt)
                return when {
                    untrustedVerification -> result.copy(independentSource = "AGENT_SELF_REPORT")
                    forceFailure -> result.copy(outcome = VerificationOutcome.FAILED, resolvedKnowledgeGapIds = emptySet(),
                        assertions = result.assertions.mapIndexed { index, assertion -> if (index == 0) assertion.copy(passed = false) else assertion })
                    else -> result
                }
            }
            override fun compensate(definition: ActionDefinition, request: ActionRequest, receipt: ActionReceipt): ActionReceipt {
                probe.external("connector.compensate")
                compensationCalls.incrementAndGet()
                assertEquals(ActionState.COMPENSATING, rawActions.findById(tenant, request.id)!!.state)
                assertNotNull(rawVerifications.findByActionRequestId(tenant, request.id), "failure must be committed before compensation")
                assertNotNull(rawReceipts.findByActionRequestId(tenant, request.id)!!.verificationId)
                return simulator.compensate(definition, request, receipt).also { compensationHook?.invoke(request, it) }
            }
            override fun verifyCompensation(definition: ActionDefinition, request: ActionRequest, receipt: ActionReceipt): VerificationResult {
                probe.external("connector.verifyCompensation")
                assertEquals(ReceiptOutcome.COMPENSATED, rawReceipts.findByActionRequestId(tenant, request.id)!!.outcome,
                    "compensation receipt must be committed before its independent readback")
                return simulator.verifyCompensation(definition, request, receipt)
            }
        }

        fun kernel(): FoundationKernel = FoundationKernel(
            checked<CaseRepository>(rawCases, probe), checked<RecommendationRepository>(object : RecommendationRepository by rawRecommendations {
                override fun findById(tenantId: String, id: String): Recommendation? =
                    if (hideBoundRecommendation) null else rawRecommendations.findById(tenantId, id)
            }, probe),
            checked<ActionRequestRepository>(rawActions, probe), checked<ActionReceiptRepository>(rawReceipts, probe),
            checked<VerificationRepository>(rawVerifications, probe), checked<MemoryRepository>(rawMemories, probe),
            checked<AuditRepository>(rawAudits, probe), connector,
            object : ModelPort {
                override val modelVersion = modelVersionOverride ?: DeterministicRecommendationEngine(clock).modelVersion
                override fun recommend(case: ExceptionCase, definitions: Collection<ActionDefinition>): Recommendation {
                    probe.external("model.recommend")
                    modelHook?.invoke()
                    return DeterministicRecommendationEngine(clock).recommend(case, definitions)
                }
            },
            object : PolicyDecisionPort {
                override fun evaluate(actor: Actor, case: ExceptionCase, definition: ActionDefinition, input: Map<String, Any>): PolicyDecision {
                    probe.external("policy.evaluate")
                    policyHook?.invoke()
                    return DeterministicApprovalPolicy().evaluate(actor, case, definition, input)
                }
            },
            object : DataAccessDecisionPort {
                override fun canRead(actor: Actor, resourceTenantId: String, aclRef: String, classification: DataClassification): Boolean {
                    probe.external("dataAccess.canRead")
                    return DeterministicDataAccessPolicy().canRead(actor, resourceTenantId, aclRef, classification)
                }
            }, clock, unitOfWork = probe,
        )

        fun prepare(kernel: FoundationKernel = kernel()): ActionRequest {
            val case = kernel.listCases(requester).first { it.scenarioKinds == setOf("SUPPLIER_DELAY") }
            val recommendation = kernel.recommend(requester, case.id)
            val action = kernel.createAction(requester, CreateActionCommand(recommendation.id, case.stateVersion, "transaction-${case.id}"))
            return kernel.approve(approver, action.id, action.stateVersion)
        }

        fun caseVersion(action: ActionRequest) = rawCases.findById(tenant, action.caseId)!!.stateVersion
    }

    @Test
    fun `read snapshots and atomic writes never hold a transaction across authorization model policy or connector calls`() {
        val fixture = Fixture()
        val kernel = fixture.kernel()
        val action = fixture.prepare(kernel)
        val executed = kernel.execute(fixture.approver, action.id, action.stateVersion)
        val verified = kernel.verify(fixture.approver, action.id, executed.request.stateVersion, fixture.caseVersion(action))
        assertEquals(ActionState.VERIFIED_SUCCEEDED, verified.request.state)
        kernel.reviewMemory(fixture.owner, verified.memoryCandidate!!.id, MemoryReviewDecision.APPROVE, verified.memoryCandidate!!.stateVersion)
        assertTrue(kernel.auditTrail(fixture.owner, verified.memoryCandidate!!.id).isNotEmpty())
        assertTrue(kernel.caseTimeline(fixture.approver, action.caseId).isNotEmpty())
        assertTrue(kernel.listApprovedMemories(fixture.owner).isNotEmpty())
        assertNotNull(kernel.getReceipt(fixture.approver, action.id))
        assertNotNull(kernel.getVerification(fixture.approver, action.id))
        assertTrue(fixture.probe.violations.isEmpty(), fixture.probe.violations.toString())
        assertTrue(fixture.probe.commits.any { setOf("CaseRepository.save", "ActionRequestRepository.save", "AuditRepository.append").all(it::contains) })
        assertTrue(fixture.probe.commits.any { setOf("VerificationRepository.save", "ActionReceiptRepository.save", "ActionRequestRepository.save", "CaseRepository.save", "MemoryRepository.save", "AuditRepository.append").all(it::contains) })
    }

    @Test
    fun `expired approval is committed with its audit before the rejection is thrown`() {
        val fixture = Fixture()
        val kernel = fixture.kernel()
        val action = fixture.prepare(kernel)
        fixture.clock.now = fixture.clock.now.plus(Duration.ofDays(1))
        val rollbackCount = fixture.probe.rollbacks
        assertThrows(BusinessValidationException::class.java) { kernel.execute(fixture.approver, action.id, action.stateVersion) }
        assertEquals(ActionState.EXPIRED, fixture.rawActions.findById(fixture.tenant, action.id)!!.state)
        assertTrue(fixture.rawAudits.findByAggregate(fixture.tenant, action.id).any { it.eventType == "action.expired" })
        assertEquals(rollbackCount, fixture.probe.rollbacks, "a deliberate persisted rejection must not roll back its state")
        assertEquals(0, fixture.executeCalls.get())
    }

    @Test
    fun `case changes during model and policy calls invalidate the later short commit`() {
        val fixture = Fixture()
        val kernel = fixture.kernel()
        val case = kernel.listCases(fixture.requester).first { it.scenarioKinds == setOf("SUPPLIER_DELAY") }
        fixture.modelHook = {
            fixture.probe.write(fixture.tenant) { fixture.rawCases.save(case.copy(stateVersion = case.stateVersion + 1), case.stateVersion) }
        }
        assertThrows(OptimisticStateException::class.java) { kernel.recommend(fixture.requester, case.id) }
        assertTrue(fixture.rawAudits.findByCase(fixture.tenant, case.id).isEmpty())
        fixture.modelHook = null
        val recommendation = kernel.recommend(fixture.requester, case.id)
        val current = fixture.rawCases.findById(fixture.tenant, case.id)!!
        fixture.policyHook = {
            fixture.probe.write(fixture.tenant) { fixture.rawCases.save(current.copy(stateVersion = current.stateVersion + 1), current.stateVersion) }
        }
        assertThrows(OptimisticStateException::class.java) {
            kernel.createAction(fixture.requester, CreateActionCommand(recommendation.id, current.stateVersion, "policy-race-command"))
        }
        assertNull(fixture.rawActions.findByIdempotencyKey(fixture.tenant, "policy-race-command"))
        assertEquals(CaseStatus.OPEN, fixture.rawCases.findById(fixture.tenant, case.id)!!.status)
        assertTrue(fixture.probe.violations.isEmpty())
    }

    @Test
    fun `execution keeps a real receipt but routes to manual when case changes during source IO`() {
        val fixture = Fixture()
        val kernel = fixture.kernel()
        val action = fixture.prepare(kernel)
        fixture.executeHook = { _, _ ->
            val current = fixture.rawCases.findById(fixture.tenant, action.caseId)!!
            fixture.probe.write(fixture.tenant) { fixture.rawCases.save(current.copy(stateVersion = current.stateVersion + 1), current.stateVersion) }
        }
        val result = kernel.execute(fixture.approver, action.id, action.stateVersion)
        assertEquals(ActionState.MANUAL_INTERVENTION, result.request.state)
        assertEquals(ReceiptOutcome.SUCCEEDED, result.receipt.outcome)
        assertEquals(result.receipt, fixture.rawReceipts.findByActionRequestId(fixture.tenant, action.id))
        assertEquals(CaseStatus.MANUAL_INTERVENTION, fixture.rawCases.findById(fixture.tenant, action.caseId)!!.status)
        assertEquals(1, fixture.executeCalls.get())
        assertTrue(fixture.probe.violations.isEmpty())
    }

    @Test
    fun `untrusted verification commits manual intervention before surfacing the error`() {
        val fixture = Fixture()
        val kernel = fixture.kernel()
        val action = fixture.prepare(kernel)
        val executed = kernel.execute(fixture.approver, action.id, action.stateVersion)
        fixture.untrustedVerification = true
        val rollbackCount = fixture.probe.rollbacks
        assertThrows(BusinessValidationException::class.java) {
            kernel.verify(fixture.approver, action.id, executed.request.stateVersion, fixture.caseVersion(action))
        }
        assertEquals(ActionState.MANUAL_INTERVENTION, fixture.rawActions.findById(fixture.tenant, action.id)!!.state)
        assertNull(fixture.rawVerifications.findByActionRequestId(fixture.tenant, action.id))
        assertEquals(rollbackCount, fixture.probe.rollbacks)
    }

    @Test
    fun `compensation checkpoint and independent readback each release the repository transaction`() {
        val fixture = Fixture()
        fixture.forceFailure = true
        val kernel = fixture.kernel()
        val action = fixture.prepare(kernel)
        val executed = kernel.execute(fixture.approver, action.id, action.stateVersion)
        val result = kernel.verify(fixture.approver, action.id, executed.request.stateVersion, fixture.caseVersion(action))
        assertEquals(ActionState.COMPENSATED, result.request.state)
        assertNotNull(result.receipt.compensationVerificationId)
        assertEquals(1, fixture.compensationCalls.get())
        assertTrue(fixture.probe.externalCalls.contains("connector.verifyCompensation"))
        assertTrue(fixture.probe.violations.isEmpty())
    }

    @Test
    fun `compensation lease blocks concurrent takeover then expiry records a late observation without overwriting reconciliation`() {
        val fixture = Fixture()
        fixture.forceFailure = true
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.compensationHook = { _, _ -> started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val original = fixture.kernel()
        val recovery = fixture.kernel()
        val action = fixture.prepare(original)
        val executed = original.execute(fixture.approver, action.id, action.stateVersion)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val running = executor.submit<VerificationView> { original.verify(fixture.approver, action.id, executed.request.stateVersion, fixture.caseVersion(action)) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val compensating = fixture.rawActions.findById(fixture.tenant, action.id)!!
            assertThrows(BusinessValidationException::class.java) {
                recovery.verify(fixture.approver, action.id, compensating.stateVersion, fixture.caseVersion(action))
            }
            fixture.clock.now = fixture.clock.now.plus(Duration.ofDays(1))
            val reconciled = recovery.verify(fixture.approver, action.id, compensating.stateVersion, fixture.caseVersion(action))
            assertEquals(ActionState.MANUAL_INTERVENTION, reconciled.request.state)
            val canonical = fixture.rawReceipts.findByActionRequestId(fixture.tenant, action.id)
            release.countDown()
            val late = assertThrows(java.util.concurrent.ExecutionException::class.java) { running.get(3, TimeUnit.SECONDS) }
            assertTrue(late.cause is OptimisticStateException)
            assertEquals(canonical, fixture.rawReceipts.findByActionRequestId(fixture.tenant, action.id))
            assertEquals(ActionState.MANUAL_INTERVENTION, fixture.rawActions.findById(fixture.tenant, action.id)!!.state)
            assertTrue(fixture.rawAudits.findByAggregate(fixture.tenant, action.id).any { it.eventType == "action.late_connector_result" })
            assertEquals(1, fixture.compensationCalls.get())
            assertTrue(fixture.probe.violations.isEmpty())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test
    fun `late execution receipt preserves the reconciled canonical unknown receipt and never replays source IO`() {
        val fixture = Fixture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.executeHook = { _, _ -> started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val original = fixture.kernel()
        val recovery = fixture.kernel()
        val action = fixture.prepare(original)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val running = executor.submit<ExecutionView> { original.execute(fixture.approver, action.id, action.stateVersion) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val executing = fixture.rawActions.findById(fixture.tenant, action.id)!!
            fixture.clock.now = fixture.clock.now.plus(Duration.ofDays(1))
            val reconciled = recovery.execute(fixture.approver, action.id, executing.stateVersion)
            assertEquals(ActionState.UNKNOWN_REQUIRES_RECONCILIATION, reconciled.request.state)
            assertEquals(ReceiptOutcome.UNKNOWN, reconciled.receipt.outcome)
            release.countDown()
            val late = assertThrows(java.util.concurrent.ExecutionException::class.java) { running.get(3, TimeUnit.SECONDS) }
            assertTrue(late.cause is OptimisticStateException)
            assertEquals(reconciled.receipt, fixture.rawReceipts.findByActionRequestId(fixture.tenant, action.id))
            assertEquals(1, fixture.executeCalls.get())
            val observations = fixture.rawAudits.findByAggregate(fixture.tenant, action.id).filter { it.eventType == "action.late_connector_result" }
            assertEquals(1, observations.size)
            assertEquals("SUCCEEDED", observations.single().details["outcome"])
            assertEquals("false", observations.single().details["independentlyAccepted"])
            assertTrue(fixture.probe.violations.isEmpty())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test
    fun `allowed warehouse and sku delimiter values cannot collide in new recommendation identifiers`() {
        fun recommendation(sku: String, warehouse: String): Recommendation {
            val fixture = Fixture()
            val original = fixture.rawCases.findAll(fixture.tenant).first { it.scenarioKinds == setOf("SUPPLIER_DELAY") }
            val altered = original.copy(claims = original.claims.map { claim -> when (claim.predicate) {
                "sku" -> claim.copy(value = sku)
                "warehouseCode" -> claim.copy(value = warehouse)
                else -> claim
            } })
            fixture.rawCases.save(altered)
            return fixture.kernel().recommend(fixture.requester, altered.id)
        }
        val one = recommendation("x;warehouseCode=y", "z")
        val two = recommendation("x", "y;warehouseCode=z")
        fun legacyMap(input: Map<String, Any>) = input.toSortedMap().entries.joinToString("") { "${it.key}=${it.value};" }
        assertEquals(legacyMap(one.actionInput), legacyMap(two.actionInput), "the synthetic inputs reproduce the legacy ambiguity")
        assertEquals(one.rationaleZh, two.rationaleZh)
        assertEquals(one.evidenceIds, two.evidenceIds)
        assertNotEquals(one.id, two.id, "new versioned length framing binds the actual fields")
    }

    @Test
    fun `legacy hashes replay by actual action semantics and matching hashes never excuse different inputs`() {
        val fixture = Fixture()
        val kernel = fixture.kernel()
        val action = fixture.prepare(kernel)
        val numericEquivalent = action.input + ("quantity" to (action.input.getValue("quantity") as Number).toLong())
        val legacy = action.copy(requestFingerprint = "0".repeat(64), input = numericEquivalent)
        fixture.rawActions.save(legacy)
        val command = CreateActionCommand(action.recommendationId, action.expectedCaseStateVersion, action.idempotencyKey)
        val commits = fixture.probe.commits.size
        assertEquals(legacy, kernel.createAction(fixture.requester, command))
        assertEquals(commits, fixture.probe.commits.size, "legacy replay must not recreate an action or change its case")
        val different = legacy.copy(input = numericEquivalent + ("sku" to "different;warehouseCode=other"))
        fixture.rawActions.save(different)
        assertThrows(IdempotencyConflictException::class.java) { kernel.createAction(fixture.requester, command) }
        fixture.rawActions.save(legacy)
        assertThrows(IdempotencyConflictException::class.java) { kernel.createAction(fixture.requester.copy(id = "another-requester"), command) }
        assertEquals(0, fixture.executeCalls.get())
    }

    @Test
    fun `memory candidate retains the bound recommendation model version after deployment changes`() {
        val fixture = Fixture()
        val original = fixture.kernel()
        val action = fixture.prepare(original)
        val executed = original.execute(fixture.approver, action.id, action.stateVersion)
        val boundVersion = fixture.rawRecommendations.findById(fixture.tenant, action.recommendationId)!!.modelVersion
        fixture.modelVersionOverride = "new-deployment-model-v2"
        val newerDeployment = fixture.kernel()
        val result = newerDeployment.verify(fixture.approver, action.id, executed.request.stateVersion, fixture.caseVersion(action))
        assertEquals(boundVersion, result.memoryCandidate!!.modelVersion)
        assertNotEquals(fixture.modelVersionOverride, result.memoryCandidate!!.modelVersion)
        assertTrue(fixture.probe.violations.isEmpty())
    }

    @Test
    fun `missing bound recommendation cannot fabricate a memory model source or partially close the case`() {
        val fixture = Fixture()
        val kernel = fixture.kernel()
        val action = fixture.prepare(kernel)
        val executed = kernel.execute(fixture.approver, action.id, action.stateVersion)
        fixture.hideBoundRecommendation = true
        assertThrows(ResourceNotFoundException::class.java) {
            kernel.verify(fixture.approver, action.id, executed.request.stateVersion, fixture.caseVersion(action))
        }
        assertEquals(executed.request, fixture.rawActions.findById(fixture.tenant, action.id))
        assertEquals(CaseStatus.IN_PROGRESS, fixture.rawCases.findById(fixture.tenant, action.caseId)!!.status)
        assertNull(fixture.rawVerifications.findByActionRequestId(fixture.tenant, action.id))
        assertTrue(fixture.rawMemories.findAll(fixture.tenant).isEmpty())
    }

    @Test
    fun `audit and case timeline authorize every fact by its type even when business IDs overlap`() {
        val fixture = Fixture()
        val kernel = fixture.kernel()
        val restrictedCase = fixture.rawCases.findAll(fixture.tenant).first { it.scenarioKinds == setOf("SUPPLIER_DELAY") }
        fixture.rawCases.save(restrictedCase.copy(claims = restrictedCase.claims.map { claim ->
            claim.copy(aclRef = "role:ADMIN", evidenceRefs = claim.evidenceRefs.map { it.copy(aclRef = "role:ADMIN") })
        }))
        val admin = fixture.owner.copy(id = "platform-admin", roles = setOf(BusinessRole.ADMIN))
        val recommendation = kernel.recommend(admin, restrictedCase.id)
        val action = kernel.createAction(admin, CreateActionCommand(recommendation.id, restrictedCase.stateVersion, "typed-audit-command"))
        val publicCase = fixture.rawCases.findAll(fixture.tenant).first { it.id != restrictedCase.id }.copy(id = action.id)
        fixture.rawCases.save(publicCase)
        fixture.rawAudits.append(AuditFact(fixture.tenant, "case", action.id, "case.public", admin.id, fixture.clock.instant()))
        fixture.rawAudits.append(AuditFact(fixture.tenant, "actionRequest", action.id, "action.sensitive", admin.id, fixture.clock.instant(),
            mapOf("caseId" to restrictedCase.id, "privateDetail" to "受限来源的细节")))
        fixture.rawAudits.append(AuditFact(fixture.tenant, "unregistered-type", action.id, "unknown.sensitive", admin.id, fixture.clock.instant()))
        assertThrows(ResourceNotFoundException::class.java) { kernel.getAction(fixture.requester, action.id) }
        val aggregate = kernel.auditTrail(fixture.requester, action.id)
        val timeline = kernel.caseTimeline(fixture.requester, action.id)
        assertEquals(listOf("case.public"), aggregate.map { it.eventType })
        assertEquals(listOf("case.public"), timeline.map { it.eventType })
        assertTrue(kernel.auditTrail(admin, action.id).any { it.eventType == "action.sensitive" })
        assertFalse(kernel.auditTrail(admin, action.id).any { it.eventType == "unknown.sensitive" }, "unknown audit types fail closed for every role")
        assertTrue(fixture.probe.violations.isEmpty())
    }
}

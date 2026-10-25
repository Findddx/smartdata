package io.smartdata.foundation.service

import io.smartdata.foundation.domain.ActionDefinition
import io.smartdata.foundation.domain.ActionReceipt
import io.smartdata.foundation.domain.ActionRequest
import io.smartdata.foundation.domain.ActionState
import io.smartdata.foundation.domain.ActionStateMachine
import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.AuditFact
import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.domain.CaseStatus
import io.smartdata.foundation.domain.Claim
import io.smartdata.foundation.domain.ClaimStatus
import io.smartdata.foundation.domain.DataClassification
import io.smartdata.foundation.domain.EvidenceRef
import io.smartdata.foundation.domain.ExceptionCase
import io.smartdata.foundation.domain.MemoryAsset
import io.smartdata.foundation.domain.MemoryLevel
import io.smartdata.foundation.domain.MemoryStatus
import io.smartdata.foundation.domain.ReceiptOutcome
import io.smartdata.foundation.domain.Recommendation
import io.smartdata.foundation.domain.StableIds
import io.smartdata.foundation.domain.VerificationOutcome
import io.smartdata.foundation.domain.VerificationResult
import io.smartdata.foundation.domain.VerificationPurpose
import io.smartdata.foundation.port.ConnectorPort
import io.smartdata.foundation.port.DataAccessDecisionPort
import io.smartdata.foundation.port.ModelPort
import io.smartdata.foundation.port.PolicyDecisionPort
import io.smartdata.foundation.repository.ActionReceiptRepository
import io.smartdata.foundation.repository.ActionRequestRepository
import io.smartdata.foundation.repository.AuditRepository
import io.smartdata.foundation.repository.CaseRepository
import io.smartdata.foundation.repository.MemoryRepository
import io.smartdata.foundation.repository.RecommendationRepository
import io.smartdata.foundation.repository.VerificationRepository
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class CreateActionCommand(
    val recommendationId: String,
    val expectedCaseStateVersion: Long,
    val idempotencyKey: String,
)

data class ExecutionView(val request: ActionRequest, val receipt: ActionReceipt)

data class VerificationView(
    val request: ActionRequest,
    val receipt: ActionReceipt,
    val verification: VerificationResult,
    val memoryCandidate: MemoryAsset? = null,
)

enum class MemoryReviewDecision { APPROVE, REVOKE }

class FoundationKernel(
    private val cases: CaseRepository,
    private val recommendations: RecommendationRepository,
    private val actions: ActionRequestRepository,
    private val receipts: ActionReceiptRepository,
    private val verifications: VerificationRepository,
    private val memories: MemoryRepository,
    private val audits: AuditRepository,
    private val connector: ConnectorPort,
    private val model: ModelPort,
    private val policy: PolicyDecisionPort,
    private val dataAccess: DataAccessDecisionPort,
    private val clock: Clock,
    private val definitions: Map<String, ActionDefinition> = FulfillmentActions.definitions,
) {
    fun listCases(actor: Actor): List<ExceptionCase> {
        requireAnyRole(actor, caseReaderRoles)
        return cases.findAll(actor.tenantId).map { visibleCase(actor, it) }
    }

    fun getCase(actor: Actor, caseId: String): ExceptionCase =
        visibleCase(actor, loadCase(actor, caseId))

    fun getEvidence(actor: Actor, caseId: String): List<EvidenceRef> = visibleCase(actor, loadCase(actor, caseId))
        .claims.flatMap { it.evidenceRefs }.distinctBy { it.id }.sortedByDescending { it.observedAt }

    fun getRecommendation(actor: Actor, id: String): Recommendation {
        val recommendation = recommendations.findById(actor.tenantId, id)
            ?: throw ResourceNotFoundException("recommendation", id)
        requireCaseEvidenceAccess(actor, recommendation.caseId, recommendation.evidenceIds, "recommendation", id)
        return recommendation
    }

    fun getAction(actor: Actor, id: String): ActionRequest {
        val action = actions.findById(actor.tenantId, id) ?: throw ResourceNotFoundException("actionRequest", id)
        requireCaseEvidenceAccess(actor, action.caseId, action.evidenceIds, "actionRequest", id)
        return action
    }

    fun getReceipt(actor: Actor, actionRequestId: String): ActionReceipt? {
        getAction(actor, actionRequestId)
        return receipts.findByActionRequestId(actor.tenantId, actionRequestId)
    }

    fun getVerification(
        actor: Actor,
        actionRequestId: String,
        purpose: VerificationPurpose = VerificationPurpose.ACTION,
    ): VerificationResult? {
        getAction(actor, actionRequestId)
        return verifications.findByActionRequestId(actor.tenantId, actionRequestId, purpose)?.also { verification ->
            if (verification.evidenceRefs.any {
                    !dataAccess.canRead(actor, it.tenantId, it.aclRef, it.classification)
                }
            ) throw ResourceNotFoundException("verification", actionRequestId)
        }
    }

    fun listMemoryCandidates(actor: Actor): List<MemoryAsset> = memories.findAll(actor.tenantId)
        .filter { it.status == MemoryStatus.PROPOSED && canRead(actor, it.tenantId, it.aclRef) }

    fun listApprovedMemories(actor: Actor): List<MemoryAsset> = memories.findAll(actor.tenantId)
        .filter { it.status == MemoryStatus.APPROVED && canRead(actor, it.tenantId, it.aclRef) }

    fun getMemory(actor: Actor, memoryId: String): MemoryAsset = memories.findById(actor.tenantId, memoryId)
        ?.takeIf { canRead(actor, it.tenantId, it.aclRef) }
        ?: throw ResourceNotFoundException("memoryAsset", memoryId)

    fun listActionDefinitions(actor: Actor): List<ActionDefinition> {
        requireAnyRole(actor, setOf(BusinessRole.AUDITOR, BusinessRole.ADMIN))
        return definitions.values.sortedBy { it.id }
    }

    fun recommend(actor: Actor, caseId: String): Recommendation {
        requireAnyRole(actor, setOf(
            BusinessRole.ORDER_SPECIALIST, BusinessRole.WAREHOUSE_APPROVER, BusinessRole.SALES_OWNER,
            BusinessRole.AUDITOR, BusinessRole.ADMIN,
        ))
        val case = getCase(actor, caseId)
        if (case.status == CaseStatus.VERIFIED_CLOSED) {
            throw BusinessValidationException("已验证结案的案件不能再次生成处置建议")
        }
        val modelCase = case.copy(claims = case.claims.filter(::isClaimCurrentForModel))
        if (modelCase.claims.isEmpty()) {
            throw BusinessValidationException("案件没有当前有效且未撤销的主张，禁止调用模型生成动作")
        }
        val proposed = model.recommend(modelCase, definitions.values)
        val definition = validateRecommendation(case, proposed)
        val recommendation = proposed.copy(
            id = StableIds.from(
                "recommendation",
                case.tenantId,
                case.id,
                case.stateVersion,
                definition.id,
                recommendationFingerprint(proposed),
            ),
            tenantId = case.tenantId,
            caseId = case.id,
            caseStateVersion = case.stateVersion,
            createdAt = clock.instant(),
            domainVersion = case.domainVersion,
            policyVersion = case.policyVersion,
            modelVersion = model.modelVersion,
            evidenceIds = proposed.evidenceIds,
        )
        recommendations.save(recommendation)
        audit(actor, "recommendation", recommendation.id, "recommendation.created",
            mapOf("caseId" to case.id, "actionDefinitionId" to recommendation.actionDefinitionId))
        return recommendation
    }

    fun createAction(actor: Actor, command: CreateActionCommand): ActionRequest {
        require(command.idempotencyKey.length in 8..128) { "Idempotency-Key 长度必须为 8 到 128 个字符" }
        val recommendation = getRecommendation(actor, command.recommendationId)
        val case = loadCase(actor, recommendation.caseId)
        val definition = definitions[recommendation.actionDefinitionId]
            ?: throw BusinessValidationException("未知动作定义：${recommendation.actionDefinitionId}")
        requireAnyRole(actor, definition.requestRoles)
        val fingerprint = fingerprint(recommendation, command.expectedCaseStateVersion)

        actions.findByIdempotencyKey(actor.tenantId, command.idempotencyKey)?.let { existing ->
            if (existing.requestFingerprint != fingerprint) throw IdempotencyConflictException()
            return existing
        }
        if (case.stateVersion != command.expectedCaseStateVersion ||
            recommendation.caseStateVersion != command.expectedCaseStateVersion
        ) {
            throw OptimisticStateException("case", case.id, command.expectedCaseStateVersion, case.stateVersion)
        }
        validateRecommendation(case, recommendation)
        val policyDecision = policy.evaluate(actor, case, definition, recommendation.actionInput)
        if (!policyDecision.allowed || policyDecision.policyVersion != case.policyVersion) {
            throw AuthorizationDeniedException("策略拒绝动作：${policyDecision.reasonZh}")
        }
        val now = clock.instant()
        val updatedCase = cases.save(
            case.copy(
                status = CaseStatus.AWAITING_APPROVAL,
                stateVersion = case.stateVersion + 1,
                updatedAt = now,
            ),
            case.stateVersion,
        )
        var request = ActionRequest(
            id = StableIds.from("action", actor.tenantId, command.idempotencyKey),
            tenantId = actor.tenantId,
            caseId = case.id,
            recommendationId = recommendation.id,
            definitionId = definition.id,
            definitionVersion = definition.version,
            input = recommendation.actionInput,
            evidenceIds = recommendation.evidenceIds,
            requestedBy = actor.id,
            idempotencyKey = command.idempotencyKey,
            requestFingerprint = fingerprint,
            expectedCaseStateVersion = command.expectedCaseStateVersion,
            boundCaseStateVersion = updatedCase.stateVersion,
            domainVersion = recommendation.domainVersion,
            policyVersion = recommendation.policyVersion,
            state = ActionState.PROPOSED,
            stateVersion = 0,
            createdAt = now,
            approvalExpiresAt = now.plus(definition.approvalTtl),
            updatedAt = now,
        )
        request = ActionStateMachine.transition(request, ActionState.POLICY_PASSED, now)
        request = ActionStateMachine.transition(request, ActionState.AWAITING_APPROVAL, now)
        actions.save(request)
        audit(actor, "actionRequest", request.id, "action.awaiting_approval",
            mapOf("caseId" to case.id, "definitionId" to definition.id))
        return request
    }

    fun approve(actor: Actor, actionRequestId: String, expectedStateVersion: Long): ActionRequest {
        val request = getAction(actor, actionRequestId)
        val definition = definitionFor(request)
        requireAnyRole(actor, definition.approvalRoles)
        if (actor.id == request.requestedBy) {
            throw AuthorizationDeniedException("申请人与审批人必须为不同人员")
        }
        requireVersion(request, expectedStateVersion)
        val case = loadCase(actor, request.caseId)
        expireWhenApprovalIsStale(request, definition, case, actor)
        if (case.stateVersion != request.boundCaseStateVersion ||
            case.domainVersion != request.domainVersion || case.policyVersion != request.policyVersion
        ) {
            val expired = ActionStateMachine.transition(request, ActionState.EXPIRED, clock.instant())
            actions.save(expired, request.stateVersion)
            throw OptimisticStateException("case", case.id, request.boundCaseStateVersion, case.stateVersion)
        }
        val approved = ActionStateMachine.transition(request, ActionState.APPROVED, clock.instant())
            .copy(approvedBy = actor.id)
        actions.save(approved, request.stateVersion)
        audit(actor, "actionRequest", approved.id, "action.approved", mapOf("caseId" to approved.caseId))
        return approved
    }

    fun reject(actor: Actor, actionRequestId: String, expectedStateVersion: Long): ActionRequest {
        val request = getAction(actor, actionRequestId)
        requireAnyRole(actor, definitionFor(request).approvalRoles)
        requireVersion(request, expectedStateVersion)
        val rejected = ActionStateMachine.transition(request, ActionState.REJECTED, clock.instant())
        actions.save(rejected, request.stateVersion)
        reopenCaseIfUnchanged(request, actor)
        audit(actor, "actionRequest", rejected.id, "action.rejected", mapOf("caseId" to rejected.caseId))
        return rejected
    }

    fun execute(actor: Actor, actionRequestId: String, expectedStateVersion: Long): ExecutionView {
        val visible = getAction(actor, actionRequestId)
        val definition = definitionFor(visible)
        requireAnyRole(actor, definition.approvalRoles)
        if (actor.id != visible.approvedBy && BusinessRole.ADMIN !in actor.roles) {
            throw AuthorizationDeniedException("只有原审批人或管理员可以触发已审批动作")
        }

        val executionKey = "${actor.tenantId}::$actionRequestId"
        val candidate = InFlightExecution(expectedStateVersion, CompletableFuture())
        val running = inFlightExecutions.putIfAbsent(executionKey, candidate)
        if (running != null) {
            if (running.expectedStateVersion != expectedStateVersion) {
                throw OptimisticStateException(
                    "actionRequest",
                    actionRequestId,
                    expectedStateVersion,
                    visible.stateVersion,
                )
            }
            return try {
                running.result.join()
            } catch (failure: CompletionException) {
                val cause = failure.cause
                if (cause is RuntimeException) throw cause
                throw BusinessValidationException("并发执行请求未能完成")
            }
        }

        return try {
            executeOnce(actor, actionRequestId, expectedStateVersion).also(candidate.result::complete)
        } catch (failure: RuntimeException) {
            candidate.result.completeExceptionally(failure)
            throw failure
        } finally {
            inFlightExecutions.remove(executionKey, candidate)
        }
    }

    private fun executeOnce(actor: Actor, actionRequestId: String, expectedStateVersion: Long): ExecutionView {
        var request = getAction(actor, actionRequestId)
        val definition = definitionFor(request)
        requireAnyRole(actor, definition.approvalRoles)
        if (actor.id != request.approvedBy && BusinessRole.ADMIN !in actor.roles) {
            throw AuthorizationDeniedException("只有原审批人或管理员可以触发已审批动作")
        }
        val existingReceipt = receipts.findByActionRequestId(actor.tenantId, request.id)
        if (request.state == ActionState.EXECUTING) {
            val receipt = existingReceipt ?: run {
                val reconciliationNotBefore = request.updatedAt
                    .plus(definition.timeout)
                    .plus(EXECUTION_RECONCILIATION_GRACE)
                if (clock.instant().isBefore(reconciliationNotBefore)) {
                    throw BusinessValidationException(
                        "动作仍处于执行租约窗口；禁止并发重放，请等待原执行完成或租约到期后对账",
                    )
                }
                unknownReceipt(
                    request,
                    "执行租约已过期且没有外部回执；禁止重放写操作，必须先独立对账",
                ).also(receipts::save)
            }
            val target = if (receipt.outcome == ReceiptOutcome.UNKNOWN) {
                ActionState.UNKNOWN_REQUIRES_RECONCILIATION
            } else {
                ActionState.EXECUTED_UNVERIFIED
            }
            request = ActionStateMachine.transition(request, target, clock.instant())
            actions.save(request, request.stateVersion - 1)
            val verificationCase = markCaseInProgress(actor, request.caseId)
            val beforeBinding = request.stateVersion
            request = request.copy(
                verificationBoundCaseStateVersion = verificationCase.stateVersion,
                stateVersion = request.stateVersion + 1,
                updatedAt = clock.instant(),
            )
            actions.save(request, beforeBinding)
            audit(actor, "actionRequest", request.id, "action.execution_reconciled_after_interruption",
                mapOf("caseId" to request.caseId, "receiptId" to receipt.id, "state" to target.name))
            return ExecutionView(request, receipt)
        }
        existingReceipt?.let { existing ->
            if (request.state in setOf(
                    ActionState.EXECUTED_UNVERIFIED,
                    ActionState.UNKNOWN_REQUIRES_RECONCILIATION,
                    ActionState.VERIFIED_SUCCEEDED,
                    ActionState.VERIFIED_FAILED,
                    ActionState.COMPENSATED,
                )
            ) return ExecutionView(request, existing)
        }
        requireVersion(request, expectedStateVersion)
        val case = loadCase(actor, request.caseId)
        expireWhenApprovalIsStale(request, definition, case, actor)
        if (case.stateVersion != request.boundCaseStateVersion ||
            case.domainVersion != request.domainVersion || case.policyVersion != request.policyVersion
        ) {
            val expired = ActionStateMachine.transition(request, ActionState.EXPIRED, clock.instant())
            actions.save(expired, request.stateVersion)
            throw OptimisticStateException("case", case.id, request.boundCaseStateVersion, case.stateVersion)
        }
        request = ActionStateMachine.transition(request, ActionState.EXECUTING, clock.instant())
        actions.save(request, expectedStateVersion)

        val result = try {
            invokeConnector(definition, "执行") {
                connector.execute(definition, request)
            }.also { validateReceipt(request, it.receipt, it.ambiguous) }
        } catch (failure: Exception) {
            io.smartdata.foundation.port.ConnectorExecutionResult(
                unknownReceipt(request, "连接异常，外部结果不可确定，必须先对账"),
                ambiguous = true,
            )
        }
        receipts.save(result.receipt)
        val target = if (result.ambiguous || result.receipt.outcome == ReceiptOutcome.UNKNOWN) {
            ActionState.UNKNOWN_REQUIRES_RECONCILIATION
        } else {
            ActionState.EXECUTED_UNVERIFIED
        }
        request = ActionStateMachine.transition(request, target, clock.instant())
        actions.save(request, request.stateVersion - 1)
        val verificationCase = markCaseInProgress(actor, case.id)
        val beforeBinding = request.stateVersion
        request = request.copy(
            verificationBoundCaseStateVersion = verificationCase.stateVersion,
            stateVersion = request.stateVersion + 1,
            updatedAt = clock.instant(),
        )
        actions.save(request, beforeBinding)
        audit(actor, "actionRequest", request.id,
            if (target == ActionState.UNKNOWN_REQUIRES_RECONCILIATION) "action.execution_unknown" else "action.executed_unverified",
            mapOf("caseId" to request.caseId, "receiptId" to result.receipt.id))
        return ExecutionView(request, result.receipt)
    }

    fun verify(
        actor: Actor,
        actionRequestId: String,
        expectedActionStateVersion: Long,
        expectedCaseStateVersion: Long,
    ): VerificationView {
        var request = getAction(actor, actionRequestId)
        requireAnyRole(actor, setOf(BusinessRole.WAREHOUSE_APPROVER, BusinessRole.AUDITOR, BusinessRole.ADMIN))
        requireVersion(request, expectedActionStateVersion)
        val caseAtVerification = loadCase(actor, request.caseId)
        if (caseAtVerification.stateVersion != expectedCaseStateVersion) {
            throw OptimisticStateException(
                "case",
                caseAtVerification.id,
                expectedCaseStateVersion,
                caseAtVerification.stateVersion,
            )
        }
        val existingVerification = getVerification(actor, request.id)
        val existingReceipt = receipts.findByActionRequestId(actor.tenantId, request.id)
            ?: throw BusinessValidationException("动作尚无外部回执，不能验证")
        if (existingVerification != null && request.state in terminalVerificationStates) {
            return VerificationView(request, existingReceipt, existingVerification,
                memories.findAll(actor.tenantId).firstOrNull {
                    request.caseId in it.sourceCaseIds && canRead(actor, it.tenantId, it.aclRef)
                })
        }
        if (request.state !in setOf(ActionState.EXECUTED_UNVERIFIED, ActionState.UNKNOWN_REQUIRES_RECONCILIATION)) {
            throw BusinessValidationException("当前动作状态 ${request.state} 不允许验证")
        }
        val definition = definitionFor(request)
        if (definition.version != request.definitionVersion) {
            moveActionToManualIntervention(actor, request, "验证器所属动作定义版本已变化")
            throw BusinessValidationException("动作定义版本变化后禁止使用新验证器判定旧动作")
        }
        if (request.verificationBoundCaseStateVersion != caseAtVerification.stateVersion ||
            request.domainVersion != caseAtVerification.domainVersion ||
            request.policyVersion != caseAtVerification.policyVersion
        ) {
            moveActionToManualIntervention(actor, request, "案件、领域包或策略在执行后发生变化")
            throw BusinessValidationException("执行后案件版本已变化，旧回执必须人工对账")
        }
        val verification = try {
            invokeConnector(definition, "验证") {
                connector.verify(definition, request, existingReceipt)
            }
        } catch (_: Exception) {
            moveActionToManualIntervention(actor, request, "独立验证器不可用或超过动作时限")
            throw BusinessValidationException("独立验证器不可用或超过动作时限，已转人工对账")
        }
        try {
            validateVerification(
                actor = actor,
                request = request,
                receipt = existingReceipt,
                case = caseAtVerification,
                verification = verification,
                expectedVerifierId = definition.verifierId,
                expectedPurpose = VerificationPurpose.ACTION,
                expectedId = StableIds.from("verification", request.tenantId, request.id),
                allowedResolvedGapCodes = definition.resolvableGapCodes,
            )
        } catch (failure: BusinessValidationException) {
            moveActionToManualIntervention(actor, request, failure.message ?: "验证结果不可信")
            throw failure
        }
        verifications.save(verification)
        var receipt = existingReceipt.copy(verificationId = verification.id, updatedAt = clock.instant())
        receipts.save(receipt)
        var candidate: MemoryAsset? = null

        when (verification.outcome) {
            VerificationOutcome.PASSED -> {
                request = ActionStateMachine.transition(request, ActionState.VERIFIED_SUCCEEDED, clock.instant())
                actions.save(request, request.stateVersion - 1)
                val remainingGaps = caseAtVerification.knowledgeGaps.filterNot {
                    it.id in verification.resolvedKnowledgeGapIds
                }
                val resolvesBusinessException = remainingGaps.isEmpty()
                cases.save(caseAtVerification.copy(
                    status = if (resolvesBusinessException) CaseStatus.VERIFIED_CLOSED else CaseStatus.IN_PROGRESS,
                    knowledgeGaps = remainingGaps,
                    stateVersion = caseAtVerification.stateVersion + 1,
                    updatedAt = clock.instant(),
                ), caseAtVerification.stateVersion)
                if (resolvesBusinessException) candidate = createMemoryCandidate(request, verification)
            }
            VerificationOutcome.FAILED -> {
                request = ActionStateMachine.transition(request, ActionState.VERIFIED_FAILED, clock.instant())
                actions.save(request, request.stateVersion - 1)
                if (definition.compensationActionId != null && existingReceipt.outcome != ReceiptOutcome.FAILED) {
                    request = ActionStateMachine.transition(request, ActionState.COMPENSATING, clock.instant())
                    actions.save(request, request.stateVersion - 1)
                    receipt = try {
                        invokeConnector(definition, "补偿") {
                            connector.compensate(definition, request, receipt)
                        }.also {
                            validateReceipt(request, it, ambiguous = false)
                            if (it.outcome != ReceiptOutcome.COMPENSATED) {
                                throw BusinessValidationException("补偿器未返回可验证的补偿回执")
                            }
                        }
                    } catch (_: Exception) {
                        receipt
                    }
                    receipts.save(receipt)
                    var compensationVerified = false
                    if (receipt.outcome == ReceiptOutcome.COMPENSATED && definition.compensationVerifierId != null) {
                        compensationVerified = try {
                            val compensationVerification = invokeConnector(definition, "补偿验证") {
                                connector.verifyCompensation(definition, request, receipt)
                            }
                            validateVerification(
                                actor = actor,
                                request = request,
                                receipt = receipt,
                                case = caseAtVerification,
                                verification = compensationVerification,
                                expectedVerifierId = definition.compensationVerifierId,
                                expectedPurpose = VerificationPurpose.COMPENSATION,
                                expectedId = StableIds.from("compensation-verification", request.tenantId, request.id),
                                allowedResolvedGapCodes = emptySet(),
                            )
                            verifications.save(compensationVerification)
                            receipt = receipt.copy(
                                compensationVerificationId = compensationVerification.id,
                                updatedAt = clock.instant(),
                            )
                            receipts.save(receipt)
                            compensationVerification.outcome == VerificationOutcome.PASSED
                        } catch (_: Exception) {
                            false
                        }
                    }
                    request = ActionStateMachine.transition(
                        request,
                        if (compensationVerified) ActionState.COMPENSATED else ActionState.MANUAL_INTERVENTION,
                        clock.instant(),
                    )
                    actions.save(request, request.stateVersion - 1)
                }
                markCaseForManualIntervention(actor, request.caseId)
            }
            VerificationOutcome.INCONCLUSIVE -> {
                if (request.state == ActionState.EXECUTED_UNVERIFIED) {
                    request = ActionStateMachine.transition(request, ActionState.UNKNOWN_REQUIRES_RECONCILIATION, clock.instant())
                    actions.save(request, request.stateVersion - 1)
                }
                markCaseForManualIntervention(actor, request.caseId)
            }
        }
        audit(actor, "actionRequest", request.id, "verification.${verification.outcome.name.lowercase()}",
            mapOf(
                "caseId" to request.caseId,
                "verificationId" to verification.id,
                "independentSource" to verification.independentSource,
            ))
        return VerificationView(request, receipt, verification, candidate)
    }

    fun reviewMemory(
        actor: Actor,
        memoryId: String,
        decision: MemoryReviewDecision,
        expectedStateVersion: Long,
    ): MemoryAsset {
        requireAnyRole(actor, setOf(BusinessRole.SALES_OWNER, BusinessRole.ADMIN))
        val current = memories.findById(actor.tenantId, memoryId)
            ?.takeIf { canRead(actor, it.tenantId, it.aclRef) }
            ?: throw ResourceNotFoundException("memoryAsset", memoryId)
        if (current.stateVersion != expectedStateVersion) {
            throw OptimisticStateException("memoryAsset", memoryId, expectedStateVersion, current.stateVersion)
        }
        if (current.status != MemoryStatus.PROPOSED) {
            throw BusinessValidationException("只有候选记忆可以审核")
        }
        val now = clock.instant()
        val reviewed = current.copy(
            status = if (decision == MemoryReviewDecision.APPROVE) MemoryStatus.APPROVED else MemoryStatus.REVOKED,
            level = if (decision == MemoryReviewDecision.APPROVE) MemoryLevel.L3_APPROVED_PLAYBOOK else current.level,
            ownerId = actor.id,
            stateVersion = current.stateVersion + 1,
            updatedAt = now,
        )
        memories.save(reviewed, current.stateVersion)
        audit(actor, "memoryAsset", reviewed.id, "memory.${reviewed.status.name.lowercase()}")
        return reviewed
    }

    fun auditTrail(actor: Actor, aggregateId: String): List<AuditFact> {
        requireAggregateAccess(actor, aggregateId)
        return audits.findByAggregate(actor.tenantId, aggregateId)
    }

    fun caseTimeline(actor: Actor, caseId: String): List<AuditFact> {
        getCase(actor, caseId)
        return audits.findByCase(actor.tenantId, caseId).filter { auditVisible(actor, it) }
    }

    private fun loadCase(actor: Actor, caseId: String): ExceptionCase =
        cases.findById(actor.tenantId, caseId) ?: throw ResourceNotFoundException("case", caseId)

    private fun visibleCase(actor: Actor, source: ExceptionCase): ExceptionCase {
        requireAnyRole(actor, caseReaderRoles)
        val visibleClaims = source.claims.mapNotNull { claim ->
            if (!dataAccess.canRead(actor, claim.tenantId, claim.aclRef, claim.classification)) {
                return@mapNotNull null
            }
            val evidence = claim.evidenceRefs.filter {
                dataAccess.canRead(actor, it.tenantId, it.aclRef, it.classification)
            }
            if (evidence.isEmpty()) null else claim.copy(evidenceRefs = evidence)
        }
        val visibleGaps = source.knowledgeGaps.filter {
            dataAccess.canRead(actor, it.tenantId, it.aclRef, it.classification)
        }
        return source.copy(claims = visibleClaims, knowledgeGaps = visibleGaps)
    }

    private fun canRead(actor: Actor, tenantId: String, aclRef: String): Boolean =
        dataAccess.canRead(actor, tenantId, aclRef, DataClassification.INTERNAL)

    private fun requireCaseEvidenceAccess(
        actor: Actor,
        caseId: String,
        evidenceIds: Set<String>,
        resourceType: String,
        resourceId: String,
    ) {
        val source = loadCase(actor, caseId)
        val readableEvidenceIds = source.claims.asSequence()
            .filter { dataAccess.canRead(actor, it.tenantId, it.aclRef, it.classification) }
            .flatMap { it.evidenceRefs.asSequence() }
            .filter { dataAccess.canRead(actor, it.tenantId, it.aclRef, it.classification) }
            .map { it.id }
            .toSet()
        if (evidenceIds.isEmpty() || !readableEvidenceIds.containsAll(evidenceIds)) {
            throw ResourceNotFoundException(resourceType, resourceId)
        }
    }

    private fun requireAggregateAccess(actor: Actor, aggregateId: String) {
        when {
            cases.findById(actor.tenantId, aggregateId) != null -> getCase(actor, aggregateId)
            recommendations.findById(actor.tenantId, aggregateId) != null -> getRecommendation(actor, aggregateId)
            actions.findById(actor.tenantId, aggregateId) != null -> getAction(actor, aggregateId)
            memories.findById(actor.tenantId, aggregateId)?.let { canRead(actor, it.tenantId, it.aclRef) } == true -> Unit
            else -> throw ResourceNotFoundException("auditAggregate", aggregateId)
        }
    }

    private fun auditVisible(actor: Actor, fact: AuditFact): Boolean = runCatching {
        requireAggregateAccess(actor, fact.aggregateId)
    }.isSuccess

    private fun createMemoryCandidate(request: ActionRequest, verification: VerificationResult): MemoryAsset {
        val now = clock.instant()
        val candidate = MemoryAsset(
            id = StableIds.from("memory", request.tenantId, request.caseId, request.id),
            tenantId = request.tenantId,
            level = MemoryLevel.L1_ATOMIC_CANDIDATE,
            status = MemoryStatus.PROPOSED,
            titleZh = "订单履约处置经验候选",
            contentZh = "经独立来源 ${verification.independentSource} 验证，动作 ${request.definitionId} 达成预期结果。",
            sourceCaseIds = setOf(request.caseId),
            sourceEvidenceIds = request.evidenceIds + verification.evidenceRefs.map { it.id },
            validFrom = now,
            ownerId = request.requestedBy,
            aclRef = derivedMemoryAcl(request, verification),
            modelVersion = model.modelVersion,
            embeddingVersion = null,
            stateVersion = 0,
            createdAt = now,
            updatedAt = now,
        )
        return memories.save(candidate)
    }

    private fun derivedMemoryAcl(request: ActionRequest, verification: VerificationResult): String {
        val sourceCase = cases.findById(request.tenantId, request.caseId)
        val caseAcls = sourceCase?.claims.orEmpty().flatMap { claim ->
            val referencedEvidence = claim.evidenceRefs.filter { it.id in request.evidenceIds }
            if (referencedEvidence.isEmpty()) emptyList() else listOf(claim.aclRef) + referencedEvidence.map { it.aclRef }
        }
        val allAcls = (caseAcls + verification.evidenceRefs.map { it.aclRef }).toSet()
        val tenantAcls = setOf("tenant", "tenant:${request.tenantId}")
        val restrictive = allAcls - tenantAcls
        return when {
            allAcls.isEmpty() -> "role:ADMIN"
            restrictive.isEmpty() -> "tenant:${request.tenantId}"
            restrictive.size == 1 -> restrictive.single()
            else -> "role:ADMIN"
        }
    }

    private fun markCaseForManualIntervention(actor: Actor, caseId: String) {
        val case = loadCase(actor, caseId)
        if (case.status != CaseStatus.MANUAL_INTERVENTION) {
            cases.save(case.copy(
                status = CaseStatus.MANUAL_INTERVENTION,
                stateVersion = case.stateVersion + 1,
                updatedAt = clock.instant(),
            ), case.stateVersion)
        }
    }

    private fun markCaseInProgress(actor: Actor, caseId: String): ExceptionCase {
        val case = loadCase(actor, caseId)
        return if (case.status != CaseStatus.IN_PROGRESS) {
            cases.save(case.copy(
                status = CaseStatus.IN_PROGRESS,
                stateVersion = case.stateVersion + 1,
                updatedAt = clock.instant(),
            ), case.stateVersion)
        } else case
    }

    private fun reopenCaseIfUnchanged(request: ActionRequest, actor: Actor) {
        val case = loadCase(actor, request.caseId)
        if (case.stateVersion == request.boundCaseStateVersion) {
            cases.save(case.copy(
                status = CaseStatus.OPEN,
                stateVersion = case.stateVersion + 1,
                updatedAt = clock.instant(),
            ), case.stateVersion)
        }
    }

    private fun definitionFor(request: ActionRequest): ActionDefinition = definitions[request.definitionId]
        ?: throw BusinessValidationException("未知动作定义：${request.definitionId}")

    private fun requireAnyRole(actor: Actor, allowed: Set<BusinessRole>) {
        if (actor.roles.intersect(allowed).isEmpty()) {
            throw AuthorizationDeniedException("角色无权执行此操作")
        }
    }

    private fun requireVersion(request: ActionRequest, expected: Long) {
        if (request.stateVersion != expected) {
            throw OptimisticStateException("actionRequest", request.id, expected, request.stateVersion)
        }
    }

    private fun expireWhenApprovalIsStale(
        request: ActionRequest,
        definition: ActionDefinition,
        case: ExceptionCase,
        actor: Actor,
    ) {
        val now = clock.instant()
        val reason = when {
            definition.version != request.definitionVersion -> "动作定义版本已变化"
            !now.isBefore(request.approvalExpiresAt) -> "审批窗口已过期"
            else -> null
        }
        if (reason != null && request.state in setOf(ActionState.AWAITING_APPROVAL, ActionState.APPROVED)) {
            val expired = ActionStateMachine.transition(request, ActionState.EXPIRED, now)
            actions.save(expired, request.stateVersion)
            audit(actor, "actionRequest", expired.id, "action.expired",
                mapOf("caseId" to case.id, "reason" to reason))
            throw BusinessValidationException(reason)
        }
    }

    private fun validateRecommendation(case: ExceptionCase, recommendation: Recommendation): ActionDefinition {
        if (recommendation.tenantId != case.tenantId || recommendation.caseId != case.id) {
            throw BusinessValidationException("模型建议越过了案件或租户边界")
        }
        if (recommendation.caseStateVersion != case.stateVersion ||
            recommendation.domainVersion != case.domainVersion ||
            recommendation.policyVersion != case.policyVersion
        ) {
            throw BusinessValidationException("模型建议未绑定当前案件、领域包或策略版本")
        }
        if (recommendation.modelVersion != model.modelVersion) {
            throw BusinessValidationException("模型建议版本与调用端口不一致")
        }
        if (recommendation.rationaleZh.isBlank() || recommendation.rationaleZh.length > 4_000) {
            throw BusinessValidationException("模型建议理由为空或超过长度限制")
        }
        val knownEvidenceIds = case.claims.filter(::isClaimCurrentForModel)
            .flatMap { it.evidenceRefs }.map { it.id }.toSet()
        if (recommendation.evidenceIds.isEmpty() || !knownEvidenceIds.containsAll(recommendation.evidenceIds)) {
            throw BusinessValidationException("模型建议引用了未知证据或没有证据")
        }
        val definition = definitions[recommendation.actionDefinitionId]
            ?: throw BusinessValidationException("模型提出了未附着的动作能力")
        validateInput(definition, recommendation.actionInput, case)
        return definition
    }

    private fun validateInput(
        definition: ActionDefinition,
        input: Map<String, Any>,
        case: ExceptionCase,
    ) {
        fun invalid(): Nothing = throw BusinessValidationException(
            "动作输入不符合定义 ${definition.id}@${definition.version}",
        )
        if (input.keys != definition.inputFields || definition.inputFields.any {
                input[it] == null || (input[it] is String && (input[it] as String).isBlank())
            }
        ) {
            invalid()
        }
        fun boundedText(field: String, maxLength: Int): String =
            (input[field] as? String)?.takeIf { it.isNotBlank() && it.length <= maxLength } ?: invalid()

        if (boundedText("caseId", 64) != case.id) invalid()
        val warehouseCode = boundedText("warehouseCode", 40)
        val sku = boundedText("sku", 80)
        val observedWarehouse = trustedClaimValue(case, "warehouseCode")
        val observedSku = trustedClaimValue(case, "sku")
        if (warehouseCode != observedWarehouse || sku != observedSku) invalid()

        when (definition.id) {
            FulfillmentActions.REQUEST_STOCK_RECOUNT -> {
                if (boundedText("assigneeId", 100) != "warehouse-team:$warehouseCode") invalid()
                val dueAt = runCatching { Instant.parse(boundedText("dueAt", 40)) }.getOrNull() ?: invalid()
                if (!dueAt.isAfter(clock.instant())) invalid()
            }
            FulfillmentActions.RESERVE_SUBSTITUTE_INVENTORY -> {
                val orderLineId = boundedText("orderLineId", 80)
                if (orderLineId != "${case.orderId}-L1") invalid()
                val quantity = input["quantity"] as? Number ?: invalid()
                val numericQuantity = quantity.toDouble()
                if (!numericQuantity.isFinite() || numericQuantity % 1.0 != 0.0 || numericQuantity !in 1.0..100_000.0) {
                    invalid()
                }
                val knownShortfall = trustedClaimValue(case, "missingQuantity").toDoubleOrNull() ?: invalid()
                if (numericQuantity > knownShortfall) invalid()
            }
            else -> invalid()
        }
    }

    private fun trustedClaimValue(case: ExceptionCase, predicate: String): String {
        val values = case.claims.asSequence()
            .filter { it.predicate == predicate }
            .filter { it.status in setOf(ClaimStatus.ASSERTED, ClaimStatus.INFERRED) }
            .filter { it.confidence >= 0.95 }
            .filter(::isClaimCurrentForModel)
            .map { it.value }
            .distinct()
            .toList()
        if (values.size != 1) {
            throw BusinessValidationException("字段 $predicate 缺失、过期、冲突或置信度不足")
        }
        return values.single()
    }

    private fun isClaimCurrentForModel(claim: Claim): Boolean {
        val now = clock.instant()
        return claim.status != ClaimStatus.REVOKED &&
            !now.isBefore(claim.validFrom) &&
            (claim.validTo == null || now.isBefore(claim.validTo)) &&
            (claim.expiresAt == null || now.isBefore(claim.expiresAt))
    }

    private fun unknownReceipt(request: ActionRequest, messageZh: String): ActionReceipt {
        val now = clock.instant()
        return ActionReceipt(
            id = StableIds.from("receipt", request.tenantId, request.id),
            tenantId = request.tenantId,
            actionRequestId = request.id,
            externalRequestId = "UNCONFIRMED-${request.id}",
            externalReceiptId = null,
            outcome = ReceiptOutcome.UNKNOWN,
            attempts = listOf(io.smartdata.foundation.domain.ActionAttempt(
                number = 1,
                startedAt = request.updatedAt,
                completedAt = now,
                outcome = ReceiptOutcome.UNKNOWN,
                messageZh = messageZh,
            )),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun validateReceipt(request: ActionRequest, receipt: ActionReceipt, ambiguous: Boolean) {
        if (receipt.tenantId != request.tenantId || receipt.actionRequestId != request.id) {
            throw BusinessValidationException("连接器回执越过了动作或租户边界")
        }
        if (receipt.id != StableIds.from("receipt", request.tenantId, request.id) ||
            receipt.externalRequestId.isBlank() || receipt.attempts.isEmpty() || receipt.attempts.size > 10
        ) {
            throw BusinessValidationException("连接器回执缺少稳定标识或有效尝试记录")
        }
        if (ambiguous && receipt.outcome != ReceiptOutcome.UNKNOWN) {
            throw BusinessValidationException("模糊执行结果必须标记为 UNKNOWN")
        }
    }

    private fun <T> invokeConnector(
        definition: ActionDefinition,
        operationName: String,
        operation: () -> T,
    ): T {
        val future = connectorExecutor.submit<T> { operation() }
        return try {
            future.get(definition.timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw BusinessValidationException("连接器${operationName}超过 ${definition.timeout.toMillis()} ms 时限")
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause is RuntimeException) throw cause
            throw BusinessValidationException("连接器${operationName}失败")
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw BusinessValidationException("连接器${operationName}被中断")
        }
    }

    private fun validateVerification(
        actor: Actor,
        request: ActionRequest,
        receipt: ActionReceipt,
        case: ExceptionCase,
        verification: VerificationResult,
        expectedVerifierId: String,
        expectedPurpose: VerificationPurpose,
        expectedId: String,
        allowedResolvedGapCodes: Set<String>,
    ) {
        val source = verification.independentSource.uppercase()
        if (verification.tenantId != request.tenantId ||
            verification.actionRequestId != request.id ||
            verification.verifierId != expectedVerifierId ||
            verification.purpose != expectedPurpose ||
            verification.id != expectedId
        ) {
            throw BusinessValidationException("验证结果未绑定当前租户、动作和验证器")
        }
        val agentSelfReport = verification.antiGamingMetrics["agent_self_report"]
        if (source == model.modelVersion.uppercase() || source.startsWith("AGENT_") || source.startsWith("LLM_") ||
            (agentSelfReport != null && agentSelfReport != 0.0)
        ) {
            throw BusinessValidationException("Agent 或模型自述不能作为独立验证")
        }
        if (verification.assertions.isEmpty() ||
            (verification.outcome == VerificationOutcome.PASSED && verification.assertions.any { !it.passed }) ||
            (verification.outcome == VerificationOutcome.FAILED && verification.assertions.none { !it.passed })
        ) {
            throw BusinessValidationException("验证结论与独立断言不一致")
        }
        if (verification.observedFrom.isBefore(receipt.createdAt) ||
            verification.createdAt.isBefore(verification.observedUntil)
        ) {
            throw BusinessValidationException("验证观测窗口未绑定外部回执或创建时间")
        }
        val sourceReadback = verification.antiGamingMetrics["source_readback"]
        if (sourceReadback == null || agentSelfReport == null ||
            !sourceReadback.isFinite() || !agentSelfReport.isFinite() ||
            sourceReadback !in 0.0..1.0 || agentSelfReport !in 0.0..1.0 ||
            (verification.outcome == VerificationOutcome.PASSED && sourceReadback != 1.0)
        ) {
            throw BusinessValidationException("验证结果缺少配对防投机指标")
        }
        if (verification.evidenceRefs.any { !source.startsWith(it.sourceSystem.uppercase()) }) {
            throw BusinessValidationException("验证证据来源与独立读取来源不一致")
        }
        if (verification.evidenceRefs.any {
                !dataAccess.canRead(actor, it.tenantId, it.aclRef, it.classification)
            }
        ) {
            throw BusinessValidationException("当前验证发起人无权读取验证证据")
        }
        val knownGapIds = case.knowledgeGaps.map { it.id }.toSet()
        if (!knownGapIds.containsAll(verification.resolvedKnowledgeGapIds)) {
            throw BusinessValidationException("验证器声称解决了案件中不存在的知识缺口")
        }
        val resolvedGapCodes = case.knowledgeGaps
            .filter { it.id in verification.resolvedKnowledgeGapIds }
            .map { it.code }
            .toSet()
        if (!allowedResolvedGapCodes.containsAll(resolvedGapCodes)) {
            throw BusinessValidationException("验证器声称解决了动作定义未授权处理的知识缺口")
        }
    }

    private fun moveActionToManualIntervention(actor: Actor, initial: ActionRequest, reason: String) {
        var request = initial
        if (request.state == ActionState.EXECUTED_UNVERIFIED) {
            val unknown = ActionStateMachine.transition(
                request,
                ActionState.UNKNOWN_REQUIRES_RECONCILIATION,
                clock.instant(),
            )
            actions.save(unknown, request.stateVersion)
            request = unknown
        }
        if (request.state == ActionState.UNKNOWN_REQUIRES_RECONCILIATION) {
            val manual = ActionStateMachine.transition(request, ActionState.MANUAL_INTERVENTION, clock.instant())
            actions.save(manual, request.stateVersion)
            request = manual
        }
        markCaseForManualIntervention(actor, request.caseId)
        audit(actor, "actionRequest", request.id, "action.manual_intervention",
            mapOf("caseId" to request.caseId, "reason" to reason))
    }

    private fun fingerprint(recommendation: Recommendation, expectedVersion: Long): String {
        val canonical = buildString {
            append(recommendation.tenantId).append('|').append(recommendation.caseId).append('|')
            append(recommendation.id).append('|').append(recommendation.actionDefinitionId).append('|')
            append(expectedVersion).append('|')
            recommendation.actionInput.toSortedMap().forEach { (key, value) -> append(key).append('=').append(value).append(';') }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun recommendationFingerprint(recommendation: Recommendation): String {
        val canonical = buildString {
            append(recommendation.actionDefinitionId).append('|')
            append(recommendation.rationaleZh).append('|')
            recommendation.actionInput.toSortedMap().forEach { (key, value) ->
                append(key).append('=').append(value).append(';')
            }
            append('|')
            recommendation.evidenceIds.sorted().forEach { append(it).append(';') }
            append('|').append(recommendation.modelVersion)
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun audit(
        actor: Actor,
        aggregateType: String,
        aggregateId: String,
        eventType: String,
        details: Map<String, String> = emptyMap(),
    ) = audits.append(AuditFact(
        tenantId = actor.tenantId,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        eventType = eventType,
        actorId = actor.id,
        occurredAt = clock.instant(),
        details = details,
    ))

    private companion object {
        val connectorExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("smartdata-connector-", 0).factory(),
        )
        val EXECUTION_RECONCILIATION_GRACE: java.time.Duration = java.time.Duration.ofSeconds(15)
        val caseReaderRoles = BusinessRole.entries.toSet()
        val terminalVerificationStates = setOf(
            ActionState.VERIFIED_SUCCEEDED,
            ActionState.VERIFIED_FAILED,
            ActionState.COMPENSATED,
            ActionState.MANUAL_INTERVENTION,
        )
    }

    private data class InFlightExecution(
        val expectedStateVersion: Long,
        val result: CompletableFuture<ExecutionView>,
    )

    private val inFlightExecutions = ConcurrentHashMap<String, InFlightExecution>()
}

package io.smartdata.foundation.runtime.api

import io.smartdata.foundation.domain.ActionRequest
import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.EvidenceRef
import io.smartdata.foundation.domain.ExceptionCase
import io.smartdata.foundation.domain.MemoryAsset
import io.smartdata.foundation.domain.Recommendation
import io.smartdata.foundation.domain.VerificationPurpose
import io.smartdata.foundation.runtime.security.ActorContextResolver
import io.smartdata.foundation.runtime.security.LocalActorContextResolver
import io.smartdata.foundation.service.CreateActionCommand
import io.smartdata.foundation.service.ExecutionView
import io.smartdata.foundation.service.FoundationKernel
import io.smartdata.foundation.service.FulfillmentActions
import io.smartdata.foundation.service.ResourceNotFoundException
import io.smartdata.foundation.service.VerificationView
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Validated
@RestController
@Profile("local")
@RequestMapping("/api/v1")
class FoundationController(
    private val kernel: FoundationKernel,
    private val actors: ActorContextResolver,
    private val events: InMemoryRunEventBus,
    private val clock: Clock,
    private val idempotency: CommandIdempotencyGate,
) {
    @GetMapping("/cases")
    fun listCases(
        request: HttpServletRequest,
        @RequestParam(defaultValue = "0") @Min(0) offset: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
    ): CasePage {
        val all = kernel.listCases(actor(request))
        return CasePage(all.drop(offset).take(limit).map(CaseSummary::from), all.size, offset, limit)
    }

    @GetMapping("/cases/{caseId}")
    fun getCase(request: HttpServletRequest, @PathVariable caseId: String): ExceptionCase =
        kernel.getCase(actor(request), caseId)

    @GetMapping("/cases/{caseId}/evidence")
    fun getEvidence(request: HttpServletRequest, @PathVariable caseId: String): List<EvidenceRef> =
        kernel.getEvidence(actor(request), caseId)

    @PostMapping("/cases/{caseId}/recommendations")
    fun recommend(
        request: HttpServletRequest,
        @PathVariable caseId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
    ): Recommendation {
        val commandActor = actor(request)
        return idempotency.execute(
            commandActor.tenantId,
            idempotencyKey,
            commandFingerprint("recommend", actorAuthorizationContext(commandActor), caseId),
            revalidate = { cached -> kernel.getRecommendation(commandActor, cached.id) },
        ) {
            val result = kernel.recommend(commandActor, caseId)
            events.publish(commandActor.tenantId, caseId, "run.started", mapOf("caseId" to caseId))
            events.publish(commandActor.tenantId, caseId, "evidence.added", mapOf("count" to result.evidenceIds.size.toString()))
            events.publish(commandActor.tenantId, caseId, "recommendation.created", mapOf("recommendationId" to result.id))
            result
        }
    }

    @GetMapping("/recommendations/{recommendationId}")
    fun getRecommendation(request: HttpServletRequest, @PathVariable recommendationId: String): Recommendation =
        kernel.getRecommendation(actor(request), recommendationId)

    @PostMapping("/action-requests")
    @ResponseStatus(HttpStatus.CREATED)
    fun createAction(
        request: HttpServletRequest,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody body: CreateActionBody,
    ): ActionRequest {
        val commandActor = actor(request)
        return idempotency.execute(
            commandActor.tenantId,
            idempotencyKey,
            commandFingerprint(
                "create-action",
                actorAuthorizationContext(commandActor),
                body.recommendationId,
                body.expectedStateVersion,
            ),
            revalidate = { cached -> kernel.getAction(commandActor, cached.id) },
        ) {
            val result = kernel.createAction(commandActor, CreateActionCommand(
                body.recommendationId, body.expectedStateVersion, idempotencyKey,
            ))
            events.publish(commandActor.tenantId, result.caseId, "approval.requested", mapOf("actionRequestId" to result.id))
            result
        }
    }

    @GetMapping("/action-requests/{actionRequestId}")
    fun getAction(request: HttpServletRequest, @PathVariable actionRequestId: String): ActionRequest =
        kernel.getAction(actor(request), actionRequestId)

    @GetMapping("/action-requests/{actionRequestId}/receipt")
    fun getReceipt(request: HttpServletRequest, @PathVariable actionRequestId: String) =
        kernel.getReceipt(actor(request), actionRequestId)
            ?: throw ResourceNotFoundException("actionReceipt", actionRequestId)

    @PostMapping("/approvals/{actionRequestId}")
    fun approve(
        request: HttpServletRequest,
        @PathVariable actionRequestId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody body: VersionBody,
    ): ActionRequest {
        val commandActor = actor(request)
        return idempotency.execute(
            commandActor.tenantId,
            idempotencyKey,
            commandFingerprint("approve", actorAuthorizationContext(commandActor), actionRequestId, body.expectedStateVersion),
            revalidate = { cached -> kernel.getAction(commandActor, cached.id) },
        ) {
            val result = kernel.approve(commandActor, actionRequestId, body.expectedStateVersion)
            events.publish(commandActor.tenantId, result.caseId, "approval.approved", mapOf("actionRequestId" to result.id))
            result
        }
    }

    @PostMapping("/approvals/{actionRequestId}/reject")
    fun reject(
        request: HttpServletRequest,
        @PathVariable actionRequestId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody body: VersionBody,
    ): ActionRequest {
        val commandActor = actor(request)
        return idempotency.execute(
            commandActor.tenantId,
            idempotencyKey,
            commandFingerprint("reject", actorAuthorizationContext(commandActor), actionRequestId, body.expectedStateVersion),
            revalidate = { cached -> kernel.getAction(commandActor, cached.id) },
        ) {
            val result = kernel.reject(commandActor, actionRequestId, body.expectedStateVersion)
            events.publish(commandActor.tenantId, result.caseId, "approval.rejected", mapOf("actionRequestId" to result.id))
            events.publish(commandActor.tenantId, result.caseId, "run.completed", mapOf("state" to result.state.name))
            result
        }
    }

    @PostMapping("/action-requests/{actionRequestId}/execute")
    fun execute(
        request: HttpServletRequest,
        @PathVariable actionRequestId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody body: VersionBody,
    ): ExecutionView {
        val commandActor = actor(request)
        return idempotency.execute(
            commandActor.tenantId,
            idempotencyKey,
            commandFingerprint("execute", actorAuthorizationContext(commandActor), actionRequestId, body.expectedStateVersion),
            revalidate = { cached ->
                kernel.getAction(commandActor, cached.request.id)
                kernel.getReceipt(commandActor, cached.request.id)
            },
        ) {
            val result = kernel.execute(commandActor, actionRequestId, body.expectedStateVersion)
            events.publish(commandActor.tenantId, result.request.caseId, "action.executed", mapOf(
                "actionRequestId" to result.request.id,
                "state" to result.request.state.name,
                "receiptId" to result.receipt.id,
            ))
            result
        }
    }

    @PostMapping("/verifications/{actionRequestId}")
    fun verify(
        request: HttpServletRequest,
        @PathVariable actionRequestId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody body: VerificationBody,
    ): VerificationView {
        val commandActor = actor(request)
        return idempotency.execute(
            commandActor.tenantId,
            idempotencyKey,
            commandFingerprint(
                "verify",
                actorAuthorizationContext(commandActor),
                actionRequestId,
                body.expectedActionStateVersion,
                body.expectedCaseStateVersion,
            ),
            revalidate = { cached ->
                kernel.getAction(commandActor, cached.request.id)
                kernel.getVerification(commandActor, cached.request.id, cached.verification.purpose)
            },
        ) {
            val result = kernel.verify(
                commandActor,
                actionRequestId,
                body.expectedActionStateVersion,
                body.expectedCaseStateVersion,
            )
            events.publish(commandActor.tenantId, result.request.caseId, "verification.completed", mapOf(
                "verificationId" to result.verification.id,
                "outcome" to result.verification.outcome.name,
            ))
            if (result.memoryCandidate != null || result.request.state.name in setOf(
                    "COMPENSATED",
                    "MANUAL_INTERVENTION",
                )
            ) {
                events.publish(commandActor.tenantId, result.request.caseId, "run.completed", mapOf("state" to result.request.state.name))
            } else if (result.request.state.name == "VERIFIED_SUCCEEDED") {
                events.publish(commandActor.tenantId, result.request.caseId, "observation.requested", mapOf(
                    "state" to "AWAITING_RECOUNT_RESULT",
                ))
            }
            result
        }
    }

    @GetMapping("/verifications/{actionRequestId}")
    fun getVerification(
        request: HttpServletRequest,
        @PathVariable actionRequestId: String,
        @RequestParam(defaultValue = "ACTION") purpose: VerificationPurpose,
    ) =
        kernel.getVerification(actor(request), actionRequestId, purpose)
            ?: throw ResourceNotFoundException("verification", actionRequestId)

    @GetMapping("/memory-candidates")
    fun memoryCandidates(request: HttpServletRequest): List<MemoryAsset> =
        kernel.listMemoryCandidates(actor(request))

    @PostMapping("/memory-candidates/{memoryId}/review")
    fun reviewMemory(
        request: HttpServletRequest,
        @PathVariable memoryId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody body: ReviewMemoryBody,
    ): MemoryAsset {
        val commandActor = actor(request)
        return idempotency.execute(
            commandActor.tenantId,
            idempotencyKey,
            commandFingerprint(
                "review-memory",
                actorAuthorizationContext(commandActor),
                memoryId,
                body.decision,
                body.expectedStateVersion,
            ),
            revalidate = { cached -> kernel.getMemory(commandActor, cached.id) },
        ) {
            kernel.reviewMemory(commandActor, memoryId, body.decision, body.expectedStateVersion)
        }
    }

    @GetMapping("/domain-packs")
    fun domainPacks(request: HttpServletRequest): List<DomainPackView> = listOf(DomainPackView(
        id = "order-fulfillment",
        version = "1.0.0",
        locale = "zh-CN",
        status = "ACTIVE",
        publicActionIds = kernel.listActionDefinitions(actor(request)).map { it.id },
        internalCompensationIds = listOf(FulfillmentActions.RELEASE_RESERVATION_COMPENSATION),
    ))

    @GetMapping("/audit/{aggregateId}")
    fun audit(request: HttpServletRequest, @PathVariable aggregateId: String) = try {
        kernel.caseTimeline(actor(request), aggregateId)
    } catch (_: io.smartdata.foundation.service.ResourceNotFoundException) {
        kernel.auditTrail(actor(request), aggregateId)
    }

    @GetMapping("/cases/{caseId}/timeline")
    fun caseTimeline(request: HttpServletRequest, @PathVariable caseId: String) =
        kernel.caseTimeline(actor(request), caseId)

    @GetMapping("/evaluations/health")
    fun evaluationHealth(request: HttpServletRequest): EvaluationHealth {
        // Reuse the platform-observer authorization boundary even though this
        // response contains only aggregate, non-sensitive metrics.
        kernel.listActionDefinitions(actor(request))
        return EvaluationHealth(
        readiness = "READY",
        mode = "DETERMINISTIC_SIMULATION",
        datasetVersion = "fulfillment-simulator-500@1.0.0",
        scenarioTemplates = 32,
        deterministicCases = 500,
        enforcedInvariants = listOf(
            "TENANT_CONTEXT_SERVER_DERIVED",
            "HUMAN_APPROVAL_REQUIRED",
            "MAKER_CHECKER_SEPARATION",
            "IDEMPOTENT_SOURCE_EFFECT",
            "AMBIGUOUS_TIMEOUT_NO_BLIND_RETRY",
            "MEMORY_REQUIRES_HUMAN_PUBLICATION",
        ),
        successAuthority = "INDEPENDENT_CONNECTOR_READBACK",
        acceptsAgentSelfReportedSuccess = false,
        generatedAt = clock.instant(),
        )
    }

    @GetMapping("/runs/{runId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun runEvents(
        request: HttpServletRequest,
        @PathVariable runId: String,
    ): SseEmitter {
        val commandActor = actor(request)
        kernel.getCase(commandActor, runId)
        return events.subscribe(commandActor.tenantId, runId)
    }

    private fun actor(request: HttpServletRequest): Actor = actors.resolve(request)

    private fun actorAuthorizationContext(actor: Actor): Map<String, Any> = mapOf(
        "actorId" to actor.id,
        "roles" to actor.roles.map { it.name }.sorted(),
    )
}

@RestController
@RequestMapping("/api/v1/local")
@Profile("local")
class LocalActorController(
    private val actors: LocalActorContextResolver,
) {
    @GetMapping("/actors")
    fun listActors(): List<Actor> = actors.listActors()
}

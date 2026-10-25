package io.smartdata.foundation.runtime.api

import io.smartdata.foundation.domain.CasePriority
import io.smartdata.foundation.domain.CaseStatus
import io.smartdata.foundation.domain.ExceptionCase
import io.smartdata.foundation.service.MemoryReviewDecision
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class CaseSummary(
    val id: String,
    val orderId: String,
    val titleZh: String,
    val priority: CasePriority,
    val status: CaseStatus,
    val scenarioKinds: Set<String>,
    val gapCount: Int,
    val stateVersion: Long,
    val updatedAt: Instant,
) {
    companion object {
        fun from(case: ExceptionCase) = CaseSummary(
            case.id, case.orderId, case.titleZh, case.priority, case.status, case.scenarioKinds,
            case.knowledgeGaps.size, case.stateVersion, case.updatedAt,
        )
    }
}

data class CasePage(
    val items: List<CaseSummary>,
    val total: Int,
    val offset: Int,
    val limit: Int,
)

data class CreateActionBody(
    @field:NotBlank
    val recommendationId: String,
    @field:Min(0)
    val expectedStateVersion: Long,
)

data class VersionBody(
    @field:Min(0)
    val expectedStateVersion: Long,
)

data class VerificationBody(
    @field:Min(0)
    val expectedActionStateVersion: Long,
    @field:Min(0)
    val expectedCaseStateVersion: Long,
)

data class ReviewMemoryBody(
    val decision: MemoryReviewDecision,
    @field:Min(0)
    val expectedStateVersion: Long,
)

data class DomainPackView(
    val id: String,
    val version: String,
    val locale: String,
    val status: String,
    val publicActionIds: List<String>,
    val internalCompensationIds: List<String>,
)

data class RunEvent(
    val id: String,
    val tenantId: String,
    val runId: String,
    val type: String,
    val occurredAt: Instant,
    val data: Map<String, String>,
)

data class ApiViolation(
    val field: String,
    val message: String,
)

data class EvaluationHealth(
    val readiness: String,
    val mode: String,
    val datasetVersion: String,
    val scenarioTemplates: Int,
    val deterministicCases: Int,
    val enforcedInvariants: List<String>,
    val successAuthority: String,
    val acceptsAgentSelfReportedSuccess: Boolean,
    val generatedAt: Instant,
)

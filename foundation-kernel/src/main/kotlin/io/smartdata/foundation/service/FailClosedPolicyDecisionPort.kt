package io.smartdata.foundation.service

import io.smartdata.foundation.domain.ActionDefinition
import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.ExceptionCase
import io.smartdata.foundation.port.PolicyDecision
import io.smartdata.foundation.port.PolicyDecisionPort

/** Explicit production-safe placeholder: no action can pass until an OPA adapter replaces it. */
class FailClosedPolicyDecisionPort : PolicyDecisionPort {
    override fun evaluate(
        actor: Actor,
        case: ExceptionCase,
        definition: ActionDefinition,
        input: Map<String, Any>,
    ): PolicyDecision = throw PolicyUnavailableException()
}

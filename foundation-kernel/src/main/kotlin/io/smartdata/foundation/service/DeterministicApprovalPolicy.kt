package io.smartdata.foundation.service

import io.smartdata.foundation.domain.ActionDefinition
import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.ExceptionCase
import io.smartdata.foundation.port.PolicyDecision
import io.smartdata.foundation.port.PolicyDecisionPort

/** Local/test policy adapter. Production must replace this port with the fail-closed OPA adapter. */
class DeterministicApprovalPolicy : PolicyDecisionPort {
    override fun evaluate(
        actor: Actor,
        case: ExceptionCase,
        definition: ActionDefinition,
        input: Map<String, Any>,
    ): PolicyDecision {
        val knownAction = definition.id in FulfillmentActions.definitions
        val allowed = knownAction && definition.approvalRequired && actor.roles.any(definition.requestRoles::contains)
        return PolicyDecision(
            allowed = allowed,
            policyVersion = case.policyVersion,
            reasonZh = if (allowed) "本地确定性策略要求人工审批" else "动作未列入策略或申请角色无权操作",
        )
    }
}

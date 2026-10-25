package io.smartdata.foundation.service

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.domain.DataClassification
import io.smartdata.foundation.port.DataAccessDecisionPort

/**
 * Deliberately small ACL evaluator for the local simulator. Production must
 * replace it with the OpenFGA-backed decision port; unknown ACL forms deny.
 */
class DeterministicDataAccessPolicy : DataAccessDecisionPort {
    override fun canRead(
        actor: Actor,
        resourceTenantId: String,
        aclRef: String,
        classification: DataClassification,
    ): Boolean {
        if (actor.tenantId != resourceTenantId) return false
        val knownAcl = aclRef == "tenant" || aclRef.startsWith("tenant:") ||
            aclRef.startsWith("actor:") || aclRef.startsWith("role:")
        if (!knownAcl) return false
        if (BusinessRole.ADMIN in actor.roles) return true
        val explicitlyAllowed = when {
            aclRef == "tenant" || aclRef == "tenant:${actor.tenantId}" -> true
            aclRef == "actor:${actor.id}" -> true
            aclRef.startsWith("role:") -> actor.roles.any {
                aclRef.removePrefix("role:").equals(it.name, ignoreCase = true)
            }
            else -> false
        }
        if (!explicitlyAllowed) return false
        return classification != DataClassification.RESTRICTED ||
            aclRef.startsWith("actor:") || aclRef.startsWith("role:")
    }
}

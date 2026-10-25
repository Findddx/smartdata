package io.smartdata.foundation.service

open class KernelException(message: String) : RuntimeException(message)

class ResourceNotFoundException(resource: String, id: String) :
    KernelException("$resource '$id' was not found")

class AuthorizationDeniedException(message: String) : KernelException(message)

class TenantBoundaryException : KernelException("Cross-tenant access is forbidden")

class OptimisticStateException(
    resource: String,
    id: String,
    expected: Long,
    actual: Long?,
) : KernelException("$resource '$id' version conflict: expected=$expected, actual=$actual")

class IdempotencyConflictException :
    KernelException("The idempotency key was already used for a different request")

class BusinessValidationException(message: String) : KernelException(message)

class PolicyUnavailableException(message: String = "Policy decision service is unavailable") : KernelException(message)

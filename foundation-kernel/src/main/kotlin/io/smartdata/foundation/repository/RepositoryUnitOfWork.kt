package io.smartdata.foundation.repository

/**
 * Atomic database work only. Never put model, connector, policy-service or
 * message-broker network calls inside either block.
 */
interface RepositoryUnitOfWork {
    fun <T> read(tenantId: String, block: () -> T): T
    fun <T> write(tenantId: String, block: () -> T): T
}

/** Local/in-memory compatibility, deliberately not a durable transaction. */
object DirectRepositoryUnitOfWork : RepositoryUnitOfWork {
    override fun <T> read(tenantId: String, block: () -> T): T = block()
    override fun <T> write(tenantId: String, block: () -> T): T = block()
}

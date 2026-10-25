package io.smartdata.foundation.runtime.api

import io.smartdata.foundation.service.IdempotencyConflictException
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ConcurrentHashMap
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * HTTP command replay boundary. Production must replace this local-only gate
 * with a durable implementation in the same transaction as its command log.
 */
interface CommandIdempotencyGate {
    fun <T : Any> execute(
        tenantId: String,
        key: String,
        fingerprint: String,
        revalidate: (T) -> Unit = {},
        operation: () -> T,
    ): T
}

@Component
@Profile("local")
class InMemoryCommandIdempotencyGate : CommandIdempotencyGate {
    private data class CommandSlot(val fingerprint: String, val result: CompletableFuture<Any>)

    private val commands = ConcurrentHashMap<String, CommandSlot>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> execute(
        tenantId: String,
        key: String,
        fingerprint: String,
        revalidate: (T) -> Unit,
        operation: () -> T,
    ): T {
        require(key.length in 8..128) { "Idempotency-Key 长度必须为 8 到 128 个字符" }
        val scopedKey = "$tenantId::$key"
        val candidate = CommandSlot(fingerprint, CompletableFuture())
        val prior = commands.putIfAbsent(scopedKey, candidate)
        if (prior != null) {
            if (prior.fingerprint != fingerprint) throw IdempotencyConflictException()
            try {
                return (prior.result.get() as T).also(revalidate)
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
        }
        return try {
            operation().also { candidate.result.complete(it) }
        } catch (failure: Throwable) {
            candidate.result.completeExceptionally(failure)
            commands.remove(scopedKey, candidate)
            throw failure
        }
    }
}

fun commandFingerprint(vararg values: Any?): String {
    val canonical = values.joinToString(separator = "\u001f") { it?.toString() ?: "<null>" }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

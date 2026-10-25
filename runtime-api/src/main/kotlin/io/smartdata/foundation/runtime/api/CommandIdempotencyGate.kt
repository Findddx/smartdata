package io.smartdata.foundation.runtime.api

import io.smartdata.foundation.service.IdempotencyConflictException
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ConcurrentHashMap
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

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
    private data class CommandKey(val tenantId: String, val key: String)
    private data class CommandSlot(val fingerprint: String, val result: CompletableFuture<Any>)

    private val commands = ConcurrentHashMap<CommandKey, CommandSlot>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> execute(
        tenantId: String,
        key: String,
        fingerprint: String,
        revalidate: (T) -> Unit,
        operation: () -> T,
    ): T {
        require(key.length in 8..128) { "Idempotency-Key 长度必须为 8 到 128 个字符" }
        val scopedKey = CommandKey(tenantId, key)
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

private val fingerprintMapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    .build()

/**
 * Hash the structured command, never a DTO's toString or delimiter-joined fields.
 * The version prefix deliberately rejects old ambiguous hashes on replay: the
 * local workspace log cannot reconstruct their original request bodies safely.
 */
fun commandFingerprint(vararg values: Any?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("smartdata.http-command.v2\u0000".toByteArray(Charsets.UTF_8))
    return "v2:" + digest.digest(fingerprintMapper.writeValueAsBytes(values.toList()))
        .joinToString("") { "%02x".format(it) }
}

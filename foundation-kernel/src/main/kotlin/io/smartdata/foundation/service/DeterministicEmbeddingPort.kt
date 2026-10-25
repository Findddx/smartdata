package io.smartdata.foundation.service

import io.smartdata.foundation.port.EmbeddingPort
import java.security.MessageDigest

/** Deterministic local adapter for tests; production adapters remain behind EmbeddingPort. */
class DeterministicEmbeddingPort : EmbeddingPort {
    override val embeddingVersion: String = "deterministic-sha256-8d-v1"

    override fun embed(tenantId: String, texts: List<String>): List<FloatArray> = texts.map { text ->
        val digest = MessageDigest.getInstance("SHA-256").digest("$tenantId|$text".toByteArray())
        FloatArray(8) { index ->
            val value = ((digest[index * 4].toInt() and 0xff) shl 24) or
                ((digest[index * 4 + 1].toInt() and 0xff) shl 16) or
                ((digest[index * 4 + 2].toInt() and 0xff) shl 8) or
                (digest[index * 4 + 3].toInt() and 0xff)
            value.toFloat() / Int.MAX_VALUE.toFloat()
        }
    }
}

package io.smartdata.foundation.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeterministicEmbeddingPortTest {
    @Test
    fun `embeddings are deterministic and tenant scoped`() {
        val port = DeterministicEmbeddingPort()
        val first = port.embed("tenant-a", listOf("相同文本")).single()
        val repeated = port.embed("tenant-a", listOf("相同文本")).single()
        val otherTenant = port.embed("tenant-b", listOf("相同文本")).single()
        assertThat(first).containsExactly(*repeated)
        assertThat(first.toList()).isNotEqualTo(otherTenant.toList())
    }
}

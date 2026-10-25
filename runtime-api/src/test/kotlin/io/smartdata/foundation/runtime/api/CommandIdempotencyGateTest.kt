package io.smartdata.foundation.runtime.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CommandIdempotencyGateTest {
    @Test
    fun `tenant and key tuple cannot alias delimiter-containing names`() {
        val gate = InMemoryCommandIdempotencyGate()
        assertThat(gate.execute("tenant-a::segment", "replay-key-0001", "fp") { "first" }).isEqualTo("first")
        assertThat(gate.execute("tenant-a", "segment::replay-key-0001", "fp") { "second" }).isEqualTo("second")
    }

    @Test
    fun `fingerprint distinguishes delimiter redistribution null text and value types`() {
        assertThat(commandFingerprint("a\u001fb", "c")).isNotEqualTo(commandFingerprint("a", "b\u001fc"))
        assertThat(commandFingerprint(null)).isNotEqualTo(commandFingerprint("<null>"))
        assertThat(commandFingerprint(12)).isNotEqualTo(commandFingerprint("12"))
        assertThat(commandFingerprint(mapOf("amount" to 12))).isNotEqualTo(commandFingerprint("{amount=12}"))
        assertThat(commandFingerprint(listOf("a", "b"))).isNotEqualTo(commandFingerprint("[a, b]"))
    }

    @Test
    fun `fingerprint sorts nested maps but preserves list order`() {
        val first = linkedMapOf("b" to linkedMapOf("y" to 2, "x" to 1), "a" to listOf("one", "two"))
        val reordered = linkedMapOf("a" to listOf("one", "two"), "b" to linkedMapOf("x" to 1, "y" to 2))
        assertThat(commandFingerprint(first)).startsWith("v2:").isEqualTo(commandFingerprint(reordered))
        assertThat(commandFingerprint(listOf("one", "two"))).isNotEqualTo(commandFingerprint(listOf("two", "one")))
    }

    @Test
    fun `different tenant keys do not share an operation lock`() {
        val gate = InMemoryCommandIdempotencyGate()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val first = pool.submit<String> {
                gate.execute("tenant-a", "parallel-key-a", "fp-a") {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    "a"
                }
            }
            val second = pool.submit<String> {
                gate.execute("tenant-b", "parallel-key-b", "fp-b") {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    "b"
                }
            }
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
            release.countDown()
            assertThat(first.get()).isEqualTo("a")
            assertThat(second.get()).isEqualTo("b")
        } finally {
            release.countDown()
            pool.close()
        }
    }

    @Test
    fun `same key is single flight and every cached replay is reauthorized`() {
        val gate = InMemoryCommandIdempotencyGate()
        val calls = AtomicInteger()
        assertThat(gate.execute("tenant-a", "replay-key-0001", "fp", operation = {
            calls.incrementAndGet()
            "sensitive-result"
        })).isEqualTo("sensitive-result")

        assertThatThrownBy {
            gate.execute<String>(
                "tenant-a",
                "replay-key-0001",
                "fp",
                revalidate = { throw SecurityException("access revoked") },
                operation = { "must-not-run" },
            )
        }.isInstanceOf(SecurityException::class.java)
        assertThat(calls.get()).isEqualTo(1)
    }
}

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

package io.smartdata.foundation.runtime.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RuntimeModeSafetyGateTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(RuntimeModeSafetyGate::class.java)

    @Test fun `default configuration rejects accidental production startup`() {
        runner.run { assertThat(it).hasFailed() }
    }

    @Test fun `local demo remains explicit and isolated`() {
        runner.withInitializer { it.environment.setActiveProfiles("local") }.run { assertThat(it).hasNotFailed() }
        listOf(arrayOf("local", "enterprise"), arrayOf("local", "production")).forEach { profiles ->
            runner.withInitializer { it.environment.setActiveProfiles(*profiles) }.run { assertThat(it).hasFailed() }
        }
    }

    @Test fun `enterprise requires postgres and enabled storage together`() {
        listOf(arrayOf("enterprise"), arrayOf("postgres"), arrayOf("enterprise", "postgres")).forEach { profiles ->
            runner.withInitializer { it.environment.setActiveProfiles(*profiles) }.run { assertThat(it).hasFailed() }
        }
        runner.withInitializer { it.environment.setActiveProfiles("enterprise", "postgres") }
            .withPropertyValues("smartdata.persistence.enabled=true").run { assertThat(it).hasNotFailed() }
    }
}

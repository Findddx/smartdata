package io.smartdata.foundation.runtime.postgres

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class PostgresProfileSafetyGateTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(PostgresProfileSafetyGate::class.java, PostgresStorageConfiguration::class.java)

    @Test
    fun `local and postgres profiles fail even when storage flag is false`() {
        runner.withPropertyValues("smartdata.persistence.enabled=false").withInitializer { it.environment.setActiveProfiles("local", "postgres") }.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `local and postgres profiles fail when storage flag is absent`() {
        runner.withInitializer { it.environment.setActiveProfiles("local", "postgres") }.run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `disabled postgres capability does not manufacture a production kernel`() {
        runner.withPropertyValues("smartdata.persistence.enabled=false").withInitializer { it.environment.setActiveProfiles("postgres") }.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(PostgresRepositories::class.java)
            assertThat(context).doesNotHaveBean(io.smartdata.foundation.service.FoundationKernel::class.java)
        }
    }
}

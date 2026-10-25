package io.smartdata.foundation.runtime.workspace

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.nio.file.Path
import java.time.Clock

enum class WorkspaceReasoningMode { RULES, SPRING_AI_EVIDENCE_V1 }
enum class ModelProviderProfile { PRIVATE_COMPATIBLE, VOLCENGINE, BAILIAN }

/** Server-only settings. There is intentionally no HTTP endpoint to change these. */
@ConfigurationProperties("smartdata.workspace.reasoning")
data class WorkspaceModelProperties(
    val mode: WorkspaceReasoningMode = WorkspaceReasoningMode.RULES,
    val profile: ModelProviderProfile = ModelProviderProfile.PRIVATE_COMPATIBLE,
    val endpoint: String = "",
    val modelId: String = "",
    // This is the operator's frozen deployment/version label, not a claim the provider proved its weights.
    val modelVersion: String = "",
    val credentialEnvRef: String = "",
    val exportApproved: Boolean = false,
    val allowedTenants: Set<String> = emptySet(),
    val allowedSourceIds: Set<String> = emptySet(),
    val timeoutSeconds: Long = 20,
    val maxInputBytes: Int = 16384,
    val maxOutputTokens: Int = 1024,
    val maxCallsPerDay: Int = 100,
    val maxReservedTokensPerDay: Long = 200000,
)

@Configuration
@Profile("local")
@EnableConfigurationProperties(WorkspaceModelProperties::class)
class WorkspaceModelConfiguration {
    @Bean
    fun assistantReasoningPort(
        settings: WorkspaceModelProperties,
        clock: Clock,
        @org.springframework.beans.factory.annotation.Value("\${smartdata.workspace.storage-dir:.local/workspace}") storageDirectory: String,
    ): AssistantReasoningPort = when (settings.mode) {
        WorkspaceReasoningMode.RULES -> DeterministicAssistantReasoningPort()
        WorkspaceReasoningMode.SPRING_AI_EVIDENCE_V1 -> SpringAiEvidenceReasoningPort(settings,
            LocalModelBudget(Path.of(storageDirectory).toAbsolutePath().normalize().resolve("model-budget"), clock), clock)
    }
}

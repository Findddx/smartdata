package io.smartdata.foundation.runtime.workspace

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.runtime.api.CommandIdempotencyGate
import io.smartdata.foundation.runtime.api.commandFingerprint
import io.smartdata.foundation.runtime.security.ActorContextResolver
import io.smartdata.foundation.runtime.workspace.lineage.SaveLineageMappingsCommand
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path
import java.time.Clock

/** Production has no file-store fallback; enabling it requires explicit local profile selection. */
@Configuration
@Profile("local")
class LocalWorkspaceConfiguration {
    @Bean
    fun workspaceService(
        clock: Clock,
        reasoning: AssistantReasoningPort,
        @Value("\${smartdata.workspace.storage-dir:.local/workspace}") storageDirectory: String,
        @Value("\${smartdata.workspace.http-allowed-hosts:}") httpHosts: String,
        @Value("\${smartdata.workspace.postgres-allowed-hosts:}") postgresHosts: String,
        @Value("\${smartdata.workspace.credential-bindings:}") credentialBindings: String,
    ): WorkspaceService {
        val http = parseHosts(httpHosts)
        val postgres = parseHosts(postgresHosts)
        val bindings = credentialBindings.split(',').map(String::trim).filter(String::isNotEmpty).map { value ->
            val parts = value.split('|')
            require(parts.size == 5 && parts[0].isNotBlank()) {
                "凭据绑定格式必须为 tenant|kind|canonicalEndpoint|schema|ENV_REF；HTTP 的 schema 填 -"
            }
            val kind = SourceKind.valueOf(parts[1])
            require(kind in setOf(SourceKind.HTTP_JSON, SourceKind.JDBC_POSTGRES_READONLY)) { "凭据只能绑定远程只读连接器" }
            validateEnvRef(parts[4])
            require((kind == SourceKind.HTTP_JSON && parts[3] == "-") ||
                (kind == SourceKind.JDBC_POSTGRES_READONLY && parts[3].matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,62}")))) {
                "凭据绑定的数据范围无效"
            }
            CredentialBinding(parts[0], kind, canonicalCredentialEndpoint(parts[2], kind), parts[3], parts[4])
        }.toSet()
        return WorkspaceService(LocalWorkspaceStore(Path.of(storageDirectory).toAbsolutePath().normalize()),
            listOf(JsonSnapshotSource(), HttpJsonSource(http, bindings), PostgresReadOnlySource(postgres, bindings)), clock,
            WorkspaceCapabilities(httpAllowedHosts = http.sorted(), postgresAllowedHosts = postgres.sorted()), reasoning)
    }

    private fun parseHosts(value: String) = value.split(',').map { it.trim().lowercase() }.filter(String::isNotBlank).toSet().also { hosts ->
        require(hosts.all { it.matches(Regex("[a-z0-9][a-z0-9.-]{0,252}")) }) { "服务端连接器主机名单只接受精确 DNS 名称或 IPv4 地址，不支持通配符" }
    }
}

@RestController
@Validated
@Profile("local | enterprise")
@RequestMapping("/api/v1/workspace")
class WorkspaceController(
    private val service: WorkspaceService,
    private val actors: ActorContextResolver,
    private val idempotency: CommandIdempotencyGate,
) {
    @GetMapping
    fun view(request: HttpServletRequest) = service.view(actors.resolve(request))

    @GetMapping("/report")
    fun report(request: HttpServletRequest) = service.report(actors.resolve(request))

    @GetMapping("/business-brief/history")
    fun businessBriefHistory(request: HttpServletRequest) = service.businessBriefHistory(actors.resolve(request))

    @GetMapping("/lineage")
    fun lineage(request: HttpServletRequest) = service.lineage(actors.resolve(request))

    @GetMapping("/lineage/sources/{sourceId}/assets/{assetId}/fields")
    fun lineageFields(request: HttpServletRequest, @PathVariable sourceId: String, @PathVariable assetId: String,
        @RequestParam(defaultValue = "0") offset: Int, @RequestParam(defaultValue = "128") limit: Int) =
        service.lineageFields(actors.resolve(request), sourceId, assetId, offset, limit)

    @GetMapping("/lineage/mappings")
    fun lineageMappings(request: HttpServletRequest) = service.lineageMappings(actors.resolve(request))

    @PostMapping("/lineage/mappings")
    fun saveLineageMappings(request: HttpServletRequest,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @RequestBody body: SaveLineageMappingsCommand) =
        command(request, key, "lineage-mappings.save", body) { actor -> service.saveLineageMappings(actor, key, body) }

    @PostMapping("/business-brief")
    fun businessBrief(request: HttpServletRequest,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @RequestBody body: SaveBusinessBriefCommand) =
        command(request, key, "business-brief.save", body) { actor -> service.saveBusinessBrief(actor, key, body) }

    @PostMapping("/business-brief/metrics/{metricId}/confirm")
    fun confirmMetric(request: HttpServletRequest, @PathVariable metricId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @RequestBody body: ConfirmMetricDefinitionCommand) =
        command(request, key, "business-brief.metric.confirm:$metricId", body) { actor -> service.confirmMetricDefinition(actor, metricId, key, body) }

    @PostMapping("/sources")
    fun source(request: HttpServletRequest, @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @RequestBody body: CreateSourceCommand) = command(request, key, "source.create", body) { actor -> service.createSource(actor, key, body) }

    @PostMapping("/sources/{sourceId}/scan")
    fun scan(request: HttpServletRequest, @PathVariable sourceId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String, @RequestBody body: VersionCommand) =
        command(request, key, "source.scan:$sourceId", body) { actor -> service.scan(actor, sourceId, key, body) }

    @PostMapping("/sources/{sourceId}/snapshot")
    fun replaceSnapshot(request: HttpServletRequest, @PathVariable sourceId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String, @RequestBody body: ReplaceSnapshotCommand) =
        command(request, key, "source.snapshot:$sourceId", body) { actor -> service.replaceSnapshot(actor, sourceId, key, body) }

    @PostMapping("/sources/{sourceId}/disconnect")
    fun disconnect(request: HttpServletRequest, @PathVariable sourceId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String, @RequestBody body: VersionCommand) =
        command(request, key, "source.disconnect:$sourceId", body) { actor -> service.disconnectSource(actor, sourceId, key, body) }

    @PostMapping("/demo/bootstrap")
    fun demo(request: HttpServletRequest, @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String, @RequestBody body: VersionCommand) =
        command(request, key, "demo.bootstrap", body) { actor -> service.bootstrapDemo(actor, key, body) }

    @PostMapping("/demo/reset")
    fun resetDemo(request: HttpServletRequest, @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String, @RequestBody body: VersionCommand) =
        command(request, key, "demo.reset", body) { actor -> service.resetDemo(actor, key, body) }

    @PostMapping("/team/provision")
    fun team(request: HttpServletRequest, @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String, @RequestBody body: VersionCommand) =
        command(request, key, "team.provision", body) { actor -> service.provisionTeam(actor, key, body) }

    @PostMapping("/tasks/{taskId}/transition")
    fun task(request: HttpServletRequest, @PathVariable taskId: String, @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @RequestBody body: TaskTransitionCommand) = command(request, key, "task.transition:$taskId", body) { actor -> service.transitionTask(actor, taskId, key, body) }

    @PostMapping("/tasks/{taskId}/comments")
    fun comment(request: HttpServletRequest, @PathVariable taskId: String, @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @RequestBody body: TaskCommentCommand) = command(request, key, "task.comment:$taskId", body) { actor -> service.addComment(actor, taskId, key, body) }

    @PostMapping("/assistants/{assistantId}/messages")
    fun message(request: HttpServletRequest, @PathVariable assistantId: String, @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @RequestBody body: AssistantMessageCommand) = command(request, key, "assistant.message:$assistantId", body) { actor -> service.message(actor, assistantId, key, body) }

    private fun command(request: HttpServletRequest, key: String, operation: String, body: Any, run: (Actor) -> WorkspaceView): WorkspaceView {
        val actor = actors.resolve(request)
        return idempotency.execute(actor.tenantId, key,
            commandFingerprint("workspace", operation, actor.id, actor.roles.map { it.name }.sorted(), body),
            revalidate = { service.view(actor) },
        ) { run(actor) }
    }
}

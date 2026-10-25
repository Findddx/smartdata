package io.smartdata.foundation.compiler

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class DomainPackCompiler {
    private val jsonMapper: ObjectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()
    private val yamlMapper: ObjectMapper = YAMLMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()
    private val validator = DomainPackValidator()
    private val sourceSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(requireNotNull(javaClass.getResourceAsStream("/domain-pack.schema.json")))

    fun compile(sourceFile: Path, outputDirectory: Path, previousIrFile: Path? = null): CompilationResult {
        require(Files.isRegularFile(sourceFile)) { "Domain pack does not exist: $sourceFile" }
        require(Files.size(sourceFile) <= MAX_SOURCE_BYTES) { "Domain pack exceeds ${MAX_SOURCE_BYTES} bytes" }

        val sourceBytes = Files.readAllBytes(sourceFile)
        val sourceSha256 = sha256(sourceBytes)
        val schemaProblems = sourceSchema.validate(sourceBytes.toString(StandardCharsets.UTF_8), InputFormat.YAML)
        if (schemaProblems.isNotEmpty()) {
            throw DomainPackValidationException(schemaProblems.map { "${it.instanceLocation}: ${it.message}" }.sorted())
        }
        val source = yamlMapper.readValue(sourceBytes, DomainPackSource::class.java)
        validator.validate(source)

        val previous: DomainPackIr? = previousIrFile
            ?.takeIf(Files::isRegularFile)
            ?.let { jsonMapper.readValue(it.toFile(), DomainPackIr::class.java) }
        val compatibility = compare(source, sourceSha256, previous)
        if (previous != null) {
            if (compareVersions(source.version, previous.packVersion) < 0) {
                throw DomainPackValidationException(listOf("domain pack version cannot move backwards"))
            }
            if (source.version == previous.packVersion && sourceSha256 != previous.sourceSha256) {
                throw DomainPackValidationException(listOf("the same domain pack version cannot identify different content"))
            }
            if (!compatibility.compatible) {
                if (major(source.version) <= major(previous.packVersion)) {
                    throw DomainPackValidationException(
                        compatibility.breakingChanges + "breaking changes require a new major version",
                    )
                }
                val migration = source.migration
                if (migration == null || migration.fromVersion != previous.packVersion || migration.steps.isEmpty()) {
                    throw DomainPackValidationException(
                        compatibility.breakingChanges + "breaking changes require an explicit migration from ${previous.packVersion}",
                    )
                }
            }
        }

        val ir = DomainPackIr(
            packId = source.id,
            packVersion = source.version,
            name = source.name,
            description = source.description,
            sourceSha256 = sourceSha256,
            objects = source.objects.sortedBy { it.id },
            events = source.events.sortedBy { it.id },
            permissions = source.permissions.sortedBy { it.id },
            actions = source.actions.sortedBy { it.id },
            verifiers = source.verifiers.sortedBy { it.id },
            workflows = source.workflows.sortedBy { it.id },
            views = source.views.sortedBy { it.id },
            migration = source.migration,
        )

        Files.createDirectories(outputDirectory)
        val files = writeArtifacts(ir, compatibility, outputDirectory)
        return CompilationResult(ir, compatibility, files)
    }

    private fun writeArtifacts(ir: DomainPackIr, report: CompatibilityReport, output: Path): List<String> {
        val files = linkedMapOf<String, Any>()
        files["domain-model.json"] = ir
        files["compatibility-report.json"] = report
        files["openapi-components.json"] = openApi(ir)
        files["mcp-tools.json"] = mcpTools(ir)
        files["opa-data.json"] = opaData(ir)
        files["ui-metadata.json"] = uiMetadata(ir)

        files.forEach { (name, value) ->
            val target = output.resolve(name)
            Files.createDirectories(target.parent)
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), value)
        }
        val generatedPath = output.resolve("generated/DomainContracts.kt")
        Files.createDirectories(generatedPath.parent)
        Files.writeString(generatedPath, kotlinContracts(ir), StandardCharsets.UTF_8)
        return files.keys.toList() + "generated/DomainContracts.kt"
    }

    private fun openApi(ir: DomainPackIr): ObjectNode = jsonMapper.createObjectNode().apply {
        put("openapi", "3.1.0")
        set("info", jsonMapper.createObjectNode().put("title", ir.name).put("version", ir.packVersion))
        val schemas = jsonMapper.createObjectNode()
        ir.actions.forEach { action -> action.inputSchema?.let { schemas.set(action.id.toPascalCase() + "Input", it) } }
        set("components", jsonMapper.createObjectNode().set("schemas", schemas))
    }

    private fun mcpTools(ir: DomainPackIr): ObjectNode = jsonMapper.createObjectNode().apply {
        put("packId", ir.packId)
        put("packVersion", ir.packVersion)
        val tools = jsonMapper.createArrayNode()
        ir.actions.filter { it.initiable }.forEach { action ->
            tools.add(
                jsonMapper.createObjectNode()
                    .put("name", "create_action_request__${action.id}")
                    .put("description", "Create an approval-gated request: ${action.description}")
                    .set("inputSchema", action.inputSchema),
            )
        }
        set("tools", tools)
    }

    private fun opaData(ir: DomainPackIr): ObjectNode = jsonMapper.createObjectNode().apply {
        put("packId", ir.packId)
        put("packVersion", ir.packVersion)
        val permissions = jsonMapper.createObjectNode()
        ir.permissions.forEach { permission -> permissions.set(permission.id, jsonMapper.valueToTree(permission.roles)) }
        set("permissions", permissions)
        val actions = jsonMapper.createObjectNode()
        ir.actions.forEach { action ->
            actions.set(
                action.id,
                jsonMapper.createObjectNode()
                    .put("requestPermission", action.permission)
                    .put("approvalPermission", action.approvalPermission)
                    .put("risk", action.risk)
                    .put("approvalRequired", action.approvalRequired)
                    .put("initiable", action.initiable)
                    .put("verifier", action.verifier)
                    .put("compensator", action.compensator)
                    .set("resolvableGapCodes", jsonMapper.valueToTree(action.resolvableGapCodes.sorted())),
            )
        }
        set("actions", actions)
    }

    private fun uiMetadata(ir: DomainPackIr): ObjectNode = jsonMapper.createObjectNode().apply {
        put("packId", ir.packId)
        set("views", jsonMapper.valueToTree(ir.views))
        set("actions", jsonMapper.valueToTree(ir.actions.filter { it.initiable }.map { mapOf("id" to it.id, "label" to it.description, "risk" to it.risk) }))
    }

    private fun kotlinContracts(ir: DomainPackIr): String = buildString {
        appendLine("// Generated by SmartData Domain Compiler. Do not edit.")
        appendLine("package io.smartdata.foundation.generated")
        appendLine()
        appendLine("object DomainPackInfo {")
        appendLine("    const val ID: String = \"${ir.packId}\"")
        appendLine("    const val VERSION: String = \"${ir.packVersion}\"")
        appendLine("}")
        appendLine()
        appendLine("enum class ObjectType(val stableId: String) {")
        appendLine(ir.objects.joinToString(",\n") { "    ${it.id.toEnumName()}(\"${it.id}\")" })
        appendLine("}")
        appendLine()
        appendLine("enum class ActionType(val stableId: String) {")
        appendLine(ir.actions.joinToString(",\n") { "    ${it.id.toEnumName()}(\"${it.id}\")" })
        appendLine("}")
    }

    private fun compare(
        current: DomainPackSource,
        currentSourceSha256: String,
        previous: DomainPackIr?,
    ): CompatibilityReport {
        if (previous == null) return CompatibilityReport(current.version, null, true, emptyList(), listOf("initial release"))
        val breaking = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        fun removed(kind: String, old: Set<String>, now: Set<String>) {
            (old - now).sorted().forEach { breaking += "$kind '$it' was removed" }
        }
        removed("object", previous.objects.map { it.id }.toSet(), current.objects.map { it.id }.toSet())
        removed("event", previous.events.map { it.id }.toSet(), current.events.map { it.id }.toSet())
        removed("action", previous.actions.map { it.id }.toSet(), current.actions.map { it.id }.toSet())
        removed("verifier", previous.verifiers.map { it.id }.toSet(), current.verifiers.map { it.id }.toSet())
        removed("permission", previous.permissions.map { it.id }.toSet(), current.permissions.map { it.id }.toSet())
        val currentObjects = current.objects.associateBy { it.id }
        previous.objects.forEach { old ->
            currentObjects[old.id]?.let { now ->
                if (old.identityKeys != now.identityKeys) breaking += "object '${old.id}' identity keys changed"
                if (old.relationships != now.relationships) breaking += "object '${old.id}' relationships changed"
            }
        }
        val currentEvents = current.events.associateBy { it.id }
        previous.events.forEach { old ->
            currentEvents[old.id]?.let { now ->
                if (old.aggregate != now.aggregate || old.schema != now.schema) breaking += "event '${old.id}' contract changed"
            }
        }
        val currentPermissions = current.permissions.associateBy { it.id }
        previous.permissions.forEach { old ->
            currentPermissions[old.id]?.let { now ->
                if (old.roles.toSet() != now.roles.toSet()) breaking += "permission '${old.id}' role coverage changed"
            }
        }
        val currentActions = current.actions.associateBy { it.id }
        previous.actions.forEach { old ->
            currentActions[old.id]?.let { now ->
                if (old.inputSchema != now.inputSchema) breaking += "action '${old.id}' input schema changed"
                if (old.permission != now.permission) breaking += "action '${old.id}' permission changed"
                if (old.approvalPermission != now.approvalPermission) breaking += "action '${old.id}' approval permission changed"
                if (old.verifier != now.verifier) breaking += "action '${old.id}' verifier changed"
                if (old.target != now.target) breaking += "action '${old.id}' target changed"
                if (old.risk != now.risk) breaking += "action '${old.id}' risk changed"
                if (old.initiable != now.initiable) breaking += "action '${old.id}' initiation exposure changed"
                if (old.approvalRequired != now.approvalRequired) breaking += "action '${old.id}' approval requirement changed"
                if (old.compensator != now.compensator) breaking += "action '${old.id}' compensator changed"
                if (old.timeoutSeconds != now.timeoutSeconds) breaking += "action '${old.id}' timeout changed"
                if (old.resolvableGapCodes != now.resolvableGapCodes) breaking += "action '${old.id}' resolvable gap codes changed"
            }
        }
        val currentVerifiers = current.verifiers.associateBy { it.id }
        previous.verifiers.forEach { old ->
            currentVerifiers[old.id]?.let { now ->
                if (old != now) breaking += "verifier '${old.id}' semantics changed"
            }
        }
        val currentWorkflows = current.workflows.associateBy { it.id }
        previous.workflows.forEach { old ->
            currentWorkflows[old.id]?.let { now -> if (old != now) breaking += "workflow '${old.id}' changed" }
        }
        if (previous.views != current.views.sortedBy { it.id }) warnings += "view metadata changed"
        if (previous.sourceSha256 != currentSourceSha256 && breaking.isEmpty() && warnings.isEmpty()) {
            warnings += "non-contract source content changed"
        }
        return CompatibilityReport(current.version, previous.packVersion, breaking.isEmpty(), breaking.distinct().sorted(), warnings)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun major(version: String): Int = version.substringBefore('.').toInt()

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.substringBefore('-').substringBefore('+').split('.').take(3).map(String::toInt)
        val rightParts = right.substringBefore('-').substringBefore('+').split('.').take(3).map(String::toInt)
        return leftParts.zip(rightParts).firstOrNull { (a, b) -> a != b }
            ?.let { (a, b) -> a.compareTo(b) }
            ?: 0
    }

    private fun String.toPascalCase(): String = split('-', '_', '.').joinToString("") { it.replaceFirstChar(Char::uppercase) }
    private fun String.toEnumName(): String = replace(Regex("[^A-Za-z0-9]+"), "_").uppercase()

    companion object {
        const val MAX_SOURCE_BYTES: Long = 1_048_576
    }
}

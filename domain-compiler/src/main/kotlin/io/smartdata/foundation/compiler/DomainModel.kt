package io.smartdata.foundation.compiler

import tools.jackson.databind.JsonNode

data class DomainPackSource(
    val id: String = "",
    val version: String = "",
    val name: String = "",
    val description: String = "",
    val objects: List<ObjectDefinition> = emptyList(),
    val events: List<EventDefinition> = emptyList(),
    val permissions: List<PermissionDefinition> = emptyList(),
    val actions: List<ActionDefinitionSource> = emptyList(),
    val verifiers: List<VerifierDefinitionSource> = emptyList(),
    val workflows: List<WorkflowDefinition> = emptyList(),
    val views: List<ViewDefinition> = emptyList(),
    val migration: MigrationPlan? = null,
)

data class MigrationPlan(
    val fromVersion: String = "",
    val description: String = "",
    val steps: List<String> = emptyList(),
)

data class ObjectDefinition(
    val id: String = "",
    val description: String = "",
    val identityKeys: List<String> = emptyList(),
    val relationships: List<RelationshipDefinition> = emptyList(),
)

data class RelationshipDefinition(
    val id: String = "",
    val target: String = "",
    val cardinality: String = "one",
)

data class EventDefinition(
    val id: String = "",
    val aggregate: String = "",
    val schema: JsonNode? = null,
)

data class PermissionDefinition(
    val id: String = "",
    val roles: List<String> = emptyList(),
    val description: String = "",
)

data class ActionDefinitionSource(
    val id: String = "",
    val description: String = "",
    val target: String = "",
    val risk: String = "MEDIUM",
    val permission: String = "",
    val approvalPermission: String = "",
    val approvalRequired: Boolean = true,
    val initiable: Boolean = true,
    val inputSchema: JsonNode? = null,
    val compensator: String? = null,
    val verifier: String = "",
    val timeoutSeconds: Long = 30,
    val resolvableGapCodes: Set<String> = emptySet(),
)

data class VerifierDefinitionSource(
    val id: String = "",
    val description: String = "",
    val target: String = "",
    val mode: String = "DETERMINISTIC",
    val observationWindowSeconds: Long = 60,
)

data class WorkflowDefinition(
    val id: String = "",
    val description: String = "",
    val start: String = "",
    val steps: List<WorkflowStep> = emptyList(),
)

data class WorkflowStep(
    val id: String = "",
    val kind: String = "",
    val ref: String? = null,
    val next: String? = null,
)

data class ViewDefinition(
    val id: String = "",
    val objectType: String = "",
    val fields: List<String> = emptyList(),
)

data class DomainPackIr(
    val formatVersion: Int = 1,
    val packId: String,
    val packVersion: String,
    val name: String,
    val description: String,
    val sourceSha256: String,
    val objects: List<ObjectDefinition>,
    val events: List<EventDefinition>,
    val permissions: List<PermissionDefinition>,
    val actions: List<ActionDefinitionSource>,
    val verifiers: List<VerifierDefinitionSource>,
    val workflows: List<WorkflowDefinition>,
    val views: List<ViewDefinition>,
    val migration: MigrationPlan? = null,
)

data class CompatibilityReport(
    val currentVersion: String,
    val previousVersion: String?,
    val compatible: Boolean,
    val breakingChanges: List<String>,
    val warnings: List<String>,
)

data class CompilationResult(
    val ir: DomainPackIr,
    val compatibility: CompatibilityReport,
    val files: List<String>,
)

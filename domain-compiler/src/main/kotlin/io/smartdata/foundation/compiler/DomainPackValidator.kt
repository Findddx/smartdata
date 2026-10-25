package io.smartdata.foundation.compiler

import tools.jackson.databind.JsonNode

class DomainPackValidationException(val problems: List<String>) :
    IllegalArgumentException(problems.joinToString(prefix = "Invalid domain pack:\n - ", separator = "\n - "))

class DomainPackValidator {
    private val idPattern = Regex("^[a-z][a-z0-9._-]{1,127}$")
    private val semVerPattern = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$")
    private val allowedRisk = setOf("LOW", "MEDIUM", "HIGH", "CRITICAL")
    private val allowedCardinality = setOf("one", "optional", "many")
    private val allowedStepKinds = setOf("OBSERVE", "ACTION", "VERIFY", "APPROVAL", "DECISION", "END")

    fun validate(pack: DomainPackSource) {
        val problems = mutableListOf<String>()
        checkId("pack", pack.id, problems)
        if (!semVerPattern.matches(pack.version)) problems += "pack version '${pack.version}' is not SemVer"
        if (pack.name.isBlank()) problems += "pack name is required"
        if (pack.objects.isEmpty()) problems += "at least one object is required"

        checkUnique("object", pack.objects.map { it.id }, problems)
        checkUnique("event", pack.events.map { it.id }, problems)
        checkUnique("permission", pack.permissions.map { it.id }, problems)
        checkUnique("action", pack.actions.map { it.id }, problems)
        checkUnique("verifier", pack.verifiers.map { it.id }, problems)
        checkUnique("workflow", pack.workflows.map { it.id }, problems)
        checkUnique("view", pack.views.map { it.id }, problems)
        checkGeneratedNameCollisions("object", pack.objects.map { it.id }, problems)
        checkGeneratedNameCollisions("action", pack.actions.map { it.id }, problems)

        val objectIds = pack.objects.map { it.id }.toSet()
        val permissionIds = pack.permissions.map { it.id }.toSet()
        val actionIds = pack.actions.map { it.id }.toSet()
        val verifierIds = pack.verifiers.map { it.id }.toSet()

        pack.objects.forEach { definition ->
            checkId("object", definition.id, problems)
            if (definition.identityKeys.isEmpty()) problems += "object '${definition.id}' must declare identityKeys"
            checkUnique("relationship in ${definition.id}", definition.relationships.map { it.id }, problems)
            definition.relationships.forEach { relationship ->
                checkId("relationship", relationship.id, problems)
                if (relationship.target !in objectIds) problems += "relationship '${definition.id}.${relationship.id}' targets unknown object '${relationship.target}'"
                if (relationship.cardinality !in allowedCardinality) problems += "relationship '${definition.id}.${relationship.id}' has unsupported cardinality '${relationship.cardinality}'"
            }
        }

        pack.events.forEach { event ->
            checkId("event", event.id, problems)
            if (event.aggregate !in objectIds) problems += "event '${event.id}' references unknown aggregate '${event.aggregate}'"
            event.schema?.let { validateSchema("event '${event.id}'", it, problems) }
        }

        pack.permissions.forEach { permission ->
            checkId("permission", permission.id, problems)
            if (permission.roles.isEmpty()) problems += "permission '${permission.id}' must allow at least one role"
            permission.roles.forEach { role -> checkId("role", role, problems) }
        }

        pack.verifiers.forEach { verifier ->
            checkId("verifier", verifier.id, problems)
            if (verifier.target !in objectIds) problems += "verifier '${verifier.id}' targets unknown object '${verifier.target}'"
            if (verifier.mode !in setOf("DETERMINISTIC", "RUBRIC", "HUMAN")) problems += "verifier '${verifier.id}' has unsupported mode '${verifier.mode}'"
            if (verifier.observationWindowSeconds <= 0) problems += "verifier '${verifier.id}' observationWindowSeconds must be positive"
        }

        pack.actions.forEach { action ->
            checkId("action", action.id, problems)
            if (action.target !in objectIds) problems += "action '${action.id}' targets unknown object '${action.target}'"
            if (action.permission !in permissionIds) problems += "action '${action.id}' references unknown permission '${action.permission}'"
            if (action.approvalPermission !in permissionIds) problems += "action '${action.id}' references unknown approval permission '${action.approvalPermission}'"
            if (action.verifier !in verifierIds) problems += "action '${action.id}' references unknown verifier '${action.verifier}'"
            if (!action.approvalRequired) problems += "write action '${action.id}' must require approval in v1"
            if (action.risk !in allowedRisk) problems += "action '${action.id}' has unsupported risk '${action.risk}'"
            if (action.timeoutSeconds !in 1..3600) problems += "action '${action.id}' timeoutSeconds must be between 1 and 3600"
            action.compensator?.let { if (it !in actionIds) problems += "action '${action.id}' references unknown compensator '$it'" }
            if (action.inputSchema == null) problems += "action '${action.id}' must declare inputSchema"
            action.inputSchema?.let { validateSchema("action '${action.id}'", it, problems) }
        }
        val actionsById = pack.actions.associateBy { it.id }
        pack.actions.mapNotNull { it.compensator }.distinct().forEach { compensatorId ->
            if (actionsById[compensatorId]?.initiable == true) problems += "compensator '$compensatorId' must set initiable: false"
        }

        pack.views.forEach { view ->
            checkId("view", view.id, problems)
            if (view.objectType !in objectIds) problems += "view '${view.id}' references unknown object '${view.objectType}'"
            if (view.fields.isEmpty()) problems += "view '${view.id}' must include fields"
        }

        pack.workflows.forEach { workflow -> validateWorkflow(workflow, actionIds, verifierIds, problems) }
        pack.migration?.let { migration ->
            if (!semVerPattern.matches(migration.fromVersion)) problems += "migration fromVersion '${migration.fromVersion}' is not SemVer"
            if (migration.description.isBlank()) problems += "migration description is required"
            if (migration.steps.isEmpty() || migration.steps.any(String::isBlank)) problems += "migration must contain non-blank steps"
        }

        if (problems.isNotEmpty()) throw DomainPackValidationException(problems.sorted())
    }

    private fun validateWorkflow(
        workflow: WorkflowDefinition,
        actionIds: Set<String>,
        verifierIds: Set<String>,
        problems: MutableList<String>,
    ) {
        checkId("workflow", workflow.id, problems)
        checkUnique("step in ${workflow.id}", workflow.steps.map { it.id }, problems)
        val stepIds = workflow.steps.map { it.id }.toSet()
        if (workflow.start !in stepIds) problems += "workflow '${workflow.id}' has unknown start '${workflow.start}'"
        workflow.steps.forEach { step ->
            checkId("workflow step", step.id, problems)
            if (step.kind !in allowedStepKinds) problems += "workflow '${workflow.id}' step '${step.id}' has unsupported kind '${step.kind}'"
            if (step.kind == "ACTION" && step.ref !in actionIds) problems += "workflow '${workflow.id}' step '${step.id}' references unknown action '${step.ref}'"
            if (step.kind == "VERIFY" && step.ref !in verifierIds) problems += "workflow '${workflow.id}' step '${step.id}' references unknown verifier '${step.ref}'"
            if (step.next != null && step.next !in stepIds) problems += "workflow '${workflow.id}' step '${step.id}' points to unknown next '${step.next}'"
        }
        detectCycle(workflow, problems)
    }

    private fun detectCycle(workflow: WorkflowDefinition, problems: MutableList<String>) {
        val nextById = workflow.steps.associate { it.id to it.next }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visiting) {
                problems += "workflow '${workflow.id}' contains a declarative cycle at '$id'"
                return
            }
            if (!visited.add(id)) return
            visiting += id
            nextById[id]?.let(::visit)
            visiting -= id
        }
        workflow.steps.forEach { visit(it.id) }
    }

    private fun validateSchema(label: String, schema: JsonNode, problems: MutableList<String>) {
        if (!schema.isObject) {
            problems += "$label schema must be an object"
            return
        }
        if (schema.path("type").asString() != "object") problems += "$label schema root type must be 'object'"
        if (!schema.has("additionalProperties") || schema.path("additionalProperties").booleanValue(true)) {
            problems += "$label schema must set additionalProperties: false"
        }
        walkSchema(schema, label, 0, problems)
    }

    private fun walkSchema(node: JsonNode, label: String, depth: Int, problems: MutableList<String>) {
        if (depth > 32) {
            problems += "$label schema exceeds maximum depth 32"
            return
        }
        if (node.isObject) {
            val ref = node.path("\$ref").takeUnless { it.isMissingNode }?.asString()
            if (ref != null && (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("file:"))) {
                problems += "$label schema contains forbidden external \$ref '$ref'"
            }
            node.properties().forEach { (_, child) -> walkSchema(child, label, depth + 1, problems) }
        } else if (node.isArray) {
            node.forEach { walkSchema(it, label, depth + 1, problems) }
        }
    }

    private fun checkId(kind: String, id: String, problems: MutableList<String>) {
        if (!idPattern.matches(id)) problems += "$kind id '$id' must match ${idPattern.pattern}"
    }

    private fun checkUnique(kind: String, ids: List<String>, problems: MutableList<String>) {
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { problems += "duplicate $kind id '$it'" }
    }

    private fun checkGeneratedNameCollisions(kind: String, ids: List<String>, problems: MutableList<String>) {
        ids.groupBy { it.replace(Regex("[^A-Za-z0-9]+"), "_").uppercase() }
            .filterValues { it.size > 1 }
            .forEach { (generated, sources) ->
                problems += "$kind ids ${sources.sorted()} collide as generated name '$generated'"
            }
    }
}

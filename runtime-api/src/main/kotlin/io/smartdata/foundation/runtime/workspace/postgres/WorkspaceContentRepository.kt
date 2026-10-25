package io.smartdata.foundation.runtime.workspace.postgres

import io.smartdata.foundation.runtime.workspace.*
import io.smartdata.foundation.service.BusinessValidationException
import org.jooq.DSLContext
import org.jooq.Record
import tools.jackson.core.type.TypeReference

/** Each collection is a separate keyed relation; immutable observation revisions are never replaced. */
internal class WorkspaceContentRepository(private val db: DSLContext, private val tenant: String) {
    private fun f(vararg values: Pair<String, Any?>) = linkedMapOf("tenant_id" to tenant, *values)
    private fun save(table: String, values: Map<String, Any?>, keys: List<String> = listOf("tenant_id", "id"), mutable: Set<String> = emptySet()) =
        WorkspaceSql.save(db, table, values, keys, mutable)
    private fun rows(table: String, order: String = "ordinal", limit: Int = 10000): List<Record> {
        val result = db.fetch("select * from $table where tenant_id=? order by $order limit ?", tenant, limit + 1)
        if (result.size > limit) throw BusinessValidationException("企业工作区当前投影超过有界查询上限，需归档或使用分页能力；没有静默截断")
        return result.toList()
    }

    fun load(version: Long): PersistedWorkspace {
        val state = PersistedWorkspace(tenantId = tenant, stateVersion = version)
        db.fetch("select r.*,s.ordinal,p.canonical_json from workspace_source s join workspace_source_revision r on r.tenant_id=s.tenant_id and r.source_id=s.id and r.revision=s.current_revision join workspace_snapshot_payload p on p.tenant_id=r.tenant_id and p.sha256=r.payload_sha256 where s.tenant_id=? order by s.ordinal", tenant).forEach { r ->
            state.sources += SourceRecord(r.text("source_id"),r.text("name"),SourceKind.valueOf(r.text("kind")),Provenance.valueOf(r.text("provenance")),
                r.optional("endpoint"),r.optional("credential_env_ref"),r.optional("username_env_ref"),
                WorkspaceSql.mapper.readValue(r.text("canonical_json"),object: TypeReference<List<Map<String,Any?>>>() {}),
                r.optional("observed_at"),r.optional("primary_key_column"),r.optional("freshness_column"),r.long("freshness_max_hours"),
                r.text("source_schema"),SourceStatus.valueOf(r.text("status")),r.optional("last_scanned_at"),r.long("revision"),r.optional("last_error"),tenant)
        }
        state.scans += rows("workspace_scan",limit=1000).map { r -> ScanView(r.text("id"),r.text("source_id"),Provenance.valueOf(r.text("provenance")),r.text("status"),r.text("started_at"),r.text("completed_at"),r.strings("asset_ids"),r.strings("finding_ids"),r.text("method"),r.optional("error")) }
        val columns = db.fetch("select c.* from workspace_asset_column c join workspace_asset a on a.tenant_id=c.tenant_id and a.id=c.asset_id and a.scan_id=c.scan_id where c.tenant_id=? order by c.asset_id,c.ordinal",tenant).groupBy { it.text("asset_id") }
        db.fetch("select r.*,a.ordinal from workspace_asset a join workspace_asset_revision r on r.tenant_id=a.tenant_id and r.id=a.id and r.scan_id=a.scan_id where a.tenant_id=? order by a.ordinal",tenant).forEach { r ->
            state.assets += AssetView(r.text("id"),r.text("source_id"),r.text("name"),Provenance.valueOf(r.text("provenance")),r.int("row_count"),r.optionalLong("total_rows"),r.int("sample_limit"),r.bool("sampled"),
                columns[r.text("id")].orEmpty().map { c -> ColumnProfile(c.text("name"),c.text("inferred_type"),c.int("missing_count"),c.int("distinct_count")) },r.text("observed_at"),r.text("evidence_sha256").trim(),r.text("sampling"))
        }
        state.findings += rows("workspace_finding").map { r -> FindingView(r.text("id"),r.text("source_id"),r.text("asset_id"),r.text("scan_id"),Provenance.valueOf(r.text("provenance")),r.text("rule_id"),r.text("title"),FindingSeverity.valueOf(r.text("severity")),r.optional("column_name"),r.text("observed"),r.text("expected"),WorkspaceSql.decode(r.text("evidence_json"),FindingEvidence::class.java),r.text("suggestion"),r.bool("active")) }
        state.assistants += rows("workspace_assistant",limit=20).map { r -> AssistantView(r.text("id"),AssistantRole.valueOf(r.text("role")),r.text("name"),r.text("responsibility"),r.text("status"),r.text("method"),r.strings("finding_ids"),r.strings("task_ids"),r.strings("focus_goal_ids"),r.strings("scope_source_ids"),r.text("scope_notice")) }
        db.fetch("select r.*,t.id,t.ordinal from workspace_task t join workspace_task_revision r on r.tenant_id=t.tenant_id and r.task_id=t.id and r.commit_version=t.current_revision where t.tenant_id=? order by t.ordinal",tenant).forEach { r ->
            state.tasks += TaskView(r.text("id"),r.text("assistant_id"),r.text("finding_id"),r.text("title"),r.text("description"),TaskStatus.valueOf(r.text("status")),r.optional("owner_id"),r.text("created_at"),r.text("updated_at"),r.optional("completion_note"),r.strings("evidence_refs"),r.long("state_version"),r.optional("verified_by"),r.strings("goal_ids"),r.bool("in_current_scope"),r.text("scope_reason"),r.optionalLong("business_brief_version"),r.optionalLong("created_under_brief_version"),r.int("priority_rank"),r.text("priority_reason"),r.optional("current_finding_id"))
        }
        state.comments += rows("workspace_comment").map { r -> TaskComment(r.text("id"),r.text("task_id"),r.text("actor_id"),r.text("body"),r.strings("evidence_refs"),r.text("created_at")) }
        state.messages += rows("workspace_message").map { r -> AssistantMessage(r.text("id"),r.text("assistant_id"),r.text("actor_id"),r.text("question"),r.text("answer"),r.strings("evidence_refs"),r.text("created_at"),r.text("method"),r.text("status"),r.strings("uncertainty"),r.optional("error_code"),r.optional("trace_json")?.let { WorkspaceSql.decode(it,AssistantModelTrace::class.java) }) }
        state.audit += rows("workspace_audit").map { r -> WorkspaceAudit(r.text("id"),r.text("actor_id"),r.text("event"),r.text("resource_id"),r.text("occurred_at"),r.long("state_version")) }
        rows("workspace_command","created_at,command_id").forEach { state.commands[it.text("command_id").trim()]=it.text("fingerprint") }
        WorkspaceDefinitionRepository(db,tenant).load(state)
        return state
    }

    fun save(before: PersistedWorkspace, state: PersistedWorkspace) {
        require(state.sources.size<=50 && state.assets.size<=1000 && state.scans.size<=1000 && state.tasks.size<=10000 && state.findings.size<=10000 && state.comments.size<=10000 && state.messages.size<=10000 && state.audit.size<=10000)
        fun retained(old: List<String>, current: List<String>) { val ids=current.toSet();require(ids.size==current.size && ids.containsAll(old)) { "不能删除或重复企业协作历史" } }
        retained(before.sources.map { it.id },state.sources.map { it.id }); retained(before.scans.map { it.id },state.scans.map { it.id })
        retained(before.findings.map { it.id },state.findings.map { it.id }); retained(before.tasks.map { it.id },state.tasks.map { it.id })
        retained(before.comments.map { it.id },state.comments.map { it.id }); retained(before.messages.map { it.id },state.messages.map { it.id }); retained(before.audit.map { it.id },state.audit.map { it.id })
        val previousSources=before.sources.associateBy { it.id }
        val previousTasks=before.tasks.associateBy { it.id }
        val previousScans=before.scans.associateBy { it.id }
        val previousAssets=before.assets.withIndex().associateBy { it.value.id }
        val previousFindings=before.findings.associateBy { it.id }
        val previousAssistants=before.assistants.associateBy { it.id }
        val previousComments=before.comments.associateBy { it.id }
        val previousMessages=before.messages.associateBy { it.id }
        val previousAudit=before.audit.associateBy { it.id }
        state.sources.forEachIndexed { index,s ->
            require(s.tenantId==tenant && s.provenance==Provenance.USER && s.kind==SourceKind.JSON_SNAPSHOT) { "首版企业存储只接受明确属于本租户的用户 JSON 快照" }
            val old=previousSources[s.id]
            if(old!=s) {
                require(s.stateVersion==(old?.stateVersion?:0)+1) { "来源版本必须逐次增加" }
                validateRows(s.snapshotRows)
                val content=WorkspaceSql.encode(s.snapshotRows)
                require(content.toByteArray(Charsets.UTF_8).size<=MAX_SOURCE_BYTES) { "独立快照正文超过1MiB上限" }
                val hash=digest(content)
                save("workspace_snapshot_payload",f("sha256" to hash,"canonical_json" to content),listOf("tenant_id","sha256"))
                save("workspace_source",f("id" to s.id,"current_revision" to s.stateVersion,"ordinal" to index),mutable=setOf("current_revision"))
                save("workspace_source_revision",f("source_id" to s.id,"revision" to s.stateVersion,"name" to s.name,"kind" to s.kind.name,"provenance" to s.provenance.name,
                    "endpoint" to s.endpoint,"credential_env_ref" to s.credentialEnvRef,"username_env_ref" to s.usernameEnvRef,"payload_sha256" to hash,"observed_at" to s.observedAt,
                    "primary_key_column" to s.primaryKey,"freshness_column" to s.freshnessColumn,"freshness_max_hours" to s.freshnessMaxHours,"source_schema" to s.schema,"status" to s.status.name,"last_scanned_at" to s.lastScannedAt,"last_error" to s.lastError),listOf("tenant_id","source_id","revision"))
            }
        }
        state.scans.forEachIndexed { i,s -> if(previousScans[s.id]!=s) save("workspace_scan",f("id" to s.id,"source_id" to s.sourceId,"ordinal" to i,"provenance" to s.provenance.name,"status" to s.status,"started_at" to s.startedAt,"completed_at" to s.completedAt,"asset_ids" to WorkspaceSql.array(s.assetIds),"finding_ids" to WorkspaceSql.array(s.findingIds),"method" to s.method,"error" to s.error)) }
        val currentIds=state.assets.map { it.id }.toSet()
        before.assets.filter { it.id !in currentIds }.forEach { db.execute("delete from workspace_asset where tenant_id=? and id=?",tenant,it.id) }
        val latestAssetScans=mutableMapOf<Pair<String,String>,ScanView>()
        state.scans.forEach { scan -> scan.assetIds.forEach { latestAssetScans[scan.sourceId to it]=scan } }
        val previousAssetScans=mutableMapOf<Pair<String,String>,String>()
        before.scans.forEach { scan -> scan.assetIds.forEach { previousAssetScans[scan.sourceId to it]=scan.id } }
        state.assets.forEachIndexed { i,a ->
            val scan=latestAssetScans[a.sourceId to a.id] ?: throw BusinessValidationException("资产缺少对应扫描版本")
            val previous=previousAssets[a.id]
            val sameObservation=previous?.value==a && previousAssetScans[a.sourceId to a.id]==scan.id
            if(!sameObservation) {
            save("workspace_asset_revision",f("id" to a.id,"scan_id" to scan.id,"source_id" to a.sourceId,"name" to a.name,"provenance" to a.provenance.name,"row_count" to a.rowCount,"total_rows" to a.totalRows,"sample_limit" to a.sampleLimit,"sampled" to a.sampled,"observed_at" to a.observedAt,"evidence_sha256" to a.evidenceSha256,"sampling" to a.sampling,"content_fingerprint" to digest(WorkspaceSql.encode(a))),listOf("tenant_id","id","scan_id"))
            a.columns.forEachIndexed { n,c -> save("workspace_asset_column",f("asset_id" to a.id,"scan_id" to scan.id,"ordinal" to n,"name" to c.name,"inferred_type" to c.inferredType,"missing_count" to c.missingCount,"distinct_count" to c.distinctCount),listOf("tenant_id","asset_id","scan_id","ordinal")) }
            }
            if(!sameObservation || previous?.index!=i) save("workspace_asset",f("id" to a.id,"scan_id" to scan.id,"ordinal" to i),mutable=setOf("scan_id","ordinal"))
        }
        state.findings.forEachIndexed { i,a -> if(previousFindings[a.id]!=a) save("workspace_finding",f("id" to a.id,"source_id" to a.sourceId,"asset_id" to a.assetId,"scan_id" to a.scanId,"ordinal" to i,"provenance" to a.provenance.name,"rule_id" to a.ruleId,"title" to a.title,"severity" to a.severity.name,"column_name" to a.column,"observed" to a.observed,"expected" to a.expected,"evidence_json" to WorkspaceSql.json(a.evidence),"suggestion" to a.suggestion,"active" to a.active),mutable=setOf("active")) }
        state.assistants.forEachIndexed { i,a ->
            if(previousAssistants[a.id]==a) return@forEachIndexed
            val fields=f("id" to a.id,"ordinal" to i,"role" to a.role.name,"name" to a.name,"responsibility" to a.responsibility,"status" to a.status,"method" to a.method,"finding_ids" to WorkspaceSql.array(a.findingIds),"task_ids" to WorkspaceSql.array(a.taskIds),"focus_goal_ids" to WorkspaceSql.array(a.focusGoalIds),"scope_source_ids" to WorkspaceSql.array(a.scopeSourceIds),"scope_notice" to a.scopeNotice)
            save("workspace_assistant",fields,mutable=fields.keys-setOf("tenant_id","id","role","ordinal"))
        }
        state.tasks.forEachIndexed { i,t ->
            if(previousTasks[t.id]!=t) {
                save("workspace_task",f("id" to t.id,"current_revision" to state.stateVersion,"ordinal" to i),mutable=setOf("current_revision"))
                save("workspace_task_revision",f("task_id" to t.id,"commit_version" to state.stateVersion,"assistant_id" to t.assistantId,"finding_id" to t.findingId,"title" to t.title,"description" to t.description,"status" to t.status.name,"owner_id" to t.ownerId,"created_at" to t.createdAt,"updated_at" to t.updatedAt,"completion_note" to t.completionNote,"evidence_refs" to WorkspaceSql.array(t.evidenceRefs),"state_version" to t.stateVersion,"verified_by" to t.verifiedBy,"goal_ids" to WorkspaceSql.array(t.goalIds),"in_current_scope" to t.inCurrentScope,"scope_reason" to t.scopeReason,"business_brief_version" to t.businessBriefVersion,"created_under_brief_version" to t.createdUnderBriefVersion,"priority_rank" to t.priorityRank,"priority_reason" to t.priorityReason,"current_finding_id" to t.currentFindingId),listOf("tenant_id","task_id","commit_version"))
            }
        }
        state.comments.forEachIndexed { i,c -> if(previousComments[c.id]!=c) save("workspace_comment",f("id" to c.id,"ordinal" to i,"task_id" to c.taskId,"actor_id" to c.actorId,"body" to c.body,"evidence_refs" to WorkspaceSql.array(c.evidenceRefs),"created_at" to c.createdAt)) }
        state.messages.forEachIndexed { i,m -> if(previousMessages[m.id]!=m) save("workspace_message",f("id" to m.id,"ordinal" to i,"assistant_id" to m.assistantId,"actor_id" to m.actorId,"question" to m.question,"answer" to m.answer,"evidence_refs" to WorkspaceSql.array(m.evidenceRefs),"created_at" to m.createdAt,"method" to m.method,"status" to m.status,"uncertainty" to WorkspaceSql.array(m.uncertainty),"error_code" to m.errorCode,"trace_json" to WorkspaceSql.json(m.trace))) }
        state.audit.forEachIndexed { i,a -> if(previousAudit[a.id]!=a) save("workspace_audit",f("id" to a.id,"ordinal" to i,"actor_id" to a.actorId,"event" to a.event,"resource_id" to a.resourceId,"occurred_at" to a.occurredAt,"state_version" to a.stateVersion)) }
        WorkspaceDefinitionRepository(db,tenant).save(before,state)
    }
}

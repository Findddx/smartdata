package io.smartdata.foundation.runtime.workspace.postgres

import io.smartdata.foundation.runtime.workspace.*
import io.smartdata.foundation.runtime.workspace.lineage.*
import org.jooq.DSLContext
import org.jooq.Record

/** A confirmed business definition is versioned documentation, never an executable metric. */
internal class WorkspaceDefinitionRepository(private val db: DSLContext, private val tenant: String) {
    private fun f(vararg values: Pair<String,Any?>) = linkedMapOf("tenant_id" to tenant,*values)
    private fun save(table:String,values:Map<String,Any?>,vararg keys:String) = WorkspaceSql.save(db,table,values,listOf("tenant_id",*keys))
    private fun rows(table:String,versionColumn:String,version:Long):List<Record> = db.fetch("select * from $table where tenant_id=? and $versionColumn=? order by ordinal",tenant,version).toList()

    fun load(state:PersistedWorkspace) {
        db.fetch("select * from workspace_brief_revision where tenant_id=? order by version",tenant).forEach { r ->
            val version=r.long("version")
            val goals=rows("workspace_goal","brief_version",version).map { g -> BusinessGoal(g.text("id"),g.text("title"),BusinessGoalType.valueOf(g.text("type")),g.text("expected_outcome"),BusinessGoalPriority.valueOf(g.text("priority")),g.strings("source_ids")) }
            val metrics=rows("workspace_metric","brief_version",version).map { m -> MetricDefinition(m.text("id"),m.text("name"),m.text("business_definition"),m.text("owner"),m.text("time_zone"),m.optional("currency"),m.text("formula_description"),m.strings("source_ids"),MetricDefinitionStatus.valueOf(m.text("status")),m.long("state_version"),m.optional("confirmed_by"),m.optional("confirmed_at"),m.text("calculation_status")) }
            state.businessBriefHistory += BusinessBrief(version,r.text("enterprise_name"),r.text("business_description"),goals,r.strings("included_source_ids"),metrics,r.text("updated_at"),r.text("updated_by"),false)
        }
        state.businessBrief=state.businessBriefHistory.lastOrNull()
        db.fetch("select * from workspace_mapping_revision where tenant_id=? order by version",tenant).forEach { r ->
            val version=r.long("version")
            val fields=rows("workspace_mapping_field","mapping_version",version)
            fun inputs(type:String,id:String)=fields.filter { it.text("owner_type")==type && it.text("owner_id")==id }.map { PhysicalFieldRef(it.text("source_id"),it.text("asset_id"),it.text("field_name")) }
            val entities=rows("workspace_entity_mapping","mapping_version",version).map { e -> EntityLineageMapping(e.text("id"),e.text("name"),e.text("entity_type"),inputs("ENTITY",e.text("id")),e.text("transformation_description")) }
            val metrics=rows("workspace_metric_mapping","mapping_version",version).map { m -> MetricLineageMapping(m.text("metric_id"),m.long("definition_version"),m.strings("input_entity_ids"),inputs("METRIC",m.text("metric_id")),m.text("transformation_description")) }
            val decisions=rows("workspace_decision_mapping","mapping_version",version).map { d -> DecisionLineageMapping(d.text("goal_id"),d.strings("input_metric_ids"),d.text("decision_question")) }
            state.lineageMappingHistory += LineageMappings(version,entities,metrics,decisions,r.optional("updated_by"),r.optional("updated_at"))
        }
        state.lineageMappings=state.lineageMappingHistory.lastOrNull()?:LineageMappings()
    }

    fun save(before:PersistedWorkspace,state:PersistedWorkspace) {
        require(state.businessBriefHistory.size<=100 && state.lineageMappingHistory.size<=50)
        require(state.businessBriefHistory.take(before.businessBriefHistory.size)==before.businessBriefHistory &&
            state.lineageMappingHistory.take(before.lineageMappingHistory.size)==before.lineageMappingHistory) { "业务约定和来源映射历史不能删除或改写" }
        require(state.businessBrief==state.businessBriefHistory.lastOrNull()) { "当前业务约定必须绑定已保存的明确历史版本" }
        require(state.lineageMappings==(state.lineageMappingHistory.lastOrNull()?:LineageMappings())) { "当前映射必须绑定已保存的明确历史版本" }
        state.businessBriefHistory.forEachIndexed { n,b ->
            if(n<before.businessBriefHistory.size) return@forEachIndexed
            require(b.version==n+1L && !b.modelContextExportEnabled)
            save("workspace_brief_revision",f("version" to b.version,"enterprise_name" to b.enterpriseName,"business_description" to b.businessDescription,"included_source_ids" to WorkspaceSql.array(b.includedSourceIds),"updated_at" to b.updatedAt,"updated_by" to b.updatedBy,"model_context_export_enabled" to false),"version")
            b.goals.forEachIndexed { i,g -> save("workspace_goal",f("brief_version" to b.version,"id" to g.id,"ordinal" to i,"title" to g.title,"type" to g.type.name,"expected_outcome" to g.expectedOutcome,"priority" to g.priority.name,"source_ids" to WorkspaceSql.array(g.sourceIds)),"brief_version","id") }
            b.metrics.forEachIndexed { i,m ->
                require(m.calculationStatus=="NOT_COMPUTED")
                save("workspace_metric",f("brief_version" to b.version,"id" to m.id,"ordinal" to i,"name" to m.name,"business_definition" to m.businessDefinition,"owner" to m.owner,"time_zone" to m.timeZone,"currency" to m.currency,"formula_description" to m.formulaDescription,"source_ids" to WorkspaceSql.array(m.sourceIds),"status" to m.status.name,"state_version" to m.stateVersion,"confirmed_by" to m.confirmedBy,"confirmed_at" to m.confirmedAt,"calculation_status" to m.calculationStatus),"brief_version","id")
            }
        }
        state.lineageMappingHistory.forEachIndexed { n,m ->
            if(n<before.lineageMappingHistory.size) return@forEachIndexed
            require(m.version==n+1L)
            save("workspace_mapping_revision",f("version" to m.version,"updated_by" to m.updatedBy,"updated_at" to m.updatedAt),"version")
            fun fields(type:String,id:String,values:List<PhysicalFieldRef>) { values.forEachIndexed { i,v -> save("workspace_mapping_field",f("mapping_version" to m.version,"owner_type" to type,"owner_id" to id,"ordinal" to i,"source_id" to v.sourceId,"asset_id" to v.assetId,"field_name" to v.fieldName),"mapping_version","owner_type","owner_id","ordinal") } }
            m.entities.forEachIndexed { i,e -> save("workspace_entity_mapping",f("mapping_version" to m.version,"id" to e.id,"ordinal" to i,"name" to e.name,"entity_type" to e.entityType,"transformation_description" to e.transformationDescription),"mapping_version","id"); fields("ENTITY",e.id,e.inputFields) }
            m.metrics.forEachIndexed { i,v -> save("workspace_metric_mapping",f("mapping_version" to m.version,"metric_id" to v.metricId,"ordinal" to i,"definition_version" to v.definitionVersion,"input_entity_ids" to WorkspaceSql.array(v.inputEntityIds),"transformation_description" to v.transformationDescription),"mapping_version","metric_id"); fields("METRIC",v.metricId,v.inputFields) }
            m.decisions.forEachIndexed { i,v -> save("workspace_decision_mapping",f("mapping_version" to m.version,"goal_id" to v.goalId,"ordinal" to i,"input_metric_ids" to WorkspaceSql.array(v.inputMetricIds),"decision_question" to v.decisionQuestion),"mapping_version","goal_id") }
        }
    }
}

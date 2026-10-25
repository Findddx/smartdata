package io.smartdata.foundation.runtime.workspace

import io.smartdata.foundation.runtime.api.GlobalErrorHandler
import io.smartdata.foundation.runtime.api.InMemoryCommandIdempotencyGate
import io.smartdata.foundation.runtime.security.LocalActorContextResolver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WorkspaceControllerTest {
    @TempDir lateinit var directory: Path
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        val clock = Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), ZoneOffset.UTC)
        val service = WorkspaceService(LocalWorkspaceStore(directory), listOf(JsonSnapshotSource()), clock, WorkspaceCapabilities())
        mvc = MockMvcBuilders.standaloneSetup(WorkspaceController(service, LocalActorContextResolver(), InMemoryCommandIdempotencyGate()))
            .setControllerAdvice(GlobalErrorHandler()).build()
    }

    @Test
    fun `API starts empty and replay does not duplicate user source`() {
        mvc.perform(get("/api/v1/workspace")).andExpect(status().isOk)
            .andExpect(jsonPath("$.sources.length()").value(0))
            .andExpect(jsonPath("$.mode").value("LOCAL_PERSISTENT"))
        val body = """{"expectedStateVersion":0,"name":"用户快照","kind":"JSON_SNAPSHOT","snapshotRows":[{"id":"x"}],"observedAt":"2026-10-25T07:59:00Z","primaryKey":"id"}"""
        repeat(2) {
            mvc.perform(post("/api/v1/workspace/sources").header("X-Demo-Actor", "admin").header("Idempotency-Key", "same-command-key")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk).andExpect(jsonPath("$.stateVersion").value(1))
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].provenance").value("USER"))
        }
        mvc.perform(get("/api/v1/workspace/report")).andExpect(status().isOk)
            .andExpect(jsonPath("$.method").value("DETERMINISTIC_EVIDENCE_REPORT_V1"))
    }

    @Test
    fun `commands require idempotency and stale aggregate version is a conflict`() {
        mvc.perform(post("/api/v1/workspace/demo/bootstrap").contentType(MediaType.APPLICATION_JSON).content("""{"expectedStateVersion":0}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/workspace/demo/bootstrap").header("Idempotency-Key", "demo-command-1")
            .contentType(MediaType.APPLICATION_JSON).content("""{"expectedStateVersion":0}"""))
            .andExpect(status().isOk)
        mvc.perform(post("/api/v1/workspace/team/provision").header("Idempotency-Key", "team-command-1")
            .contentType(MediaType.APPLICATION_JSON).content("""{"expectedStateVersion":0}"""))
            .andExpect(status().isConflict)
    }

    @Test
    fun `auditor cannot load demo and malformed source kind is a client error`() {
        mvc.perform(post("/api/v1/workspace/demo/bootstrap").header("X-Demo-Actor", "auditor").header("Idempotency-Key", "audit-command-1")
            .contentType(MediaType.APPLICATION_JSON).content("""{"expectedStateVersion":0}"""))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/workspace/sources").header("Idempotency-Key", "bad-source-kind")
            .contentType(MediaType.APPLICATION_JSON).content("""{"expectedStateVersion":0,"name":"wrong","kind":"SHELL"}"""))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `nullable cells are preserved while a null row is rejected`() {
        mvc.perform(post("/api/v1/workspace/sources").header("X-Demo-Actor", "admin").header("Idempotency-Key", "nullable-cells-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"expectedStateVersion":0,"name":"nullable cells","kind":"JSON_SNAPSHOT","snapshotRows":[{"id":"x","email":null}],"observedAt":"2026-10-25T07:59:00Z"}"""))
            .andExpect(status().isOk)
        mvc.perform(get("/api/v1/workspace")).andExpect(status().isOk)
            .andExpect(jsonPath("$.sources.length()").value(1))
        mvc.perform(post("/api/v1/workspace/sources").header("X-Demo-Actor", "admin").header("Idempotency-Key", "null-row-rejected-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"expectedStateVersion":1,"name":"invalid row","kind":"JSON_SNAPSHOT","snapshotRows":[null],"observedAt":"2026-10-25T07:59:00Z"}"""))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `business endpoints persist versioned drafts and permit only business owner confirmation`() {
        mvc.perform(post("/api/v1/workspace/demo/bootstrap").header("Idempotency-Key", "brief-demo-setup")
            .contentType(MediaType.APPLICATION_JSON).content("""{"expectedStateVersion":0}"""))
            .andExpect(status().isOk)
        val body = """{"expectedStateVersion":1,"expectedBriefVersion":0,"enterpriseName":"用户填写的企业", "includedSourceIds":["demo-orders"],"goals":[],"metrics":[{"id":"orders","name":"有效订单","businessDefinition":"业务确认状态有效的订单","owner":"业务负责人","timeZone":"Asia/Shanghai","currency":null,"formulaDescription":"按业务确认状态计数；此处仅登记说明","sourceIds":["demo-orders"]}]}"""
        repeat(2) {
            mvc.perform(post("/api/v1/workspace/business-brief").header("Idempotency-Key", "brief-save-once")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk).andExpect(jsonPath("$.stateVersion").value(2))
                .andExpect(jsonPath("$.businessBrief.version").value(1))
                .andExpect(jsonPath("$.businessBrief.modelContextExportEnabled").value(false))
                .andExpect(jsonPath("$.businessBrief.metrics[0].status").value("DRAFT"))
        }
        val confirm = """{"expectedStateVersion":2,"expectedBriefVersion":1,"expectedMetricVersion":1}"""
        mvc.perform(post("/api/v1/workspace/business-brief/metrics/orders/confirm").header("Idempotency-Key", "brief-denied-confirm")
            .contentType(MediaType.APPLICATION_JSON).content(confirm))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/workspace/business-brief/metrics/orders/confirm").header("X-Demo-Actor", "sales-owner")
            .header("Idempotency-Key", "brief-owner-confirm").contentType(MediaType.APPLICATION_JSON).content(confirm))
            .andExpect(status().isOk).andExpect(jsonPath("$.businessBrief.version").value(2))
            .andExpect(jsonPath("$.businessBrief.metrics[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.businessBrief.metrics[0].confirmedBy").value("sales-owner"))
            .andExpect(jsonPath("$.businessBrief.metrics[0].calculationStatus").value("NOT_COMPUTED"))
        mvc.perform(get("/api/v1/workspace/business-brief/history").header("X-Demo-Actor", "auditor"))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].metrics[0].status").value("DRAFT"))
            .andExpect(jsonPath("$[1].metrics[0].status").value("CONFIRMED"))
        mvc.perform(post("/api/v1/workspace/business-brief").header("Idempotency-Key", "brief-stale-update")
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict)
    }

    @Test
    fun `null business list elements are client errors rather than partial business records`() {
        listOf("\"goals\":[null]", "\"metrics\":[null]", "\"includedSourceIds\":[null]").forEachIndexed { index, extra ->
            mvc.perform(post("/api/v1/workspace/business-brief").header("Idempotency-Key", "brief-null-item-$index")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"expectedStateVersion":0,"expectedBriefVersion":0,"enterpriseName":"正常名称",$extra}"""))
                .andExpect(status().isBadRequest)
        }
        mvc.perform(get("/api/v1/workspace")).andExpect(status().isOk)
            .andExpect(jsonPath("$.stateVersion").value(0))
        mvc.perform(get("/api/v1/workspace/business-brief/history")).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }
}

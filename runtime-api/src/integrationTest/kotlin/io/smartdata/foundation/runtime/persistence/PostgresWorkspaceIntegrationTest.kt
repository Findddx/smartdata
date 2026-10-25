package io.smartdata.foundation.runtime.persistence

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.runtime.api.commandFingerprint
import io.smartdata.foundation.runtime.postgres.PostgresRepositories
import io.smartdata.foundation.runtime.security.enterprise.EnterpriseDirectoryService
import io.smartdata.foundation.runtime.security.enterprise.EnterpriseProperties
import io.smartdata.foundation.runtime.workspace.*
import io.smartdata.foundation.runtime.workspace.lineage.*
import io.smartdata.foundation.runtime.workspace.postgres.PostgresWorkspaceStore
import io.smartdata.foundation.service.AuthorizationDeniedException
import io.smartdata.foundation.service.IdempotencyConflictException
import io.smartdata.foundation.service.OptimisticStateException
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Same real low-privilege PG18 fixture as the kernel; never substitutes H2 or skips Docker failures. */
class PostgresWorkspaceIntegrationTest {
    private lateinit var fixture: PostgresFixture
    private lateinit var pg: PostgresRepositories
    private lateinit var store: PostgresWorkspaceStore
    private lateinit var service: WorkspaceService
    private lateinit var clock: TestClock
    private val tenant="tenant-a"
    private val admin=Actor("acct_"+"a".repeat(64),"负责人",tenant,setOf(BusinessRole.ADMIN),1)
    private val worker=Actor("acct_"+"b".repeat(64),"处理人",tenant,setOf(BusinessRole.ORDER_SPECIALIST),1)
    private val reviewer=Actor("acct_"+"c".repeat(64),"复核人",tenant,setOf(BusinessRole.SALES_OWNER),1)
    private var sequence=0
    private fun key()="workspace-command-${++sequence}"

    @BeforeEach fun setup() {
        fixture=PostgresIntegrationDatabase.fixture()
        fixture.seedTenant(tenant); fixture.seedTenant("tenant-b")
        clock=TestClock(Instant.parse("2026-10-25T08:00:00Z"))
        listOf(admin,worker,reviewer).forEach(::seedMember)
        pg=PostgresRepositories(fixture.runtimeDataSource)
        store=newStore(pg)
        service=newService(store)
    }
    @AfterEach fun close()=fixture.close()

    private fun seedMember(actor:Actor)=fixture.admin { c ->
        c.prepareStatement("insert into enterprise_account(tenant_id,account_id,issuer,subject,display_name,status,created_at,updated_at) values(?,?, 'https://identity.example',?,?,'ACTIVE',current_timestamp,current_timestamp)").use { s ->
            s.setString(1,actor.tenantId);s.setString(2,actor.id);s.setString(3,actor.id);s.setString(4,actor.displayName);s.executeUpdate()
        }
        c.prepareStatement("insert into enterprise_member(tenant_id,account_id,roles,status,state_version,updated_at) values(?,?,?,'ACTIVE',1,current_timestamp)").use { s ->
            s.setString(1,actor.tenantId);s.setString(2,actor.id);s.setArray(3,c.createArrayOf("text",actor.roles.map { it.name }.toTypedArray()));s.executeUpdate()
        }
    }
    private fun newStore(repositories:PostgresRepositories):PostgresWorkspaceStore {
        val directory=EnterpriseDirectoryService(repositories,EnterpriseProperties(tenantId=tenant),clock)
        return PostgresWorkspaceStore(repositories.unitOfWork,WorkspaceTransactionAccessPort(directory::checkCurrent),clock)
    }
    private fun newService(storage:PostgresWorkspaceStore,reasoning:AssistantReasoningPort=DeterministicAssistantReasoningPort())=
        WorkspaceService(storage,listOf(JsonSnapshotSource()),clock,WorkspaceCapabilities(sourceKinds=listOf(SourceKind.JSON_SNAPSHOT)),reasoning)
    private fun source(version:Long=0)=CreateSourceCommand(version,"订单上传",SourceKind.JSON_SNAPSHOT,
        snapshotRows=listOf(mapOf("order_id" to "O-1","amount" to 10),mapOf("order_id" to "O-1","amount" to null)),observedAt=clock.instant().toString(),primaryKey="order_id")
    private fun prepared():WorkspaceView {
        var view=service.createSource(admin,key(),source())
        view=service.scan(worker,view.sources.single().id,key(),VersionCommand(view.stateVersion))
        return service.provisionTeam(worker,key(),VersionCommand(view.stateVersion))
    }
    private fun count(table:String)=fixture.admin { c -> c.prepareStatement("select count(*) from $table where tenant_id=?").use { s ->
        s.setString(1,tenant);s.executeQuery().use { r -> r.next();r.getLong(1) }
    } }

    @Test fun `two current members hand off complete evidence and recover normalized history`() {
        var view=prepared()
        val finding=view.findings.first { it.ruleId=="DQ.DUPLICATE_PRIMARY_KEY.v1" }
        val task=view.tasks.first { it.findingId==finding.id }
        view=service.transitionTask(worker,task.id,key(),TaskTransitionCommand(view.stateVersion,TaskStatus.ACCEPTED,"领取处理"))
        view=service.transitionTask(worker,task.id,key(),TaskTransitionCommand(view.stateVersion,TaskStatus.COMPLETED,"已修正上传快照",listOf(finding.id)))
        clock.advance()
        view=service.replaceSnapshot(admin,view.sources.single().id,key(),ReplaceSnapshotCommand(view.stateVersion,
            listOf(mapOf("order_id" to "O-1","amount" to 10),mapOf("order_id" to "O-2","amount" to 20)),clock.instant().toString()))
        view=service.scan(worker,view.sources.single().id,key(),VersionCommand(view.stateVersion))
        val next=view.scans.last().id
        // Even a changed business role cannot turn the same canonical account into a second reviewer.
        fixture.admin { c -> c.prepareStatement("update enterprise_member set roles=ARRAY['SALES_OWNER'],state_version=2 where tenant_id=? and account_id=?").use { s ->s.setString(1,tenant);s.setString(2,worker.id);s.executeUpdate() } }
        val upgradedWorker=worker.copy(roles=setOf(BusinessRole.SALES_OWNER),authorizationVersion=2)
        assertThrows(IllegalArgumentException::class.java) { service.transitionTask(upgradedWorker,task.id,key(),TaskTransitionCommand(view.stateVersion,TaskStatus.VERIFIED,"自己复核",listOf(next))) }
        view=service.transitionTask(reviewer,task.id,key(),TaskTransitionCommand(view.stateVersion,TaskStatus.VERIFIED,"独立复核新快照",listOf(next)))
        val recovered=newService(newStore(PostgresRepositories(fixture.runtimeDataSource))).view(admin)
        assertEquals(view,recovered)
        assertEquals("POSTGRES_SHARED",recovered.mode)
        assertEquals(reviewer.id,recovered.tasks.first { it.id==task.id }.verifiedBy)
        assertEquals(2,count("workspace_snapshot_payload"))
        assertTrue(count("workspace_source_revision")>=4)
        assertTrue(count("workspace_asset_revision")>recovered.assets.size)
        assertTrue(count("outbox_event")>=recovered.audit.size)
        assertFalse(WorkspaceSqlForTest.json(recovered).contains("snapshotRows"))
        assertThrows(IllegalArgumentException::class.java) { service.bootstrapDemo(admin,key(),VersionCommand(view.stateVersion)) }
    }

    @Test fun `brief metric confirmation and mapping revisions survive fresh repository instances`() {
        var view=prepared()
        val sourceId=view.sources.single().id
        val goal=BusinessGoal("goal","核对销售口径",BusinessGoalType.ANALYTICS_TRUST,"让口径可追溯",BusinessGoalPriority.HIGH,listOf(sourceId))
        view=service.saveBusinessBrief(admin,key(),SaveBusinessBriefCommand(view.stateVersion,0,"测试企业",goals=listOf(goal),includedSourceIds=listOf(sourceId),
            metrics=listOf(MetricDefinitionInput("sales","销售额","仅登记","负责人","UTC","CNY","sum(amount)",listOf(sourceId)))))
        view=service.confirmMetricDefinition(reviewer,"sales",key(),ConfirmMetricDefinitionCommand(view.stateVersion,1,1))
        val asset=view.assets.single()
        val mapping=SaveLineageMappingsCommand(view.stateVersion,0,
            listOf(EntityLineageMapping("orders","订单实体","ORDER",listOf(PhysicalFieldRef(sourceId,asset.id,"order_id")),"声明订单键")),
            listOf(MetricLineageMapping("sales",2,listOf("orders"),transformationDescription="只声明求和输入")),
            listOf(DecisionLineageMapping("goal",listOf("sales"),"销售口径是否明确")))
        view=service.saveLineageMappings(admin,key(),mapping)
        val fresh=newService(newStore(PostgresRepositories(fixture.runtimeDataSource)))
        assertEquals(2,fresh.businessBriefHistory(admin).size)
        assertEquals(1,fresh.lineageMappings(admin).version)
        assertEquals(view,fresh.view(admin))
        assertTrue(fresh.lineage(admin).nodes.filter { it.layer!=LineageLayer.L0 }.all { it.materialization==LineageMaterialization.NOT_MATERIALIZED })
    }

    @Test fun `independent instances reject stale CAS without losing accepted comments`() {
        val view=prepared();val task=view.tasks.first()
        val other=newService(newStore(PostgresRepositories(fixture.runtimeDataSource)))
        val executor=Executors.newFixedThreadPool(2)
        val start=CountDownLatch(1)
        try {
            val futures=listOf(service,other).mapIndexed { i,s -> executor.submit<Boolean> {
                start.await();try { s.addComment(worker,task.id,"concurrent-comment-$i",TaskCommentCommand(view.stateVersion,"评论$i"));true }
                catch(_:OptimisticStateException) { false }
            } }
            start.countDown()
            assertEquals(1,futures.count { it.get(10,TimeUnit.SECONDS) })
            assertEquals(1,service.view(admin).comments.size)
        } finally { executor.shutdownNow() }
    }

    @Test fun `replay is durable returns current projection and refuses changed intent`() {
        val command=source();val replayKey=key()
        val first=service.createSource(admin,replayKey,command)
        val later=service.scan(worker,first.sources.single().id,key(),VersionCommand(first.stateVersion))
        val fresh=newService(newStore(PostgresRepositories(fixture.runtimeDataSource)))
        assertEquals(later,fresh.createSource(admin,replayKey,command))
        assertThrows(IdempotencyConflictException::class.java) { fresh.createSource(admin,replayKey,command.copy(name="不同命令")) }
        assertEquals(1,count("workspace_source"))
    }

    @Test fun `durable operation fencing prevents concurrent or unknown model replay`() {
        val view=prepared();val assistant=view.assistants.first { it.role==AssistantRole.ANALYSIS }
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        val reasoning=object:AssistantReasoningPort { override fun respond(context:AssistantReasoningContext):AssistantReasoningResult {
            assertFalse(pg.unitOfWork.transactionActiveOnCurrentThread)
            calls.incrementAndGet();entered.countDown();check(release.await(10,TimeUnit.SECONDS))
            return AssistantReasoningResult("仅复述本轮证据",context.findings.map { it.id },"CONTRACT_MODEL_STUB")
        } }
        val first=newService(store,reasoning)
        val other=newService(newStore(PostgresRepositories(fixture.runtimeDataSource)),reasoning)
        val operationKey=key();val body=AssistantMessageCommand(view.stateVersion,"检查本轮证据")
        val executor=Executors.newSingleThreadExecutor()
        try {
            val pending=executor.submit<WorkspaceView> { first.message(worker,assistant.id,operationKey,body) }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertThrows(RuntimeException::class.java) { other.message(worker,assistant.id,operationKey,body) }
            assertEquals(1,calls.get())
            release.countDown();val answer=pending.get(10,TimeUnit.SECONDS)
            assertEquals(answer,other.message(worker,assistant.id,operationKey,body))
            assertEquals(1,calls.get())
            val uncertainKey=key();val fp=commandFingerprint("uncertain")
            val reserved=store.reserveExternalOperation(worker,answer.stateVersion,uncertainKey,fp,"MODEL_REASONING")
            store.markOperationUnknown(worker,requireNotNull(reserved.lease))
            assertEquals(WorkspaceOperationStatus.UNKNOWN,newStore(PostgresRepositories(fixture.runtimeDataSource)).reserveExternalOperation(worker,answer.stateVersion,uncertainKey,fp,"MODEL_REASONING").status)
        } finally { release.countDown();executor.shutdownNow() }
    }

    @Test fun `revocation while reasoning is outside SQL prevents late success commit`() {
        val view=prepared();val assistant=view.assistants.first { it.role==AssistantRole.ANALYSIS }
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val reasoning=object:AssistantReasoningPort { override fun respond(context:AssistantReasoningContext):AssistantReasoningResult {
            assertFalse(pg.unitOfWork.transactionActiveOnCurrentThread)
            entered.countDown();check(release.await(10,TimeUnit.SECONDS))
            return AssistantReasoningResult("不能在撤权后提交",context.findings.map { it.id },"CONTRACT_MODEL_STUB")
        } }
        val executor=Executors.newSingleThreadExecutor()
        try {
            val future=executor.submit<Throwable?> { try { newService(store,reasoning).message(worker,assistant.id,key(),AssistantMessageCommand(view.stateVersion,"待撤权请求"));null } catch(e:Throwable) { e } }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            fixture.admin { c -> c.prepareStatement("update enterprise_member set status='SUSPENDED',state_version=2 where tenant_id=? and account_id=?").use { s -> s.queryTimeout=3;s.setString(1,tenant);s.setString(2,worker.id);s.executeUpdate() } }
            release.countDown()
            assertInstanceOf(AuthorizationDeniedException::class.java,future.get(10,TimeUnit.SECONDS))
            assertTrue(service.view(admin).messages.isEmpty())
            assertThrows(AuthorizationDeniedException::class.java) { store.readAuthorized(worker) }
        } finally { release.countDown();executor.shutdownNow() }
    }

    @Test fun `failed multi relation write rolls back projection command audit and outbox`() {
        val version=service.view(admin).stateVersion
        assertThrows(RuntimeException::class.java) { store.mutateAuthorized(admin,version,key(),commandFingerprint("rollback")) { state ->
            state.audit+=WorkspaceAudit("audit-rollback",admin.id,"test.rollback","workspace",clock.instant().toString(),1)
            state.comments+=TaskComment("orphan-comment","missing-task",admin.id,"应整体回滚",emptyList(),clock.instant().toString())
        } }
        assertEquals(version,service.view(admin).stateVersion)
        assertEquals(0,count("workspace_comment"));assertEquals(0,count("workspace_command"));assertEquals(0,count("workspace_audit"));assertEquals(0,count("outbox_event"))
    }

    @Test fun `expired operation cannot publish a late result and remains unknown after recovery`() {
        val operationKey=key();val fp=commandFingerprint("expired operation")
        val reservation=store.reserveExternalOperation(admin,0,operationKey,fp,"MODEL_REASONING")
        val lease=requireNotNull(reservation.lease)
        fixture.admin { c -> c.prepareStatement("update workspace_operation set lease_until=clock_timestamp()-interval '1 second' where tenant_id=? and id=?::uuid").use { s ->s.setString(1,tenant);s.setString(2,lease.operationId);s.executeUpdate() } }
        assertThrows(IllegalArgumentException::class.java) { store.mutateAuthorized(admin,0,operationKey,fp,lease) { state ->
            state.audit+=WorkspaceAudit("late-audit",admin.id,"late.result","workspace",clock.instant().toString(),1)
        } }
        assertEquals(WorkspaceOperationStatus.UNKNOWN,newStore(PostgresRepositories(fixture.runtimeDataSource)).reserveExternalOperation(admin,0,operationKey,fp,"MODEL_REASONING").status)
        assertEquals(0L,service.view(admin).stateVersion)
        assertEquals(0L,count("workspace_command"));assertEquals(0L,count("workspace_audit"))
    }

    @Test fun `history triggers and tenant RLS protect raw payload and scan history`() {
        prepared()
        fixture.runtimeDataSource.connection.use { c ->
            c.autoCommit=false
            c.createStatement().use { s -> s.executeQuery("select count(*) from workspace_snapshot_payload").use { r ->r.next();assertEquals(0,r.getInt(1)) } }
            c.prepareStatement("select set_config('app.tenant_id',?,true)").use { s ->s.setString(1,"tenant-b");s.executeQuery().close() }
            c.createStatement().use { s -> s.executeQuery("select count(*) from workspace_scan").use { r ->r.next();assertEquals(0,r.getInt(1)) } }
            c.rollback()
            c.prepareStatement("select set_config('app.tenant_id',?,true)").use { s ->s.setString(1,tenant);s.executeQuery().close() }
            assertThrows(java.sql.SQLException::class.java) { c.createStatement().use { it.executeUpdate("update workspace_snapshot_payload set canonical_json='[]'") } }
            c.rollback()
            c.prepareStatement("select set_config('app.tenant_id',?,true)").use { s ->s.setString(1,tenant);s.executeQuery().close() }
            val changedFact=assertThrows(java.sql.SQLException::class.java) { c.createStatement().use { it.executeUpdate("update workspace_finding set title='改写原事实'") } }
            assertEquals("23514",changedFact.sqlState)
            c.rollback()
            c.prepareStatement("select set_config('app.tenant_id',?,true)").use { s ->s.setString(1,tenant);s.executeQuery().close() }
            val selfReview=assertThrows(java.sql.SQLException::class.java) { c.prepareStatement("insert into workspace_task_revision select (jsonb_populate_record(null::workspace_task_revision,to_jsonb(r)||jsonb_build_object('commit_version',999999,'status','VERIFIED','owner_id',?::text,'verified_by',?::text))).* from workspace_task_revision r where tenant_id=? limit 1").use { s ->
                s.setString(1,worker.id);s.setString(2,worker.id);s.setString(3,tenant);s.executeUpdate()
            } }
            assertEquals("23514",selfReview.sqlState)
            c.rollback()
        }
        assertEquals(1,count("workspace_snapshot_payload"))
        assertThrows(AuthorizationDeniedException::class.java) { store.readAuthorized(admin.copy(tenantId="tenant-b")) }
    }

    private class TestClock(at:Instant):Clock() {
        private val current=AtomicReference(at)
        override fun instant()=current.get()
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=Clock.fixed(current.get(),zone)
        fun advance() { current.updateAndGet { it.plusSeconds(10) } }
    }
    private object WorkspaceSqlForTest { fun json(value:Any)=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value) }
    companion object {
        @JvmStatic @BeforeAll fun startRealPostgres()=PostgresIntegrationDatabase.start()
        @JvmStatic @AfterAll fun stopRealPostgres()=PostgresIntegrationDatabase.stop()
    }
}

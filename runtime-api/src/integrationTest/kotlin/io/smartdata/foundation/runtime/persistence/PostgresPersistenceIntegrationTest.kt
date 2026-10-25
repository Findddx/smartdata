package io.smartdata.foundation.runtime.persistence

import io.smartdata.foundation.domain.*
import io.smartdata.foundation.port.*
import io.smartdata.foundation.runtime.postgres.PostgresRepositories
import io.smartdata.foundation.runtime.postgres.StoredCommand
import io.smartdata.foundation.service.*
import io.smartdata.foundation.simulation.DeterministicFulfillmentSimulator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.*
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.Connection
import java.sql.SQLException
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Every test uses real PostgreSQL and a separate database with a low-privilege principal. */
class PostgresPersistenceIntegrationTest {
    private lateinit var fixture: PostgresFixture
    private lateinit var pg: PostgresRepositories
    private val tenant = "tenant-a"
    private val otherTenant = "tenant-b"
    private val at = Instant.parse("2026-10-25T08:00:00Z")

    @BeforeEach
    fun createDatabase() {
        fixture = PostgresIntegrationDatabase.fixture()
        fixture.seedTenant(tenant)
        fixture.seedTenant(otherTenant)
        pg = PostgresRepositories(fixture.runtimeDataSource)
    }

    @AfterEach
    fun closePool() = fixture.close()

    @Test
    fun `fresh migrations retain non UUID ids facts and exact confidence`() {
        val original = richCase(tenant)
        assertThat(pg.cases.save(original)).isEqualTo(original)
        assertThat(pg.cases.findById(tenant, original.id)).isEqualTo(original)
        assertThat(pg.cases.findById(tenant, original.id)!!.claims.single().confidence).isLessThan(0.95)
        val nanos = at.plusNanos(123456789)
        val evidence = EvidenceRef("evidence/nanos", tenant, "ERP", "row/nanos", "c".repeat(64), nanos)
        val nanoCase = simpleCase(tenant, "case/nanos").copy(createdAt = nanos, updatedAt = nanos,
            claims = listOf(Claim("claim/nanos", tenant, "Order", "order/nanos", "status", "pending", listOf(evidence), nanos, recordedAt = nanos, confidence = 0.94999)),
            knowledgeGaps = listOf(KnowledgeGap("gap/nanos", tenant, KnowledgeGapKind.UNKNOWN, "NANOS_TEST", "order/nanos", "合成精度检查", "核对", "补充观测", nanos)))
        val savedNanos = pg.cases.save(nanoCase)
        assertThat(pg.cases.save(nanoCase)).isEqualTo(savedNanos)
        assertThat(savedNanos.claims.single().validFrom).isEqualTo(nanos.truncatedTo(ChronoUnit.MICROS))
        fixture.admin { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select confidence, confidence_exact from claim").use { row ->
                    assertThat(row.next()).isTrue()
                    assertThat(row.getDouble(1)).isEqualTo(0.95)
                    assertThat(row.getDouble(2)).isEqualTo(0.94999)
                }
                statement.executeQuery("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind='r' and c.relname <> 'flyway_schema_history' and (not c.relrowsecurity or not c.relforcerowsecurity)").use { row ->
                    assertThat(row.next()).isTrue()
                    assertThat(row.getLong(1)).isZero()
                }
            }
        }
    }

    @Test
    fun `V1 upgrade retains UUID rows and refuses to invent missing claim membership`() {
        fixture.close()
        fixture = PostgresIntegrationDatabase.fixture("1")
        fixture.seedTenant(tenant)
        val legacyId = UUID.randomUUID()
        fixture.admin { connection ->
            connection.prepareStatement("insert into exception_case(tenant_id,id,order_id,title_zh,priority,status,scenario_kinds,domain_version,policy_version,state_version,created_at,updated_at) values (?,?,'legacy-order','旧案件','HIGH','OPEN',?,'domain-1','policy-1',0,?,?)").use { statement ->
                statement.setString(1, tenant)
                statement.setObject(2, legacyId)
                statement.setArray(3, connection.createArrayOf("text", arrayOf("SUPPLIER_DELAY")))
                statement.setObject(4, at.atOffset(ZoneOffset.UTC))
                statement.setObject(5, at.atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }
        fixture.migrate()
        fixture.grantRuntimeAccess()
        pg = PostgresRepositories(fixture.runtimeDataSource)
        fixture.admin { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("select id,business_id,context_complete from exception_case").use { row ->
                assertThat(row.next()).isTrue()
                assertThat(row.getObject(1, UUID::class.java)).isEqualTo(legacyId)
                assertThat(row.getString(2)).isEqualTo(legacyId.toString())
                assertThat(row.getBoolean(3)).isFalse()
            }
        } }
        assertThatThrownBy { pg.cases.findById(tenant, legacyId.toString()) }.isInstanceOf(BusinessValidationException::class.java)
        val explicitImport = simpleCase(tenant, legacyId.toString()).copy(stateVersion = 1)
        pg.cases.save(explicitImport, 0)
        assertThat(pg.cases.findById(tenant, legacyId.toString())).isEqualTo(explicitImport)
    }

    @Test
    fun `RLS independently blocks missing and foreign tenant SQL and resets local context`() {
        val id = "same-business-id"
        pg.cases.save(simpleCase(tenant, id))
        pg.cases.save(simpleCase(otherTenant, id))
        assertThat(pg.cases.findById(tenant, id)!!.tenantId).isEqualTo(tenant)
        assertThat(pg.cases.findById(otherTenant, id)!!.tenantId).isEqualTo(otherTenant)
        fixture.runtimeDataSource.connection.use { connection ->
            connection.autoCommit = false
            assertThat(countCases(connection)).isZero()
            bindTenant(connection, tenant)
            assertThat(countCases(connection)).isEqualTo(1)
            connection.prepareStatement("update exception_case set title_zh='foreign mutation' where tenant_id=?").use { statement ->
                statement.setString(1, otherTenant)
                assertThat(statement.executeUpdate()).isZero()
            }
            connection.commit()
            assertThat(countCases(connection)).isZero()
            connection.rollback()
            bindTenant(connection, otherTenant)
            assertThat(countCases(connection)).isEqualTo(1)
            connection.rollback()
            assertThat(countCases(connection)).isZero()
            connection.rollback()
            bindTenant(connection, tenant)
            val denied = org.junit.jupiter.api.Assertions.assertThrows(SQLException::class.java) {
                connection.createStatement().use { it.executeUpdate("insert into exception_case(tenant_id,id,business_id,order_id,title_zh,priority,status,scenario_kinds,domain_version,policy_version,state_version,created_at,updated_at,context_complete) values ('tenant-b','${UUID.randomUUID()}','forged','o','forged','LOW','OPEN','{}','d','p',0,current_timestamp,current_timestamp,true)") }
            }
            assertThat(denied.sqlState).isEqualTo("42501")
            connection.rollback()
        }
        assertThat(pg.cases.findById(otherTenant, id)!!.titleZh).isEqualTo("合成案件")
    }

    @Test
    fun `privileged and non inheriting table owner member roles are rejected`() {
        fixture.dataSource(PostgresIntegrationDatabase.username, PostgresIntegrationDatabase.adminPassword).use { admin ->
            assertThatThrownBy { PostgresRepositories(admin) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        val ownerRole = "owner_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        fixture.admin { connection -> connection.createStatement().use { statement ->
            statement.execute("create role \"$ownerRole\" nologin nosuperuser nobypassrls")
            statement.execute("alter table exception_case owner to \"$ownerRole\"")
            statement.execute("grant \"$ownerRole\" to \"${fixture.runtimeRole}\" with inherit false")
        } }
        assertThatThrownBy { PostgresRepositories(fixture.runtimeDataSource) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `runtime role with schema CREATE or direct and inherited TRUNCATE is rejected`() {
        fixture.admin { connection -> connection.createStatement().use { it.execute("grant create on schema public to \"${fixture.runtimeRole}\"") } }
        assertThatThrownBy { PostgresRepositories(fixture.runtimeDataSource) }.isInstanceOf(IllegalArgumentException::class.java)
        fixture.admin { connection -> connection.createStatement().use {
            it.execute("revoke create on schema public from \"${fixture.runtimeRole}\"")
            it.execute("grant truncate on exception_case to \"${fixture.runtimeRole}\"")
        } }
        assertThatThrownBy { PostgresRepositories(fixture.runtimeDataSource) }.isInstanceOf(IllegalArgumentException::class.java)
        val truncateRole = "trunc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        fixture.admin { connection -> connection.createStatement().use {
            it.execute("revoke truncate on exception_case from \"${fixture.runtimeRole}\"")
            it.execute("create role \"$truncateRole\" nologin nosuperuser nobypassrls")
            it.execute("grant truncate on exception_case to \"$truncateRole\"")
            it.execute("grant \"$truncateRole\" to \"${fixture.runtimeRole}\" with inherit false")
        } }
        assertThatThrownBy { PostgresRepositories(fixture.runtimeDataSource) }.isInstanceOf(IllegalArgumentException::class.java)
        fixture.admin { connection -> connection.createStatement().use {
            it.execute("revoke \"$truncateRole\" from \"${fixture.runtimeRole}\"")
            it.execute("alter role \"${fixture.runtimeRole}\" createrole")
        } }
        assertThatThrownBy { PostgresRepositories(fixture.runtimeDataSource) }.isInstanceOf(IllegalArgumentException::class.java)
        val databaseRole = "db_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        fixture.admin { connection -> connection.createStatement().use {
            it.execute("alter role \"${fixture.runtimeRole}\" nocreaterole")
            it.execute("create role \"$databaseRole\" nologin nosuperuser nobypassrls createdb")
            it.execute("grant \"$databaseRole\" to \"${fixture.runtimeRole}\" with inherit false")
        } }
        assertThatThrownBy { PostgresRepositories(fixture.runtimeDataSource) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `same connection temporary table cannot shadow the protected public table`() {
        val value = pg.cases.save(simpleCase(tenant))
        fixture.runtimeDataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("create temporary table exception_case (like public.exception_case including defaults)") }
            val sameConnection = PostgresRepositories(SingleConnectionDataSource(connection, true))
            assertThat(sameConnection.cases.findById(tenant, value.id)).isEqualTo(value)
            connection.rollback()
        }
    }

    @Test
    fun `two independent repositories race CAS and only winner emits audit and outbox`() {
        val original = pg.cases.save(simpleCase(tenant))
        val second = PostgresRepositories(fixture.runtimeDataSource)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(pg, second).mapIndexed { index, repository -> executor.submit(Callable {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                runCatching { repository.unitOfWork.write(tenant) {
                    repository.cases.save(original.copy(titleZh = "writer-$index", stateVersion = 1), 0)
                    repository.audits.append(fact(original.id, "writer-$index"))
                } }
            }) }
            check(ready.await(10, TimeUnit.SECONDS)); start.countDown()
            val outcomes = results.map { it.get(15, TimeUnit.SECONDS) }
            assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
            assertThat(outcomes.single { it.isFailure }.exceptionOrNull()).isInstanceOf(OptimisticStateException::class.java)
        } finally { executor.shutdownNow() }
        assertThat(pg.cases.findById(tenant, original.id)!!.stateVersion).isEqualTo(1)
        assertThat(pg.audits.findByCase(tenant, original.id)).hasSize(1)
        assertThat(pg.outbox.pendingCount(tenant)).isEqualTo(1)
    }

    @Test
    fun `concurrent action inserts with the same digest reject different actual input`() {
        val value = pg.cases.save(simpleCase(tenant))
        val recommendation = Recommendation("recommendation/race", tenant, value.id, 0,
            FulfillmentActions.REQUEST_STOCK_RECOUNT, mapOf("quantity" to 1), "合成并发测试", emptySet(), at,
            value.domainVersion, value.policyVersion, "deterministic-test")
        pg.recommendations.save(recommendation)
        val action = ActionRequest("action/race", tenant, value.id, recommendation.id, recommendation.actionDefinitionId,
            "1.0.0", recommendation.actionInput, emptySet(), "requester", idempotencyKey = "same-digest-action-race",
            requestFingerprint = "a".repeat(64), expectedCaseStateVersion = 0, boundCaseStateVersion = 0,
            domainVersion = value.domainVersion, policyVersion = value.policyVersion, state = ActionState.AWAITING_APPROVAL,
            stateVersion = 0, createdAt = at, approvalExpiresAt = at.plusSeconds(3600), updatedAt = at)
        // A test-only trigger holds both INSERTs after both repository pre-reads saw no row.
        // Releasing the database lock exercises ON CONFLICT, rather than relying on thread timing.
        fixture.admin { connection -> connection.createStatement().use { statement ->
            statement.execute("create function integration_pause_action() returns trigger language plpgsql as 'begin perform pg_advisory_xact_lock(827341199); return NEW; end;'")
            statement.execute("create trigger integration_pause_action before insert on action_request for each row execute function integration_pause_action()")
        } }
        val executor = Executors.newFixedThreadPool(2)
        fixture.admin { lockConnection -> lockConnection.createStatement().use { lock ->
            lock.execute("select pg_advisory_lock(827341199)")
            try {
                val repositories = listOf(PostgresRepositories(fixture.runtimeDataSource, queryTimeoutSeconds = 15),
                    PostgresRepositories(fixture.runtimeDataSource, queryTimeoutSeconds = 15))
                val results = repositories.mapIndexed { index, repository -> executor.submit(Callable {
                    runCatching { repository.actions.save(action.copy(input = mapOf("quantity" to index + 1))) }
                }) }
                await().atMost(Duration.ofSeconds(8)).until {
                    lock.executeQuery("select count(*) from pg_locks where locktype='advisory' and not granted and database=(select oid from pg_database where datname=current_database())").use {
                        it.next(); it.getLong(1) == 2L
                    }
                }
                lock.execute("select pg_advisory_unlock(827341199)")
                val outcomes = results.map { it.get(20, TimeUnit.SECONDS) }
                assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
                assertThat(outcomes.single { it.isFailure }.exceptionOrNull()).isInstanceOf(IdempotencyConflictException::class.java)
                val winner = outcomes.single { it.isSuccess }.getOrThrow()
                assertThat(pg.actions.findById(tenant, action.id)).isEqualTo(winner)
                assertThat(pg.actions.findByIdempotencyKey(tenant, action.idempotencyKey)).isEqualTo(winner)
            } finally {
                lock.execute("select pg_advisory_unlock(827341199)")
                executor.shutdownNow()
            }
        } }
    }

    @Test
    fun `outer transaction rollback removes aggregate children audit and outbox together`() {
        val value = richCase(tenant)
        assertThatThrownBy { pg.unitOfWork.write(tenant) {
            pg.cases.save(value)
            pg.audits.append(fact(value.id))
            throw IllegalStateException("synthetic failure after writes")
        } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(pg.cases.findById(tenant, value.id)).isNull()
        assertThat(pg.audits.findByCase(tenant, value.id)).isEmpty()
        assertThat(pg.outbox.pendingCount(tenant)).isZero()
        fixture.admin { connection -> connection.createStatement().use { statement ->
            listOf("claim", "evidence_ref", "case_claim_link").forEach { table ->
                statement.executeQuery("select count(*) from $table").use { row -> row.next(); assertThat(row.getLong(1)).isZero() }
            }
        } }
    }

    @Test
    fun `read writes and caught cross tenant nesting poison the complete transaction`() {
        assertThatThrownBy { pg.unitOfWork.read(tenant) { pg.cases.save(simpleCase(tenant)) } }.isInstanceOf(BusinessValidationException::class.java)
        assertThatThrownBy { pg.cases.lockById(tenant, "missing") }.isInstanceOf(BusinessValidationException::class.java)
        assertThatThrownBy { pg.unitOfWork.write(tenant) {
            pg.cases.save(simpleCase(tenant))
            runCatching { pg.unitOfWork.write(otherTenant) { pg.cases.save(simpleCase(otherTenant)) } }
        } }.isInstanceOf(BusinessValidationException::class.java)
        assertThat(pg.cases.findAll(tenant)).isEmpty()
        assertThat(pg.cases.findAll(otherTenant)).isEmpty()
        assertThat(pg.unitOfWork.activeTransactionCount).isZero()
    }

    @Test
    fun `immutable evidence rejects same id with changed hash and rolls back case version`() {
        val initial = pg.cases.save(richCase(tenant))
        val claim = initial.claims.single()
        val poisoned = claim.copy(evidenceRefs = listOf(claim.evidenceRefs.single().copy(contentHash = "b".repeat(64))))
        assertThatThrownBy { pg.cases.save(initial.copy(stateVersion = 1, claims = listOf(poisoned)), 0) }.isInstanceOf(BusinessValidationException::class.java)
        assertThat(pg.cases.findById(tenant, initial.id)).isEqualTo(initial)
    }

    @Test
    fun `same claim correction keeps old case revision and action evidence immutable`() {
        val initial = pg.cases.save(richCase(tenant))
        val originalClaim = initial.claims.single()
        val evidenceId = originalClaim.evidenceRefs.single().id
        val recommendation = Recommendation("recommendation/001", tenant, initial.id, 0,
            FulfillmentActions.REQUEST_STOCK_RECOUNT, mapOf("caseId" to initial.id), "合成建议", setOf(evidenceId), at,
            initial.domainVersion, initial.policyVersion, "deterministic-test")
        pg.recommendations.save(recommendation)
        val action = ActionRequest("action/001", tenant, initial.id, recommendation.id, recommendation.actionDefinitionId, "1.0.0",
            recommendation.actionInput, setOf(evidenceId), "requester", idempotencyKey = "claim-history-action",
            requestFingerprint = "a".repeat(64), expectedCaseStateVersion = 0, boundCaseStateVersion = 0,
            domainVersion = initial.domainVersion, policyVersion = initial.policyVersion, state = ActionState.AWAITING_APPROVAL,
            stateVersion = 0, createdAt = at, approvalExpiresAt = at.plusSeconds(3600), updatedAt = at)
        pg.actions.save(action)
        val later = at.plusSeconds(60)
        val corrected = originalClaim.copy(value = "corrected", status = ClaimStatus.REVOKED, validTo = later, recordedAt = later,
            evidenceRefs = listOf(originalClaim.evidenceRefs.single().copy(id = "evidence/002", contentHash = "b".repeat(64), observedAt = later, recordedAt = later)))
        pg.cases.save(initial.copy(stateVersion = 1, updatedAt = later, claims = listOf(corrected)), 0)
        assertThat(pg.cases.findById(tenant, initial.id)!!.claims.single()).isEqualTo(corrected)
        assertThat(pg.actions.findById(tenant, action.id)!!.evidenceIds).containsExactly(evidenceId)
        fixture.admin { connection -> connection.prepareStatement("""select r.value_json::text,r.status,r.valid_to,e.business_id,e.content_hash
            from case_claim_link l join claim_revision r on r.tenant_id=l.tenant_id and r.claim_id=l.claim_id and r.revision=l.claim_revision
            join claim_revision_evidence_link re on re.tenant_id=r.tenant_id and re.claim_id=r.claim_id and re.revision=r.revision
            join evidence_ref e on e.tenant_id=re.tenant_id and e.id=re.evidence_id where l.tenant_id=? and l.case_state_version=0""").use { statement ->
            statement.setString(1, tenant)
            statement.executeQuery().use { row ->
                assertThat(row.next()).isTrue()
                assertThat(row.getString(1)).isEqualTo("\"pending\"")
                assertThat(row.getString(2)).isEqualTo("ASSERTED")
                assertThat(row.getObject(3)).isNull()
                assertThat(row.getString(4)).isEqualTo(evidenceId)
                assertThat(row.getString(5).trim()).isEqualTo("a".repeat(64))
            }
        } }
        fixture.runtimeDataSource.connection.use { connection ->
            connection.autoCommit = false; bindTenant(connection, tenant)
            val immutable = org.junit.jupiter.api.Assertions.assertThrows(SQLException::class.java) { connection.createStatement().use { it.executeUpdate("update claim_revision set status='INFERRED'") } }
            assertThat(immutable.sqlState).isEqualTo("23514")
            connection.rollback()
        }
    }

    @Test
    fun `business string and derived UUID alias cannot overwrite another record`() {
        val original = pg.cases.save(simpleCase(tenant, "abc"))
        val internalId = fixture.admin { connection -> connection.prepareStatement("select id from exception_case where tenant_id=? and business_id='abc'").use { statement ->
            statement.setString(1, tenant); statement.executeQuery().use { row -> row.next(); row.getObject(1, UUID::class.java) }
        } }
        assertThatThrownBy { pg.cases.save(simpleCase(tenant, internalId.toString()).copy(titleZh = "must not replace abc")) }
            .isInstanceOf(BusinessValidationException::class.java)
        assertThat(pg.cases.findById(tenant, "abc")).isEqualTo(original)
        assertThat(pg.cases.findById(tenant, internalId.toString())).isNull()
        assertThat(pg.cases.findAll(tenant)).hasSize(1)
        pg.cases.save(simpleCase(otherTenant, internalId.toString()))
        assertThat(pg.cases.findById(otherTenant, internalId.toString())!!.tenantId).isEqualTo(otherTenant)
    }

    @Test
    fun `command replay reference is atomic with aggregate audit and outbox`() {
        val value = simpleCase(tenant)
        val command = StoredCommand(tenant, "durable-command-key", "case.create", "v2:" + "a".repeat(64), "b".repeat(64), "case", value.id, 0, at, at.plusSeconds(3600))
        assertThatThrownBy { pg.commands.complete(command) }.isInstanceOf(BusinessValidationException::class.java)
        assertThatThrownBy { pg.unitOfWork.write(tenant) {
            pg.cases.save(value); pg.audits.append(fact(value.id)); pg.commands.complete(command)
            throw IllegalStateException("synthetic transaction abort")
        } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(pg.commands.find(tenant, command.idempotencyKey)).isNull()
        assertThat(pg.cases.findById(tenant, value.id)).isNull()
        assertThat(pg.outbox.pendingCount(tenant)).isZero()
        pg.unitOfWork.write(tenant) { pg.cases.save(value); pg.audits.append(fact(value.id)); pg.commands.complete(command) }
        assertThat(pg.commands.find(tenant, command.idempotencyKey)).isEqualTo(command)
        val nullableVersion = command.copy(idempotencyKey = "durable-command-null-version", resultStateVersion = null)
        pg.unitOfWork.write(tenant) { pg.commands.complete(nullableVersion) }
        assertThat(pg.commands.find(tenant, nullableVersion.idempotencyKey)).isEqualTo(nullableVersion)
        pg.unitOfWork.write(tenant) { assertThat(pg.commands.complete(command)).isEqualTo(command) }
        assertThatThrownBy { pg.unitOfWork.write(tenant) { pg.commands.complete(command.copy(requestFingerprint = "a".repeat(64))) } }
            .isInstanceOf(IdempotencyConflictException::class.java)
        assertThatThrownBy { pg.unitOfWork.write(tenant) {
            pg.cases.save(value.copy(stateVersion = 1), 0)
            pg.commands.complete(command.copy(requestFingerprint = "v2:" + "c".repeat(64)))
        } }.isInstanceOf(IdempotencyConflictException::class.java)
        assertThat(pg.cases.findById(tenant, value.id)!!.stateVersion).isZero()
        assertThat(pg.outbox.pendingCount(tenant)).isEqualTo(1)
        assertThat(pg.commands.find(otherTenant, command.idempotencyKey)).isNull()
    }

    @Test
    fun `approval survives pool reconstruction and complete outcome survives another reconstruction`() {
        val clock = MutableTestClock(at)
        val simulator = DeterministicFulfillmentSimulator(clock)
        val value = simulator.generateCases(32, tenant).first { it.scenarioKinds == setOf("SUPPLIER_DELAY") }
        pg.cases.save(value)
        val calls = AtomicInteger()
        val connector = checkedConnector(simulator, calls)
        var kernel = kernel(connector, clock)
        val recommendation = kernel.recommend(requester(), value.id)
        val command = CreateActionCommand(recommendation.id, value.stateVersion, "durable-approval-key")
        val pending = kernel.createAction(requester(), command)
        assertThat(pending.state).isEqualTo(ActionState.AWAITING_APPROVAL)
        assertThat(pg.actions.save(pending.copy(input = pending.input + ("quantity" to (pending.input.getValue("quantity") as Number).toLong()),
            createdAt = pending.createdAt.plusNanos(321), updatedAt = pending.updatedAt.plusNanos(321), approvalExpiresAt = pending.approvalExpiresAt.plusNanos(321)))).isEqualTo(pending)
        assertThatThrownBy { kernel.execute(approver(), pending.id, pending.stateVersion) }.isInstanceOf(RuntimeException::class.java)
        assertThat(calls.get()).isZero()
        fixture.runtimeDataSource.close()
        fixture.dataSource().use { rebuiltPool ->
            pg = PostgresRepositories(rebuiltPool)
            kernel = kernel(connector, clock)
            assertThat(kernel.createAction(requester(), command).id).isEqualTo(pending.id)
            var action = kernel.getAction(requester(), pending.id)
            assertThat(action.state).isEqualTo(ActionState.AWAITING_APPROVAL)
            action = kernel.approve(approver(), action.id, action.stateVersion)
            val execution = kernel.execute(approver(), action.id, action.stateVersion)
            action = execution.request
            assertThatThrownBy { pg.receipts.save(execution.receipt.copy(externalRequestId = "changed-request")) }.isInstanceOf(BusinessValidationException::class.java)
            assertThatThrownBy { pg.receipts.save(execution.receipt.copy(createdAt = execution.receipt.createdAt.minusSeconds(1))) }.isInstanceOf(BusinessValidationException::class.java)
            assertThatThrownBy { pg.receipts.save(execution.receipt.copy(attempts = emptyList())) }.isInstanceOf(BusinessValidationException::class.java)
            assertThatThrownBy { pg.receipts.save(execution.receipt.copy(attempts = execution.receipt.attempts.map { it.copy(messageZh = "rewritten history") })) }.isInstanceOf(BusinessValidationException::class.java)
            assertThat(pg.receipts.findByActionRequestId(tenant, action.id)).isEqualTo(execution.receipt)
            fixture.dataSource().use { rawPool -> rawPool.connection.use { connection ->
                connection.autoCommit = false
                listOf("delete from receipt_attempt", "update receipt_attempt set message_zh='rewritten history'").forEach { sql ->
                    bindTenant(connection, tenant)
                    val immutable = org.junit.jupiter.api.Assertions.assertThrows(SQLException::class.java) { connection.createStatement().use { it.executeUpdate(sql) } }
                    assertThat(immutable.sqlState).isEqualTo("23514")
                    connection.rollback()
                }
            } }
            val verified = kernel.verify(approver(), action.id, action.stateVersion, kernel.getCase(approver(), value.id).stateVersion)
            assertThat(verified.request.state).isEqualTo(ActionState.VERIFIED_SUCCEEDED)
            val canonicalReceipt = requireNotNull(pg.receipts.findByActionRequestId(tenant, action.id))
            val wrongPurpose = pg.verifications.save(verified.verification.copy(id = "verification/wrong-purpose", purpose = VerificationPurpose.COMPENSATION))
            val otherAction = pg.actions.save(pending.copy(id = "action/other", idempotencyKey = "other-verification-key"))
            val foreignVerification = pg.verifications.save(verified.verification.copy(id = "verification/other-action", actionRequestId = otherAction.id))
            assertThatThrownBy { pg.receipts.save(canonicalReceipt.copy(verificationId = wrongPurpose.id)) }.isInstanceOf(BusinessValidationException::class.java)
            assertThatThrownBy { pg.receipts.save(canonicalReceipt.copy(verificationId = foreignVerification.id)) }.isInstanceOf(BusinessValidationException::class.java)
            assertThatThrownBy { pg.receipts.save(canonicalReceipt.copy(compensationVerificationId = verified.verification.id)) }.isInstanceOf(BusinessValidationException::class.java)
            assertThat(pg.receipts.findByActionRequestId(tenant, action.id)).isEqualTo(canonicalReceipt)
            val memory = requireNotNull(verified.memoryCandidate)
            val nanos = at.plusNanos(123456789)
            val nanoMemory = memory.copy(id = "memory/nanos", validFrom = nanos, expiresAt = nanos.plusSeconds(3600), createdAt = nanos, updatedAt = nanos)
            val savedNanoMemory = pg.memories.save(nanoMemory)
            assertThat(pg.memories.save(nanoMemory)).isEqualTo(savedNanoMemory)
            kernel.reviewMemory(Actor("owner", "负责人", tenant, setOf(BusinessRole.SALES_OWNER)), memory.id, MemoryReviewDecision.APPROVE, memory.stateVersion)
        }
        fixture.dataSource().use { lastPool ->
            pg = PostgresRepositories(lastPool)
            assertThat(pg.actions.findById(tenant, pending.id)!!.state).isEqualTo(ActionState.VERIFIED_SUCCEEDED)
            assertThat(pg.receipts.findByActionRequestId(tenant, pending.id)!!.verificationId).isNotBlank()
            assertThat(pg.verifications.findByActionRequestId(tenant, pending.id)!!.outcome).isEqualTo(VerificationOutcome.PASSED)
            assertThat(pg.memories.findAll(tenant).filter { it.status == MemoryStatus.APPROVED }).hasSize(1)
            assertThat(pg.recommendations.findById(tenant, recommendation.id)).isEqualTo(recommendation)
            assertThat(pg.audits.findByCase(tenant, value.id)).isNotEmpty()
        }
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `ambiguous external timeout survives reconstruction without repeating the write`() {
        val clock = MutableTestClock(at)
        val simulator = DeterministicFulfillmentSimulator(clock)
        val value = simulator.generateCases(32, tenant).first { it.scenarioKinds == setOf("INVENTORY_MISMATCH", "AMBIGUOUS_TIMEOUT") }
        pg.cases.save(value)
        val calls = AtomicInteger(); val connector = checkedConnector(simulator, calls)
        var kernel = kernel(connector, clock)
        val recommendation = kernel.recommend(requester(), value.id)
        var action = kernel.createAction(requester(), CreateActionCommand(recommendation.id, value.stateVersion, "ambiguous-durable-key"))
        action = kernel.approve(approver(), action.id, action.stateVersion)
        val first = kernel.execute(approver(), action.id, action.stateVersion)
        assertThat(first.request.state).isEqualTo(ActionState.UNKNOWN_REQUIRES_RECONCILIATION)
        fixture.runtimeDataSource.close()
        fixture.dataSource().use { rebuilt ->
            pg = PostgresRepositories(rebuilt); kernel = kernel(connector, clock)
            val replay = kernel.execute(approver(), action.id, first.request.stateVersion)
            assertThat(replay.receipt).isEqualTo(first.receipt)
            assertThat(replay.receipt.attempts).hasSize(1)
            assertThat(calls.get()).isEqualTo(1)
            val verified = kernel.verify(approver(), action.id, replay.request.stateVersion, kernel.getCase(approver(), value.id).stateVersion)
            assertThat(verified.request.state).isEqualTo(ActionState.VERIFIED_SUCCEEDED)
        }
    }

    @Test
    fun `expired executing lease with no receipt reconciles without calling connector`() {
        val clock = MutableTestClock(at)
        val simulator = DeterministicFulfillmentSimulator(clock)
        val value = simulator.generateCases(32, tenant).first { it.scenarioKinds == setOf("SUPPLIER_DELAY") }
        pg.cases.save(value)
        val calls = AtomicInteger(); val connector = checkedConnector(simulator, calls)
        var kernel = kernel(connector, clock)
        val recommendation = kernel.recommend(requester(), value.id)
        var action = kernel.createAction(requester(), CreateActionCommand(recommendation.id, value.stateVersion, "executing-crash-key"))
        action = kernel.approve(approver(), action.id, action.stateVersion)
        val executing = ActionStateMachine.transition(action, ActionState.EXECUTING, clock.instant())
        pg.actions.save(executing, action.stateVersion)
        clock.advance(Duration.ofMinutes(10))
        fixture.runtimeDataSource.close()
        fixture.dataSource().use { rebuilt ->
            pg = PostgresRepositories(rebuilt); kernel = kernel(connector, clock)
            val recovered = kernel.execute(approver(), action.id, executing.stateVersion)
            assertThat(recovered.request.state).isEqualTo(ActionState.UNKNOWN_REQUIRES_RECONCILIATION)
            assertThat(recovered.receipt.outcome).isEqualTo(ReceiptOutcome.UNKNOWN)
            assertThat(calls.get()).isZero()
        }
    }

    @Test
    fun `outbox concurrent claims tenant isolation and token acknowledgement are durable`() {
        pg.audits.append(fact("outbox-case"))
        val second = PostgresRepositories(fixture.runtimeDataSource)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val claimed = try {
            val futures = listOf(pg, second).mapIndexed { index, repository -> executor.submit(Callable {
                check(start.await(10, TimeUnit.SECONDS))
                repository.outbox.claimBatch(tenant, "worker-$index", 10)
            }) }
            start.countDown(); futures.flatMap { it.get(15, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        assertThat(claimed).hasSize(1)
        val event = claimed.single()
        assertThat(pg.outbox.claimBatch(otherTenant, "foreign-worker")).isEmpty()
        assertThat(pg.outbox.markPublished(otherTenant, event.eventId, event.leaseToken)).isFalse()
        assertThat(pg.outbox.markPublished(tenant, event.eventId, UUID.randomUUID().toString())).isFalse()
        assertThat(pg.outbox.markPublished(tenant, event.eventId, event.leaseToken)).isTrue()
        assertThat(pg.outbox.markPublished(tenant, event.eventId, event.leaseToken)).isFalse()
        assertThat(pg.outbox.pendingCount(tenant)).isZero()
    }

    @Test
    fun `expired outbox lease can be reclaimed while stale token cannot publish`() {
        pg.audits.append(fact("lease-case"))
        val first = pg.outbox.claimBatch(tenant, "worker-old", 1, Duration.ofSeconds(1)).single()
        await().atMost(Duration.ofSeconds(10)).until { fixture.admin { connection ->
            connection.prepareStatement("select clock_timestamp() > ?").use { statement ->
                statement.setObject(1, first.leaseUntil.atOffset(ZoneOffset.UTC))
                statement.executeQuery().use { row -> row.next(); row.getBoolean(1) }
            }
        } }
        val next = pg.outbox.claimBatch(tenant, "worker-new", 1).single()
        assertThat(next.eventId).isEqualTo(first.eventId)
        assertThat(next.leaseToken).isNotEqualTo(first.leaseToken)
        assertThat(next.attempts).isEqualTo(2)
        assertThat(pg.outbox.markPublished(tenant, first.eventId, first.leaseToken)).isFalse()
        assertThat(pg.outbox.markPublished(tenant, next.eventId, next.leaseToken)).isTrue()
    }

    @Test
    fun `committed aggregate and outbox survive actual database process crash and recovery`() {
        // Flush unrelated fixtures first. The case/outbox written below are
        // committed after this checkpoint and must survive the immediate stop.
        fixture.admin { connection -> connection.createStatement().use { it.execute("checkpoint") } }
        val value = richCase(tenant)
        pg.unitOfWork.write(tenant) { pg.cases.save(value); pg.audits.append(fact(value.id)) }
        fixture.runtimeDataSource.close()
        PostgresIntegrationDatabase.crashAndRestart()
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(300)).until {
            runCatching { PostgresIntegrationDatabase.admin { connection -> connection.isValid(2) } }.getOrDefault(false)
        }
        fixture.dataSource().use { rebuilt ->
            pg = PostgresRepositories(rebuilt)
            assertThat(pg.cases.findById(tenant, value.id)).isEqualTo(value)
            assertThat(pg.outbox.pendingCount(tenant)).isEqualTo(1)
            assertThat(pg.audits.findByCase(tenant, value.id)).hasSize(1)
        }
    }

    private fun simpleCase(tenantId: String, id: String = "case/业务:001") = ExceptionCase(id, tenantId, "order/001", "合成案件", CasePriority.HIGH,
        CaseStatus.OPEN, setOf("SUPPLIER_DELAY"), emptyList(), emptyList(), 0, "domain-1", "policy-1", at, at)

    private fun richCase(tenantId: String): ExceptionCase {
        val evidence = EvidenceRef("evidence/001", tenantId, "ERP", "row/001", "a".repeat(64), at)
        val claim = Claim("claim/001", tenantId, "Order", "order/001", "status", "pending", listOf(evidence), at,
            recordedAt = at, confidence = 0.94999)
        return simpleCase(tenantId).copy(claims = listOf(claim))
    }

    private fun fact(id: String, actor: String = "operator") = AuditFact(tenant, "case", id, "case.updated", actor, at, mapOf("caseId" to id))
    private fun requester() = Actor("requester", "订单专员", tenant, setOf(BusinessRole.ORDER_SPECIALIST))
    private fun approver() = Actor("approver", "仓库审批人", tenant, setOf(BusinessRole.WAREHOUSE_APPROVER))
    private fun bindTenant(connection: Connection, tenantId: String) = connection.prepareStatement("select set_config('app.tenant_id',?,true)").use { it.setString(1, tenantId); it.execute() }
    private fun countCases(connection: Connection): Long = connection.createStatement().use { it.executeQuery("select count(*) from exception_case").use { row -> row.next(); row.getLong(1) } }

    private fun noTransaction() {
        assertThat(pg.unitOfWork.transactionActiveOnCurrentThread).describedAs("external calls cannot hold a transaction on this thread").isFalse()
        assertThat(pg.unitOfWork.activeTransactionCount).describedAs("external calls cannot hold a transaction on another worker thread").isZero()
    }

    private fun checkedConnector(simulator: ConnectorPort, calls: AtomicInteger): ConnectorPort = object : ConnectorPort by simulator {
        override fun execute(definition: ActionDefinition, request: ActionRequest): ConnectorExecutionResult { noTransaction(); calls.incrementAndGet(); return simulator.execute(definition, request) }
        override fun verify(definition: ActionDefinition, request: ActionRequest, receipt: ActionReceipt): VerificationResult { noTransaction(); return simulator.verify(definition, request, receipt) }
        override fun compensate(definition: ActionDefinition, request: ActionRequest, receipt: ActionReceipt): ActionReceipt { noTransaction(); return simulator.compensate(definition, request, receipt) }
        override fun verifyCompensation(definition: ActionDefinition, request: ActionRequest, receipt: ActionReceipt): VerificationResult { noTransaction(); return simulator.verifyCompensation(definition, request, receipt) }
    }

    private fun kernel(connector: ConnectorPort, clock: Clock): FoundationKernel {
        val baseModel = DeterministicRecommendationEngine(clock)
        val model = object : ModelPort {
            override val modelVersion = baseModel.modelVersion
            override fun recommend(case: ExceptionCase, definitions: Collection<ActionDefinition>): Recommendation { noTransaction(); return baseModel.recommend(case, definitions) }
        }
        val policy = object : PolicyDecisionPort {
            override fun evaluate(actor: Actor, case: ExceptionCase, definition: ActionDefinition, input: Map<String, Any>): PolicyDecision { noTransaction(); return DeterministicApprovalPolicy().evaluate(actor, case, definition, input) }
        }
        val dataAccess = object : DataAccessDecisionPort {
            override fun canRead(actor: Actor, resourceTenantId: String, aclRef: String, classification: DataClassification): Boolean { noTransaction(); return DeterministicDataAccessPolicy().canRead(actor, resourceTenantId, aclRef, classification) }
        }
        return FoundationKernel(pg.cases, pg.recommendations, pg.actions, pg.receipts, pg.verifications, pg.memories, pg.audits,
            connector, model, policy, dataAccess, clock, unitOfWork = pg.unitOfWork)
    }

    private class MutableTestClock(initial: Instant) : Clock() {
        private val value = AtomicReference(initial)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(value.get(), zone)
        override fun instant(): Instant = value.get()
        fun advance(duration: Duration) { value.updateAndGet { it.plus(duration) } }
    }

    companion object {
        @JvmStatic @BeforeAll fun startRealPostgres() = PostgresIntegrationDatabase.start()
        @JvmStatic @AfterAll fun stopRealPostgres() = PostgresIntegrationDatabase.stop()
    }
}

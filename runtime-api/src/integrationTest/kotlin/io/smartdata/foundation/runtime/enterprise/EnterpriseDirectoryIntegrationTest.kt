package io.smartdata.foundation.runtime.enterprise

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.runtime.persistence.PostgresFixture
import io.smartdata.foundation.runtime.persistence.PostgresIntegrationDatabase
import io.smartdata.foundation.runtime.postgres.PostgresRepositories
import io.smartdata.foundation.runtime.security.enterprise.*
import io.smartdata.foundation.service.AuthorizationDeniedException
import io.smartdata.foundation.service.BusinessValidationException
import io.smartdata.foundation.service.IdempotencyConflictException
import io.smartdata.foundation.service.OptimisticStateException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** DAO/RLS/locking checks use real PG18; actual OIDC is separately proven over TLS. */
class EnterpriseDirectoryIntegrationTest {
    private lateinit var fixture:PostgresFixture
    private lateinit var pg:PostgresRepositories
    private lateinit var directory:EnterpriseDirectoryService
    private lateinit var clock:DirectoryClock
    private lateinit var properties:EnterpriseProperties
    private val tenant="enterprise-directory-test"
    private val owner=EnterpriseAccounts.identity("https://identity.example.test","owner","负责人")
    private val otherOwner=EnterpriseAccounts.identity("https://identity.example.test","other-owner","第二负责人")
    private val worker=EnterpriseAccounts.identity("https://identity.example.test","worker","同事")
    private val outsider=EnterpriseAccounts.identity("https://identity.example.test","outsider","其他账号")

    @BeforeEach fun setup() {
        fixture=PostgresIntegrationDatabase.fixture();fixture.seedTenant(tenant);fixture.seedTenant("another-enterprise")
        pg=PostgresRepositories(fixture.runtimeDataSource)
        clock=DirectoryClock(Instant.parse("2026-10-25T12:00:00Z"))
        properties=EnterpriseProperties(true,tenant,"合成企业","https://identity.example.test","enterprise-console","smartdata-runtime","https://console.example.test",listOf(owner.accountId,otherOwner.accountId))
        directory=EnterpriseDirectoryService(pg,properties,clock)
        listOf(owner,otherOwner,worker,outsider).forEach(directory::recordLogin)
    }
    @AfterEach fun close()=fixture.close()

    @Test fun `only exact configured owners and exact invited accounts acquire membership`() {
        val admin=directory.actor(owner)
        assertThat(directory.me(worker,null).authenticated).isTrue()
        assertThat(directory.me(worker,null).capabilities.canUseWorkspace).isFalse()
        assertThatThrownBy { directory.actor(worker) }.isInstanceOf(AuthorizationDeniedException::class.java)
        val view=directory.directory(admin)
        val invited=directory.invite(admin,"invite-worker-0001",EnterpriseInviteCommand(view.stateVersion,worker.accountId,setOf(BusinessRole.ORDER_SPECIALIST)))
        val invitation=invited.invitations.single()
        assertThatThrownBy { directory.accept(outsider,"accept-wrong-0001",invitation.id,EnterpriseVersionCommand(invitation.stateVersion)) }.isInstanceOf(AuthorizationDeniedException::class.java)
        directory.accept(worker,"accept-worker-0001",invitation.id,EnterpriseVersionCommand(invitation.stateVersion))
        assertThat(directory.actor(worker).roles).containsExactly(BusinessRole.ORDER_SPECIALIST)
        assertThat(directory.me(worker,null).capabilities.canManageMembers).isFalse()
        assertThatThrownBy { directory.invite(directory.actor(worker),"self-admin-000001",EnterpriseInviteCommand(invited.stateVersion,outsider.accountId,setOf(BusinessRole.ADMIN))) }.isInstanceOf(AuthorizationDeniedException::class.java)
        val forgedTenant=admin.copy(tenantId="another-enterprise")
        assertThatThrownBy { directory.checkCurrent(forgedTenant,false) }.isInstanceOf(AuthorizationDeniedException::class.java)
        val pending=directory.invite(admin,"tenant-stop-invite",EnterpriseInviteCommand(directory.directory(admin).stateVersion,outsider.accountId,setOf(BusinessRole.AUDITOR))).invitations.single { it.accountId==outsider.accountId }
        fixture.admin { connection -> connection.prepareStatement("update tenant set status='SUSPENDED' where id=?").use { it.setString(1,tenant);it.executeUpdate() } }
        assertThat(directory.me(owner,null).roles).isEmpty()
        assertThat(directory.me(owner,null).capabilities.canUseWorkspace).isFalse()
        assertThat(directory.me(owner,null).capabilities.canManageMembers).isFalse()
        assertThat(directory.me(outsider,null).invitations).isEmpty()
        assertThatThrownBy { directory.actor(owner) }.isInstanceOf(AuthorizationDeniedException::class.java)
        assertThatThrownBy { directory.checkCurrent(admin,false) }.isInstanceOf(AuthorizationDeniedException::class.java)
        assertThatThrownBy { directory.accept(outsider,"stopped-tenant-accept",pending.id,EnterpriseVersionCommand(pending.stateVersion)) }.isInstanceOf(AuthorizationDeniedException::class.java)
    }

    @Test fun `directory CAS command dedup and immutable journal commit atomically`() {
        val admin=directory.actor(owner)
        val initial=directory.directory(admin)
        val command=EnterpriseInviteCommand(initial.stateVersion,worker.accountId,setOf(BusinessRole.AUDITOR))
        val created=directory.invite(admin,"dedup-invite-0001",command)
        assertThat(directory.invite(admin,"dedup-invite-0001",command)).isEqualTo(created)
        assertThatThrownBy { directory.invite(admin,"dedup-invite-0001",command.copy(roles=setOf(BusinessRole.ADMIN))) }.isInstanceOf(IdempotencyConflictException::class.java)
        assertThatThrownBy { directory.invite(admin,"stale-invite-0001",EnterpriseInviteCommand(initial.stateVersion,outsider.accountId,setOf(BusinessRole.AUDITOR))) }.isInstanceOf(OptimisticStateException::class.java)
        assertThat(directory.directory(admin)).isEqualTo(created)
        fixture.runtimeDataSource.connection.use { connection ->
            connection.autoCommit=false
            connection.prepareStatement("select set_config('app.tenant_id',?,true)").use { it.setString(1,tenant);it.execute() }
            assertThatThrownBy { connection.createStatement().executeUpdate("delete from enterprise_command") }.isInstanceOf(SQLException::class.java)
            connection.rollback()
        }
        assertThat(directory.directory(admin).stateVersion).isEqualTo(created.stateVersion)
    }

    @Test fun `suspension invalidates old grants invitations and bootstrap cannot resurrect a member`() {
        val admin=directory.actor(owner)
        val oldOther=directory.actor(otherOwner)
        val invited=directory.invite(oldOther,"second-owner-invite",EnterpriseInviteCommand(directory.directory(admin).stateVersion,worker.accountId,setOf(BusinessRole.SALES_OWNER)))
        val invitation=invited.invitations.single()
        val suspended=directory.changeStatus(admin,"suspend-owner-0001",otherOwner.accountId,EnterpriseMemberStateCommand(invited.stateVersion,requireNotNull(oldOther.authorizationVersion)),false)
        directory.recordLogin(otherOwner)
        assertThat(directory.me(otherOwner,null).membership!!.status).isEqualTo(EnterpriseMemberStatus.SUSPENDED)
        assertThatThrownBy { directory.actor(otherOwner) }.isInstanceOf(AuthorizationDeniedException::class.java)
        assertThatThrownBy { directory.accept(worker,"revoked-invite-accept",invitation.id,EnterpriseVersionCommand(invitation.stateVersion)) }.isInstanceOf(OptimisticStateException::class.java)
        val target=suspended.members.single { it.accountId==otherOwner.accountId }
        directory.changeStatus(admin,"activate-owner-0001",otherOwner.accountId,EnterpriseMemberStateCommand(suspended.stateVersion,target.stateVersion),true)
        assertThat(directory.actor(otherOwner).authorizationVersion).isGreaterThan(requireNotNull(oldOther.authorizationVersion))
        assertThatThrownBy { pg.unitOfWork.write(tenant) { directory.checkCurrent(oldOther,true) } }.isInstanceOf(AuthorizationDeniedException::class.java)
        assertThat(directory.me(worker,null).invitations).isEmpty()
        val activeOther=directory.actor(otherOwner)
        directory.changeRoles(admin,"role-audit-000001",otherOwner.accountId,EnterpriseMemberRolesCommand(directory.directory(admin).stateVersion,requireNotNull(activeOther.authorizationVersion),setOf(BusinessRole.AUDITOR)))
        val fact=pg.audits.findByAggregate(tenant,otherOwner.accountId).single { it.eventType=="enterprise.member.roles_changed" }
        assertThat(fact.actorId).isEqualTo(owner.accountId)
        assertThat(fact.details).containsEntry("rolesBefore","ADMIN").containsEntry("rolesAfter","AUDITOR")
            .containsEntry("statusBefore","ACTIVE").containsEntry("statusAfter","ACTIVE")
            .containsEntry("membershipVersionBefore",requireNotNull(activeOther.authorizationVersion).toString())
            .containsEntry("membershipVersionAfter",(requireNotNull(activeOther.authorizationVersion)+1).toString())
        assertThatThrownBy { directory.changeRoles(admin,"self-demote-0001",owner.accountId,EnterpriseMemberRolesCommand(directory.directory(admin).stateVersion,requireNotNull(admin.authorizationVersion),setOf(BusinessRole.AUDITOR))) }.isInstanceOf(BusinessValidationException::class.java)
    }

    @Test fun `expired invitations cannot grant and a fresh invitation has its own immutable intent`() {
        val admin=directory.actor(owner)
        val before=directory.directory(admin)
        val invited=directory.invite(admin,"expiring-invite-0001",EnterpriseInviteCommand(before.stateVersion,worker.accountId,setOf(BusinessRole.ORDER_SPECIALIST)))
        val invitation=invited.invitations.single()
        clock.advanceSeconds(8*86400L)
        assertThat(directory.me(worker,null).invitations).isEmpty()
        assertThatThrownBy { directory.accept(worker,"expired-invite-accept",invitation.id,EnterpriseVersionCommand(invitation.stateVersion)) }.isInstanceOf(BusinessValidationException::class.java)
        val fresh=directory.invite(admin,"fresh-invite-000001",EnterpriseInviteCommand(invited.stateVersion,worker.accountId,setOf(BusinessRole.AUDITOR)))
        assertThat(fresh.invitations).hasSize(2)
        assertThat(fresh.invitations.count { it.status==EnterpriseInvitationStatus.EXPIRED }).isEqualTo(1)
        val pending=fresh.invitations.single { it.status==EnterpriseInvitationStatus.PENDING }
        directory.accept(worker,"fresh-invite-accept",pending.id,EnterpriseVersionCommand(pending.stateVersion))
        assertThat(directory.actor(worker).roles).containsExactly(BusinessRole.AUDITOR)
    }

    @Test fun `member share lock linearizes an authorized commit against concurrent revocation`() {
        val admin=directory.actor(owner)
        val invited=directory.invite(admin,"race-worker-invite",EnterpriseInviteCommand(directory.directory(admin).stateVersion,worker.accountId,setOf(BusinessRole.ORDER_SPECIALIST)))
        val invitation=invited.invitations.single()
        directory.accept(worker,"race-worker-accept",invitation.id,EnterpriseVersionCommand(invitation.stateVersion))
        val oldActor=directory.actor(worker)
        val before=directory.directory(admin)
        val locked=CountDownLatch(1);val release=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        try {
            val writing=pool.submit<Boolean> {
                pg.unitOfWork.write(tenant) {
                    directory.checkCurrent(oldActor,true);locked.countDown()
                    check(release.await(5,TimeUnit.SECONDS));true
                }
            }
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue()
            val other=EnterpriseDirectoryService(PostgresRepositories(fixture.runtimeDataSource),properties,clock)
            val revoking=pool.submit<EnterpriseDirectoryView> { other.changeStatus(admin,"race-worker-suspend",worker.accountId,EnterpriseMemberStateCommand(before.stateVersion,requireNotNull(oldActor.authorizationVersion)),false) }
            Thread.sleep(150)
            assertThat(revoking.isDone).isFalse()
            release.countDown()
            assertThat(writing.get(5,TimeUnit.SECONDS)).isTrue()
            revoking.get(5,TimeUnit.SECONDS)
            assertThatThrownBy { pg.unitOfWork.write(tenant) { directory.checkCurrent(oldActor,true) } }.isInstanceOf(AuthorizationDeniedException::class.java)
        } finally { release.countDown();pool.shutdownNow() }
    }

    @Test fun `session and business roles are isolated in both directions including assumable roles`() {
        val sessionRole="session_dao_"+UUID.randomUUID().toString().replace("-","").take(12)
        val password=UUID.randomUUID().toString()
        fixture.admin { connection -> connection.createStatement().use {
            it.execute("create role $sessionRole login nosuperuser nobypassrls nocreatedb nocreaterole password '$password'")
            it.execute("grant usage on schema smartdata_session to $sessionRole")
            it.execute("grant select,insert,update,delete on all tables in schema smartdata_session to $sessionRole")
        } }
        fixture.dataSource(sessionRole,password).use { sessionDataSource ->
            EnterpriseSessionRoleGuard(sessionDataSource,fixture.runtimeDataSource)
            fixture.admin { it.createStatement().execute("grant select on smartdata_session.spring_session to ${fixture.runtimeRole}") }
            assertThatThrownBy { EnterpriseSessionRoleGuard(sessionDataSource,fixture.runtimeDataSource) }.isInstanceOf(IllegalArgumentException::class.java)
            fixture.admin { it.createStatement().execute("revoke select on smartdata_session.spring_session from ${fixture.runtimeRole}") }
            fixture.admin { it.createStatement().execute("grant select on enterprise_account to $sessionRole") }
            assertThatThrownBy { EnterpriseSessionRoleGuard(sessionDataSource,fixture.runtimeDataSource) }.isInstanceOf(IllegalArgumentException::class.java)
            fixture.admin { it.createStatement().execute("revoke select on enterprise_account from $sessionRole") }
            val bridge="session_bridge_"+UUID.randomUUID().toString().replace("-","").take(10)
            fixture.admin { connection -> connection.createStatement().use {
                it.execute("create role $bridge nologin noinherit nosuperuser nobypassrls nocreatedb nocreaterole")
                it.execute("grant select on smartdata_session.spring_session to $bridge")
                it.execute("grant $bridge to ${fixture.runtimeRole} with inherit false, set true")
            } }
            assertThatThrownBy { EnterpriseSessionRoleGuard(sessionDataSource,fixture.runtimeDataSource) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    companion object {
        @JvmStatic @BeforeAll fun start()=PostgresIntegrationDatabase.start()
        @JvmStatic @AfterAll fun stop()=PostgresIntegrationDatabase.stop()
    }
}

private class DirectoryClock(initial:Instant):Clock() {
    private val time=AtomicReference(initial)
    override fun getZone():ZoneId=ZoneOffset.UTC
    override fun withZone(zone:ZoneId):Clock=this
    override fun instant():Instant=time.get()
    fun advanceSeconds(seconds:Long) { time.updateAndGet { it.plusSeconds(seconds) } }
}

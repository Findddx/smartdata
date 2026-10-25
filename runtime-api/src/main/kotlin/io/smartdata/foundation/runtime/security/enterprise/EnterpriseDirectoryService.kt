package io.smartdata.foundation.runtime.security.enterprise

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.AuditFact
import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.runtime.api.commandFingerprint
import io.smartdata.foundation.runtime.postgres.PostgresRepositories
import io.smartdata.foundation.service.AuthorizationDeniedException
import io.smartdata.foundation.service.BusinessValidationException
import io.smartdata.foundation.service.IdempotencyConflictException
import io.smartdata.foundation.service.OptimisticStateException
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Identity state and workspace mutations share the same PostgreSQL transaction boundary. */
class EnterpriseDirectoryService(
    private val repositories: PostgresRepositories,
    val properties: EnterpriseProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val tx = repositories.unitOfWork
    private val tenant get() = properties.tenantId

    fun recordLogin(identity: EnterpriseAccountIdentity) {
        validateIdentity(identity)
        tx.write(tenant) {
            tx.query(tenant, true) { db ->
                if (db.fetchOne("select status from tenant where id=?", tenant)?.get("status", String::class.java) != "ACTIVE") deny()
                db.execute("insert into enterprise_directory(tenant_id,state_version) values(?,0) on conflict do nothing", tenant)
                lockDirectory(db)
                db.execute("insert into enterprise_account(tenant_id,account_id,issuer,subject,display_name,status,created_at,updated_at) values(?,?,?,?,?,'ACTIVE',?::timestamptz,?::timestamptz) on conflict do nothing",
                    tenant, identity.accountId, identity.issuer, identity.subject, identity.displayName, timestamp(), timestamp())
                val stored = db.fetchOne("select * from enterprise_account where tenant_id=? and account_id=?", tenant, identity.accountId) ?: deny()
                if (stored.get("issuer", String::class.java) != identity.issuer || stored.get("subject", String::class.java) != identity.subject || stored.get("status", String::class.java) != "ACTIVE") deny()
                db.execute("update enterprise_account set display_name=?,updated_at=?::timestamptz where tenant_id=? and account_id=?", identity.displayName, timestamp(), tenant, identity.accountId)
                // Explicit deployment whitelist is an initial grant only. Suspended
                // or subsequently changed memberships are never repaired at login.
                if (identity.accountId in properties.bootstrapOwners && member(db, identity.accountId) == null) {
                    db.execute("insert into enterprise_member(tenant_id,account_id,roles,status,state_version,updated_at) values(?,?,?::text[],'ACTIVE',1,?::timestamptz)",
                        tenant, identity.accountId, arrayOf("ADMIN"), timestamp())
                    bump(db)
                    audit(identity.accountId, "enterprise.owner.bootstrapped", identity.accountId, mapOf("source" to "explicit_server_whitelist"))
                }
                audit(identity.accountId, "enterprise.login", identity.accountId)
            }
        }
    }

    fun actor(identity: EnterpriseAccountIdentity): Actor {
        validateIdentity(identity)
        return tx.read(tenant) { tx.query(tenant) { db ->
            requireActiveTenant(db)
            val row = member(db, identity.accountId) ?: deny()
            if (row.get("status", String::class.java) != "ACTIVE" || row.get("account_status", String::class.java) != "ACTIVE") deny()
            Actor(identity.accountId, identity.displayName, tenant, roles(row), row.long("state_version"))
        } }
    }

    /** Called inside the store's transaction. A write holds a member lock until commit. */
    fun checkCurrent(actor: Actor, write: Boolean) {
        if (actor.tenantId != tenant || !EnterpriseProperties.ACCOUNT_ID.matches(actor.id) || actor.authorizationVersion == null) deny()
        if (write) tx.requireWriteTransaction(tenant)
        tx.query(tenant, write) { db ->
            val row = member(db, actor.id, write) ?: deny()
            if (row.get("status", String::class.java) != "ACTIVE" || row.get("account_status", String::class.java) != "ACTIVE" ||
                row.long("state_version") != actor.authorizationVersion || roles(row) != actor.roles) deny()
            requireActiveTenant(db)
        }
    }

    fun me(identity: EnterpriseAccountIdentity?, csrf: EnterpriseCsrfView?): EnterpriseMeView {
        if (identity == null) return EnterpriseMeView(authenticated = false, accountId = null, displayName = null, tenant = null,
            membership = null, roles = emptySet(), capabilities = EnterpriseCapabilities(false, false), logoutUrl = null, csrf = csrf)
        validateIdentity(identity)
        return tx.read(tenant) { tx.query(tenant) { db ->
            val row = member(db, identity.accountId)
            val accountActive = db.fetchOne("select status from enterprise_account where tenant_id=? and account_id=?",tenant,identity.accountId)?.get("status",String::class.java)=="ACTIVE"
            val tenantActive=db.fetchOne("select status from tenant where id=?",tenant)?.get("status",String::class.java)=="ACTIVE"
            val active = tenantActive && accountActive && row?.get("status",String::class.java)=="ACTIVE"
            val roleSet = if (active) roles(requireNotNull(row)) else emptySet()
            val membership = row?.let { EnterpriseMembershipView(if (active) EnterpriseMemberStatus.ACTIVE else EnterpriseMemberStatus.SUSPENDED, it.long("state_version")) }
            val invitations = if (tenantActive && row == null && accountActive) db.fetch("select * from enterprise_invitation where tenant_id=? and account_id=? and status='PENDING' and expires_at>?::timestamptz order by created_at,id",tenant,identity.accountId,timestamp()).map(::invitation) else emptyList()
            EnterpriseMeView(authenticated=true, accountId=identity.accountId,displayName=identity.displayName,
                tenant=if(row!=null) EnterpriseTenantView(tenant,properties.tenantDisplayName) else null,membership=membership,
                roles=roleSet,capabilities=EnterpriseCapabilities(active,active && BusinessRole.ADMIN in roleSet),csrf=csrf,invitations=invitations)
        } }
    }

    fun directory(actor: Actor): EnterpriseDirectoryView = tx.read(tenant) {
        checkCurrent(actor,false)
        if (BusinessRole.ADMIN !in actor.roles) deny()
        tx.query(tenant) { directoryView(it) }
    }

    fun invite(actor: Actor, key: String, command: EnterpriseInviteCommand): EnterpriseDirectoryView {
        validateAccount(command.accountId); validateRoles(command.roles)
        return mutate(actor,key,command.expectedStateVersion,"enterprise.invitation.created",command.accountId,command) { db ->
            if (member(db,command.accountId)!=null) invalid("该账号已有成员记录，请修改角色或恢复成员")
            db.execute("update enterprise_invitation set status='EXPIRED',state_version=state_version+1 where tenant_id=? and account_id=? and status='PENDING' and expires_at<=?::timestamptz",tenant,command.accountId,timestamp())
            if(db.fetchOne("select id from enterprise_invitation where tenant_id=? and account_id=? and status='PENDING'",tenant,command.accountId)!=null) invalid("该账号已有待接受邀请")
            if((db.fetchOne("select count(*) as n from enterprise_member where tenant_id=?",tenant)?.long("n")?:0)>=1000) invalid("当前企业成员达到首版1000人上限")
            db.execute("insert into enterprise_invitation(tenant_id,id,account_id,roles,status,state_version,created_by,inviter_membership_version,expires_at,created_at) values(?,?,?,?::text[],'PENDING',1,?,?,?::timestamptz,?::timestamptz)",
                tenant,UUID.randomUUID(),command.accountId,command.roles.names(),actor.id,requireNotNull(actor.authorizationVersion),clock.instant().plusSeconds(7*86400L).atOffset(ZoneOffset.UTC),timestamp())
        }
    }

    fun changeRoles(actor: Actor,key:String,accountId:String,command:EnterpriseMemberRolesCommand):EnterpriseDirectoryView {
        validateRoles(command.roles)
        return mutate(actor,key,command.expectedStateVersion,"enterprise.member.roles_changed",accountId,command) { db ->
            val current=target(db,accountId,command.expectedMembershipVersion)
            if(accountId==actor.id && BusinessRole.ADMIN !in command.roles) invalid("请由另一位负责人调整你自己的管理权限")
            protectLastAdmin(db,current,command.roles,current.get("status",String::class.java)=="ACTIVE")
            db.execute("update enterprise_member set roles=?::text[],state_version=state_version+1,updated_at=?::timestamptz where tenant_id=? and account_id=? and state_version=?",command.roles.names(),timestamp(),tenant,accountId,command.expectedMembershipVersion).also { if(it!=1) deny() }
            revokePending(db,accountId)
        }
    }

    fun changeStatus(actor:Actor,key:String,accountId:String,command:EnterpriseMemberStateCommand,activate:Boolean):EnterpriseDirectoryView =
        mutate(actor,key,command.expectedStateVersion,if(activate) "enterprise.member.activated" else "enterprise.member.suspended",accountId,listOf(command,activate)) { db ->
            val current=target(db,accountId,command.expectedMembershipVersion)
            if(!activate && accountId==actor.id) invalid("请由另一位负责人暂停你的账号")
            if(activate && current.get("account_status",String::class.java)!="ACTIVE") deny()
            protectLastAdmin(db,current,roles(current),activate)
            db.execute("update enterprise_member set status=?,state_version=state_version+1,updated_at=?::timestamptz where tenant_id=? and account_id=? and state_version=?",
                if(activate) "ACTIVE" else "SUSPENDED",timestamp(),tenant,accountId,command.expectedMembershipVersion).also { if(it!=1) deny() }
            // Activation never revives invitations or past authorization stamps.
            revokePending(db,accountId)
        }

    fun revoke(actor:Actor,key:String,id:String,command:EnterpriseVersionCommand):EnterpriseDirectoryView =
        mutate(actor,key,command.expectedStateVersion,"enterprise.invitation.revoked",id,command) { db ->
            val invitationId=uuid(id)
            val row=db.fetchOne("select * from enterprise_invitation where tenant_id=? and id=? for update",tenant,invitationId)?:invalid("邀请不存在")
            if(row.get("status",String::class.java)!="PENDING") invalid("邀请已不再待处理")
            db.execute("update enterprise_invitation set status='REVOKED',state_version=state_version+1 where tenant_id=? and id=?",tenant,invitationId)
        }

    fun accept(identity:EnterpriseAccountIdentity,key:String,id:String,command:EnterpriseVersionCommand) {
        validateIdentity(identity); validateKey(key)
        val fingerprint=commandFingerprint("enterprise.invitation.accept",identity.accountId,id,command)
        tx.write(tenant) { tx.query(tenant,true) { db ->
            requireActiveTenant(db)
            lockDirectory(db)
            if(commandExists(db,key,identity.accountId,fingerprint)) return@query
            val account=db.fetchOne("select status from enterprise_account where tenant_id=? and account_id=? for share",tenant,identity.accountId)?:deny()
            if(account.get("status",String::class.java)!="ACTIVE") deny()
            val row=db.fetchOne("select * from enterprise_invitation where tenant_id=? and id=? for update",tenant,uuid(id))?:invalid("邀请不存在")
            if(row.get("account_id",String::class.java)!=identity.accountId) deny()
            if(row.long("state_version")!=command.expectedStateVersion) conflict(command.expectedStateVersion,row.long("state_version"))
            if(row.get("status",String::class.java)!="PENDING" || !row.instant("expires_at").isAfter(clock.instant())) invalid("邀请已撤销、接受或过期")
            if(member(db,identity.accountId)!=null) invalid("该账号已有成员记录；暂停成员只能由负责人恢复")
            val inviter=member(db,requireNotNull(row.get("created_by",String::class.java)),true)?:deny()
            if(inviter.get("status",String::class.java)!="ACTIVE" || inviter.get("account_status",String::class.java)!="ACTIVE" ||
                BusinessRole.ADMIN !in roles(inviter) || inviter.long("state_version")!=row.long("inviter_membership_version")) deny()
            db.execute("insert into enterprise_member(tenant_id,account_id,roles,status,state_version,updated_at) values(?,?,?::text[],'ACTIVE',1,?::timestamptz)",tenant,identity.accountId,roles(row).names(),timestamp())
            db.execute("update enterprise_invitation set status='ACCEPTED',state_version=state_version+1 where tenant_id=? and id=?",tenant,uuid(id))
            completeCommand(db,key,identity.accountId,fingerprint);bump(db)
            audit(identity.accountId,"enterprise.invitation.accepted",id,mapOf("targetAccountId" to identity.accountId,
                "rolesAfter" to roles(row).map { it.name }.sorted().joinToString(","),"statusAfter" to "ACTIVE",
                "membershipVersionAfter" to "1","inviterAccountId" to requireNotNull(row.get("created_by",String::class.java))))
        } }
    }

    private fun mutate(actor:Actor,key:String,expected:Long,event:String,target:String,body:Any,block:(DSLContext)->Unit):EnterpriseDirectoryView {
        validateKey(key)
        val fingerprint=commandFingerprint(event,actor.id,target,body)
        return tx.write(tenant) { tx.query(tenant,true) { db ->
            val current=lockDirectory(db)
            checkCurrent(actor,true)
            if(BusinessRole.ADMIN !in actor.roles) deny()
            if(commandExists(db,key,actor.id,fingerprint)) return@query directoryView(db)
            if(current!=expected) conflict(expected,current)
            val before=memberAuditState(db,target,"Before")
            block(db)
            completeCommand(db,key,actor.id,fingerprint);bump(db)
            val details=linkedMapOf("authorizationVersion" to requireNotNull(actor.authorizationVersion).toString(),
                "directoryVersionBefore" to current.toString(),"directoryVersionAfter" to (current+1).toString(),"targetId" to target)
            if(EnterpriseProperties.ACCOUNT_ID.matches(target)) details["targetAccountId"]=target
            details.putAll(before);details.putAll(memberAuditState(db,target,"After"))
            if(body is EnterpriseInviteCommand) {
                details["invitedRoles"]=body.roles.map { it.name }.sorted().joinToString(",")
                db.fetchOne("select id from enterprise_invitation where tenant_id=? and account_id=? and status='PENDING'",tenant,target)?.get("id",UUID::class.java)?.let { details["invitationId"]=it.toString() }
            }
            audit(actor.id,event,target,details)
            directoryView(db)
        } }
    }

    private fun target(db:DSLContext,id:String,expected:Long):Record {
        validateAccount(id)
        val row=member(db,id,true,exclusive=true)?:invalid("成员不存在")
        if(row.long("state_version")!=expected) conflict(expected,row.long("state_version"))
        return row
    }
    private fun protectLastAdmin(db:DSLContext,current:Record,newRoles:Set<BusinessRole>,active:Boolean) {
        if(current.get("status",String::class.java)=="ACTIVE" && BusinessRole.ADMIN in roles(current) && (!active || BusinessRole.ADMIN !in newRoles)) {
            val others=db.fetchOne("select count(*) as n from enterprise_member m join enterprise_account a on a.tenant_id=m.tenant_id and a.account_id=m.account_id where m.tenant_id=? and m.account_id<>? and m.status='ACTIVE' and a.status='ACTIVE' and 'ADMIN'=any(m.roles)",tenant,current.get("account_id",String::class.java))?.long("n")?:0
            if(others==0L) invalid("企业必须保留至少一位有效负责人")
        }
    }
    private fun revokePending(db:DSLContext,id:String) { db.execute("update enterprise_invitation set status='REVOKED',state_version=state_version+1 where tenant_id=? and status='PENDING' and (account_id=? or created_by=?)",tenant,id,id) }
    private fun requireActiveTenant(db:DSLContext) { if(db.fetchOne("select status from tenant where id=?",tenant)?.get("status",String::class.java)!="ACTIVE") deny() }
    private fun memberAuditState(db:DSLContext,id:String,suffix:String):Map<String,String> {
        if(!EnterpriseProperties.ACCOUNT_ID.matches(id)) return emptyMap()
        val row=member(db,id)?:return emptyMap()
        return mapOf("roles$suffix" to roles(row).map { it.name }.sorted().joinToString(","),
            "status$suffix" to requireNotNull(row.get("status",String::class.java)),"membershipVersion$suffix" to row.long("state_version").toString())
    }
    private fun lockDirectory(db:DSLContext):Long = db.fetchOne("select state_version from enterprise_directory where tenant_id=? for update",tenant)?.long("state_version")?:deny()
    private fun bump(db:DSLContext) { db.execute("update enterprise_directory set state_version=state_version+1 where tenant_id=?",tenant) }
    private fun member(db:DSLContext,id:String,lock:Boolean=false,exclusive:Boolean=false):Record? = db.fetchOne("select m.*,a.display_name,a.status as account_status from enterprise_member m join enterprise_account a on a.tenant_id=m.tenant_id and a.account_id=m.account_id where m.tenant_id=? and m.account_id=?"+(if(exclusive) " for update of m,a" else if(lock) " for share of m,a" else ""),tenant,id)
    private fun directoryView(db:DSLContext)=EnterpriseDirectoryView(
        db.fetchOne("select state_version from enterprise_directory where tenant_id=?",tenant)?.long("state_version")?:0,
        db.fetch("select m.*,a.display_name from enterprise_member m join enterprise_account a on a.tenant_id=m.tenant_id and a.account_id=m.account_id where m.tenant_id=? order by m.account_id",tenant).map { EnterpriseMemberView(requireNotNull(it.get("account_id",String::class.java)),it.get("display_name",String::class.java),roles(it),EnterpriseMemberStatus.valueOf(requireNotNull(it.get("status",String::class.java))),it.long("state_version"),it.instant("updated_at")) },
        db.fetch("select * from enterprise_invitation where tenant_id=? order by created_at,id",tenant).map(::invitation))
    private fun invitation(row:Record)=EnterpriseInvitationView(requireNotNull(row.get("id",UUID::class.java)).toString(),requireNotNull(row.get("account_id",String::class.java)),roles(row),
        if(row.get("status",String::class.java)=="PENDING"&&!row.instant("expires_at").isAfter(clock.instant())) EnterpriseInvitationStatus.EXPIRED else EnterpriseInvitationStatus.valueOf(requireNotNull(row.get("status",String::class.java))),row.long("state_version"),requireNotNull(row.get("created_by",String::class.java)),row.instant("expires_at"))
    private fun commandExists(db:DSLContext,key:String,account:String,fingerprint:String):Boolean {
        val row=db.fetchOne("select account_id,fingerprint from enterprise_command where tenant_id=? and idempotency_key=?",tenant,key)?:return false
        if(row.get("account_id",String::class.java)!=account || row.get("fingerprint",String::class.java)!=fingerprint) throw IdempotencyConflictException()
        return true
    }
    private fun completeCommand(db:DSLContext,key:String,account:String,fingerprint:String) { db.execute("insert into enterprise_command(tenant_id,idempotency_key,account_id,fingerprint,created_at) values(?,?,?,?,?::timestamptz)",tenant,key,account,fingerprint,timestamp()) }
    private fun audit(actor:String,event:String,target:String,details:Map<String,String> = emptyMap()) { repositories.audits.append(AuditFact(tenant,"enterpriseMember",target,event,actor,clock.instant(),details)) }
    private fun validateIdentity(identity:EnterpriseAccountIdentity) {
        if(identity.issuer!=properties.issuerUri || EnterpriseAccounts.identity(identity.issuer,identity.subject,identity.displayName).accountId!=identity.accountId) deny()
    }
    private fun validateAccount(id:String) { if(!EnterpriseProperties.ACCOUNT_ID.matches(id)) invalid("请粘贴该成员登录后显示的完整账号标识") }
    private fun validateRoles(value:Set<BusinessRole>) { if(value.isEmpty()||value.size>5) invalid("必须选择有效业务角色") }
    private fun validateKey(key:String) { if(key.length !in 8..128 || key.any(Char::isISOControl)) invalid("幂等键无效") }
    private fun Set<BusinessRole>.names()=map { it.name }.sorted().toTypedArray()
    private fun roles(row:Record):Set<BusinessRole> = (row.get("roles",Array<String>::class.java)?:emptyArray()).map { BusinessRole.valueOf(it) }.toSet()
    private fun Record.long(name:String)=requireNotNull(get(name,Long::class.javaObjectType))
    private fun Record.instant(name:String)=requireNotNull(get(name,OffsetDateTime::class.java)).toInstant()
    private fun timestamp()=clock.instant().atOffset(ZoneOffset.UTC)
    private fun uuid(value:String)=try { UUID.fromString(value) } catch(_:IllegalArgumentException) { invalid("邀请标识无效") }
    private fun conflict(expected:Long,actual:Long):Nothing=throw OptimisticStateException("enterprise-directory",tenant,expected,actual)
    private fun deny():Nothing=throw AuthorizationDeniedException("企业成员权限无效或已变化，请刷新身份后重试")
    private fun invalid(message:String):Nothing=throw BusinessValidationException(message)
}

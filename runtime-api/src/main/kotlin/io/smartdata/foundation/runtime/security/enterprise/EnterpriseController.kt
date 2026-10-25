package io.smartdata.foundation.runtime.security.enterprise

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.context.annotation.Profile
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

@RestController
@Validated
@Profile("enterprise")
class EnterpriseController(private val access:EnterpriseAccessPort,private val directory:EnterpriseDirectoryService) {
    @GetMapping("/api/v1/me")
    fun me(request:HttpServletRequest):EnterpriseMeView=directory.me(access.currentIdentity(),csrf(request))

    @GetMapping("/api/v1/enterprise/members")
    fun members()=directory.directory(access.currentActor())

    @PostMapping("/api/v1/enterprise/invitations")
    fun invite(@RequestHeader("Idempotency-Key") @Size(min=8,max=128) key:String,@Valid @RequestBody body:EnterpriseInviteCommand)=directory.invite(access.currentActor(),key,body)

    @PostMapping("/api/v1/enterprise/members/{accountId}/roles")
    fun roles(@PathVariable accountId:String,@RequestHeader("Idempotency-Key") @Size(min=8,max=128) key:String,@Valid @RequestBody body:EnterpriseMemberRolesCommand)=directory.changeRoles(access.currentActor(),key,accountId,body)

    @PostMapping("/api/v1/enterprise/members/{accountId}/suspend")
    fun suspend(@PathVariable accountId:String,@RequestHeader("Idempotency-Key") @Size(min=8,max=128) key:String,@Valid @RequestBody body:EnterpriseMemberStateCommand)=directory.changeStatus(access.currentActor(),key,accountId,body,false)

    @PostMapping("/api/v1/enterprise/members/{accountId}/activate")
    fun activate(@PathVariable accountId:String,@RequestHeader("Idempotency-Key") @Size(min=8,max=128) key:String,@Valid @RequestBody body:EnterpriseMemberStateCommand)=directory.changeStatus(access.currentActor(),key,accountId,body,true)

    @PostMapping("/api/v1/enterprise/invitations/{id}/revoke")
    fun revoke(@PathVariable id:String,@RequestHeader("Idempotency-Key") @Size(min=8,max=128) key:String,@Valid @RequestBody body:EnterpriseVersionCommand)=directory.revoke(access.currentActor(),key,id,body)

    @PostMapping("/api/v1/enterprise/invitations/{id}/accept")
    fun accept(request:HttpServletRequest,@PathVariable id:String,@RequestHeader("Idempotency-Key") @Size(min=8,max=128) key:String,@Valid @RequestBody body:EnterpriseVersionCommand):EnterpriseMeView {
        val identity=access.currentIdentity()?:throw io.smartdata.foundation.service.AuthorizationDeniedException("请先登录企业账号")
        directory.accept(identity,key,id,body)
        return directory.me(identity,csrf(request))
    }
    private fun csrf(request:HttpServletRequest):EnterpriseCsrfView?=(request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.let { EnterpriseCsrfView(it.headerName,it.token) }
}

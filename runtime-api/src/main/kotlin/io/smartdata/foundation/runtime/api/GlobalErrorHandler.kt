package io.smartdata.foundation.runtime.api

import io.smartdata.foundation.domain.InvalidActionTransition
import io.smartdata.foundation.runtime.security.PayloadTooLargeException
import io.smartdata.foundation.service.AuthorizationDeniedException
import io.smartdata.foundation.service.BusinessValidationException
import io.smartdata.foundation.service.IdempotencyConflictException
import io.smartdata.foundation.service.OptimisticStateException
import io.smartdata.foundation.service.PolicyUnavailableException
import io.smartdata.foundation.service.ResourceNotFoundException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalErrorHandler {
    @ExceptionHandler(ResourceNotFoundException::class)
    fun notFound(error: ResourceNotFoundException, request: HttpServletRequest) =
        problem(HttpStatus.NOT_FOUND, "资源不存在", error.message, request)

    @ExceptionHandler(AuthorizationDeniedException::class)
    fun forbidden(error: AuthorizationDeniedException, request: HttpServletRequest) =
        problem(HttpStatus.FORBIDDEN, "操作被拒绝", error.message, request)

    @ExceptionHandler(OptimisticStateException::class, IdempotencyConflictException::class)
    fun conflict(error: RuntimeException, request: HttpServletRequest) =
        problem(HttpStatus.CONFLICT, "状态冲突", error.message, request)

    @ExceptionHandler(BusinessValidationException::class, InvalidActionTransition::class)
    fun unprocessable(error: RuntimeException, request: HttpServletRequest) =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "业务规则不允许", error.message, request)

    @ExceptionHandler(PayloadTooLargeException::class)
    fun tooLarge(error: PayloadTooLargeException, request: HttpServletRequest) =
        problem(HttpStatus.PAYLOAD_TOO_LARGE, "请求过大", error.message, request)

    @ExceptionHandler(PolicyUnavailableException::class)
    fun policyUnavailable(error: PolicyUnavailableException, request: HttpServletRequest) =
        problem(HttpStatus.SERVICE_UNAVAILABLE, "策略服务不可用", "写操作已安全拒绝", request)

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        ConstraintViolationException::class,
        MissingRequestHeaderException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        IllegalArgumentException::class,
    )
    fun badRequest(error: Exception, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val violations = if (error is MethodArgumentNotValidException) {
            error.bindingResult.fieldErrors.map { ApiViolation(it.field, it.defaultMessage ?: "参数无效") }
        } else emptyList()
        val detail = when (error) {
            is HttpMessageNotReadableException -> "请求正文不是有效的接口数据"
            is MethodArgumentTypeMismatchException -> "查询或路径参数的类型无效"
            else -> error.message
        }
        return problem(HttpStatus.BAD_REQUEST, "请求参数无效", detail, request, violations)
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception, request: HttpServletRequest) =
        problem(HttpStatus.INTERNAL_SERVER_ERROR, "服务处理失败", "请求未完成，请使用审计标识排查", request)

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String?,
        request: HttpServletRequest,
        violations: List<ApiViolation> = emptyList(),
    ): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail ?: title)
        body.title = title
        body.instance = java.net.URI.create(request.requestURI)
        if (violations.isNotEmpty()) body.setProperty("violations", violations)
        return ResponseEntity.status(status).body(body)
    }
}

package io.smartdata.foundation.runtime.workspace

import com.openai.core.RequestOptions
import com.openai.core.http.Headers
import com.openai.core.http.HttpClient
import com.openai.core.http.HttpRequest
import com.openai.core.http.HttpResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SDK transport only: JSON/model semantics still belong to Spring AI and the official SDK.
 * One destination, pinned DNS, ordinary TLS verification, no redirects, no retry, bounded bodies.
 */
internal class BoundedModelHttpClient(
    private val endpoint: URI,
    timeout: Duration,
    private val maxRequestBytes: Int,
    allowPrivateAddresses: Boolean,
    private val maxResponseBytes: Int = 131072,
) : HttpClient {
    private val deadline = System.nanoTime() + timeout.toNanos()
    private val client: okhttp3.OkHttpClient

    init {
        val resolver = Executors.newVirtualThreadPerTaskExecutor()
        val future = resolver.submit<List<InetAddress>> { InetAddress.getAllByName(endpoint.host).toList() }
        val addresses = try { future.get(timeout.toMillis(), TimeUnit.MILLISECONDS) }
        finally { future.cancel(true); resolver.shutdownNow() }
        require(addresses.isNotEmpty() && addresses.none { it.isAnyLocalAddress || it.isLinkLocalAddress || it.isMulticastAddress }) {
            "模型端点解析到禁止的地址范围"
        }
        require(allowPrivateAddresses || addresses.none { it.isLoopbackAddress || it.isSiteLocalAddress ||
            (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc) }) { "云模型配置档不能连接本机或私网地址" }
        // A hostname resolving to loopback never grants a plaintext exemption.
        require(endpoint.scheme == "https" || (endpoint.host == "127.0.0.1" && addresses.all { it.hostAddress == "127.0.0.1" }))
        val remaining = Duration.ofNanos(deadline - System.nanoTime())
        require(!remaining.isNegative && !remaining.isZero) { "模型请求时间预算已用尽" }
        client = okhttp3.OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == endpoint.host) addresses else throw java.net.UnknownHostException("模型目标已改变")
            })
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(remaining).readTimeout(remaining).writeTimeout(remaining).callTimeout(remaining).build()
    }

    override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse {
        val uri = URI.create(request.url())
        val expectedPath = endpoint.path.trimEnd('/') + "/chat/completions"
        require(uri.scheme == endpoint.scheme && uri.host == endpoint.host && effectivePort(uri) == effectivePort(endpoint) &&
            uri.path == expectedPath && uri.rawQuery == null && request.method.name == "POST") { "禁止模型客户端改变已批准的目标或能力" }
        val body = request.body ?: throw IOException("模型请求缺少正文")
        val bounded = object : RequestBody() {
            override fun contentType() = body.contentType()?.toMediaTypeOrNull()
            override fun contentLength() = body.contentLength()
            override fun writeTo(sink: BufferedSink) {
                var written = 0
                val destination = object : java.io.OutputStream() {
                    override fun write(value: Int) {
                        if (++written > maxRequestBytes) throw IOException("模型请求正文超过限制")
                        sink.writeByte(value)
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        if (length > maxRequestBytes - written) throw IOException("模型请求正文超过限制")
                        written += length
                        sink.write(bytes, offset, length)
                    }
                }
                body.writeTo(destination)
            }
        }
        val builder = Request.Builder().url(uri.toString()).post(bounded)
        request.headers.names().forEach { name -> request.headers.values(name).forEach { builder.addHeader(name, it) } }
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw java.net.SocketTimeoutException("模型请求超过总时限")
        val call = client.newCall(builder.build())
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
        return body.use {
            call.execute().use { response ->
                val responseBody = response.body ?: throw IOException("模型未返回正文")
                val bytes = responseBody.byteStream().readNBytes(maxResponseBytes + 1)
                if (bytes.size > maxResponseBytes) throw IOException("模型响应正文超过限制")
                val headers = Headers.builder().also { result ->
                    // Retain only protocol headers needed by SDK parsing, never arbitrary provider cookies.
                    response.header("Content-Type")?.let { result.put("Content-Type", it) }
                }.build()
                object : HttpResponse {
                    private val input = ByteArrayInputStream(bytes)
                    override fun statusCode() = response.code
                    override fun headers() = headers
                    override fun body() = input
                    override fun close() = input.close()
                }
            }
        }
    }

    override fun executeAsync(request: HttpRequest, requestOptions: RequestOptions): CompletableFuture<HttpResponse> =
        CompletableFuture.failedFuture(UnsupportedOperationException("本版本仅允许有界的同步模型调用"))

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    private fun effectivePort(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
}

package io.smartdata.foundation.runtime.enterprise

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** A protocol fixture, never an application authentication bypass or a deployable identity service. */
internal class TestOidcProvider(private val directory: Path) : AutoCloseable {
    val keyStorePath: Path = directory.resolve("ephemeral-tls.p12")
    val keyStorePassword: String = UUID.randomUUID().toString()
    val clientId = "smartdata-integration"
    val clientSecret: String = UUID.randomUUID().toString()
    private val json = JsonMapper.builder().build()
    private val key = RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).keyUse(KeyUse.SIGNATURE).generate()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val previousSslContext = SSLContext.getDefault()
    private val previousSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
    val sslContext: SSLContext
    private val server: HttpsServer
    val issuer: String
    val authorizations = AtomicInteger()
    val tokenExchanges = AtomicInteger()
    val pkceChecks = AtomicInteger()
    val userInfoRequests = AtomicInteger()
    val nextTokenFault = AtomicReference<String?>()
    val rejectedRequests = CopyOnWriteArrayList<String>()
    @Volatile var registeredRedirect: String? = null
    private data class Grant(val subject: String, val redirect: String, val challenge: String, val nonce: String, val expiresAt: Instant)
    private val codes = ConcurrentHashMap<String, Grant>()
    private val accessTokens = ConcurrentHashMap<String, Grant>()

    init {
        Files.createDirectories(directory)
        val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val generation = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "localhost", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
            "-keystore", keyStorePath.toString(), "-storepass:env", "SMARTDATA_FIXTURE_STOREPASS", "-noprompt")
            .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile())
        generation.environment()["SMARTDATA_FIXTURE_STOREPASS"] = keyStorePassword
        val process = generation.start()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "Ephemeral test TLS certificate could not be created" }
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keyStorePath).use { load(it, keyStorePassword.toCharArray()) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, keyStorePassword.toCharArray()) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        sslContext = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        // Scoped to this non-parallel integration JVM; neither trust-all managers nor hostname bypasses.
        SSLContext.setDefault(sslContext)
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(sslContext)
            executor = this@TestOidcProvider.executor
        }
        issuer = "https://localhost:${server.address.port}"
        server.createContext("/") { exchange ->
            try { dispatch(exchange) } catch (failure: Exception) {
                rejectedRequests += "${exchange.requestURI.path}:${(failure as? FixtureProtocolException)?.code ?: "INVALID_REQUEST"}"
                respond(exchange, 400, mapOf("error" to "invalid_request"))
            }
            finally { exchange.close() }
        }
        server.start()
    }

    private fun dispatch(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/.well-known/openid-configuration" -> respond(exchange, 200, mapOf(
                "issuer" to issuer, "authorization_endpoint" to "$issuer/authorize", "token_endpoint" to "$issuer/token",
                "jwks_uri" to "$issuer/jwks", "userinfo_endpoint" to "$issuer/userinfo", "response_types_supported" to listOf("code"),
                "subject_types_supported" to listOf("public"), "id_token_signing_alg_values_supported" to listOf("RS256"),
                "token_endpoint_auth_methods_supported" to listOf("client_secret_basic"), "code_challenge_methods_supported" to listOf("S256"),
                "scopes_supported" to listOf("openid", "profile")))
            "/jwks" -> respond(exchange, 200, JWKSet(key.toPublicJWK()).toJSONObject())
            "/fixture/login" -> {
                require(exchange.requestMethod == "POST")
                val subject = parameters(exchange.requestBody.readNBytes(1024).toString(UTF_8))["subject"]
                require(subject in setOf("owner", "worker", "outsider"))
                exchange.responseHeaders.add("Set-Cookie", "FIXTURE_ACCOUNT=$subject; Path=/; HttpOnly; Secure; SameSite=Lax")
                respond(exchange, 200, mapOf("fixtureOnly" to true))
            }
            "/authorize" -> authorize(exchange)
            "/token" -> token(exchange)
            "/userinfo" -> {
                userInfoRequests.incrementAndGet()
                val grant = accessTokens[exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")]
                require(grant != null && grant.expiresAt.isAfter(Instant.now()))
                respond(exchange, 200, mapOf("sub" to grant.subject, "name" to "Integration ${grant.subject}"))
            }
            else -> respond(exchange, 404, mapOf("error" to "not_found"))
        }
    }

    private fun authorize(exchange: HttpExchange) {
        require(exchange.requestMethod == "GET")
        val parameters = parameters(exchange.requestURI.rawQuery.orEmpty())
        val subject = exchange.requestHeaders.getFirst("Cookie").orEmpty().split(';').map(String::trim)
            .firstOrNull { it.startsWith("FIXTURE_ACCOUNT=") }?.substringAfter('=')
        verify(subject in setOf("owner", "worker", "outsider"), "LOGIN_COOKIE")
        verify(parameters["client_id"] == clientId && parameters["response_type"] == "code", "CLIENT_REQUEST")
        val redirect = requireNotNull(parameters["redirect_uri"])
        verify(redirect == registeredRedirect && URI(redirect).scheme == "https", "REDIRECT_URI")
        require(parameters["scope"].orEmpty().split(' ').contains("openid"))
        verify(parameters["code_challenge_method"] == "S256", "PKCE_METHOD")
        val challenge = requireNotNull(parameters["code_challenge"]).also { require(it.matches(Regex("[A-Za-z0-9_-]{43}"))) }
        val nonce = requireNotNull(parameters["nonce"]).also { require(it.isNotBlank()) }
        val state = requireNotNull(parameters["state"]).also { require(it.isNotBlank()) }
        val code = UUID.randomUUID().toString()
        codes[code] = Grant(requireNotNull(subject), redirect, challenge, nonce, Instant.now().plusSeconds(60))
        authorizations.incrementAndGet()
        exchange.responseHeaders.add("Location", "$redirect?code=${encode(code)}&state=${encode(state)}")
        exchange.sendResponseHeaders(302, -1)
    }

    private fun token(exchange: HttpExchange) {
        require(exchange.requestMethod == "POST")
        val basic = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Basic ")
        val credentials = Base64.getDecoder().decode(basic).toString(UTF_8).split(':', limit = 2).map { URLDecoder.decode(it, UTF_8) }
        verify(credentials.size == 2 && credentials[0] == clientId && MessageDigest.isEqual(credentials[1].toByteArray(), clientSecret.toByteArray()), "CLIENT_AUTHENTICATION")
        val parameters = parameters(exchange.requestBody.readNBytes(8192).toString(UTF_8))
        require(parameters["grant_type"] == "authorization_code")
        val grant = requireNotNull(codes.remove(parameters["code"]))
        verify(grant.expiresAt.isAfter(Instant.now()) && parameters["redirect_uri"] == grant.redirect, "CODE_EXPIRY_OR_REDIRECT")
        val verifier = requireNotNull(parameters["code_verifier"])
        require(verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(UTF_8)))
        verify(MessageDigest.isEqual(challenge.toByteArray(UTF_8), grant.challenge.toByteArray(UTF_8)), "PKCE_PROOF")
        pkceChecks.incrementAndGet()
        val now = Instant.now()
        val fault = nextTokenFault.getAndSet(null)
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), JWTClaimsSet.Builder()
            .issuer(if (fault == "ISSUER") "$issuer/other" else issuer).subject(grant.subject)
            .audience(if (fault == "AUDIENCE") "unrelated-client" else clientId).issueTime(Date.from(now.minusSeconds(600)))
            .expirationTime(Date.from(if (fault == "EXPIRED") now.minusSeconds(300) else now.plusSeconds(60)))
            .claim("nonce", if (fault == "NONCE") "unrelated-nonce" else grant.nonce).claim("name", "Integration ${grant.subject}").build())
        jwt.sign(RSASSASigner(if (fault == "SIGNATURE") RSAKeyGenerator(2048).generate() else key))
        val accessToken = UUID.randomUUID().toString()
        accessTokens[accessToken] = grant
        tokenExchanges.incrementAndGet()
        respond(exchange, 200, mapOf("token_type" to "Bearer", "access_token" to accessToken, "id_token" to jwt.serialize(), "expires_in" to 60,
            "scope" to "openid profile"))
    }

    private fun parameters(text: String): Map<String, String> = text.split('&').filter(String::isNotEmpty).associate {
        val pair = it.split('=', limit = 2)
        require(pair.size == 2)
        URLDecoder.decode(pair[0], UTF_8) to URLDecoder.decode(pair[1], UTF_8)
    }

    fun resourceToken(subject: String, fault: String? = null): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder().issuer(issuer)
            .audience(if (fault == "AUDIENCE") "unrelated-service" else "smartdata-runtime")
            .issueTime(Date.from(now.minusSeconds(600)))
            .expirationTime(Date.from(if (fault == "EXPIRED") now.minusSeconds(300) else now.plusSeconds(600)))
            .jwtID(UUID.randomUUID().toString())
            .claim("name", "API $subject").claim("smartdata_roles", listOf("ADMIN"))
        if (fault != "SUBJECT") claims.subject(subject)
        if (fault != "CLIENT") claims.claim("client_id", clientId)
        val token = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID)
            .type(if (fault == "ID_TOKEN_TYPE") JOSEObjectType.JWT else JOSEObjectType("at+jwt")).build(), claims.build())
        token.sign(RSASSASigner(key))
        return token.serialize()
    }

    private fun encode(text: String) = URLEncoder.encode(text, UTF_8)
    private class FixtureProtocolException(val code: String) : RuntimeException()
    private fun verify(condition: Boolean, code: String) { if (!condition) throw FixtureProtocolException(code) }

    private fun respond(exchange: HttpExchange, status: Int, body: Any) {
        val bytes = json.writeValueAsBytes(body)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() {
        server.stop(0)
        executor.close()
        SSLContext.setDefault(previousSslContext)
        HttpsURLConnection.setDefaultSSLSocketFactory(previousSocketFactory)
    }
}

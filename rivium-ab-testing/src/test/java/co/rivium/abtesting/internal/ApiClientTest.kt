package co.rivium.abtesting.internal

import co.rivium.abtesting.Redact
import co.rivium.abtesting.RiviumAbTestingConfig
import co.rivium.abtesting.RiviumTokenProvider
import co.rivium.abtesting.models.EventType
import co.rivium.abtesting.models.TrackEvent
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun token(sub: String, expiresIn: Long = 3600): String {
        val exp = System.currentTimeMillis() / 1000 + expiresIn
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"$sub","exp":$exp}""".toByteArray())
        return "header.$payload.signature"
    }

    private fun client(provider: RiviumTokenProvider? = null, userToken: String? = null) = ApiClient(
        RiviumAbTestingConfig(apiKey = "rv_live_abcdef123456", tokenProvider = provider, userToken = userToken),
        baseUrl = server.url("").toString().trimEnd('/')
    )

    private fun ok(body: String = """{"flags":[]}""") = MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun `sends the api key and no user token without a provider`() = runBlocking {
        server.enqueue(ok())
        val api = client()

        api.fetchFeatureFlags()

        val request = server.takeRequest()
        assertEquals("rv_live_abcdef123456", request.getHeader("x-api-key"))
        assertNull(request.getHeader("x-user-token"))
        assertFalse(api.usesUserToken)
    }

    @Test
    fun `sends the token and reuses it while it is fresh`() = runBlocking {
        server.enqueue(ok())
        server.enqueue(ok())
        var calls = 0
        val api = client(RiviumTokenProvider { calls++; token("alice") })

        api.fetchFeatureFlags()
        api.fetchFeatureFlags()

        assertEquals(1, calls)
        val first = server.takeRequest().getHeader("x-user-token")
        val second = server.takeRequest().getHeader("x-user-token")
        assertTrue(first!!.startsWith("header."))
        assertEquals(first, second)
    }

    @Test
    fun `refetches a token that is about to expire`() = runBlocking {
        server.enqueue(ok())
        server.enqueue(ok())
        var calls = 0
        val api = client(RiviumTokenProvider { calls++; token("alice", expiresIn = 30) })

        api.fetchFeatureFlags()
        api.fetchFeatureFlags()

        assertEquals(2, calls)
    }

    @Test
    fun `on token_expired refetches and retries once`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"token_expired"}"""))
        server.enqueue(ok())
        var calls = 0
        val api = client(RiviumTokenProvider { calls++; token("alice") })

        val result = api.fetchFeatureFlags()

        assertTrue(result.isSuccess)
        assertEquals(2, server.requestCount)
        assertEquals(2, calls)
    }

    @Test
    fun `does not retry forever`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"token_expired"}""")) }
        val api = client(RiviumTokenProvider { token("alice") })

        val result = api.fetchFeatureFlags()

        assertTrue(result.isFailure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `clearToken gets a token for the next user`() = runBlocking {
        server.enqueue(ok())
        server.enqueue(ok())
        var user = "alice"
        val api = client(RiviumTokenProvider { token(user) })

        api.fetchFeatureFlags()
        user = "bob"
        api.clearToken()
        api.fetchFeatureFlags()

        val subs = (1..2).map {
            val t = server.takeRequest().getHeader("x-user-token")!!
            Base64Url.decodeToString(t.split('.')[1])
        }
        assertTrue(subs[0].contains("alice"))
        assertTrue(subs[1].contains("bob"))
    }

    @Test
    fun `sync with an explicit token sends that token and asks the provider nothing`() = runBlocking {
        server.enqueue(ok("""{"success":true,"synced":1,"failed":0}"""))
        var calls = 0
        val api = client(RiviumTokenProvider { calls++; token("bob") })
        val aliceToken = token("alice")
        val event = TrackEvent(experimentId = "e", variantId = "v", userId = "alice", eventType = EventType.VIEW)

        api.syncOfflineEvents(listOf(event), "device", explicitToken = aliceToken, useExplicitToken = true)

        assertEquals(aliceToken, server.takeRequest().getHeader("x-user-token"))
        assertEquals(0, calls)
    }

    @Test
    fun `a failing provider does not break the request`() = runBlocking {
        server.enqueue(ok())
        val api = client(RiviumTokenProvider { throw IllegalStateException("offline") })

        val result = api.fetchFeatureFlags()

        assertTrue(result.isSuccess)
        assertNull(server.takeRequest().getHeader("x-user-token"))
    }

    @Test
    fun `api keys are redacted for logs`() {
        assertEquals("rv_live_…3456", Redact.key("rv_live_abcdef123456"))
        assertFalse(
            RiviumAbTestingConfig(apiKey = "rv_live_abcdef123456").toString().contains("abcdef")
        )
    }
}

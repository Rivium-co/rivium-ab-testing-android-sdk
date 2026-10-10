package co.rivium.abtesting.internal

import co.rivium.abtesting.Redact
import co.rivium.abtesting.RiviumAbTestingConfig
import co.rivium.abtesting.RiviumTokenProvider
import co.rivium.abtesting.models.EventType
import co.rivium.abtesting.models.TrackEvent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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

    private fun client(provider: RiviumTokenProvider? = null, userToken: String? = null) = ApiClient(
        RiviumAbTestingConfig(apiKey = "rv_live_abcdef123456", tokenProvider = provider, userToken = userToken),
        baseUrl = server.url("").toString().trimEnd('/')
    )

    private fun ok(body: String = """{"flags":[]}""") = MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun `reads send the api key and never the user token`() {
        server.enqueue(ok())
        server.enqueue(ok("""{"data":[]}"""))
        var calls = 0
        val api = client(RiviumTokenProvider { calls++; userToken("alice") })

        assertTrue(api.fetchFeatureFlags() is ApiResult.Success)
        assertTrue(api.fetchExperiments() is ApiResult.Success)

        repeat(2) {
            val request = server.takeRequest()
            assertEquals("rv_live_abcdef123456", request.getHeader("x-api-key"))
            assertNull(request.getHeader("x-user-token"))
        }
        assertEquals(0, calls)
    }

    @Test
    fun `a user call sends the token it is given`() {
        server.enqueue(ok("""{"success":true,"synced":1,"failed":0}"""))
        val api = client()
        val token = userToken("alice")
        val event = TrackEvent(experimentId = "e", variantId = "v", userId = "alice", eventType = EventType.VIEW)

        val result = api.syncEvents(listOf(event), "device", token)

        assertEquals(1, result.valueOrNull()?.synced)
        val request = server.takeRequest()
        assertEquals(token, request.getHeader("x-user-token"))
        assertTrue(request.body.readUtf8().contains(event.clientEventId))
    }

    @Test
    fun `the provider is asked once while its token is fresh`() {
        var calls = 0
        val api = client(RiviumTokenProvider { calls++; userToken("alice") })

        val first = api.fetchToken()
        val second = api.heldToken() ?: api.fetchToken()

        assertEquals(1, calls)
        assertEquals(first, second)
    }

    @Test
    fun `a token that is about to expire is fetched again`() {
        var calls = 0
        val api = client(RiviumTokenProvider { calls++; userToken("alice", expiresIn = 30) })

        api.fetchToken()
        assertNull(api.heldToken())
        api.fetchToken()

        assertEquals(2, calls)
    }

    @Test
    fun `callers at the same moment share one provider call`() {
        val calls = AtomicInteger()
        val api = client(RiviumTokenProvider { calls.incrementAndGet(); Thread.sleep(200); userToken("alice") })

        val threads = (1..4).map { thread { api.fetchToken() } }
        threads.forEach { it.join() }

        assertEquals(1, calls.get())
    }

    @Test
    fun `a provider that returns null or throws means no token`() {
        assertNull(client(RiviumTokenProvider { null }).fetchToken())
        assertNull(client(RiviumTokenProvider { throw IllegalStateException("signed out") }).fetchToken())
        assertNull(client(RiviumTokenProvider { "" }).fetchToken())
        assertNull(client().fetchToken())
    }

    @Test
    fun `a token the app set wins and null takes every token away`() {
        val api = client(RiviumTokenProvider { userToken("alice") })
        api.fetchToken()

        val pushed = userToken("alice", salt = "2")
        api.setUserToken(pushed)
        assertEquals(pushed, api.heldToken())

        api.setUserToken(null)
        assertNull(api.peekToken())
    }

    @Test
    fun `tokens for another user are dropped on a user change`() {
        val api = client(userToken = userToken("alice"))

        api.dropTokensNotFor("alice")
        assertTrue(api.heldToken() != null)

        api.dropTokensNotFor("bob")
        assertNull(api.heldToken())
    }

    @Test
    fun `error code and retry-after are read from the answer`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "7")
                .setBody("""{"statusCode":429,"message":"Too many requests"}""")
        )
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"statusCode":401,"code":"token_required","message":"A signed user token is required"}"""))
        val api = client()

        val limited = api.getAssignment("checkout", "alice", null, "t") as ApiResult.Failure
        val refused = api.getAssignment("checkout", "alice", null, "t") as ApiResult.Failure

        assertEquals(429, limited.status)
        assertEquals(7000L, limited.retryAfterMs)
        assertEquals("token_required", refused.code)
        assertEquals("A signed user token is required", refused.message)
    }

    @Test
    fun `no answer is a failure without a status`() {
        server.shutdown()
        val failure = client().fetchFeatureFlags() as ApiResult.Failure
        assertNull(failure.status)
    }

    @Test
    fun `api keys are redacted for logs`() {
        assertEquals("rv_live_…3456", Redact.key("rv_live_abcdef123456"))
        assertFalse(
            RiviumAbTestingConfig(apiKey = "rv_live_abcdef123456").toString().contains("abcdef")
        )
    }
}

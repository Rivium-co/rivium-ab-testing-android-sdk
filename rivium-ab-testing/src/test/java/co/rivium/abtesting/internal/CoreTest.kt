package co.rivium.abtesting.internal

import co.rivium.abtesting.RiviumAbTestingCallback
import co.rivium.abtesting.RiviumAbTestingConfig
import co.rivium.abtesting.RiviumAbTestingError
import co.rivium.abtesting.RiviumTokenProvider
import co.rivium.abtesting.models.EventType
import kotlinx.coroutines.Dispatchers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CoreTest {
    private lateinit var server: MockWebServer
    private val service = FakeService()
    private val prefs = InMemoryStore()
    private val eventStore = InMemoryStore()
    private val cores = mutableListOf<Core>()

    private val errors = CopyOnWriteArrayList<RiviumAbTestingError>()
    private val assigned = CopyOnWriteArrayList<String>()
    private val synced = CopyOnWriteArrayList<Triple<Int, Int, Int>>()
    private val initialized = AtomicInteger()
    private val awaiting = AtomicInteger()

    private val callback = object : RiviumAbTestingCallback {
        override fun onInitialized() { initialized.incrementAndGet() }
        override fun onError(error: RiviumAbTestingError) { errors.add(error) }
        override fun onExperimentAssigned(experimentKey: String, variantKey: String, config: Map<String, Any>?) {
            assigned.add("$experimentKey=$variantKey")
        }
        override fun onSyncCompleted(synced: Int, failed: Int, pending: Int) {
            this@CoreTest.synced.add(Triple(synced, failed, pending))
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = service
        server.start()
    }

    @After
    fun tearDown() {
        cores.forEach { it.close(clearData = false) }
        server.shutdown()
    }

    /** A started SDK, waited for until its first load is done. */
    private fun start(
        provider: RiviumTokenProvider? = null,
        userToken: String? = null,
        maxQueueSize: Int = 100_000,
        awaitLoad: Boolean = true
    ): Core {
        val config = RiviumAbTestingConfig(
            apiKey = "rv_live_abcdef123456",
            flushInterval = 3_600_000L, // the tests flush by hand
            maxQueueSize = maxQueueSize,
            tokenProvider = provider,
            userToken = userToken
        )
        val core = Core(
            config = config,
            storage = Storage(prefs),
            api = ApiClient(config, baseUrl = server.url("").toString().trimEnd('/')),
            eventStore = eventStore,
            deviceId = { "device" },
            callbacks = listOf(callback),
            onAwaitingUserToken = { awaiting.incrementAndGet() },
            main = Dispatchers.Unconfined
        )
        cores.add(core)
        val before = service.to("/public/flags").size
        core.start()
        if (awaitLoad) {
            eventually { service.to("/public/flags").size > before }
            settle(150)
        }
        return core
    }

    private fun Core.track(experimentKey: String = "checkout", type: EventType = EventType.CLICK) =
        trackEvent(experimentKey, type, null, null, null)

    private fun Core.variantAsync(experimentKey: String = "checkout", default: String = "control"): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        getVariantAsync(experimentKey, default) { result.set(it); latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun syncedEvents() = service.to("/public/sync").flatMap { FakeService.eventsOf(it) }

    // ---------------------------------------------------------------
    // Waiting for a user token
    // ---------------------------------------------------------------

    @Test
    fun `without a token nothing is sent for a user, the default is returned and the event is parked`() {
        val core = start()

        assertEquals(1, initialized.get())
        assertTrue(core.isAwaitingUserToken)
        assertEquals(1, awaiting.get())

        assertEquals("fallback", core.getVariant("checkout", "fallback"))
        assertEquals("fallback", core.variantAsync(default = "fallback"))
        core.track()
        core.flush()
        settle()

        assertEquals(1, core.pendingEventCount())
        assertNull(core.getVariantConfig("checkout"))
        assertTrue(service.userCalls().isEmpty())
        assertTrue(errors.isEmpty())
        assertEquals("told once", 1, awaiting.get())
    }

    @Test
    fun `a provider that returns null and then a token - the assignment is fetched and the parked events are sent`() {
        val current = AtomicReference<String?>(null)
        val core = start(provider = RiviumTokenProvider { current.get() })
        core.setUserId("alice")
        eventually { core.isAwaitingUserToken }

        assertEquals("control", core.getVariant("checkout", "control"))
        core.track()
        core.track(type = EventType.CONVERSION)
        settle()
        assertTrue(service.userCalls().isEmpty())
        assertEquals(2, core.pendingEventCount())

        val token = userToken("alice")
        current.set(token)
        core.refreshUserToken()

        eventually { core.pendingEventCount() == 0 }
        assertFalse(core.isAwaitingUserToken)
        assertEquals("treatment", core.getVariant("checkout", "control"))
        assertEquals(listOf("checkout=treatment"), assigned.toList())
        assertEquals(token, service.to("/public/assign").single().token)

        val events = syncedEvents()
        assertEquals(2, events.size)
        assertTrue(events.all { it.getString("variantId") == "var-treatment" && it.getString("userId") == "alice" })
        assertTrue(events.all { it.getString("clientEventId").isNotEmpty() })
        assertTrue(service.to("/public/sync").all { it.token == token })
        eventually { synced.isNotEmpty() }
        assertEquals(Triple(2, 0, 0), synced.first())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a provider that throws is not an error`() {
        val core = start(provider = RiviumTokenProvider { throw IllegalStateException("signed out") })

        assertTrue(core.isAwaitingUserToken)
        assertEquals("control", core.getVariant("checkout", "control"))
        settle()
        assertTrue(errors.isEmpty())
        assertTrue(service.userCalls().isEmpty())
    }

    @Test
    fun `setUserToken ends the wait and null starts it again`() {
        val core = start()
        assertTrue(core.isAwaitingUserToken)
        core.getVariant("checkout", "control")

        core.setUserToken(userToken("alice"))
        assertFalse(core.isAwaitingUserToken)
        assertEquals("alice", core.userId)
        eventually { core.getVariant("checkout", "control") == "treatment" }
        core.setUserAttributes(mapOf("plan" to "pro"))
        core.track()

        core.setUserToken(null)

        assertTrue(core.isAwaitingUserToken)
        assertEquals(2, awaiting.get())
        assertEquals("control", core.getVariant("checkout", "control"))
        assertTrue(core.userId != "alice")
        assertNull(Storage(prefs).userAttributes)
        assertEquals(0, core.pendingEventCount())
    }

    @Test
    fun `while waiting the provider is not asked on every call`() {
        val calls = AtomicInteger()
        val core = start(provider = RiviumTokenProvider { calls.incrementAndGet(); null })
        assertEquals(1, calls.get())

        repeat(20) { core.getVariant("checkout", "control"); core.track(); core.flush() }
        core.variantAsync()
        settle()

        assertEquals(1, calls.get())
        assertTrue(service.userCalls().isEmpty())
    }

    @Test
    fun `events recorded before sign-in are sent only for the same user id`() {
        val core = start()
        val anonymous = core.userId
        core.getVariant("checkout", "control")
        core.track()
        assertEquals(1, core.pendingEventCount())

        core.setUserToken(userToken("alice"))

        eventually { core.getVariant("checkout", "control") == "treatment" }
        settle()
        assertTrue(anonymous != core.userId)
        assertEquals(0, core.pendingEventCount())
        assertTrue(service.to("/public/sync").isEmpty())
    }

    @Test
    fun `attributes set before sign-in are kept for the user who signs in`() {
        val core = start()
        core.setUserAttributes(mapOf("plan" to "pro"))
        core.getVariant("checkout", "control")

        core.setUserToken(userToken("alice"))

        eventually { service.to("/public/assign").isNotEmpty() }
        assertTrue(service.to("/public/assign").single().body.contains("\"plan\":\"pro\""))
    }

    @Test
    fun `setUserId keeps the attributes of an anonymous user and clears another user's`() {
        val core = start()
        core.setUserAttributes(mapOf("plan" to "pro"))

        core.setUserId("alice")
        assertEquals("pro", Storage(prefs).userAttributes?.get("plan"))

        core.setUserId("bob")
        assertNull(Storage(prefs).userAttributes)
    }

    @Test
    fun `a user id kept by an older version is not taken for an anonymous one`() {
        val storage = Storage(prefs)
        storage.userId = "alice"
        assertFalse(storage.userIdGenerated)
        storage.userId = "3f2b8c1e-9d4a-4b7c-8e21-0a1b2c3d4e5f"
        assertTrue(storage.userIdGenerated)
    }

    // ---------------------------------------------------------------
    // What the service says about the token
    // ---------------------------------------------------------------

    @Test
    fun `token_required puts the SDK in the waiting state without retrying`() {
        service.assign = { tokenError("token_required") }
        val core = start(userToken = "not-a-real-token")
        assertFalse(core.isAwaitingUserToken)

        assertEquals("control", core.variantAsync())
        assertTrue(core.isAwaitingUserToken)
        repeat(5) { core.getVariant("checkout", "control") }
        core.track()
        core.flush()
        settle()

        assertEquals(1, service.to("/public/assign").size)
        assertTrue(service.to("/public/sync").isEmpty())
        assertTrue(errors.isEmpty())
        assertEquals(1, awaiting.get())
        assertEquals(1, core.pendingEventCount())
    }

    @Test
    fun `token_expired - one refresh and one retry`() {
        val calls = AtomicInteger()
        val core = start(provider = RiviumTokenProvider { userToken("alice", salt = "${calls.incrementAndGet()}") })
        val answers = AtomicInteger()
        service.assign = { if (answers.incrementAndGet() == 1) tokenError("token_expired") else FakeService.assigned() }

        assertEquals("treatment", core.variantAsync())

        assertEquals(2, service.to("/public/assign").size)
        assertEquals(2, calls.get())
        assertTrue(service.to("/public/assign")[0].token != service.to("/public/assign")[1].token)
        assertFalse(core.isAwaitingUserToken)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `token_expired and no new token - the SDK waits, it does not loop`() {
        val calls = AtomicInteger()
        val core = start(provider = RiviumTokenProvider { if (calls.incrementAndGet() == 1) userToken("alice") else null })
        service.assign = { tokenError("token_expired") }

        assertEquals("control", core.variantAsync())
        settle()

        assertTrue(core.isAwaitingUserToken)
        assertEquals(1, service.to("/public/assign").size)
        assertEquals(2, calls.get())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `token_expired again after the refresh is not retried for ever`() {
        val calls = AtomicInteger()
        val core = start(provider = RiviumTokenProvider { userToken("alice", salt = "${calls.incrementAndGet()}") })
        service.assign = { tokenError("token_expired") }

        assertEquals("control", core.variantAsync())
        repeat(3) { core.getVariant("checkout", "control") }
        settle()

        assertEquals(2, service.to("/public/assign").size)
    }

    @Test
    fun `token_invalid is reported once and not retried`() {
        val core = start(userToken = userToken("alice"))
        service.assign = { tokenError("token_invalid") }

        assertEquals("control", core.variantAsync())
        repeat(5) { core.getVariant("checkout", "control") }
        assertEquals("control", core.variantAsync("pricing"))
        settle()

        assertEquals(1, service.to("/public/assign").size)
        assertEquals(1, errors.size)
        assertEquals(401, (errors.single() as RiviumAbTestingError.ApiError).code)
        assertTrue(core.isAwaitingUserToken)
    }

    // ---------------------------------------------------------------
    // Users
    // ---------------------------------------------------------------

    @Test
    fun `after a user switch nothing of the first user is sent under the second user's token`() {
        val user = AtomicReference("alice")
        val core = start(provider = RiviumTokenProvider { userToken(user.get()) })
        assertEquals("alice", core.userId)
        assertEquals("treatment", core.variantAsync())
        core.setUserAttributes(mapOf("plan" to "pro"))
        // The service is unreachable for alice's events, so they are still queued at the switch.
        service.sync = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        core.track()
        core.track()

        user.set("bob")
        service.sync = { call -> FakeService.synced(FakeService.eventsOf(call).size) }
        core.setUserId("bob")

        eventually { service.to("/public/assign").size == 1 && core.userId == "bob" }
        assertEquals("control", core.getVariant("checkout", "control"))
        eventually { core.getVariant("checkout", "control") == "treatment" }
        core.track()
        core.flush()
        eventually { syncedEvents().any { it.getString("userId") == "bob" } }
        settle()

        assertNull("attributes are the first user's", Storage(prefs).userAttributes?.get("plan"))
        for (call in service.to("/public/sync")) {
            val tokenUser = TokenClaims.subject(call.token)
            assertTrue(FakeService.eventsOf(call).all { it.getString("userId") == tokenUser })
        }
        assertTrue(service.to("/public/assign").drop(1).all { TokenClaims.subject(it.token) == "bob" })
    }

    @Test
    fun `a token for another user is a user switch`() {
        val core = start(userToken = userToken("alice"))
        assertEquals("treatment", core.variantAsync())
        core.setUserAttributes(mapOf("plan" to "pro"))
        service.sync = { json(500, "{}") }
        core.track()
        core.flush()
        eventually { service.to("/public/sync").size == 1 }
        service.sync = { call -> FakeService.synced(FakeService.eventsOf(call).size) }

        core.setUserToken(userToken("bob"))

        assertEquals("bob", core.userId)
        assertEquals("control", core.getVariant("checkout", "control"))
        assertNull(Storage(prefs).userAttributes)
        settle()
        for (call in service.to("/public/sync")) {
            val tokenUser = TokenClaims.subject(call.token)
            assertTrue(FakeService.eventsOf(call).all { it.getString("userId") == tokenUser })
        }
        assertEquals(0, core.pendingEventCount())
    }

    @Test
    fun `clearUserId signs the user out`() {
        val core = start(userToken = userToken("alice"))
        assertEquals("treatment", core.variantAsync())

        core.clearUserId()

        assertTrue(core.isAwaitingUserToken)
        assertTrue(core.userId != null && core.userId != "alice")
        assertEquals("control", core.getVariant("checkout", "control"))
        val before = service.userCalls().size
        core.track()
        core.flush()
        settle()
        assertEquals(before, service.userCalls().size)
    }

    // ---------------------------------------------------------------
    // Variants
    // ---------------------------------------------------------------

    @Test
    fun `201 and 200 are both a success`() {
        service.assign = { call ->
            if (call.body.contains("pricing")) FakeService.assigned(201, "exp-2", "pricing", "b") else FakeService.assigned(200)
        }
        val core = start(userToken = userToken("alice"))

        assertEquals("treatment", core.variantAsync("checkout"))
        assertEquals("b", core.variantAsync("pricing"))
        assertEquals("blue", core.getVariantConfig("pricing")?.get("color"))
    }

    @Test
    fun `getVariant does not wait for the service`() {
        service.assign = { FakeService.assigned().setBodyDelay(700, TimeUnit.MILLISECONDS) }
        val core = start(userToken = userToken("alice"))

        val started = System.currentTimeMillis()
        val first = core.getVariant("checkout", "control")
        val took = System.currentTimeMillis() - started

        assertEquals("control", first)
        assertTrue("took ${took}ms", took < 300)
        eventually { core.getVariant("checkout", "control") == "treatment" }
        assertEquals(1, service.to("/public/assign").size)
    }

    @Test
    fun `no variant is made up on the device when the service cannot be asked`() {
        service.assign = { json(500, "{}") }
        val core = start(userToken = userToken("alice"))

        assertEquals("control", core.variantAsync())
        assertEquals("control", core.getVariant("checkout", "control"))
        assertNull(core.getVariantConfig("checkout"))
        assertTrue(assigned.isEmpty())
    }

    @Test
    fun `a user outside the experiment gets the control and their events are sent once`() {
        service.assign = { FakeService.assigned(variantKey = "control", inExperiment = false) }
        // The service takes the request and counts such an event as not stored.
        service.sync = { call ->
            json(200, """{"success":true,"synced":0,"failed":${FakeService.eventsOf(call).size},"errors":["Event x: User is not in this experiment"]}""")
        }
        val core = start(userToken = userToken("alice"))

        assertEquals("control", core.variantAsync(default = "fallback"))
        core.track()
        core.flush()
        eventually { service.to("/public/sync").size == 1 }
        settle()
        core.flush()
        settle()

        val sent = syncedEvents()
        assertEquals(1, sent.size)
        assertEquals("exp-1", sent[0].getString("experimentId"))
        assertEquals("alice", sent[0].getString("userId"))
        // Not kept, not sent again, and not an error.
        assertEquals(0, core.pendingEventCount())
        assertEquals(1, service.to("/public/sync").size)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `events recorded before the service said the user is outside are sent too`() {
        val token = AtomicReference<String?>(null)
        service.assign = { FakeService.assigned(variantKey = "control", inExperiment = false) }
        val core = start(provider = RiviumTokenProvider { token.get() })
        core.setUserId("alice")
        eventually { core.isAwaitingUserToken }

        assertEquals("fallback", core.getVariant("checkout", "fallback"))
        core.track()
        assertEquals(1, core.pendingEventCount())
        assertTrue(service.userCalls().isEmpty())

        token.set(userToken("alice"))
        core.refreshUserToken()
        eventually { service.to("/public/sync").isNotEmpty() }

        val sent = syncedEvents()
        assertEquals(1, sent.size)
        assertEquals("alice", sent[0].getString("userId"))
        assertTrue(sent[0].getString("variantId").isNotEmpty())
        eventually { core.pendingEventCount() == 0 }
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `an answer without inExperiment (older service) is an assignment`() {
        service.assign = { FakeService.assigned(inExperiment = null) }
        val core = start(userToken = userToken("alice"))

        assertEquals("treatment", core.variantAsync())
        core.track()
        core.flush()
        eventually { syncedEvents().size == 1 }
    }

    // ---------------------------------------------------------------
    // The event queue
    // ---------------------------------------------------------------

    @Test
    fun `the queue keeps the newest 1000 events`() {
        val core = start()
        core.getVariant("checkout", "control")

        repeat(1100) { core.track() }

        assertEquals(1000, core.pendingEventCount())
        assertTrue(service.userCalls().isEmpty())
    }

    @Test
    fun `events are sent 100 at a time and each one once`() {
        val core = start(userToken = userToken("alice"))
        assertEquals("treatment", core.variantAsync())
        service.sync = { call -> FakeService.synced(FakeService.eventsOf(call).size).setBodyDelay(50, TimeUnit.MILLISECONDS) }

        repeat(250) { core.track() }
        repeat(3) { core.flush() }

        eventually { core.pendingEventCount() == 0 }
        settle()
        assertEquals(listOf(100, 100, 50), service.to("/public/sync").map { FakeService.eventsOf(it).size })
        val ids = syncedEvents().map { it.getString("clientEventId") }
        assertEquals(250, ids.toSet().size)
    }

    @Test
    fun `a refused chunk is dropped and the rest is still sent`() {
        val core = start(userToken = userToken("alice"))
        assertEquals("treatment", core.variantAsync())
        val answers = AtomicInteger()
        service.sync = { call ->
            if (answers.incrementAndGet() == 1) json(400, """{"statusCode":400,"message":"Bad Request"}""")
            else FakeService.synced(FakeService.eventsOf(call).size)
        }

        repeat(150) { core.track() }
        core.flush()

        eventually { core.pendingEventCount() == 0 }
        assertEquals(listOf(100, 50), service.to("/public/sync").map { FakeService.eventsOf(it).size })
    }

    @Test
    fun `429 is honoured - nothing is sent before Retry-After has passed`() {
        val core = start(userToken = userToken("alice"))
        assertEquals("treatment", core.variantAsync())
        val answers = AtomicInteger()
        service.sync = { call ->
            if (answers.incrementAndGet() == 1) json(429, """{"statusCode":429,"message":"Too many requests"}""").setHeader("Retry-After", "2")
            else FakeService.synced(FakeService.eventsOf(call).size)
        }
        core.track()

        core.flush()
        eventually { service.to("/public/sync").size == 1 }
        repeat(5) { core.flush(); core.track() }
        assertEquals("control", core.variantAsync("pricing"))
        settle()
        assertEquals(1, service.to("/public/sync").size)
        assertTrue(service.to("/public/assign").size == 1)
        assertEquals(6, core.pendingEventCount())

        Thread.sleep(2000)
        core.flush()
        eventually { core.pendingEventCount() == 0 }
        assertEquals(2, service.to("/public/sync").size)
    }

    @Test
    fun `the monthly event limit keeps the events and stops sending`() {
        val core = start(userToken = userToken("alice"), maxQueueSize = 1)
        assertEquals("treatment", core.variantAsync())
        service.sync = { json(403, """{"statusCode":403,"code":"event_limit_reached","message":"Event limit reached"}""") }

        repeat(10) { core.track() }
        core.flush()
        settle()

        assertEquals(1, service.to("/public/sync").size)
        assertEquals(10, core.pendingEventCount())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a server error is retried later, not at once`() {
        val core = start(userToken = userToken("alice"), maxQueueSize = 1)
        assertEquals("treatment", core.variantAsync())
        service.sync = { json(503, "{}") }

        repeat(10) { core.track() }
        repeat(3) { core.flush() }
        settle()

        assertEquals(1, service.to("/public/sync").size)
        assertEquals(10, core.pendingEventCount())
    }

    @Test
    fun `queued events survive a restart`() {
        val first = start()
        first.getVariant("checkout", "control")
        first.setUserId("alice")
        first.getVariant("checkout", "control")
        first.track()
        first.close(clearData = false)

        val second = start(userToken = userToken("alice"))

        eventually { second.pendingEventCount() == 0 && syncedEvents().size == 1 }
        assertEquals("var-treatment", syncedEvents().single().getString("variantId"))
    }

    // ---------------------------------------------------------------
    // Start-up
    // ---------------------------------------------------------------

    @Test
    fun `a failed first load leaves a working SDK that can load again`() {
        service.reads = { json(503, "{}") }
        val core = start(userToken = userToken("alice"))

        assertFalse(core.isInitialized)
        assertEquals(1, errors.size)
        assertEquals("control", core.getVariant("checkout", "control"))
        assertFalse(core.isFeatureEnabled("new-ui", false))
        core.track()
        core.setUserAttributes(mapOf("plan" to "pro"))

        service.reads = { null }
        core.retryLoad()

        eventually { core.isInitialized }
        assertEquals(1, initialized.get())
        assertTrue(core.isFeatureEnabled("new-ui", false))
        assertEquals("treatment", core.variantAsync())
    }

    @Test
    fun `flag evaluation needs a token too and falls back to the local answer`() {
        val core = start()
        val latch = CountDownLatch(1)
        val enabled = AtomicReference<Boolean>()
        core.isFeatureEnabledAsync("new-ui", false) { enabled.set(it); latch.countDown() }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(enabled.get())
        assertTrue(service.to("/public/flag-evaluation").isEmpty())
    }

    @Test
    fun `a callback that throws does not break the SDK`() {
        val config = RiviumAbTestingConfig(apiKey = "rv_live_abcdef123456", flushInterval = 3_600_000L, userToken = userToken("alice"))
        val core = Core(
            config, Storage(prefs), ApiClient(config, baseUrl = server.url("").toString().trimEnd('/')), eventStore,
            { "device" },
            listOf(object : RiviumAbTestingCallback {
                override fun onExperimentAssigned(experimentKey: String, variantKey: String, config: Map<String, Any>?) {
                    throw IllegalStateException("app bug")
                }
            }, callback),
            { throw IllegalStateException("app bug") },
            main = Dispatchers.Unconfined
        )
        cores.add(core)
        core.start()

        eventually { core.isInitialized }
        assertEquals("treatment", core.variantAsync())
        assertEquals(listOf("checkout=treatment"), assigned.toList())
    }
}

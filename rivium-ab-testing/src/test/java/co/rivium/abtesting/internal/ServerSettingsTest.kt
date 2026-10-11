package co.rivium.abtesting.internal

import co.rivium.abtesting.RiviumAbTestingConfig
import co.rivium.abtesting.models.EventType
import kotlinx.coroutines.Dispatchers
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** The send interval and the list cache the console sets, and the time on each event. */
class ServerSettingsTest {
    private lateinit var server: MockWebServer
    private val service = FakeService()
    private val cores = mutableListOf<Core>()
    private val now = AtomicLong(1_760_000_000_000L)

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

    private fun start(flushInterval: Long? = null, userToken: String? = null): Core {
        val config = if (flushInterval == null) {
            RiviumAbTestingConfig(apiKey = "rv_live_abcdef123456", maxQueueSize = 100_000, userToken = userToken)
        } else {
            RiviumAbTestingConfig(
                apiKey = "rv_live_abcdef123456",
                flushInterval = flushInterval,
                maxQueueSize = 100_000,
                userToken = userToken
            )
        }
        val core = Core(
            config = config,
            storage = Storage(InMemoryStore()),
            api = ApiClient(config, baseUrl = server.url("").toString().trimEnd('/')),
            eventStore = InMemoryStore(),
            deviceId = { "device" },
            callbacks = emptyList(),
            onAwaitingUserToken = {},
            main = Dispatchers.Unconfined,
            clock = { now.get() }
        )
        cores.add(core)
        core.start()
        eventually { service.to("/public/flags").isNotEmpty() }
        settle(150)
        return core
    }

    @Test
    fun `the console's send interval and list cache are followed`() {
        service.config = """{"cacheTtlSeconds":120,"syncIntervalSeconds":60}"""
        val core = start()

        assertEquals(60_000L, core.flushIntervalMs)
        assertEquals(120_000L, core.listCacheMs)
    }

    @Test
    fun `without an answer from the console the built-in values stay`() {
        service.config = """{}"""
        val core = start()

        assertEquals(30_000L, core.flushIntervalMs)
        assertEquals(300_000L, core.listCacheMs)
    }

    @Test
    fun `an interval the app chose wins over the console's`() {
        service.config = """{"cacheTtlSeconds":120,"syncIntervalSeconds":60}"""
        val core = start(flushInterval = 5_000L)

        assertEquals(5_000L, core.flushIntervalMs)
        // The list cache has no option in the app: the console's value is used.
        assertEquals(120_000L, core.listCacheMs)
    }

    @Test
    fun `values out of bounds are brought inside them`() {
        service.config = """{"cacheTtlSeconds":1,"syncIntervalSeconds":1}"""
        val low = start()
        assertEquals(10_000L, low.flushIntervalMs)
        assertEquals(60_000L, low.listCacheMs)

        service.config = """{"cacheTtlSeconds":99999999,"syncIntervalSeconds":99999999}"""
        val high = start()
        assertEquals(3_600_000L, high.flushIntervalMs)
        assertEquals(86_400_000L, high.listCacheMs)

        service.config = """{"cacheTtlSeconds":0,"syncIntervalSeconds":-5}"""
        val none = start()
        assertEquals(30_000L, none.flushIntervalMs)
        assertEquals(300_000L, none.listCacheMs)
    }

    @Test
    fun `the list is reused while it is fresh and asked for again once it is stale`() {
        service.config = """{"cacheTtlSeconds":120,"syncIntervalSeconds":60}"""
        val core = start(userToken = userToken("user-1"))
        val lists = service.to("/public/experiments").size
        val inits = service.to("/public/init").size

        now.addAndGet(119_000L)
        repeat(5) { core.getVariant("checkout", "control") }
        settle()
        assertEquals(lists, service.to("/public/experiments").size)
        assertEquals(inits, service.to("/public/init").size)

        // The console changed both values in the meantime.
        service.config = """{"cacheTtlSeconds":600,"syncIntervalSeconds":45}"""
        now.addAndGet(2_000L)
        repeat(5) { core.getVariant("checkout", "control") }
        eventually { service.to("/public/experiments").size == lists + 1 }
        settle()

        // One refresh, however many callers; the new values are in use without a restart.
        assertEquals(lists + 1, service.to("/public/experiments").size)
        assertEquals(inits + 1, service.to("/public/init").size)
        assertEquals(45_000L, core.flushIntervalMs)
        assertEquals(600_000L, core.listCacheMs)

        now.addAndGet(599_000L)
        core.getVariant("checkout", "control")
        settle()
        assertEquals(lists + 1, service.to("/public/experiments").size)
    }

    @Test
    fun `a refresh that fails is not repeated on every call`() {
        val core = start(userToken = userToken("user-1"))
        val lists = service.to("/public/experiments").size

        service.reads = { json(500, "{}") }
        now.addAndGet(301_000L)
        repeat(5) { core.getVariant("checkout", "control"); settle(60) }
        assertEquals(lists + 1, service.to("/public/experiments").size)

        service.reads = { null }
        now.addAndGet(61_000L)
        core.getVariant("checkout", "control")
        eventually { service.to("/public/experiments").size == lists + 2 }
    }

    @Test
    fun `every event carries the time it was recorded, in milliseconds`() {
        val core = start(userToken = userToken("user-1"))
        core.getVariant("checkout", "control")
        eventually { service.to("/public/assign").isNotEmpty() }
        settle()

        val before = System.currentTimeMillis()
        core.trackEvent("checkout", EventType.VIEW, null, null, null)
        Thread.sleep(5)
        core.trackEvent("checkout", EventType.CONVERSION, "purchase", 9.5, null)
        val after = System.currentTimeMillis()
        core.flush()
        eventually { service.to("/public/sync").isNotEmpty() }

        val events = FakeService.eventsOf(service.to("/public/sync").first())
        assertEquals(2, events.size)
        val times = events.map { it.getLong("timestamp") }
        assertTrue(times.all { it in before..after })
        assertTrue(times[0] < times[1])
    }
}

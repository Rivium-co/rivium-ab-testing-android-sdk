package co.rivium.abtesting.internal

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal class InMemoryStore : KeyValueStore {
    private val values = ConcurrentHashMap<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun clear() = values.clear()
}

/** A token shaped like the real one: only `sub` and `exp` matter to the SDK. */
internal fun userToken(sub: String, expiresIn: Long = 3600, salt: String = ""): String {
    val exp = System.currentTimeMillis() / 1000 + expiresIn
    val payload = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"sub":"$sub","exp":$exp}""".toByteArray())
    return "header.$payload.signature$salt"
}

internal fun json(code: Int, body: String): MockResponse = MockResponse().setResponseCode(code).setBody(body)

internal fun tokenError(code: String): MockResponse =
    json(401, """{"statusCode":401,"error":"Unauthorized","code":"$code","message":"$code"}""")

/** Stands in for the service: answers by route and records what was sent. */
internal class FakeService : Dispatcher() {
    class Call(val path: String, val token: String?, val body: String)

    val calls = CopyOnWriteArrayList<Call>()

    @Volatile var reads: () -> MockResponse? = { null }
    @Volatile var assign: (Call) -> MockResponse = { assigned() }
    @Volatile var sync: (Call) -> MockResponse = { call -> synced(eventsOf(call).size) }
    @Volatile var flagEvaluation: (Call) -> MockResponse = { json(200, """{"flagKey":"f","enabled":true}""") }

    fun to(path: String) = calls.filter { it.path == path }

    /** Calls that act for a user: none of these may happen without a user token. */
    fun userCalls() = calls.filter { it.path in setOf("/public/assign", "/public/sync", "/public/flag-evaluation") }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val call = Call(
            request.path.orEmpty().substringBefore('?'),
            request.getHeader("x-user-token"),
            request.body.readUtf8()
        )
        calls.add(call)
        return when (call.path) {
            "/public/init" -> reads() ?: json(200, """{"experiments":[],"config":{}}""")
            "/public/experiments" -> reads() ?: json(200, EXPERIMENTS)
            "/public/flags" -> reads() ?: json(200, """{"flags":[{"id":"f1","key":"new-ui","name":"New UI","enabled":true,"rolloutPercentage":100}]}""")
            "/public/assign" -> assign(call)
            "/public/sync" -> sync(call)
            "/public/flag-evaluation" -> flagEvaluation(call)
            else -> json(404, "{}")
        }
    }

    companion object {
        const val EXPERIMENTS = """{"data":[
            {"id":"exp-1","key":"checkout","name":"Checkout","status":"running","trafficAllocation":100,"targetingRules":null,"variants":[]},
            {"id":"exp-2","key":"pricing","name":"Pricing","status":"running","trafficAllocation":100,"targetingRules":null,"variants":[]}
        ]}"""

        fun assigned(
            code: Int = 200,
            experimentId: String = "exp-1",
            experimentKey: String = "checkout",
            variantKey: String = "treatment",
            inExperiment: Boolean? = true
        ): MockResponse = json(
            code,
            """{"data":{"experimentId":"$experimentId","experimentKey":"$experimentKey","variantId":"var-$variantKey",""" +
                """"variantKey":"$variantKey","variantName":"T","config":{"color":"blue"},"isControl":false,"isNew":true""" +
                (if (inExperiment != null) ""","inExperiment":$inExperiment""" else "") + "}}"
        )

        fun synced(count: Int): MockResponse = json(200, """{"success":true,"synced":$count,"failed":0}""")

        fun eventsOf(call: Call): List<JSONObject> {
            val events = JSONObject(call.body).getJSONArray("events")
            return (0 until events.length()).map { events.getJSONObject(it) }
        }
    }
}

/** Waits until [condition] holds; fails the test if it never does. */
internal fun eventually(timeoutMs: Long = 5000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("condition not met within ${timeoutMs}ms")
}

/** Gives background work a moment, for asserting that something did NOT happen. */
internal fun settle(ms: Long = 250) = Thread.sleep(ms)

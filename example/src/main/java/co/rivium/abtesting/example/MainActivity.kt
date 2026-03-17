package co.rivium.abtesting.example

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import co.rivium.abtesting.RiviumAbTesting
import co.rivium.abtesting.RiviumAbTestingCallback
import co.rivium.abtesting.RiviumAbTestingConfig
import co.rivium.abtesting.RiviumAbTestingError
import co.rivium.abtesting.models.EventType
import co.rivium.abtesting.models.Experiment
import co.rivium.abtesting.models.FeatureFlag
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/// ============================================
/// CONFIGURATION — matches Flutter example and
/// docs/ABTEST_FLAGS_TEST_SCENARIO.md
/// ============================================
private const val API_KEY = "YOUR_API_KEY_HERE"
private const val BASE_URL = "https://abtest.rivium.co"

// Experiments created in Part 2 of the test scenario
private const val EXPERIMENT_CHECKOUT = "checkout-flow-test"
private const val EXPERIMENT_PRICING = "pricing-page-test"

// Feature flags created in Part 3 (managed via RiviumFlags)
private const val FLAG_DARK_MODE = "dark_mode"
private const val FLAG_ONBOARDING_FLOW = "onboarding_flow"
private const val FLAG_DARK_MODE_SETTINGS = "dark_mode_settings"
private const val FLAG_HOLIDAY_BANNER = "holiday_banner"

class MainActivity : AppCompatActivity() {

    private val logs = mutableListOf<String>()
    private var isInitialized = false
    private var currentVariant: String? = null
    private var variantConfig: Map<String, Any>? = null

    private lateinit var logAdapter: ArrayAdapter<String>
    private lateinit var logListView: ListView
    private lateinit var statusText: TextView
    private lateinit var statusBar: LinearLayout
    private lateinit var variantChip: TextView
    private lateinit var configChip: TextView
    private lateinit var buttonContainer: LinearLayout

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Status bar
        statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FEE2E2")) // red-50
        }

        statusText = TextView(this).apply {
            text = "SDK Not Initialized"
            setTextColor(Color.parseColor("#DC2626"))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        statusBar.addView(statusText)

        variantChip = TextView(this).apply {
            visibility = android.view.View.GONE
            setPadding(16, 4, 16, 4)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#F3E8FF"))
            setTextColor(Color.parseColor("#7C3AED"))
        }
        statusBar.addView(variantChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 16 })

        configChip = TextView(this).apply {
            visibility = android.view.View.GONE
            setPadding(16, 4, 16, 4)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#DBEAFE"))
            setTextColor(Color.parseColor("#2563EB"))
        }
        statusBar.addView(configChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 8 })

        root.addView(statusBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Buttons area (scrollable)
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24)
        }

        addSection("Setup")
        addButton("Run Full Scenario", Color.parseColor("#7C3AED")) { runFullScenario() }
        addButton("Init SDK", Color.parseColor("#2563EB")) { initSDK() }
        addButton("Set User", Color.parseColor("#2563EB")) { setUser() }

        addSection("Experiments")
        addButton("Checkout Variant", Color.parseColor("#0D9488")) { getCheckoutVariant() }
        addButton("Checkout Config", Color.parseColor("#0D9488")) { getCheckoutConfig() }
        addButton("Pricing Variant", Color.parseColor("#0D9488")) { getPricingVariant() }
        addButton("List Experiments", Color.parseColor("#0D9488")) { listExperiments() }
        addButton("Refresh Experiments", Color.parseColor("#0D9488")) { refreshExperiments() }

        addSection("Core Events")
        addButton("Track View", Color.parseColor("#EA580C")) { trackView() }
        addButton("Track Click", Color.parseColor("#EA580C")) { trackClick() }
        addButton("Track Conversion", Color.parseColor("#EA580C")) { trackConversion() }
        addButton("Track Custom", Color.parseColor("#EA580C")) { trackCustomEvent() }
        addButton("Track Generic", Color.parseColor("#EA580C")) { trackGenericEvent() }

        addSection("Specialized Events")
        addButton("Engagement Events", Color.parseColor("#4F46E5")) { trackEngagementEvents() }
        addButton("E-Commerce Events", Color.parseColor("#4F46E5")) { trackEcommerceEvents() }
        addButton("Media Events", Color.parseColor("#4F46E5")) { trackMediaEvents() }
        addButton("Auth Events", Color.parseColor("#4F46E5")) { trackAuthEvents() }

        addSection("Feature Flags")
        addButton("Test All Flags", Color.parseColor("#16A34A")) { testFeatureFlags() }

        addSection("Advanced")
        addButton("Multi-User Test", Color.parseColor("#7C3AED")) { testMultipleUsers() }

        addSection("Lifecycle")
        addButton("Flush Events", Color.parseColor("#CA8A04")) { flushEvents() }
        addButton("Reset SDK", Color.parseColor("#DC2626")) { resetSDK() }

        scrollView.addView(buttonContainer)
        root.addView(scrollView)

        // Log panel
        val logPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1F2937"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 500
            )
        }

        val logHeader = TextView(this).apply {
            text = "Logs (0)"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(16, 8, 16, 4)
        }
        logPanel.addView(logHeader)

        logAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, logs) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val textView = view as TextView
                textView.textSize = 11f
                textView.typeface = android.graphics.Typeface.MONOSPACE
                textView.setBackgroundColor(Color.TRANSPARENT)
                val logText = getItem(position) ?: ""
                textView.setTextColor(when {
                    logText.contains("failed") || logText.contains("ERROR") -> Color.parseColor("#F87171")
                    logText.contains("Tracked:") || logText.contains("SUCCESS") -> Color.parseColor("#4ADE80")
                    logText.contains("===") -> Color.parseColor("#FBBF24")
                    else -> Color.parseColor("#9CA3AF")
                })
                return view
            }
        }

        logListView = ListView(this).apply {
            adapter = logAdapter
            divider = null
            setBackgroundColor(Color.TRANSPARENT)
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        }
        logPanel.addView(logListView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(logPanel)

        setContentView(root)

        // Store log header ref for updating count
        this.logHeader = logHeader
    }

    private lateinit var logHeader: TextView

    private fun addSection(title: String) {
        val sectionLabel = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
        buttonContainer.addView(sectionLabel)

        // FlowLayout-like container for buttons
        val flowLayout = com.google.android.flexbox.FlexboxLayout(this).apply {
            flexWrap = com.google.android.flexbox.FlexWrap.WRAP
            tag = "section_$title"
        }
        buttonContainer.addView(flowLayout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun addButton(label: String, color: Int, onClick: () -> Unit) {
        // Find the last FlexboxLayout added
        val flowLayout = buttonContainer.getChildAt(buttonContainer.childCount - 1) as com.google.android.flexbox.FlexboxLayout

        val btn = Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(color)
            textSize = 12f
            isAllCaps = false
            setPadding(24, 12, 24, 12)
            val lp = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 16, 16)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
        flowLayout.addView(btn)
    }

    private fun log(message: String) {
        runOnUiThread {
            val timestamp = dateFormat.format(Date())
            logs.add(0, "[$timestamp] $message")
            if (logs.size > 100) logs.removeAt(logs.size - 1)
            logAdapter.notifyDataSetChanged()
            logHeader.text = "Logs (${logs.size})"
        }
    }

    private fun updateStatus() {
        runOnUiThread {
            if (isInitialized) {
                statusBar.setBackgroundColor(Color.parseColor("#DCFCE7"))
                statusText.text = "SDK Initialized"
                statusText.setTextColor(Color.parseColor("#16A34A"))
            } else {
                statusBar.setBackgroundColor(Color.parseColor("#FEE2E2"))
                statusText.text = "SDK Not Initialized"
                statusText.setTextColor(Color.parseColor("#DC2626"))
            }

            if (currentVariant != null) {
                variantChip.text = "Variant: $currentVariant"
                variantChip.visibility = android.view.View.VISIBLE
            } else {
                variantChip.visibility = android.view.View.GONE
            }

            if (variantConfig != null) {
                configChip.text = "Config: ${variantConfig!!.size} keys"
                configChip.visibility = android.view.View.VISIBLE
            } else {
                configChip.visibility = android.view.View.GONE
            }
        }
    }

    // ============================================
    // 1. INITIALIZATION
    // ============================================

    private fun initSDK() {
        log("Initializing SDK...")
        try {
            val config = RiviumAbTestingConfig(
                apiKey = API_KEY,
                debug = true,
                flushInterval = 10000L,
                maxQueueSize = 50
            )

            RiviumAbTesting.init(this, config, object : RiviumAbTestingCallback {
                override fun onInitialized() {
                    isInitialized = true
                    updateStatus()
                    log("SDK initialized successfully")
                }

                override fun onExperimentAssigned(experimentKey: String, variantKey: String, config: Map<String, Any>?) {
                    log("Event: experimentAssigned - $experimentKey -> $variantKey")
                }

                override fun onExperimentsRefreshed(experiments: List<Experiment>) {
                    log("Event: experimentsRefreshed - ${experiments.size} experiments")
                }

                override fun onFeatureFlagsRefreshed(flags: List<FeatureFlag>) {
                    log("Event: featureFlagsRefreshed - ${flags.size} flags")
                }

                override fun onSyncCompleted(synced: Int, failed: Int, pending: Int) {
                    log("Event: syncCompleted - synced=$synced, failed=$failed, pending=$pending")
                }

                override fun onError(error: RiviumAbTestingError) {
                    log("ERROR: ${error.message}")
                }
            })

            log("SDK instance created, waiting for initialization...")
        } catch (e: Exception) {
            log("Init failed: ${e.message}")
        }
    }

    // ============================================
    // 2. USER MANAGEMENT
    // ============================================

    private fun setUser() {
        try {
            RiviumAbTesting.setUserId("user-premium-123")
            log("User ID set: user-premium-123")

            RiviumAbTesting.setUserAttributes(mapOf(
                "plan" to "premium",
                "country" to "US",
                "age" to 25,
                "appVersion" to "2.1.0",
                "platform" to "android"
            ))
            log("User attributes set (premium, US, age=25)")
        } catch (e: Exception) {
            log("Set user failed: ${e.message}")
        }
    }

    // ============================================
    // 3. EXPERIMENTS
    // ============================================

    private fun getCheckoutVariant() {
        try {
            RiviumAbTesting.getVariantAsync(EXPERIMENT_CHECKOUT) { variant ->
                currentVariant = variant
                updateStatus()
                log("Checkout variant: $variant")
            }
        } catch (e: Exception) {
            log("Get checkout variant failed: ${e.message}")
        }
    }

    private fun getCheckoutConfig() {
        try {
            val config = RiviumAbTesting.getVariantConfig(EXPERIMENT_CHECKOUT)
            variantConfig = config
            updateStatus()
            log("Checkout config: $config")
            config?.let {
                log("  layout: ${it["layout"]}")
                log("  button_color: ${it["button_color"]}")
            }
        } catch (e: Exception) {
            log("Get checkout config failed: ${e.message}")
        }
    }

    private fun getPricingVariant() {
        try {
            RiviumAbTesting.getVariantAsync(EXPERIMENT_PRICING) { variant ->
                log("Pricing variant: $variant")
            }
        } catch (e: Exception) {
            log("Get pricing variant failed: ${e.message}")
        }
    }

    private fun listExperiments() {
        try {
            val experiments = RiviumAbTesting.getExperiments()
            log("Experiments (${experiments.size}):")
            for (exp in experiments) {
                log("  - ${exp.name} [${exp.status?.name}] (${exp.variants?.size ?: 0} variants, ${exp.trafficAllocation}% traffic)")
            }
        } catch (e: Exception) {
            log("Get experiments failed: ${e.message}")
        }
    }

    private fun refreshExperiments() {
        try {
            RiviumAbTesting.refreshExperiments()
            log("Experiments refreshing...")
        } catch (e: Exception) {
            log("Refresh failed: ${e.message}")
        }
    }

    // ============================================
    // 4. CORE EVENT TRACKING
    // ============================================

    private fun trackView() {
        try {
            RiviumAbTesting.trackView(EXPERIMENT_CHECKOUT)
            log("Tracked: VIEW ($EXPERIMENT_CHECKOUT)")
        } catch (e: Exception) {
            log("Track view failed: ${e.message}")
        }
    }

    private fun trackClick() {
        try {
            RiviumAbTesting.trackClick(EXPERIMENT_CHECKOUT)
            log("Tracked: CLICK ($EXPERIMENT_CHECKOUT)")
        } catch (e: Exception) {
            log("Track click failed: ${e.message}")
        }
    }

    private fun trackConversion() {
        try {
            RiviumAbTesting.trackConversion(EXPERIMENT_CHECKOUT, 49.99)
            log("Tracked: CONVERSION (\$49.99)")
        } catch (e: Exception) {
            log("Track conversion failed: ${e.message}")
        }
    }

    private fun trackCustomEvent() {
        try {
            RiviumAbTesting.trackCustomEvent(
                EXPERIMENT_CHECKOUT,
                "button_hover",
                mapOf("duration_ms" to 1500, "element" to "cta_button")
            )
            log("Tracked: CUSTOM (button_hover)")
        } catch (e: Exception) {
            log("Track custom event failed: ${e.message}")
        }
    }

    private fun trackGenericEvent() {
        try {
            RiviumAbTesting.track(
                EXPERIMENT_CHECKOUT,
                EventType.CUSTOM,
                "page_load_time",
                2.3,
                mapOf("page" to "/checkout", "cached" to false)
            )
            log("Tracked: GENERIC (page_load_time, 2.3s)")
        } catch (e: Exception) {
            log("Generic tracking failed: ${e.message}")
        }
    }

    // ============================================
    // 5. ENGAGEMENT EVENTS
    // ============================================

    private fun trackEngagementEvents() {
        try {
            RiviumAbTesting.trackScroll(EXPERIMENT_CHECKOUT, 75.0, mapOf("page" to "product_detail"))
            log("Tracked: SCROLL (depth: 75%)")

            RiviumAbTesting.trackFormSubmit(EXPERIMENT_CHECKOUT, "shipping_address", mapOf("fields_count" to 5))
            log("Tracked: FORM_SUBMIT (shipping_address)")

            RiviumAbTesting.trackSearch(EXPERIMENT_CHECKOUT, "express shipping", mapOf("results_count" to 3))
            log("Tracked: SEARCH (express shipping)")

            RiviumAbTesting.trackShare(EXPERIMENT_CHECKOUT, "copy_link", mapOf("content_id" to "product-456"))
            log("Tracked: SHARE (copy_link)")
        } catch (e: Exception) {
            log("Engagement tracking failed: ${e.message}")
        }
    }

    // ============================================
    // 6. E-COMMERCE EVENTS
    // ============================================

    private fun trackEcommerceEvents() {
        try {
            RiviumAbTesting.trackAddToCart(EXPERIMENT_CHECKOUT, 49.99, "SKU-001", mapOf("quantity" to 2, "category" to "electronics"))
            log("Tracked: ADD_TO_CART (SKU-001, \$49.99)")

            RiviumAbTesting.trackRemoveFromCart(EXPERIMENT_CHECKOUT, 49.99, "SKU-001")
            log("Tracked: REMOVE_FROM_CART (SKU-001)")

            RiviumAbTesting.trackBeginCheckout(EXPERIMENT_CHECKOUT, 149.97, mapOf("items_count" to 3, "coupon" to "SAVE10"))
            log("Tracked: BEGIN_CHECKOUT (\$149.97)")

            RiviumAbTesting.trackPurchase(EXPERIMENT_CHECKOUT, 134.97, "TXN-12345", mapOf("items_count" to 3, "payment_method" to "credit_card", "currency" to "USD"))
            log("Tracked: PURCHASE (TXN-12345, \$134.97)")
        } catch (e: Exception) {
            log("E-commerce tracking failed: ${e.message}")
        }
    }

    // ============================================
    // 7. MEDIA EVENTS
    // ============================================

    private fun trackMediaEvents() {
        try {
            RiviumAbTesting.trackVideoStart(EXPERIMENT_CHECKOUT, "onboarding-video", mapOf("duration" to 120, "quality" to "1080p"))
            log("Tracked: VIDEO_START (onboarding-video)")

            RiviumAbTesting.trackVideoComplete(EXPERIMENT_CHECKOUT, "onboarding-video", mapOf("watch_time" to 118))
            log("Tracked: VIDEO_COMPLETE (onboarding-video)")
        } catch (e: Exception) {
            log("Media tracking failed: ${e.message}")
        }
    }

    // ============================================
    // 8. AUTH EVENTS
    // ============================================

    private fun trackAuthEvents() {
        try {
            RiviumAbTesting.trackSignUp(EXPERIMENT_CHECKOUT, "google", mapOf("referral" to "organic"))
            log("Tracked: SIGN_UP (google)")

            RiviumAbTesting.trackLogin(EXPERIMENT_CHECKOUT, "email", mapOf("remember_me" to true))
            log("Tracked: LOGIN (email)")

            RiviumAbTesting.trackLogout(EXPERIMENT_CHECKOUT, mapOf("session_duration" to 3600))
            log("Tracked: LOGOUT")
        } catch (e: Exception) {
            log("Auth tracking failed: ${e.message}")
        }
    }

    // ============================================
    // 9. FEATURE FLAGS
    // ============================================

    private fun testFeatureFlags() {
        try {
            val darkMode = RiviumAbTesting.isFeatureEnabled(FLAG_DARK_MODE, false)
            log("Flag \"$FLAG_DARK_MODE\" enabled: $darkMode")

            val onboarding = RiviumAbTesting.getFeatureValue(FLAG_ONBOARDING_FLOW, "classic_onboarding")
            log("Flag \"$FLAG_ONBOARDING_FLOW\" value: $onboarding")

            val darkSettings = RiviumAbTesting.isFeatureEnabled(FLAG_DARK_MODE_SETTINGS, false)
            log("Flag \"$FLAG_DARK_MODE_SETTINGS\" enabled: $darkSettings (depends on dark_mode=$darkMode)")

            val holiday = RiviumAbTesting.isFeatureEnabled(FLAG_HOLIDAY_BANNER, false)
            log("Flag \"$FLAG_HOLIDAY_BANNER\" enabled: $holiday (scheduled)")

            val flags = RiviumAbTesting.getFeatureFlags()
            log("All flags (${flags.size}):")
            for (flag in flags) {
                log("  - ${flag.key}: enabled=${flag.enabled}, rollout=${flag.rolloutPercentage}%")
            }

            RiviumAbTesting.refreshFeatureFlags()
            log("Feature flags refreshed")
        } catch (e: Exception) {
            log("Feature flags test failed: ${e.message}")
        }
    }

    // ============================================
    // 10. MULTI-USER TEST
    // ============================================

    private fun testMultipleUsers() {
        scope.launch {
            log("=== MULTI-USER TEST (20 users) ===")
            var holdoutCount = 0
            var checkoutAssigned = 0
            var pricingAssigned = 0

            for (i in 1..20) {
                RiviumAbTesting.setUserId("test-user-$i")
                RiviumAbTesting.setUserAttributes(mapOf(
                    "country" to "US",
                    "plan" to "premium",
                    "age" to 25
                ))

                val checkoutVariant = withContext(Dispatchers.IO) {
                    RiviumAbTesting.getVariant(EXPERIMENT_CHECKOUT, "control")
                }
                val pricingVariant = withContext(Dispatchers.IO) {
                    RiviumAbTesting.getVariant(EXPERIMENT_PRICING, "control")
                }

                val isHoldout = checkoutVariant == "control" && pricingVariant == "control"
                if (isHoldout) holdoutCount++
                if (checkoutVariant != "control") checkoutAssigned++
                if (pricingVariant != "control") pricingAssigned++

                log("  User $i: checkout=$checkoutVariant, pricing=$pricingVariant")
            }

            log("--- Summary ---")
            log("  Possible holdout: $holdoutCount/20")
            log("  Checkout non-control: $checkoutAssigned/20")
            log("  Pricing non-control: $pricingAssigned/20")

            // Restore original user
            RiviumAbTesting.setUserId("user-premium-123")
            log("Restored user: user-premium-123")
            log("=== MULTI-USER TEST COMPLETE ===")
        }
    }

    // ============================================
    // 11. LIFECYCLE
    // ============================================

    private fun flushEvents() {
        try {
            val pending = RiviumAbTesting.getPendingEventCount()
            log("Pending events: $pending")

            RiviumAbTesting.flush()
            log("Flush initiated")
        } catch (e: Exception) {
            log("Flush failed: ${e.message}")
        }
    }

    private fun resetSDK() {
        try {
            RiviumAbTesting.reset()
            isInitialized = false
            currentVariant = null
            variantConfig = null
            updateStatus()
            log("SDK reset")
        } catch (e: Exception) {
            log("Reset failed: ${e.message}")
        }
    }

    // ============================================
    // 12. RUN ALL — Full test scenario
    // ============================================

    private fun runFullScenario() {
        scope.launch {
            log("=== RUNNING FULL TEST SCENARIO ===")

            // Init
            initSDK()
            delay(3000) // Wait for init to complete

            // Set user
            setUser()
            delay(500)

            // Experiments
            listExperiments()
            getCheckoutVariant()
            delay(1000)
            getCheckoutConfig()
            getPricingVariant()
            delay(1000)

            // All event types (18 events)
            trackView()
            trackClick()
            trackConversion()
            trackCustomEvent()
            trackEngagementEvents()
            trackEcommerceEvents()
            trackMediaEvents()
            trackAuthEvents()
            trackGenericEvent()

            // Feature flags (4 flags)
            testFeatureFlags()

            // Flush everything
            delay(1000)
            flushEvents()

            log("=== FULL TEST SCENARIO COMPLETE ===")
            log("Check billing: ~18 events under riviumABTesting")
            log("Check billing: flag evals under riviumFlags")
        }
    }
}

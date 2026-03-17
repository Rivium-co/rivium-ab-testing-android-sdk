package co.rivium.abtesting.example

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import co.rivium.abtesting.RiviumAbTesting
import co.rivium.abtesting.models.EventType

/**
 * Simple Example: How to use RiviumAbTesting SDK for A/B Testing
 *
 * This shows the basic flow:
 * 1. Get experiment assignment (which variant the user sees)
 * 2. Track events (what the user does)
 * 3. Track conversions (when the user completes a goal)
 */
class SimpleTestActivity : AppCompatActivity() {

    // Current experiment key
    private var experimentKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_test)

        // Back button
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        // Step 1: Get assignment when page loads
        getAssignment()

        // Step 2: Set up event tracking buttons
        setupEventButtons()
    }

    /**
     * STEP 1: Get Assignment
     *
     * When your app screen loads, get the experiment assignment.
     * This tells you which variant (A or B) the user should see.
     */
    private fun getAssignment() {
        val experiments = RiviumAbTesting.getExperiments()

        if (experiments.isEmpty()) {
            updateStatus("No experiments found")
            return
        }

        // Get the first running experiment
        val experiment = experiments.find { it.status?.name == "RUNNING" } ?: experiments[0]
        val expKey = experiment.key ?: experiment.id

        // Get variant for this user
        RiviumAbTesting.getVariantAsync(expKey) { variant ->
            runOnUiThread {
                // Save for tracking
                experimentKey = expKey

                // Show which variant user got
                updateStatus("Variant: $variant")
                val isControl = variant == "control"
                updateVariantUI(variant, isControl)

                // Track that user saw the experiment
                trackView()
            }
        }
    }

    /**
     * STEP 2: Track Events
     *
     * Track what users do in your app.
     * Use different event types for different actions.
     */
    private fun setupEventButtons() {

        // VIEW - Track page/screen views
        findViewById<MaterialButton>(R.id.btnView).setOnClickListener {
            trackEvent(EventType.VIEW, "screen_view")
        }

        // CLICK - Track button/link clicks
        findViewById<MaterialButton>(R.id.btnClick).setOnClickListener {
            trackEvent(EventType.CLICK, "button_click")
        }

        // SCROLL - Track scroll depth
        findViewById<MaterialButton>(R.id.btnScroll).setOnClickListener {
            experimentKey?.let { key ->
                RiviumAbTesting.trackScroll(key, 50.0)
            }
            showToast("scroll: scroll_50")
        }

        // SEARCH - Track searches
        findViewById<MaterialButton>(R.id.btnSearch).setOnClickListener {
            experimentKey?.let { key ->
                RiviumAbTesting.trackSearch(key, "shoes")
            }
            showToast("search: product_search")
        }

        // ADD_TO_CART - Track e-commerce
        findViewById<MaterialButton>(R.id.btnAddCart).setOnClickListener {
            experimentKey?.let { key ->
                RiviumAbTesting.trackAddToCart(key, 29.99, "SKU001")
            }
            showToast("add_to_cart: add_item")
        }

        // PURCHASE - Track purchase (this is a conversion!)
        findViewById<MaterialButton>(R.id.btnPurchase).setOnClickListener {
            trackConversion(99.99)
        }

        // LOGIN - Track user login
        findViewById<MaterialButton>(R.id.btnLogin).setOnClickListener {
            experimentKey?.let { key ->
                RiviumAbTesting.trackLogin(key, "email")
            }
            showToast("login: user_login")
        }

        // CUSTOM - Track any custom event
        findViewById<MaterialButton>(R.id.btnCustom).setOnClickListener {
            experimentKey?.let { key ->
                RiviumAbTesting.trackCustomEvent(key, "my_custom_event", mapOf("custom_data" to "value"))
            }
            showToast("custom: my_custom_event")
        }
    }

    /**
     * Track a view event (called automatically when screen loads)
     */
    private fun trackView() {
        experimentKey?.let { key ->
            RiviumAbTesting.trackView(key)
        }
    }

    /**
     * Generic event tracking function
     */
    private fun trackEvent(
        eventType: EventType,
        eventName: String
    ) {
        val expKey = experimentKey ?: return

        when (eventType) {
            EventType.VIEW -> RiviumAbTesting.trackView(expKey)
            EventType.CLICK -> RiviumAbTesting.trackClick(expKey)
            EventType.CONVERSION -> RiviumAbTesting.trackConversion(expKey)
            else -> RiviumAbTesting.track(expKey, eventType, eventName)
        }

        showToast("${eventType.name.lowercase()}: $eventName")
    }

    /**
     * STEP 3: Track Conversions
     *
     * Conversions are the goals of your experiment.
     * Example: purchases, sign-ups, subscriptions
     */
    private fun trackConversion(value: Double) {
        val expKey = experimentKey ?: return

        RiviumAbTesting.trackConversion(expKey, value)

        showToast("CONVERSION: purchase = $$value")
    }

    // UI Helpers
    private fun updateStatus(text: String) {
        findViewById<TextView>(R.id.statusText).text = text
    }

    private fun updateVariantUI(variantName: String, isControl: Boolean) {
        val label = if (isControl) "Control (A)" else "Variant (B)"
        findViewById<TextView>(R.id.variantLabel).text = "$label: $variantName"
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

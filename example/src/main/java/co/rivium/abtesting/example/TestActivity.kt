package co.rivium.abtesting.example

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import co.rivium.abtesting.RiviumAbTesting
import co.rivium.abtesting.models.EventType
import java.text.NumberFormat
import java.util.*

class TestActivity : AppCompatActivity() {

    // Experiment Info
    private lateinit var experimentName: TextView
    private lateinit var experimentStatus: TextView
    private lateinit var variantChip: Chip
    private lateinit var configCard: MaterialCardView
    private lateinit var configText: TextView

    // Simulated UI
    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var ctaButton: MaterialButton
    private lateinit var priceText: TextView

    // Core Event Tracking
    private lateinit var btnTrackView: MaterialButton
    private lateinit var btnTrackClick: MaterialButton
    private lateinit var btnTrackScroll: MaterialButton

    // Engagement Events
    private lateinit var btnTrackFormSubmit: MaterialButton
    private lateinit var btnTrackSearch: MaterialButton
    private lateinit var btnTrackShare: MaterialButton

    // E-commerce Events
    private lateinit var btnAddToCart: MaterialButton
    private lateinit var btnRemoveFromCart: MaterialButton
    private lateinit var btnBeginCheckout: MaterialButton
    private lateinit var btnPurchase: MaterialButton

    // Media Events
    private lateinit var btnVideoStart: MaterialButton
    private lateinit var btnVideoComplete: MaterialButton

    // User Events
    private lateinit var btnSignUp: MaterialButton
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnLogout: MaterialButton

    // Custom Event
    private lateinit var customEventName: TextInputEditText
    private lateinit var btnTrackCustom: MaterialButton

    // Conversion
    private lateinit var conversionType: TextInputEditText
    private lateinit var conversionValue: TextInputEditText
    private lateinit var btnTrackConversion: MaterialButton

    // Stats
    private lateinit var statsViews: TextView
    private lateinit var statsClicks: TextView
    private lateinit var statsConversions: TextView
    private lateinit var statsRevenue: TextView
    private lateinit var btnResetStats: MaterialButton
    private lateinit var btnNewUser: MaterialButton

    // State
    private var currentExperimentKey: String? = null
    private var currentVariant: String? = null
    private var viewCount = 0
    private var clickCount = 0
    private var conversionCount = 0
    private var totalRevenue = 0.0

    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        initViews()
        setupListeners()
        loadExperimentData()
    }

    private fun initViews() {
        // Toolbar
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        // Experiment Info
        experimentName = findViewById(R.id.experimentName)
        experimentStatus = findViewById(R.id.experimentStatus)
        variantChip = findViewById(R.id.variantChip)
        configCard = findViewById(R.id.configCard)
        configText = findViewById(R.id.configText)

        // Simulated UI
        heroTitle = findViewById(R.id.heroTitle)
        heroSubtitle = findViewById(R.id.heroSubtitle)
        ctaButton = findViewById(R.id.ctaButton)
        priceText = findViewById(R.id.priceText)

        // Core Event Tracking
        btnTrackView = findViewById(R.id.btnTrackView)
        btnTrackClick = findViewById(R.id.btnTrackClick)
        btnTrackScroll = findViewById(R.id.btnTrackScroll)

        // Engagement Events
        btnTrackFormSubmit = findViewById(R.id.btnTrackFormSubmit)
        btnTrackSearch = findViewById(R.id.btnTrackSearch)
        btnTrackShare = findViewById(R.id.btnTrackShare)

        // E-commerce Events
        btnAddToCart = findViewById(R.id.btnAddToCart)
        btnRemoveFromCart = findViewById(R.id.btnRemoveFromCart)
        btnBeginCheckout = findViewById(R.id.btnBeginCheckout)
        btnPurchase = findViewById(R.id.btnPurchase)

        // Media Events
        btnVideoStart = findViewById(R.id.btnVideoStart)
        btnVideoComplete = findViewById(R.id.btnVideoComplete)

        // User Events
        btnSignUp = findViewById(R.id.btnSignUp)
        btnLogin = findViewById(R.id.btnLogin)
        btnLogout = findViewById(R.id.btnLogout)

        // Custom Event
        customEventName = findViewById(R.id.customEventName)
        btnTrackCustom = findViewById(R.id.btnTrackCustom)

        // Conversion
        conversionType = findViewById(R.id.conversionType)
        conversionValue = findViewById(R.id.conversionValue)
        btnTrackConversion = findViewById(R.id.btnTrackConversion)

        // Stats
        statsViews = findViewById(R.id.statsViews)
        statsClicks = findViewById(R.id.statsClicks)
        statsConversions = findViewById(R.id.statsConversions)
        statsRevenue = findViewById(R.id.statsRevenue)
        btnResetStats = findViewById(R.id.btnResetStats)
        btnNewUser = findViewById(R.id.btnNewUser)
    }

    private fun setupListeners() {
        // CTA Button tracks click
        ctaButton.setOnClickListener {
            trackClick()
            clickCount++
            updateStats()
            showToast("CTA Button Clicked!")
        }

        // Quick event buttons
        btnTrackView.setOnClickListener {
            trackView()
            viewCount++
            updateStats()
            showToast("View tracked")
        }

        btnTrackClick.setOnClickListener {
            trackClick()
            clickCount++
            updateStats()
            showToast("Click tracked")
        }

        btnTrackScroll.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackScroll(key, 75.0, mapOf("depth" to "75%"))
            }
            showToast("Scroll tracked")
        }

        // Engagement events
        btnTrackFormSubmit.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackFormSubmit(key, "contact_form", mapOf("form_id" to "contact"))
            }
            showToast("Form Submit tracked")
        }

        btnTrackSearch.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackSearch(key, "test product")
            }
            showToast("Search tracked")
        }

        btnTrackShare.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackShare(key, "twitter")
            }
            showToast("Share tracked")
        }

        // E-commerce events
        btnAddToCart.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackAddToCart(key, 29.99, "SKU123")
            }
            showToast("Add to Cart tracked")
        }

        btnRemoveFromCart.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackRemoveFromCart(key, 29.99, "SKU123")
            }
            showToast("Remove from Cart tracked")
        }

        btnBeginCheckout.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackBeginCheckout(key, 59.98, mapOf("items" to 2))
            }
            showToast("Begin Checkout tracked")
        }

        btnPurchase.setOnClickListener {
            val value = 59.98
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackPurchase(key, value, "TXN${System.currentTimeMillis()}")
            }
            conversionCount++
            totalRevenue += value
            updateStats()
            showToast("Purchase tracked: ${currencyFormat.format(value)}")
        }

        // Media events
        btnVideoStart.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackVideoStart(key, "promo_video_1")
            }
            showToast("Video Start tracked")
        }

        btnVideoComplete.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackVideoComplete(key, "promo_video_1")
            }
            showToast("Video Complete tracked")
        }

        // User events
        btnSignUp.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackSignUp(key, "email")
            }
            showToast("Sign Up tracked")
        }

        btnLogin.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackLogin(key, "google")
            }
            showToast("Login tracked")
        }

        btnLogout.setOnClickListener {
            currentExperimentKey?.let { key ->
                RiviumAbTesting.trackLogout(key)
            }
            showToast("Logout tracked")
        }

        // Custom event
        btnTrackCustom.setOnClickListener {
            val eventName = customEventName.text.toString().trim()
            if (eventName.isNotBlank()) {
                currentExperimentKey?.let { key ->
                    RiviumAbTesting.trackCustomEvent(key, eventName)
                }
                showToast("Custom event '$eventName' tracked")
            } else {
                showToast("Enter event name")
            }
        }

        // Conversion
        btnTrackConversion.setOnClickListener {
            val type = conversionType.text.toString().trim()
            val value = conversionValue.text.toString().toDoubleOrNull() ?: 0.0

            if (type.isNotBlank()) {
                currentExperimentKey?.let { key ->
                    RiviumAbTesting.trackConversion(key, value)
                }
                conversionCount++
                totalRevenue += value
                updateStats()
                showToast("Conversion tracked: ${currencyFormat.format(value)}")
            } else {
                showToast("Enter conversion type")
            }
        }

        // Reset stats
        btnResetStats.setOnClickListener {
            viewCount = 0
            clickCount = 0
            conversionCount = 0
            totalRevenue = 0.0
            updateStats()
            showToast("Stats reset")
        }

        // Simulate new user
        btnNewUser.setOnClickListener {
            simulateNewUser()
        }
    }

    private fun loadExperimentData() {
        val experiments = RiviumAbTesting.getExperiments()

        if (experiments.isEmpty()) {
            experimentName.text = "No Experiments"
            experimentStatus.text = "Create an experiment in dashboard"
            disableTracking()
            return
        }

        // Use first running experiment, or first available
        val experiment = experiments.find { it.status?.name == "RUNNING" } ?: experiments[0]
        val experimentKey = experiment.key ?: experiment.id

        experimentName.text = experiment.name
        experimentStatus.text = "Status: ${experiment.status?.name ?: "UNKNOWN"} | Traffic: ${experiment.trafficAllocation}%"

        // Get variant for this experiment
        RiviumAbTesting.getVariantAsync(experimentKey) { variant ->
            runOnUiThread {
                currentExperimentKey = experimentKey
                currentVariant = variant

                updateVariantUI(variant)
                enableTracking()

                // Auto-track view on load
                trackView()
                viewCount++
                updateStats()
            }
        }
    }

    private fun updateVariantUI(variantKey: String) {
        // Update variant chip
        variantChip.text = variantKey
        val isControl = variantKey == "control"
        if (isControl) {
            variantChip.setChipBackgroundColorResource(R.color.text_secondary)
            variantChip.setTextColor(Color.WHITE)
        } else {
            variantChip.setChipBackgroundColorResource(R.color.primary)
            variantChip.setTextColor(Color.WHITE)
        }

        // Show config if available
        val config = currentExperimentKey?.let { RiviumAbTesting.getVariantConfig(it) }
        if (config != null && config.isNotEmpty()) {
            configCard.visibility = View.VISIBLE
            configText.text = config.toString()

            // Apply config to simulated UI
            applyVariantConfig(config)
        } else {
            configCard.visibility = View.GONE
        }
    }

    private fun applyVariantConfig(config: Map<String, Any>) {
        // Apply button color
        config["color"]?.let { colorValue ->
            try {
                val color = Color.parseColor(colorValue.toString())
                ctaButton.setBackgroundColor(color)
            } catch (e: Exception) {
                // Invalid color format
            }
        }

        // Apply button text
        config["buttonText"]?.let { text ->
            ctaButton.text = text.toString()
        }

        // Apply hero title
        config["title"]?.let { text ->
            heroTitle.text = text.toString()
        }

        // Apply hero subtitle
        config["subtitle"]?.let { text ->
            heroSubtitle.text = text.toString()
        }

        // Apply price
        config["price"]?.let { price ->
            priceText.visibility = View.VISIBLE
            val priceValue = (price as? Number)?.toDouble() ?: price.toString().toDoubleOrNull() ?: 0.0
            priceText.text = currencyFormat.format(priceValue) + "/month"
        }

        // Apply discount badge
        config["showBadge"]?.let { show ->
            if (show == true || show.toString() == "true") {
                heroSubtitle.text = "Limited time offer!"
                heroSubtitle.setTextColor(Color.parseColor("#EF4444"))
            }
        }
    }

    private fun trackView() {
        currentExperimentKey?.let { key ->
            RiviumAbTesting.trackView(key)
        }
    }

    private fun trackClick() {
        currentExperimentKey?.let { key ->
            RiviumAbTesting.trackClick(key)
        }
    }

    private fun simulateNewUser() {
        // Generate new user ID
        val newUserId = "user_" + UUID.randomUUID().toString().take(8)
        RiviumAbTesting.setUserId(newUserId)

        // Reset stats
        viewCount = 0
        clickCount = 0
        conversionCount = 0
        totalRevenue = 0.0
        updateStats()

        // Reload data
        loadExperimentData()

        showToast("New user: $newUserId")
    }

    private fun updateStats() {
        statsViews.text = viewCount.toString()
        statsClicks.text = clickCount.toString()
        statsConversions.text = conversionCount.toString()
        statsRevenue.text = currencyFormat.format(totalRevenue)
    }

    private fun enableTracking() {
        // Core events
        btnTrackView.isEnabled = true
        btnTrackClick.isEnabled = true
        btnTrackScroll.isEnabled = true
        // Engagement events
        btnTrackFormSubmit.isEnabled = true
        btnTrackSearch.isEnabled = true
        btnTrackShare.isEnabled = true
        // E-commerce events
        btnAddToCart.isEnabled = true
        btnRemoveFromCart.isEnabled = true
        btnBeginCheckout.isEnabled = true
        btnPurchase.isEnabled = true
        // Media events
        btnVideoStart.isEnabled = true
        btnVideoComplete.isEnabled = true
        // User events
        btnSignUp.isEnabled = true
        btnLogin.isEnabled = true
        btnLogout.isEnabled = true
        // Custom & Conversion
        btnTrackCustom.isEnabled = true
        btnTrackConversion.isEnabled = true
        ctaButton.isEnabled = true
    }

    private fun disableTracking() {
        // Core events
        btnTrackView.isEnabled = false
        btnTrackClick.isEnabled = false
        btnTrackScroll.isEnabled = false
        // Engagement events
        btnTrackFormSubmit.isEnabled = false
        btnTrackSearch.isEnabled = false
        btnTrackShare.isEnabled = false
        // E-commerce events
        btnAddToCart.isEnabled = false
        btnRemoveFromCart.isEnabled = false
        btnBeginCheckout.isEnabled = false
        btnPurchase.isEnabled = false
        // Media events
        btnVideoStart.isEnabled = false
        btnVideoComplete.isEnabled = false
        // User events
        btnSignUp.isEnabled = false
        btnLogin.isEnabled = false
        btnLogout.isEnabled = false
        // Custom & Conversion
        btnTrackCustom.isEnabled = false
        btnTrackConversion.isEnabled = false
        ctaButton.isEnabled = false
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

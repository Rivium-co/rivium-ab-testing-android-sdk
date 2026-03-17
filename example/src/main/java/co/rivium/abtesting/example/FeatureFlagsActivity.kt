package co.rivium.abtesting.example

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import co.rivium.abtesting.RiviumAbTesting
import co.rivium.abtesting.RiviumAbTestingCallback
import co.rivium.abtesting.RiviumAbTestingError
import co.rivium.abtesting.models.FeatureFlag

/**
 * Feature Flags Example: How to use RiviumAbTesting SDK for Feature Flags
 *
 * Feature flags let you toggle features without deploying new code.
 * This example shows:
 * 1. Simple boolean flags (on/off)
 * 2. Multivariate flags (different values)
 * 3. Targeting based on user attributes
 * 4. Gradual rollouts
 */
class FeatureFlagsActivity : AppCompatActivity(), RiviumAbTestingCallback {

    // UI Components
    private lateinit var darkModeCard: MaterialCardView
    private lateinit var darkModeStatus: TextView
    private lateinit var darkModeSwitch: SwitchMaterial

    private lateinit var newCheckoutCard: MaterialCardView
    private lateinit var newCheckoutStatus: TextView
    private lateinit var checkoutVariantText: TextView

    private lateinit var premiumCard: MaterialCardView
    private lateinit var premiumStatus: TextView
    private lateinit var premiumFeaturesList: TextView

    private lateinit var btnRefreshFlags: MaterialButton
    private lateinit var btnSetPremiumUser: MaterialButton
    private lateinit var btnSetFreeUser: MaterialButton
    private lateinit var userAttributesText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feature_flags)

        // Back button
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        initViews()
        setupListeners()

        // Register callback to listen for flag updates
        RiviumAbTesting.addCallback(this)

        // Show loaded flags and evaluate
        showLoadedFlags()
        evaluateAllFlags()
    }

    override fun onDestroy() {
        super.onDestroy()
        RiviumAbTesting.removeCallback(this)
    }

    // RiviumAbTestingCallback - called when feature flags are refreshed from server
    override fun onFeatureFlagsRefreshed(flags: List<FeatureFlag>) {
        runOnUiThread {
            showToast("Feature flags updated: ${flags.size} flags")
            showLoadedFlags()
            evaluateAllFlags()
        }
    }

    override fun onError(error: RiviumAbTestingError) {
        runOnUiThread {
            showToast("Error: ${error.message}")
        }
    }

    private fun showLoadedFlags() {
        val flags = RiviumAbTesting.getFeatureFlags()
        val flagsList = if (flags.isEmpty()) {
            "No feature flags loaded.\nCreate flags in the RiviumAbTesting dashboard."
        } else {
            flags.joinToString("\n") { flag ->
                "• ${flag.key}: ${if (flag.enabled) "ON" else "OFF"} (${flag.rolloutPercentage}%)"
            }
        }
        userAttributesText.text = "Loaded Flags:\n$flagsList"
    }

    private fun initViews() {
        // Dark Mode Flag
        darkModeCard = findViewById(R.id.darkModeCard)
        darkModeStatus = findViewById(R.id.darkModeStatus)
        darkModeSwitch = findViewById(R.id.darkModeSwitch)

        // New Checkout Flag
        newCheckoutCard = findViewById(R.id.newCheckoutCard)
        newCheckoutStatus = findViewById(R.id.newCheckoutStatus)
        checkoutVariantText = findViewById(R.id.checkoutVariantText)

        // Premium Features Flag
        premiumCard = findViewById(R.id.premiumCard)
        premiumStatus = findViewById(R.id.premiumStatus)
        premiumFeaturesList = findViewById(R.id.premiumFeaturesList)

        // Controls
        btnRefreshFlags = findViewById(R.id.btnRefreshFlags)
        btnSetPremiumUser = findViewById(R.id.btnSetPremiumUser)
        btnSetFreeUser = findViewById(R.id.btnSetFreeUser)
        userAttributesText = findViewById(R.id.userAttributesText)
    }

    private fun setupListeners() {
        btnRefreshFlags.setOnClickListener {
            // Refresh flags from server
            RiviumAbTesting.refreshFeatureFlags()
            showToast("Refreshing flags from server...")
        }

        btnSetPremiumUser.setOnClickListener {
            setPremiumUser()
        }

        btnSetFreeUser.setOnClickListener {
            setFreeUser()
        }

        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            // In real app, you would apply dark mode here
            showToast(if (isChecked) "Dark mode enabled" else "Dark mode disabled")
        }
    }

    /**
     * EXAMPLE 1: Simple Boolean Flag
     */
    private fun evaluateDarkModeFlag() {
        val isDarkModeEnabled = RiviumAbTesting.isFeatureEnabled("dark_mode")

        runOnUiThread {
            if (isDarkModeEnabled) {
                darkModeStatus.text = "ENABLED"
                darkModeStatus.setTextColor(Color.parseColor("#10B981"))
                darkModeSwitch.isEnabled = true
                darkModeSwitch.visibility = View.VISIBLE
            } else {
                darkModeStatus.text = "DISABLED"
                darkModeStatus.setTextColor(Color.parseColor("#EF4444"))
                darkModeSwitch.isEnabled = false
                darkModeSwitch.visibility = View.GONE
            }
        }
    }

    /**
     * EXAMPLE 2: Multivariate Flag (A/B/C variants)
     */
    private fun evaluateCheckoutFlag() {
        val checkoutVariant = RiviumAbTesting.getVariant("new_checkout_flow", defaultVariant = "control")
        val config = RiviumAbTesting.getVariantConfig("new_checkout_flow")
        val buttonColor = config?.get("button_color") as? String ?: "#7C3AED"

        runOnUiThread {
            when (checkoutVariant) {
                "control" -> {
                    newCheckoutStatus.text = "CONTROL"
                    newCheckoutStatus.setTextColor(Color.parseColor("#6B7280"))
                    checkoutVariantText.text = "Original checkout flow\n(3-step process)"
                }
                "variant_a" -> {
                    newCheckoutStatus.text = "VARIANT A"
                    newCheckoutStatus.setTextColor(Color.parseColor("#3B82F6"))
                    checkoutVariantText.text = "Simplified checkout\n(Single page)"
                }
                "variant_b" -> {
                    newCheckoutStatus.text = "VARIANT B"
                    newCheckoutStatus.setTextColor(Color.parseColor("#8B5CF6"))
                    checkoutVariantText.text = "One-click checkout\n(Express mode)"
                }
                else -> {
                    newCheckoutStatus.text = checkoutVariant.uppercase()
                    newCheckoutStatus.setTextColor(Color.parseColor("#10B981"))
                    checkoutVariantText.text = "Custom variant: $checkoutVariant"
                }
            }

            config?.let {
                checkoutVariantText.append("\nButton: $buttonColor")
            }
        }
    }

    /**
     * EXAMPLE 3: Targeted Feature Flag
     */
    private fun evaluatePremiumFlag() {
        val hasPremiumFeatures = RiviumAbTesting.isFeatureEnabled("premium_features")
        val premiumConfig = RiviumAbTesting.getVariantConfig("premium_features")

        runOnUiThread {
            if (hasPremiumFeatures) {
                premiumStatus.text = "UNLOCKED"
                premiumStatus.setTextColor(Color.parseColor("#F59E0B"))
                premiumFeaturesList.text = buildString {
                    append("Available features:\n")
                    append("  Advanced Analytics\n")
                    append("  Priority Support\n")
                    append("  Custom Themes\n")
                    append("  Export Data")
                }
                premiumCard.setCardBackgroundColor(Color.parseColor("#FFFBEB"))
            } else {
                premiumStatus.text = "LOCKED"
                premiumStatus.setTextColor(Color.parseColor("#9CA3AF"))
                premiumFeaturesList.text = "Upgrade to Premium to unlock\nadvanced features!"
                premiumCard.setCardBackgroundColor(Color.parseColor("#F9FAFB"))
            }
        }
    }

    private fun evaluateAllFlags() {
        evaluateDarkModeFlag()
        evaluateCheckoutFlag()
        evaluatePremiumFlag()
    }

    private fun setPremiumUser() {
        RiviumAbTesting.setUserAttributes(mapOf(
            "plan" to "premium",
            "country" to "US",
            "app_version" to "2.1.0",
            "is_beta_tester" to true
        ))

        showToast("Set as Premium user")
        evaluateAllFlags()
        showLoadedFlags()
    }

    private fun setFreeUser() {
        RiviumAbTesting.setUserAttributes(mapOf(
            "plan" to "free",
            "country" to "US",
            "app_version" to "2.0.0",
            "is_beta_tester" to false
        ))

        showToast("Set as Free user")
        evaluateAllFlags()
        showLoadedFlags()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

# Rivium AB Testing Android SDK

A/B Testing and Feature Flags SDK for Android with offline-first sync.

[![API 21+](https://img.shields.io/badge/API-21%2B-brightgreen.svg)](https://developer.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## Features

- A/B testing with automatic variant assignment
- Feature flags with targeting rules and rollout percentages
- Sticky bucketing — users stay in the same variant
- Offline-first event queue with automatic sync
- 17 built-in event types (view, click, conversion, purchase, etc.)
- Java and Kotlin support via `@JvmStatic`

## Installation

Add the SDK to your app's `build.gradle`:

```gradle
dependencies {
    implementation 'co.rivium:rivium-ab-testing-android:0.2.0'
}
```

## Quick Start

```kotlin
import co.rivium.abtesting.RiviumAbTesting
import co.rivium.abtesting.RiviumAbTestingConfig

// 1. Initialize the SDK
val config = RiviumAbTestingConfig(
    apiKey = "rv_live_your_api_key",
    // Your server mints this for the signed-in user (see "User tokens").
    // Runs on a background thread, so a blocking call is fine.
    tokenProvider = RiviumTokenProvider { myBackend.fetchRiviumTokenBlocking() }
)
RiviumAbTesting.init(context, config)

// 2. Set user ID
RiviumAbTesting.setUserId("user-123")

// 3. Get variant for an experiment
val variant = RiviumAbTesting.getVariant("checkout-redesign")

if (variant == "variant-a") {
    // Show new design
} else {
    // Show control
}

// 4. Track conversion
RiviumAbTesting.trackConversion("checkout-redesign", 49.99)

// 5. Flush pending events
RiviumAbTesting.flush()
```

## User tokens

The API key ships inside your app, so anyone can read it. On its own it can't
prove which user a request is for. Your server can: it holds your project's
**server secret** and mints a short-lived token for the signed-in user
(`POST https://auth.rivium.co/users/token`, or `createUserToken()` in the
Node.js SDK). The SDK sends it with every request, and the service takes the
user from the token instead of from the app.

`RiviumTokenProvider.fetchToken()` is called on a background thread when a
token is needed, again shortly before it expires, and once more if the service
reports it expired. Calling `setUserId` with a different user sends the last
user's pending events under their own token, then drops their variants and
token.

A token is **required**: assigning variants, tracking events and
evaluating flags are refused without one (reading the experiment and flag
lists is not, so the app can load them before anyone signs in). The same token works for Rivium Chat and Sync.

**Never put the server secret in the app.**

## A/B Testing

### Get Variant

```kotlin
// Synchronous
val variant = RiviumAbTesting.getVariant(
    experimentKey = "experiment-key",
    defaultVariant = "control"  // fallback if offline and no cache
)

// Asynchronous
RiviumAbTesting.getVariantAsync("experiment-key") { variant ->
    when (variant) {
        "variant-a" -> showNewCheckout()
        else -> showOriginalCheckout()
    }
}
```

### Get Variant Config

```kotlin
val config = RiviumAbTesting.getVariantConfig("experiment-key")
// config is a Map<String, Any>? set in the Rivium dashboard
val layout = config?.get("layout")
val buttonColor = config?.get("button_color")
```

### List Experiments

```kotlin
val experiments = RiviumAbTesting.getExperiments()
experiments.forEach { exp ->
    Log.d("Rivium", "${exp.name} [${exp.status}] - ${exp.variants?.size} variants")
}

// Refresh from server
RiviumAbTesting.refreshExperiments()
```

## Feature Flags

```kotlin
// Check if feature is enabled
val darkMode = RiviumAbTesting.isFeatureEnabled("dark-mode")

// Async with server-side evaluation
RiviumAbTesting.isFeatureEnabledAsync("dark-mode") { enabled ->
    if (enabled) enableDarkMode()
}

// Get feature value (string, number, JSON, etc.)
val maxUpload = RiviumAbTesting.getFeatureValue("max-upload-size", defaultValue = 10)

// Get all flags
val flags = RiviumAbTesting.getFeatureFlags()
flags.forEach { flag ->
    Log.d("Rivium", "${flag.key}: enabled=${flag.enabled}, rollout=${flag.rolloutPercentage}%")
}

// Refresh flags from server
RiviumAbTesting.refreshFeatureFlags()
```

## Event Tracking

Track user interactions with 17 built-in event types:

```kotlin
// Core events
RiviumAbTesting.trackView("experiment-key")
RiviumAbTesting.trackClick("experiment-key")
RiviumAbTesting.trackConversion("experiment-key", 99.99)

// Custom event
RiviumAbTesting.trackCustomEvent(
    "experiment-key",
    "button_hover",
    mapOf("duration_ms" to 1500, "element" to "cta_button")
)

// E-commerce events
RiviumAbTesting.trackAddToCart("experiment-key", 29.99, "sku-123", mapOf("quantity" to 2))
RiviumAbTesting.trackPurchase("experiment-key", 59.99, "txn-456", mapOf("currency" to "USD"))
RiviumAbTesting.trackRemoveFromCart("experiment-key", 29.99, "sku-123")
RiviumAbTesting.trackBeginCheckout("experiment-key", 59.99)

// Engagement events
RiviumAbTesting.trackScroll("experiment-key", 75.0)
RiviumAbTesting.trackFormSubmit("experiment-key", "signup")
RiviumAbTesting.trackSearch("experiment-key", "shoes")
RiviumAbTesting.trackShare("experiment-key", "twitter")

// Media events
RiviumAbTesting.trackVideoStart("experiment-key", "vid-001")
RiviumAbTesting.trackVideoComplete("experiment-key", "vid-001")

// Auth events
RiviumAbTesting.trackSignUp("experiment-key", "google")
RiviumAbTesting.trackLogin("experiment-key", "email")
RiviumAbTesting.trackLogout("experiment-key")
```

### Generic Event Tracking

```kotlin
RiviumAbTesting.track(
    experimentKey = "experiment-key",
    eventType = EventType.CUSTOM,
    eventName = "page_load_time",
    eventValue = 2.3,
    properties = mapOf("page" to "/checkout", "cached" to false)
)
```

## User Attributes

Set attributes for targeting rules:

```kotlin
RiviumAbTesting.setUserId("user-123")

RiviumAbTesting.setUserAttributes(mapOf(
    "plan" to "premium",
    "country" to "US",
    "age" to 28,
    "platform" to "android"
))
```

## Callbacks

Listen for SDK events:

```kotlin
RiviumAbTesting.init(context, config, object : RiviumAbTestingCallback {
    override fun onInitialized() {
        Log.d("Rivium", "SDK initialized")
    }

    override fun onExperimentAssigned(experimentKey: String, variantKey: String, config: Map<String, Any>?) {
        Log.d("Rivium", "Assigned: $experimentKey -> $variantKey")
    }

    override fun onExperimentsRefreshed(experiments: List<Experiment>) {
        Log.d("Rivium", "Refreshed ${experiments.size} experiments")
    }

    override fun onFeatureFlagsRefreshed(flags: List<FeatureFlag>) {
        Log.d("Rivium", "Refreshed ${flags.size} flags")
    }

    override fun onSyncCompleted(synced: Int, failed: Int, pending: Int) {
        Log.d("Rivium", "Synced: $synced, failed: $failed, pending: $pending")
    }

    override fun onError(error: RiviumAbTestingError) {
        Log.e("Rivium", "Error: ${error.message}")
    }
})
```

## Offline Support

Events are queued locally and automatically synced when the device comes online:

```kotlin
// Check online status
val online = RiviumAbTesting.isOnline

// Get number of pending events
val pending = RiviumAbTesting.getPendingEventCount()

// Force sync
RiviumAbTesting.flush()
```

## Configuration

```kotlin
val config = RiviumAbTestingConfig(
    apiKey = "rv_live_your_api_key",
    tokenProvider = tokenProvider, // user token minted by your server
    debug = false,               // log to logcat (development only; no keys or bodies are logged)
    flushInterval = 10000L,      // Auto-flush interval in ms (default: 30s)
    maxQueueSize = 50            // Max events before auto-flush (default: 100)
)
```

## Lifecycle

```kotlin
// Refresh experiments from server
RiviumAbTesting.refreshExperiments()

// Reset all state (clears cache, assignments, events)
RiviumAbTesting.reset()

// Release resources
RiviumAbTesting.dispose()
```

## API Reference

| Method | Description |
|---|---|
| `init(context, config)` | Initialize the SDK |
| `setUserId(id)` | Set user ID for assignment |
| `getUserId()` | Get current user ID |
| `setUserAttributes(attrs)` | Set targeting attributes |
| `getVariant(key)` | Get assigned variant (sync) |
| `getVariantAsync(key, callback)` | Get assigned variant (async) |
| `getVariantConfig(key)` | Get variant configuration |
| `isFeatureEnabled(key)` | Check if feature flag is on |
| `isFeatureEnabledAsync(key, callback)` | Check flag with server eval |
| `getFeatureValue(key)` | Get feature flag value |
| `getFeatureFlags()` | Get all feature flags |
| `refreshFeatureFlags()` | Refresh flags from server |
| `refreshExperiments()` | Refresh experiments from server |
| `getExperiments()` | Get all experiments |
| `flush()` | Force sync pending events |
| `reset()` | Clear all state and cache |
| `dispose()` | Release resources |

## Example App

See the `example/` directory for a complete working example.

## Documentation

- [Rivium Console](https://console.rivium.co)
- [Android SDK Docs](https://console.rivium.co/dashboard/rivium-abtest/docs/android)

## License

MIT

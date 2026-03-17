package co.rivium.abtesting

import co.rivium.abtesting.models.FeatureFlag

/**
 * Callback interface for RiviumAbTesting SDK events
 */
interface RiviumAbTestingCallback {
    /**
     * Called when SDK is initialized successfully
     */
    fun onInitialized() {}

    /**
     * Called when an error occurs
     */
    fun onError(error: RiviumAbTestingError) {}

    /**
     * Called when a user is assigned to an experiment variant
     */
    fun onExperimentAssigned(experimentKey: String, variantKey: String, config: Map<String, Any>?) {}

    /**
     * Called when experiments are refreshed from the server
     */
    fun onExperimentsRefreshed(experiments: List<Experiment>) {}

    /**
     * Called when feature flags are refreshed from the server
     */
    fun onFeatureFlagsRefreshed(flags: List<FeatureFlag>) {}

    /**
     * Called when events are synced with the server
     */
    fun onSyncCompleted(synced: Int, failed: Int, pending: Int) {}

    /**
     * Called when network becomes available
     */
    fun onOnlineMode() {}

    /**
     * Called when network is lost
     */
    fun onOfflineMode() {}
}

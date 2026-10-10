package co.rivium.abtesting.models

import com.google.gson.annotations.SerializedName

/**
 * Experiment model
 */
data class Experiment(
    @SerializedName("id")
    val id: String,

    @SerializedName("key")
    val key: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("status")
    val status: ExperimentStatus,

    @SerializedName("trafficAllocation")
    val trafficAllocation: Int,

    @SerializedName("variants")
    val variants: List<Variant>,

    @SerializedName("targetingRules")
    val targetingRules: Map<String, Any>? = null
)

enum class ExperimentStatus {
    @SerializedName("draft")
    DRAFT,
    @SerializedName("running")
    RUNNING,
    @SerializedName("paused")
    PAUSED,
    @SerializedName("completed")
    COMPLETED,
    @SerializedName("archived")
    ARCHIVED
}

/**
 * Variant model
 */
data class Variant(
    @SerializedName("id")
    val id: String,

    @SerializedName("key")
    val key: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("trafficSplit")
    val trafficSplit: Int,

    @SerializedName("isControl")
    val isControl: Boolean,

    @SerializedName("config")
    val config: Map<String, Any>? = null
)

/**
 * Assignment result
 *
 * @param inExperiment false when the user is shown this (control) variant
 *   without taking part in the experiment; null when the service did not say.
 */
data class Assignment(
    val experimentId: String,
    val experimentKey: String,
    val variantId: String,
    val variantKey: String,
    val isControl: Boolean,
    val config: Map<String, Any>?,
    val inExperiment: Boolean? = null
)

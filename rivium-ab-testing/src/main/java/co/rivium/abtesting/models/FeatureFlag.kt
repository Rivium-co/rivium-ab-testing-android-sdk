package co.rivium.abtesting.models

import com.google.gson.annotations.SerializedName

/**
 * Feature Flag model
 */
data class FeatureFlag(
    @SerializedName("key")
    val key: String,

    @SerializedName("enabled")
    val enabled: Boolean,

    @SerializedName("rolloutPercentage")
    val rolloutPercentage: Int = 100,

    @SerializedName("targetingRules")
    val targetingRules: Map<String, Any>? = null,

    @SerializedName("variants")
    val variants: List<FlagVariant>? = null,

    @SerializedName("defaultValue")
    val defaultValue: Any? = null
)

/**
 * Feature Flag Variant
 */
data class FlagVariant(
    @SerializedName("key")
    val key: String,

    @SerializedName("value")
    val value: Any? = null,

    @SerializedName("weight")
    val weight: Int = 0
)

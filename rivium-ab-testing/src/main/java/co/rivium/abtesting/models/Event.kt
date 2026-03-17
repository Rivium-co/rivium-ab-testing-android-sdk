package co.rivium.abtesting.models

import com.google.gson.annotations.SerializedName

/**
 * Event types for tracking user interactions
 */
enum class EventType {
    // Core events
    @SerializedName("view")
    VIEW,
    @SerializedName("click")
    CLICK,
    @SerializedName("conversion")
    CONVERSION,
    @SerializedName("custom")
    CUSTOM,

    // Engagement events
    @SerializedName("scroll")
    SCROLL,
    @SerializedName("form_submit")
    FORM_SUBMIT,
    @SerializedName("search")
    SEARCH,
    @SerializedName("share")
    SHARE,

    // E-commerce events
    @SerializedName("add_to_cart")
    ADD_TO_CART,
    @SerializedName("remove_from_cart")
    REMOVE_FROM_CART,
    @SerializedName("begin_checkout")
    BEGIN_CHECKOUT,
    @SerializedName("purchase")
    PURCHASE,

    // Media events
    @SerializedName("video_start")
    VIDEO_START,
    @SerializedName("video_complete")
    VIDEO_COMPLETE,

    // User events
    @SerializedName("sign_up")
    SIGN_UP,
    @SerializedName("login")
    LOGIN,
    @SerializedName("logout")
    LOGOUT
}

/**
 * Event model for tracking
 */
data class TrackEvent(
    @SerializedName("experimentId")
    val experimentId: String,

    @SerializedName("variantId")
    val variantId: String,

    @SerializedName("userId")
    val userId: String,

    @SerializedName("eventType")
    val eventType: EventType,

    @SerializedName("eventName")
    val eventName: String? = null,

    @SerializedName("eventValue")
    val eventValue: Double? = null,

    @SerializedName("properties")
    val properties: Map<String, Any>? = null,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @SerializedName("clientEventId")
    val clientEventId: String = java.util.UUID.randomUUID().toString()
)

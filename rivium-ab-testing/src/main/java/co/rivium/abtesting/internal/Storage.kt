package co.rivium.abtesting.internal

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import co.rivium.abtesting.models.Assignment
import co.rivium.abtesting.models.Experiment
import co.rivium.abtesting.models.FeatureFlag

internal class Storage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "rivium_ab_testing_sdk"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EXPERIMENTS = "experiments"
        private const val KEY_FEATURE_FLAGS = "feature_flags"
        private const val KEY_ASSIGNMENTS = "assignments"
        private const val KEY_PENDING_EVENTS = "pending_events"
        private const val KEY_USER_ATTRIBUTES = "user_attributes"
    }

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var userAttributes: Map<String, Any>?
        get() {
            val json = prefs.getString(KEY_USER_ATTRIBUTES, null) ?: return null
            val type = object : TypeToken<Map<String, Any>>() {}.type
            return gson.fromJson(json, type)
        }
        set(value) {
            val json = if (value != null) gson.toJson(value) else null
            prefs.edit().putString(KEY_USER_ATTRIBUTES, json).apply()
        }

    fun saveExperiments(experiments: List<Experiment>) {
        val json = gson.toJson(experiments)
        prefs.edit().putString(KEY_EXPERIMENTS, json).apply()
    }

    fun getExperiments(): List<Experiment> {
        val json = prefs.getString(KEY_EXPERIMENTS, null) ?: return emptyList()
        val type = object : TypeToken<List<Experiment>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveFeatureFlags(flags: List<FeatureFlag>) {
        val json = gson.toJson(flags)
        prefs.edit().putString(KEY_FEATURE_FLAGS, json).apply()
    }

    fun getFeatureFlags(): List<FeatureFlag> {
        val json = prefs.getString(KEY_FEATURE_FLAGS, null) ?: return emptyList()
        val type = object : TypeToken<List<FeatureFlag>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveAssignment(experimentKey: String, assignment: Assignment) {
        val assignments = getAssignments().toMutableMap()
        assignments[experimentKey] = assignment
        val json = gson.toJson(assignments)
        prefs.edit().putString(KEY_ASSIGNMENTS, json).apply()
    }

    fun getAssignment(experimentKey: String): Assignment? {
        return getAssignments()[experimentKey]
    }

    fun getAssignments(): Map<String, Assignment> {
        val json = prefs.getString(KEY_ASSIGNMENTS, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, Assignment>>() {}.type
        return gson.fromJson(json, type)
    }

    fun clearAssignments() {
        prefs.edit().remove(KEY_ASSIGNMENTS).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

package co.rivium.abtesting.internal

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import co.rivium.abtesting.models.Assignment
import co.rivium.abtesting.models.Experiment
import co.rivium.abtesting.models.FeatureFlag

internal class Storage(private val prefs: KeyValueStore) {
    constructor(context: Context) : this(SharedPreferencesStore(context, PREFS_NAME))

    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "rivium_ab_testing_sdk"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_ID_GENERATED = "user_id_generated"
        private const val KEY_EXPERIMENTS = "experiments"
        private const val KEY_FEATURE_FLAGS = "feature_flags"
        private const val KEY_ASSIGNMENTS = "assignments"
        private const val KEY_USER_ATTRIBUTES = "user_attributes"
        private val GENERATED_ID =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }

    var userId: String?
        get() = prefs.getString(KEY_USER_ID)
        set(value) = prefs.putString(KEY_USER_ID, value)

    /**
     * True while [userId] is the random id the SDK made for a user who has not
     * signed in. When unknown (an install from an older version) an id that
     * looks like the SDK's random ids counts as one.
     */
    var userIdGenerated: Boolean
        get() = when (prefs.getString(KEY_USER_ID_GENERATED)) {
            "true" -> true
            "false" -> false
            else -> userId?.let { GENERATED_ID.matches(it) } ?: true
        }
        set(value) = prefs.putString(KEY_USER_ID_GENERATED, value.toString())

    var userAttributes: Map<String, Any>?
        get() {
            val json = prefs.getString(KEY_USER_ATTRIBUTES) ?: return null
            val type = object : TypeToken<Map<String, Any>>() {}.type
            return try {
                gson.fromJson(json, type)
            } catch (e: Exception) {
                null
            }
        }
        set(value) {
            prefs.putString(KEY_USER_ATTRIBUTES, if (value != null) gson.toJson(value) else null)
        }

    fun saveExperiments(experiments: List<Experiment>) {
        prefs.putString(KEY_EXPERIMENTS, gson.toJson(experiments))
    }

    fun getExperiments(): List<Experiment> {
        val json = prefs.getString(KEY_EXPERIMENTS) ?: return emptyList()
        val type = object : TypeToken<List<Experiment>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFeatureFlags(flags: List<FeatureFlag>) {
        prefs.putString(KEY_FEATURE_FLAGS, gson.toJson(flags))
    }

    fun getFeatureFlags(): List<FeatureFlag> {
        val json = prefs.getString(KEY_FEATURE_FLAGS) ?: return emptyList()
        val type = object : TypeToken<List<FeatureFlag>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveAssignment(experimentKey: String, assignment: Assignment) {
        val assignments = getAssignments().toMutableMap()
        assignments[experimentKey] = assignment
        prefs.putString(KEY_ASSIGNMENTS, gson.toJson(assignments))
    }

    fun getAssignment(experimentKey: String): Assignment? {
        return getAssignments()[experimentKey]
    }

    fun getAssignments(): Map<String, Assignment> {
        val json = prefs.getString(KEY_ASSIGNMENTS) ?: return emptyMap()
        val type = object : TypeToken<Map<String, Assignment>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Forget the assignments that say "not in the experiment", so the service is asked again. */
    @Synchronized
    fun clearOutsideAssignments() {
        val assignments = getAssignments()
        val kept = assignments.filterValues { it.inExperiment != false }
        if (kept.size != assignments.size) prefs.putString(KEY_ASSIGNMENTS, gson.toJson(kept))
    }

    @Synchronized
    fun clearAssignments() {
        prefs.putString(KEY_ASSIGNMENTS, null)
    }

    fun clear() {
        prefs.clear()
    }
}

package co.rivium.abtesting

/**
 * RiviumAbTesting SDK errors
 */
sealed class RiviumAbTestingError : Exception() {
    data class NotInitialized(override val message: String = "RiviumAbTesting SDK not initialized") : RiviumAbTestingError()
    data class InvalidConfig(override val message: String = "Invalid configuration") : RiviumAbTestingError()
    data class NetworkError(override val message: String = "Network error") : RiviumAbTestingError()
    data class ExperimentNotFound(val experimentKey: String) : RiviumAbTestingError() {
        override val message: String = "Experiment not found: $experimentKey"
    }
    data class ApiError(val code: Int, override val message: String) : RiviumAbTestingError()
}

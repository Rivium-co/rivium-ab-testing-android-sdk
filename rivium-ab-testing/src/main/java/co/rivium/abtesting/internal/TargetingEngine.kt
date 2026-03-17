package co.rivium.abtesting.internal

/**
 * Evaluates targeting rules to determine if a user should be included in an experiment
 */
internal class TargetingEngine {

    /**
     * Evaluate targeting rules against user attributes
     * Returns true if user matches all rules (AND logic)
     */
    fun evaluate(rules: Map<String, Any>?, userAttributes: Map<String, Any>?): Boolean {
        if (rules == null || rules.isEmpty()) {
            return true // No rules means everyone is included
        }

        for ((key, rule) in rules) {
            if (!evaluateRule(key, rule, userAttributes ?: emptyMap())) {
                return false
            }
        }

        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun evaluateRule(key: String, rule: Any, attributes: Map<String, Any>): Boolean {
        val value = attributes[key]

        return when (rule) {
            is Map<*, *> -> evaluateComplexRule(value, rule as Map<String, Any>)
            else -> value == rule // Simple equality check
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun evaluateComplexRule(value: Any?, rule: Map<String, Any>): Boolean {
        // equals operator
        rule["equals"]?.let { expected ->
            return value == expected
        }

        // notEquals operator
        rule["notEquals"]?.let { expected ->
            return value != expected
        }

        // in operator
        (rule["in"] as? List<*>)?.let { expectedList ->
            return expectedList.contains(value)
        }

        // notIn operator
        (rule["notIn"] as? List<*>)?.let { expectedList ->
            return !expectedList.contains(value)
        }

        // greaterThan operator
        rule["greaterThan"]?.let { expected ->
            return compareNumbers(value, expected) > 0
        }

        // lessThan operator
        rule["lessThan"]?.let { expected ->
            return compareNumbers(value, expected) < 0
        }

        // greaterThanOrEqual operator
        rule["greaterThanOrEqual"]?.let { expected ->
            return compareNumbers(value, expected) >= 0
        }

        // lessThanOrEqual operator
        rule["lessThanOrEqual"]?.let { expected ->
            return compareNumbers(value, expected) <= 0
        }

        // contains operator (for strings)
        rule["contains"]?.let { expected ->
            if (value is String && expected is String) {
                return value.contains(expected)
            }
            return false
        }

        // regex operator
        rule["regex"]?.let { expected ->
            if (value is String && expected is String) {
                return try {
                    Regex(expected).containsMatchIn(value)
                } catch (e: Exception) {
                    false
                }
            }
            return false
        }

        // exists operator
        rule["exists"]?.let { expected ->
            val shouldExist = expected as? Boolean ?: true
            return if (shouldExist) value != null else value == null
        }

        // AND logic
        (rule["and"] as? List<*>)?.let { andRules ->
            return andRules.all { subRule ->
                evaluateComplexRule(value, subRule as Map<String, Any>)
            }
        }

        // OR logic
        (rule["or"] as? List<*>)?.let { orRules ->
            return orRules.any { subRule ->
                evaluateComplexRule(value, subRule as Map<String, Any>)
            }
        }

        return false
    }

    private fun compareNumbers(a: Any?, b: Any?): Int {
        val numA = (a as? Number)?.toDouble() ?: return 0
        val numB = (b as? Number)?.toDouble() ?: return 0
        return numA.compareTo(numB)
    }
}

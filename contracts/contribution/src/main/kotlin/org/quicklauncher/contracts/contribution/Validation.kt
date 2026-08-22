package org.quicklauncher.contracts.contribution

data class ValidationError(
    val code: String,
    val path: String,
    val message: String,
)

sealed interface ValidationResult {
    data object Valid : ValidationResult

    data class Invalid(val errors: List<ValidationError>) : ValidationResult {
        init {
            require(errors.isNotEmpty()) { "Invalid validation results must contain at least one error" }
        }
    }

    companion object {
        fun from(errors: List<ValidationError>): ValidationResult =
            if (errors.isEmpty()) Valid else Invalid(errors.toList())
    }
}

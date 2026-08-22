package org.quicklauncher.contracts.ui

import org.quicklauncher.contracts.domain.StableKey

enum class AccessibilityRole {
    BUTTON,
    IMAGE,
    LIST,
    LIST_ITEM,
    SEARCH_FIELD,
    STATUS,
    TEXT,
}

data class AccessibilityActionDeclaration(
    val id: StableKey,
    val label: String,
)

class AccessibilitySemantics(
    val id: StableKey,
    val label: String,
    val role: AccessibilityRole,
    val stateDescription: String?,
    actions: Collection<AccessibilityActionDeclaration>,
) {
    val actions: List<AccessibilityActionDeclaration> = immutableList(actions)

    override fun equals(other: Any?): Boolean = other is AccessibilitySemantics &&
        id == other.id &&
        label == other.label &&
        role == other.role &&
        stateDescription == other.stateDescription &&
        actions == other.actions

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + (stateDescription?.hashCode() ?: 0)
        return 31 * result + actions.hashCode()
    }

    override fun toString(): String =
        "AccessibilitySemantics(id=$id, label=$label, role=$role, " +
            "stateDescription=$stateDescription, actions=$actions)"
}

class AccessibilityDeclaration(
    semantics: Collection<AccessibilitySemantics>,
    focusOrder: Collection<StableKey>,
    val supportsLargeText: Boolean,
    val supportsKeyboardNavigation: Boolean,
    val supportsReducedMotion: Boolean,
) {
    val semantics: List<AccessibilitySemantics> = immutableList(semantics)
    val focusOrder: List<StableKey> = immutableList(focusOrder)

    override fun equals(other: Any?): Boolean = other is AccessibilityDeclaration &&
        semantics == other.semantics &&
        focusOrder == other.focusOrder &&
        supportsLargeText == other.supportsLargeText &&
        supportsKeyboardNavigation == other.supportsKeyboardNavigation &&
        supportsReducedMotion == other.supportsReducedMotion

    override fun hashCode(): Int {
        var result = semantics.hashCode()
        result = 31 * result + focusOrder.hashCode()
        result = 31 * result + supportsLargeText.hashCode()
        result = 31 * result + supportsKeyboardNavigation.hashCode()
        return 31 * result + supportsReducedMotion.hashCode()
    }

    override fun toString(): String =
        "AccessibilityDeclaration(semantics=$semantics, focusOrder=$focusOrder, " +
            "supportsLargeText=$supportsLargeText, " +
            "supportsKeyboardNavigation=$supportsKeyboardNavigation, " +
            "supportsReducedMotion=$supportsReducedMotion)"
}

data class AccessibilityValidationError(
    val code: String,
    val path: String,
    val message: String,
)

object AccessibilityDeclarationValidator {
    fun validate(declaration: AccessibilityDeclaration): List<AccessibilityValidationError> {
        val errors = mutableListOf<AccessibilityValidationError>()
        declaration.semantics
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sortedBy { it.value }
            .forEach { id ->
                errors += error(
                    "accessibility.duplicate-semantics",
                    "semantics.${id.value}",
                    "Accessibility semantics ID '${id.value}' appears more than once",
                )
            }
        declaration.semantics.forEach { semantics ->
            if (semantics.label.isBlank()) {
                errors += error(
                    "accessibility.blank-label",
                    "semantics.${semantics.id.value}",
                    "Accessibility label must not be blank",
                )
            }
            semantics.actions.filter { it.label.isBlank() }.forEach { action ->
                errors += error(
                    "accessibility.blank-action-label",
                    "semantics.${semantics.id.value}.actions.${action.id.value}",
                    "Accessibility action label must not be blank",
                )
            }
            semantics.actions
                .groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .sortedBy { it.value }
                .forEach { actionId ->
                    errors += error(
                        "accessibility.duplicate-action",
                        "semantics.${semantics.id.value}.actions.${actionId.value}",
                        "Accessibility action ID '${actionId.value}' appears more than once",
                    )
                }
        }

        declaration.focusOrder
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sortedBy { it.value }
            .forEach { id ->
                errors += error(
                    "accessibility.duplicate-focus",
                    "focus.${id.value}",
                    "Focus target '${id.value}' appears more than once",
                )
            }

        val semanticsIds = declaration.semantics.mapTo(mutableSetOf()) { it.id }
        declaration.focusOrder.filterNot { it in semanticsIds }.forEach { id ->
            errors += error(
                "accessibility.unknown-focus",
                "focus.${id.value}",
                "Focus target '${id.value}' has no accessibility semantics",
            )
        }
        if (!declaration.supportsLargeText) {
            errors += error(
                "accessibility.large-text",
                "accessibility.largeText",
                "Contribution must declare large-text support",
            )
        }
        if (!declaration.supportsKeyboardNavigation) {
            errors += error(
                "accessibility.keyboard",
                "accessibility.keyboard",
                "Contribution must declare D-pad and keyboard navigation support",
            )
        }
        if (!declaration.supportsReducedMotion) {
            errors += error(
                "accessibility.reduced-motion",
                "accessibility.reducedMotion",
                "Contribution must declare reduced-motion behavior",
            )
        }
        return immutableList(errors)
    }

    private fun error(code: String, path: String, message: String) =
        AccessibilityValidationError(code, path, message)
}

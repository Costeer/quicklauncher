package org.quicklauncher.host.data.store

interface LauncherStore {
    suspend fun read(): LauncherSnapshot

    /** Reads app visibility and presentation policy without decoding the launcher map aggregate. */
    suspend fun readAppOverrides(): List<AppOverrideRecord> = read().appOverrides

    suspend fun commit(transaction: LauncherTransaction): CommitResult

    suspend fun reconcileConfigurations(): ConfigurationReconciliation
}

/** A launcher store whose owned persistence resources can be released deterministically. */
interface CloseableLauncherStore : LauncherStore, AutoCloseable

sealed interface CommitResult {
    data class Committed(val state: LauncherSnapshot) : CommitResult

    data class Rejected(
        val reason: StoreRejection,
        val current: LauncherSnapshot,
    ) : CommitResult
}

enum class StoreRejectionCode {
    STALE_REVISION,
    ALREADY_BOOTSTRAPPED,
    STORE_EMPTY,
    DUPLICATE_ID,
    NOT_FOUND,
    INVALID_REFERENCE,
    COORDINATE_OCCUPIED,
    COORDINATE_OVERFLOW,
    DISCONNECTED_MAP,
    DESTINATION_GROUP_DISCONNECTED,
    START_DESTINATION_REQUIRED,
    LAYOUT_SELECTION_REQUIRED,
    PLACEMENT_CYCLE,
    PLACEMENT_INDEX_CONFLICT,
    INCOMPATIBLE_PLACEMENT,
    SLOT_AT_CAPACITY,
    PLACEMENT_POLICY_UNAVAILABLE,
    CONFIRMATION_REQUIRED,
    CONFIGURATION_TYPE_MISMATCH,
    CONFIGURATION_INVALID,
    CONTRIBUTION_CATEGORY_MISMATCH,
    RECOVERY_STATE_MISMATCH,
}

data class StoreRejection(
    val code: StoreRejectionCode,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "Store rejection message must not be blank" }
    }
}

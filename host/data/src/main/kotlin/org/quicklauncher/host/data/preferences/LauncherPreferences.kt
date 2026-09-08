package org.quicklauncher.host.data.preferences

import java.util.Collections
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ThemeProfileId

enum class GestureMode {
    CONTENT_HANDOFF,
    EDGE_ACTIVATION,
}

enum class HistoryPolicy {
    DISABLED,
    LOCAL,
}

enum class NotificationStyle {
    HIDDEN,
    DOT,
    APPROXIMATE_COUNT,
}

enum class OnboardingState {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
}

class LauncherPreferences private constructor(
    val gestureMode: GestureMode,
    enabledSearchProviders: Collection<ContributionId>,
    val historyPolicy: HistoryPolicy,
    val themeProfileId: ThemeProfileId?,
    val notificationStyle: NotificationStyle,
    val onboardingState: OnboardingState,
) {
    val enabledSearchProviders: Set<ContributionId> = immutableSet(enabledSearchProviders)

    internal fun copy(
        gestureMode: GestureMode = this.gestureMode,
        enabledSearchProviders: Collection<ContributionId> = this.enabledSearchProviders,
        historyPolicy: HistoryPolicy = this.historyPolicy,
        themeProfileId: ThemeProfileId? = this.themeProfileId,
        notificationStyle: NotificationStyle = this.notificationStyle,
        onboardingState: OnboardingState = this.onboardingState,
    ): LauncherPreferences = LauncherPreferences(
        gestureMode = gestureMode,
        enabledSearchProviders = enabledSearchProviders,
        historyPolicy = historyPolicy,
        themeProfileId = themeProfileId,
        notificationStyle = notificationStyle,
        onboardingState = onboardingState,
    )

    override fun equals(other: Any?): Boolean =
        other is LauncherPreferences &&
            gestureMode == other.gestureMode &&
            enabledSearchProviders == other.enabledSearchProviders &&
            historyPolicy == other.historyPolicy &&
            themeProfileId == other.themeProfileId &&
            notificationStyle == other.notificationStyle &&
            onboardingState == other.onboardingState

    override fun hashCode(): Int {
        var result = gestureMode.hashCode()
        result = 31 * result + enabledSearchProviders.hashCode()
        result = 31 * result + historyPolicy.hashCode()
        result = 31 * result + (themeProfileId?.hashCode() ?: 0)
        result = 31 * result + notificationStyle.hashCode()
        result = 31 * result + onboardingState.hashCode()
        return result
    }

    override fun toString(): String =
        "LauncherPreferences(" +
            "gestureMode=$gestureMode, " +
            "enabledSearchProviders=$enabledSearchProviders, " +
            "historyPolicy=$historyPolicy, " +
            "themeProfileId=$themeProfileId, " +
            "notificationStyle=$notificationStyle, " +
            "onboardingState=$onboardingState)"

    companion object {
        val Default: LauncherPreferences = LauncherPreferences(
            gestureMode = GestureMode.CONTENT_HANDOFF,
            enabledSearchProviders = emptySet(),
            historyPolicy = HistoryPolicy.DISABLED,
            themeProfileId = null,
            notificationStyle = NotificationStyle.HIDDEN,
            onboardingState = OnboardingState.NOT_STARTED,
        )

        fun create(
            gestureMode: GestureMode = GestureMode.CONTENT_HANDOFF,
            enabledSearchProviders: Collection<ContributionId> = emptySet(),
            historyPolicy: HistoryPolicy = HistoryPolicy.DISABLED,
            themeProfileId: ThemeProfileId? = null,
            notificationStyle: NotificationStyle = NotificationStyle.HIDDEN,
            onboardingState: OnboardingState = OnboardingState.NOT_STARTED,
        ): LauncherPreferences = LauncherPreferences(
            gestureMode = gestureMode,
            enabledSearchProviders = enabledSearchProviders,
            historyPolicy = historyPolicy,
            themeProfileId = themeProfileId,
            notificationStyle = notificationStyle,
            onboardingState = onboardingState,
        )
    }
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

package org.quicklauncher.host.data.preferences

import kotlinx.coroutines.flow.Flow
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ThemeProfileId

interface LauncherPreferencesStore : AutoCloseable {
    val state: Flow<LauncherPreferences>

    suspend fun read(): LauncherPreferences

    suspend fun setGestureMode(mode: GestureMode): LauncherPreferences

    suspend fun setEnabledSearchProviders(
        providerIds: Set<ContributionId>,
    ): LauncherPreferences

    suspend fun setHistoryPolicy(policy: HistoryPolicy): LauncherPreferences

    suspend fun setThemeProfile(themeProfileId: ThemeProfileId?): LauncherPreferences

    suspend fun setNotificationStyle(style: NotificationStyle): LauncherPreferences

    suspend fun setOnboardingState(state: OnboardingState): LauncherPreferences
}

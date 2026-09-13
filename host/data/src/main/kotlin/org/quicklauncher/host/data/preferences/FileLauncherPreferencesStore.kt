package org.quicklauncher.host.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.data.preferences.proto.StoredLauncherPreferences

class FileLauncherPreferencesStore private constructor(
    private val dataStore: DataStore<StoredLauncherPreferences>,
    private val scope: CoroutineScope,
) : LauncherPreferencesStore {
    private val closed = AtomicBoolean(false)

    private val data: Flow<LauncherPreferences> = dataStore.data
        .map(LauncherPreferencesProtoCodec::decode)
        .distinctUntilChanged()

    override val state: Flow<LauncherPreferences> = flow {
        checkOpen()
        emitAll(data)
    }

    override suspend fun read(): LauncherPreferences {
        checkOpen()
        currentCoroutineContext().ensureActive()
        return state.first()
    }

    override suspend fun setGestureMode(mode: GestureMode): LauncherPreferences =
        update { it.copy(gestureMode = mode) }

    override suspend fun setEnabledSearchProviders(
        providerIds: Set<ContributionId>,
    ): LauncherPreferences {
        val providerSnapshot = providerIds.toSet()
        return update { it.copy(enabledSearchProviders = providerSnapshot) }
    }

    override suspend fun setHistoryPolicy(policy: HistoryPolicy): LauncherPreferences =
        update { it.copy(historyPolicy = policy) }

    override suspend fun setThemeProfile(
        themeProfileId: ThemeProfileId?,
    ): LauncherPreferences = update { it.copy(themeProfileId = themeProfileId) }

    override suspend fun setNotificationStyle(
        style: NotificationStyle,
    ): LauncherPreferences = update { it.copy(notificationStyle = style) }

    override suspend fun setOnboardingState(
        state: OnboardingState,
    ): LauncherPreferences = update { it.copy(onboardingState = state) }

    override suspend fun setPrivateSpaceVisibility(
        visibility: PrivateSpaceVisibility,
    ): LauncherPreferences = update { it.copy(privateSpaceVisibility = visibility) }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "Launcher preferences store is closed" }
    }

    private suspend fun update(
        transform: (LauncherPreferences) -> LauncherPreferences,
    ): LauncherPreferences {
        checkOpen()
        currentCoroutineContext().ensureActive()
        return dataStore.updateData { stored ->
            LauncherPreferencesProtoCodec.encode(
                transform(LauncherPreferencesProtoCodec.decode(stored)),
            )
        }.let(LauncherPreferencesProtoCodec::decode)
    }

    companion object {
        fun open(
            file: File,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): FileLauncherPreferencesStore {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val dataStore = DataStoreFactory.create(
                serializer = LauncherPreferencesSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler {
                    LauncherPreferencesProtoCodec.defaultValue
                },
                scope = scope,
                produceFile = { file },
            )
            return FileLauncherPreferencesStore(dataStore, scope)
        }
    }
}

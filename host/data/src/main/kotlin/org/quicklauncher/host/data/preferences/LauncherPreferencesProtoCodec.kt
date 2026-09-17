package org.quicklauncher.host.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.data.preferences.proto.StoredGestureMode
import org.quicklauncher.host.data.preferences.proto.StoredHistoryPolicy
import org.quicklauncher.host.data.preferences.proto.StoredLauncherPreferences
import org.quicklauncher.host.data.preferences.proto.StoredNotificationStyle
import org.quicklauncher.host.data.preferences.proto.StoredOnboardingState
import org.quicklauncher.host.data.preferences.proto.StoredPrivateSpaceVisibility

internal object LauncherPreferencesProtoCodec {
    const val SCHEMA_VERSION: Int = 1

    val defaultValue: StoredLauncherPreferences = encode(LauncherPreferences.Default)

    fun decode(value: StoredLauncherPreferences): LauncherPreferences {
        if (value.schemaVersion > SCHEMA_VERSION) {
            throw UnsupportedLauncherPreferencesVersionException(value.schemaVersion)
        }
        require(value.schemaVersion == SCHEMA_VERSION) {
            "Unsupported launcher preferences schema version ${value.schemaVersion}"
        }
        return LauncherPreferences.create(
            gestureMode = when (value.gestureMode) {
                StoredGestureMode.STORED_GESTURE_MODE_CONTENT_HANDOFF -> GestureMode.CONTENT_HANDOFF
                StoredGestureMode.STORED_GESTURE_MODE_EDGE_ACTIVATION -> GestureMode.EDGE_ACTIVATION
                else -> error("Unknown gesture mode ${value.gestureMode}")
            },
            enabledSearchProviders = value.enabledSearchProviderIdsList.map(ContributionId::parse),
            searchProvidersInitialized = value.searchProvidersInitialized,
            historyPolicy = when (value.historyPolicy) {
                StoredHistoryPolicy.STORED_HISTORY_POLICY_DISABLED -> HistoryPolicy.DISABLED
                StoredHistoryPolicy.STORED_HISTORY_POLICY_LOCAL -> HistoryPolicy.LOCAL
                else -> error("Unknown history policy ${value.historyPolicy}")
            },
            themeProfileId = if (value.hasThemeProfileId()) {
                ThemeProfileId.parse(value.themeProfileId)
            } else {
                null
            },
            notificationStyle = when (value.notificationStyle) {
                StoredNotificationStyle.STORED_NOTIFICATION_STYLE_HIDDEN -> NotificationStyle.HIDDEN
                StoredNotificationStyle.STORED_NOTIFICATION_STYLE_DOT -> NotificationStyle.DOT
                StoredNotificationStyle.STORED_NOTIFICATION_STYLE_APPROXIMATE_COUNT ->
                    NotificationStyle.APPROXIMATE_COUNT
                else -> error("Unknown notification style ${value.notificationStyle}")
            },
            onboardingState = when (value.onboardingState) {
                StoredOnboardingState.STORED_ONBOARDING_STATE_NOT_STARTED ->
                    OnboardingState.NOT_STARTED
                StoredOnboardingState.STORED_ONBOARDING_STATE_IN_PROGRESS ->
                    OnboardingState.IN_PROGRESS
                StoredOnboardingState.STORED_ONBOARDING_STATE_COMPLETED ->
                    OnboardingState.COMPLETED
                else -> error("Unknown onboarding state ${value.onboardingState}")
            },
            privateSpaceVisibility = when (value.privateSpaceVisibility) {
                StoredPrivateSpaceVisibility.STORED_PRIVATE_SPACE_VISIBILITY_UNSPECIFIED,
                StoredPrivateSpaceVisibility.STORED_PRIVATE_SPACE_VISIBILITY_VISIBLE,
                -> PrivateSpaceVisibility.VISIBLE
                StoredPrivateSpaceVisibility.STORED_PRIVATE_SPACE_VISIBILITY_HIDDEN ->
                    PrivateSpaceVisibility.HIDDEN
                else -> error("Unknown Private Space visibility ${value.privateSpaceVisibility}")
            },
        )
    }

    fun encode(value: LauncherPreferences): StoredLauncherPreferences =
        StoredLauncherPreferences.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setGestureMode(
                when (value.gestureMode) {
                    GestureMode.CONTENT_HANDOFF ->
                        StoredGestureMode.STORED_GESTURE_MODE_CONTENT_HANDOFF
                    GestureMode.EDGE_ACTIVATION ->
                        StoredGestureMode.STORED_GESTURE_MODE_EDGE_ACTIVATION
                },
            )
            .addAllEnabledSearchProviderIds(
                value.enabledSearchProviders.map { it.value }.sorted(),
            )
            .setSearchProvidersInitialized(value.searchProvidersInitialized)
            .setHistoryPolicy(
                when (value.historyPolicy) {
                    HistoryPolicy.DISABLED -> StoredHistoryPolicy.STORED_HISTORY_POLICY_DISABLED
                    HistoryPolicy.LOCAL -> StoredHistoryPolicy.STORED_HISTORY_POLICY_LOCAL
                },
            )
            .also { builder ->
                value.themeProfileId?.let { builder.themeProfileId = it.value }
            }
            .setNotificationStyle(
                when (value.notificationStyle) {
                    NotificationStyle.HIDDEN ->
                        StoredNotificationStyle.STORED_NOTIFICATION_STYLE_HIDDEN
                    NotificationStyle.DOT -> StoredNotificationStyle.STORED_NOTIFICATION_STYLE_DOT
                    NotificationStyle.APPROXIMATE_COUNT ->
                        StoredNotificationStyle.STORED_NOTIFICATION_STYLE_APPROXIMATE_COUNT
                },
            )
            .setOnboardingState(
                when (value.onboardingState) {
                    OnboardingState.NOT_STARTED ->
                        StoredOnboardingState.STORED_ONBOARDING_STATE_NOT_STARTED
                    OnboardingState.IN_PROGRESS ->
                        StoredOnboardingState.STORED_ONBOARDING_STATE_IN_PROGRESS
                    OnboardingState.COMPLETED ->
                        StoredOnboardingState.STORED_ONBOARDING_STATE_COMPLETED
                },
            )
            .setPrivateSpaceVisibility(
                when (value.privateSpaceVisibility) {
                    PrivateSpaceVisibility.VISIBLE ->
                        StoredPrivateSpaceVisibility.STORED_PRIVATE_SPACE_VISIBILITY_VISIBLE
                    PrivateSpaceVisibility.HIDDEN ->
                        StoredPrivateSpaceVisibility.STORED_PRIVATE_SPACE_VISIBILITY_HIDDEN
                },
            )
            .build()
}

class UnsupportedLauncherPreferencesVersionException(
    val schemaVersion: Int,
) : IOException("Unsupported launcher preferences schema version $schemaVersion")

internal object LauncherPreferencesSerializer : Serializer<StoredLauncherPreferences> {
    override val defaultValue: StoredLauncherPreferences = LauncherPreferencesProtoCodec.defaultValue

    override suspend fun readFrom(input: InputStream): StoredLauncherPreferences = try {
        StoredLauncherPreferences.parseFrom(input).also(LauncherPreferencesProtoCodec::decode)
    } catch (exception: InvalidProtocolBufferException) {
        throw CorruptionException("Cannot parse launcher preferences", exception)
    } catch (exception: IllegalArgumentException) {
        throw CorruptionException("Launcher preferences are invalid", exception)
    } catch (exception: IllegalStateException) {
        throw CorruptionException("Launcher preferences are invalid", exception)
    }

    override suspend fun writeTo(
        t: StoredLauncherPreferences,
        output: OutputStream,
    ) {
        LauncherPreferencesProtoCodec.decode(t)
        t.writeTo(output)
    }
}

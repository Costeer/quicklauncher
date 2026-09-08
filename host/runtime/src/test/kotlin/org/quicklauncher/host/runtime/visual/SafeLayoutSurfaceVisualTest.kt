package org.quicklauncher.host.runtime.visual

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.LauncherApp
import org.quicklauncher.host.runtime.LauncherRuntimeState
import org.quicklauncher.host.runtime.LauncherRuntimeStatus
import org.quicklauncher.host.runtime.LauncherSurface
import org.quicklauncher.host.runtime.SafeLayoutSurface
import org.quicklauncher.host.runtime.catalog.AppCatalogStatus
import org.quicklauncher.host.runtime.permissions.HomeRoleState

class SafeLayoutSurfaceVisualTest {
    @get:Rule
    val paparazzi = Paparazzi(
        validateAccessibility = true,
        showSystemUi = false,
    )

    @Test
    fun `safe layout remains usable across orientation theme and large text`() {
        variants.forEach { variant ->
            paparazzi.unsafeUpdateConfig(variant.device)
            paparazzi.snapshot(name = "safe-layout-${variant.id}") {
                MaterialTheme(
                    colorScheme = if (variant.dark) darkColorScheme() else lightColorScheme(),
                ) {
                    SafeLayoutSurface(
                        state = state,
                        onQueryChanged = {},
                        onLaunch = {},
                        onOpenMap = {},
                        onOpenSettings = {},
                        onOpenRecovery = {},
                        onRequestHomeRole = {},
                    )
                }
            }
        }
    }

    private val state = LauncherRuntimeState(
        status = LauncherRuntimeStatus.READY,
        currentDestinationId = null,
        surface = LauncherSurface.SAFE_LAYOUT,
        localQuery = "",
        homeRoleState = HomeRoleState.AVAILABLE_NOT_HELD,
        catalogStatus = AppCatalogStatus.READY,
        visibleApps = listOf(
            app(0, "Calendar", "org.quicklauncher.fixture.calendar", "CalendarActivity"),
            app(10, "Work documents", "org.quicklauncher.fixture.documents", "DocumentsActivity", true),
            app(0, "A deliberately long application label", "org.quicklauncher.fixture.longlabel", "LongLabelActivity"),
        ),
        destinations = emptyList(),
        recoveryInstances = emptyList(),
    )

    private fun app(
        profile: Long,
        label: String,
        packageName: String,
        activityName: String,
        workProfile: Boolean = false,
    ): LauncherApp = LauncherApp(
        identity = AppActivityIdentity(
            profile = ProfileSerial.of(profile),
            packageName = PackageName.parse(packageName),
            activityName = ActivityName.parse("$packageName.$activityName"),
        ),
        label = label,
        workProfile = workProfile,
    )

    private companion object {
        val variants = listOf(
            VisualVariant(
                id = "light-portrait",
                dark = false,
                device = device(false, 1f),
            ),
            VisualVariant(
                id = "dark-landscape",
                dark = true,
                device = device(true, 1f),
            ),
            VisualVariant(
                id = "large-text-light-portrait",
                dark = false,
                device = device(false, 2f),
            ),
        )

        fun device(landscape: Boolean, fontScale: Float): DeviceConfig =
            DeviceConfig.PIXEL_5.copy(
                screenWidth = if (landscape) 2_340 else 1_080,
                screenHeight = if (landscape) 1_080 else 2_340,
                orientation = if (landscape) {
                    ScreenOrientation.LANDSCAPE
                } else {
                    ScreenOrientation.PORTRAIT
                },
                nightMode = if (landscape) NightMode.NIGHT else NightMode.NOTNIGHT,
                fontScale = fontScale,
            )
    }
}

private data class VisualVariant(
    val id: String,
    val dark: Boolean,
    val device: DeviceConfig,
)

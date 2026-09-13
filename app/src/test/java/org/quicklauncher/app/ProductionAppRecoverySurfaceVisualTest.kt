package org.quicklauncher.app

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

class ProductionAppRecoverySurfaceVisualTest {
    @get:Rule
    val paparazzi = Paparazzi(
        validateAccessibility = true,
        showSystemUi = false,
    )

    @Test
    fun `production recovery keeps launch and actions separate across visual modes`() {
        variants.forEach { variant ->
            paparazzi.unsafeUpdateConfig(variant.device)
            paparazzi.snapshot(name = "app-recovery-${variant.id}") {
                MaterialTheme(
                    colorScheme = if (variant.dark) darkColorScheme() else lightColorScheme(),
                ) {
                    ProductionAppRecoverySurface(
                        apps = apps,
                        onLaunch = {},
                        onOpenActions = {},
                        onClose = {},
                    )
                }
            }
        }
    }

    private companion object {
        val apps = listOf(
            app(
                profile = 0,
                packageName = "org.quicklauncher.fixture.personal",
                label = "Personal calendar",
                workProfile = false,
            ),
            app(
                profile = 10,
                packageName = "org.quicklauncher.fixture.work",
                label = "Work app with a deliberately long recovery label",
                workProfile = true,
            ),
        )

        val variants = listOf(
            VisualVariant("light-portrait", false, device(false, 1f)),
            VisualVariant("dark-landscape", true, device(true, 1f)),
            VisualVariant("large-text-light-portrait", false, device(false, 2f)),
        )

        fun app(
            profile: Long,
            packageName: String,
            label: String,
            workProfile: Boolean,
        ): LauncherApp = LauncherApp(
            identity = AppActivityIdentity(
                profile = ProfileSerial.of(profile),
                packageName = PackageName.parse(packageName),
                activityName = ActivityName.parse("$packageName.MainActivity"),
            ),
            label = label,
            workProfile = workProfile,
        )

        fun device(landscape: Boolean, fontScale: Float): DeviceConfig =
            DeviceConfig.PIXEL_5.copy(
                screenWidth = if (landscape) 2_340 else 1_080,
                screenHeight = if (landscape) 1_080 else 2_340,
                orientation = if (landscape) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
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

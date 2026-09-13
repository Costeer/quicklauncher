package org.quicklauncher.host.editor.visual

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test
import org.quicklauncher.host.editor.DestinationEditorSurface
import org.quicklauncher.host.editor.LauncherOnboardingSurface
import org.quicklauncher.host.editor.MapEditorSurface
import org.quicklauncher.host.editor.OnboardingPreview
import org.quicklauncher.host.editor.OnboardingTemplate
import org.quicklauncher.host.editor.TemplateOnboardingTest
import org.quicklauncher.host.editor.destinationState
import org.quicklauncher.host.editor.mapState

class LauncherEditorVisualTest {
    @get:Rule
    val paparazzi = Paparazzi(validateAccessibility = true, showSystemUi = false)

    @Test
    fun `map destination and onboarding editors remain usable at large text`() {
        paparazzi.unsafeUpdateConfig(
            DeviceConfig.PIXEL_5.copy(fontScale = 2f, nightMode = NightMode.NOTNIGHT),
        )
        paparazzi.snapshot(name = "map-editor-large-text") {
            VisualSurface { MapEditorSurface(mapState(), {}) }
        }
        paparazzi.snapshot(name = "destination-editor-large-text") {
            VisualSurface { DestinationEditorSurface(destinationState(), {}) }
        }
        paparazzi.snapshot(name = "onboarding-preview-large-text") {
            VisualSurface {
                LauncherOnboardingSurface(
                    templates = listOf(
                        OnboardingTemplate(
                            TemplateOnboardingTest.TEMPLATE,
                            "Modular",
                            "Grid center with entry above and apps to the right",
                            "{\"includeClock\":true,\"includeFavorites\":true}",
                            listOf("Include clock and date", "Include favorites"),
                        ),
                    ),
                    preview = OnboardingPreview(
                        TemplateOnboardingTest.TEMPLATE,
                        TemplateOnboardingTest.plan(TemplateOnboardingTest.BLOCK),
                        emptyList(),
                    ),
                    installed = false,
                    message = null,
                    onPreview = { _, _ -> },
                    onInstall = {},
                    onRequestHomeRole = {},
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun VisualSurface(content: @androidx.compose.runtime.Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}

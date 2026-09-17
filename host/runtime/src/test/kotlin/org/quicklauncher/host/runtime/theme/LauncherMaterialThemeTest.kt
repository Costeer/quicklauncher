package org.quicklauncher.host.runtime.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LauncherMaterialThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `material surface uses the resolved contract colors`() {
        val resolved = BuiltInLauncherThemeResolver.resolve(
            ThemeResolutionRequest(systemDark = true, textScale = 1f, reducedMotion = false),
        )

        compose.setContent {
            LauncherMaterialTheme(resolved) {
                val colors = MaterialTheme.colorScheme
                assertEquals(Color(resolved.theme.accent.value), colors.primary)
                assertEquals(Color(resolved.theme.background.value), colors.background)
                assertEquals(Color(resolved.theme.foreground.value), colors.onBackground)
                assertEquals(Color(resolved.theme.background.value), colors.surface)
                assertEquals(Color(resolved.theme.foreground.value), colors.onSurface)
                assertEquals(
                    RoundedCornerShape(resolved.theme.shapes.corners.mediumDp.dp),
                    MaterialTheme.shapes.medium,
                )
            }
        }
    }
}

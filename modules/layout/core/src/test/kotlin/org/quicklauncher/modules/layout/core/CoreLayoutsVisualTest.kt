package org.quicklauncher.modules.layout.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.quicklauncher.contracts.contribution.ActiveCancellationSignal
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.LayoutContribution
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.LayoutAction
import org.quicklauncher.contracts.ui.LayoutRenderInput
import org.quicklauncher.contracts.ui.PlacedChild
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.SlotRenderState
import org.quicklauncher.contracts.ui.SlotRenderer
import org.quicklauncher.contracts.ui.ThemeMode
import org.quicklauncher.contracts.ui.WindowOrientation
import org.quicklauncher.testing.fakes.FakeHostStates

class CoreLayoutsVisualTest {
    @get:Rule
    val paparazzi = Paparazzi(validateAccessibility = true, showSystemUi = false)

    @Test
    fun `core layouts cover all visual contract scenarios`() {
        snapshots("grid", GridLayout, GridLayoutCodec)
        snapshots("single", SingleBlockLayout, SingleBlockLayoutCodec)
    }

    private fun <C : Any> snapshots(
        name: String,
        contribution: LayoutContribution<C>,
        codec: ConfigurationCodec<C>,
    ) {
        PreviewScenario.entries.forEach { scenario ->
            val state = FakeHostStates.layout(scenario)
            val landscape = state.window.orientation == WindowOrientation.LANDSCAPE
            paparazzi.unsafeUpdateConfig(
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = if (landscape) 800 else 360,
                    screenHeight = if (landscape) 360 else 800,
                    orientation = if (landscape) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
                    nightMode = if (state.theme.mode == ThemeMode.DARK) NightMode.NIGHT else NightMode.NOTNIGHT,
                    fontScale = state.theme.textScale,
                ),
            )
            val scope = CoroutineScope(SupervisorJob())
            val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/$name-visual")
            val session = contribution.open(
                ContributionContext(instanceId, codec.default, ActiveCancellationSignal, scope),
            )
            paparazzi.snapshot(name = "$name-${scenario.name.lowercase()}") {
                MaterialTheme(
                    colorScheme = if (state.theme.mode == ThemeMode.DARK) {
                        darkColorScheme()
                    } else {
                        lightColorScheme()
                    },
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        session.Render(
                            LayoutRenderInput(
                                instanceId,
                                state,
                                VisualChildren,
                                ActionSink<LayoutAction> { ActionDispatchResult.Accepted },
                            ),
                        )
                    }
                }
            }
            session.close()
            scope.cancel()
        }
    }
}

private object VisualChildren : SlotRenderer {
    @Composable
    override fun Render(slot: SlotRenderState, modifier: Modifier) {
        slot.placements.forEach { Text(it.contributionId.value.substringAfterLast('/'), modifier) }
    }

    @Composable
    override fun RenderChild(slot: SlotRenderState, child: PlacedChild, modifier: Modifier) {
        Text(child.contributionId.value.substringAfterLast('/'), modifier)
    }
}

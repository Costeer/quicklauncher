package org.quicklauncher.modules.block.core

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.quicklauncher.contracts.contribution.ActiveCancellationSignal
import org.quicklauncher.contracts.contribution.BlockContribution
import org.quicklauncher.contracts.contribution.ConfigurationCodec
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.BlockAction
import org.quicklauncher.contracts.ui.BlockRenderInput
import org.quicklauncher.contracts.ui.BlockRenderState
import org.quicklauncher.contracts.ui.PreparedContentItem
import org.quicklauncher.contracts.ui.PreparedContentKind
import org.quicklauncher.contracts.ui.PreparedContentRenderer
import org.quicklauncher.contracts.ui.PreparedHostContent
import org.quicklauncher.contracts.ui.PreparedHostSurface
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.SlotRenderState
import org.quicklauncher.contracts.ui.SlotRenderer
import org.quicklauncher.contracts.ui.ThemeMode
import org.quicklauncher.contracts.ui.WindowOrientation
import org.quicklauncher.testing.fakes.FakeHostStates

class CoreBlocksVisualTest {
    @get:Rule
    val paparazzi = Paparazzi(validateAccessibility = true, showSystemUi = false)

    @Test
    fun `core block renderers cover every required preview scenario`() {
        snapshots("alphabetical", AlphabeticalAppsBlock, AlphabeticalAppsCodec)
        snapshots("app-grid", AppGridBlock, AppGridCodec)
        snapshots("favorites", FavoritesBlock, FavoritesCodec)
        snapshots("folder", FolderBlock, FolderCodec)
        snapshots("widget", WidgetBlock, WidgetCodec)
        snapshots("clock-date", ClockDateBlock, ClockDateCodec)
        snapshots("search", SearchBlock, SearchCodec)
    }

    private fun <C : Any> snapshots(
        name: String,
        contribution: BlockContribution<C>,
        codec: ConfigurationCodec<C>,
    ) {
        PreviewScenario.entries.forEach { scenario ->
            val baseState = FakeHostStates.block(scenario)
            val state = when (name) {
                "folder" -> folderState(baseState)
                "widget" -> widgetState(baseState)
                else -> baseState
            }
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
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/$name-preview")
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
                            BlockRenderInput(
                                instanceId,
                                state,
                                EmptyVisualSlotRenderer,
                                VisualContentRenderer,
                                ActionSink<BlockAction> { ActionDispatchResult.Accepted },
                            ),
                        )
                    }
                }
            }
            session.close()
            scope.cancel()
        }
    }

    private fun folderState(source: BlockRenderState) = BlockRenderState(
        source.theme,
        source.window,
        source.backgroundContrast,
        source.status,
        source.editorMode,
        source.composition,
        source.placement,
        PreparedHostContent(
            listOf(
                PreparedContentItem(
                    org.quicklauncher.contracts.domain.ContentItemId.parse(
                        "org.quicklauncher.content/folder-preview",
                    ),
                    "Travel",
                    "3 items",
                    PreparedContentKind.FOLDER,
                    enabled = true,
                ),
            ),
            emptyList(),
        ),
        source.childSlots,
    )

    private fun widgetState(source: BlockRenderState) = BlockRenderState(
        source.theme,
        source.window,
        source.backgroundContrast,
        source.status,
        source.editorMode,
        source.composition,
        source.placement,
        PreparedHostContent(
            emptyList(),
            listOf(
                PreparedHostSurface(
                    org.quicklauncher.contracts.domain.StableKey.parse("widget-preview"),
                    org.quicklauncher.contracts.ui.PreparedSurfaceKind.WIDGET,
                    org.quicklauncher.contracts.ui.RenderStatus.Ready,
                ),
            ),
        ),
        source.childSlots,
    )
}

private object EmptyVisualSlotRenderer : SlotRenderer {
    @Composable
    override fun Render(slot: SlotRenderState, modifier: Modifier) = Unit
}

private object VisualContentRenderer : PreparedContentRenderer {
    @Composable
    override fun RenderItem(item: PreparedContentItem, modifier: Modifier) {
        Text(item.label, modifier)
    }

    @Composable
    override fun RenderSurface(surface: PreparedHostSurface, modifier: Modifier) {
        Text("Prepared ${surface.kind.name.lowercase()} surface", modifier)
    }
}

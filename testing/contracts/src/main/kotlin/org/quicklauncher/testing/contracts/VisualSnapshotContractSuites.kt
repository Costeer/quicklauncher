package org.quicklauncher.testing.contracts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.accessibility.AccessibilityRenderExtension
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.ActionSink
import org.quicklauncher.contracts.ui.BlockAction
import org.quicklauncher.contracts.ui.BlockRenderInput
import org.quicklauncher.contracts.ui.LayoutAction
import org.quicklauncher.contracts.ui.LayoutRenderInput
import org.quicklauncher.contracts.ui.PreparedContentItem
import org.quicklauncher.contracts.ui.PreparedContentRenderer
import org.quicklauncher.contracts.ui.PreparedHostSurface
import org.quicklauncher.contracts.ui.PreviewScenario
import org.quicklauncher.contracts.ui.SlotRenderState
import org.quicklauncher.contracts.ui.SlotRenderer
import org.quicklauncher.contracts.ui.ThemeMode
import org.quicklauncher.contracts.ui.WindowOrientation

abstract class LayoutVisualSnapshotContractSuite<C : Any> {
    protected abstract val contract: LayoutContractDeclaration<C>

    @get:Rule
    val paparazzi = Paparazzi(
        renderExtensions = setOf(AccessibilityRenderExtension()),
        showSystemUi = false,
    )

    @Test
    fun `layout composable screenshots cover every visual fixture and typed slot`() {
        assertEquals(PreviewScenario.entries.toSet(), contract.fixtures.map { it.scenario }.toSet())
        contract.fixtures.forEach { fixture ->
            updateDevice(fixture.state.window.orientation, fixture.state.theme.mode, fixture.state.theme.textScale)
            val lifecycle = SnapshotLifecycle()
            val session = contract.target.open(
                ContributionContext(
                    contract.instanceId,
                    contract.codec.default,
                    SnapshotCancellation,
                    lifecycle.scope,
                ),
            )
            val slots = ScreenshotSlotRenderer(fixture.state.theme.foreground.value)
            paparazzi.snapshot(name = fixture.screenshotId.value) {
                session.Render(
                    LayoutRenderInput(
                        contract.instanceId,
                        fixture.state,
                        slots,
                        ActionSink<LayoutAction> { ActionDispatchResult.Accepted },
                    ),
                )
            }
            assertEquals(fixture.state.slots.map { it.id }, slots.rendered.map { it.id })
            session.close()
            lifecycle.close()
        }
    }

    private fun updateDevice(
        orientation: WindowOrientation,
        mode: ThemeMode,
        textScale: Float,
    ) {
        val landscape = orientation == WindowOrientation.LANDSCAPE
        paparazzi.unsafeUpdateConfig(
            DeviceConfig.PIXEL_5.copy(
                screenWidth = if (landscape) 800 else 360,
                screenHeight = if (landscape) 360 else 800,
                orientation = if (landscape) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
                nightMode = if (mode == ThemeMode.DARK) NightMode.NIGHT else NightMode.NOTNIGHT,
                fontScale = textScale,
            ),
        )
    }
}

abstract class BlockVisualSnapshotContractSuite<C : Any> {
    protected abstract val contract: BlockContractDeclaration<C>

    @get:Rule
    val paparazzi = Paparazzi(
        renderExtensions = setOf(AccessibilityRenderExtension()),
        showSystemUi = false,
    )

    @Test
    fun `block composable screenshots cover slots and prepared host content`() {
        assertEquals(PreviewScenario.entries.toSet(), contract.fixtures.map { it.scenario }.toSet())
        contract.fixtures.forEach { fixture ->
            val landscape = fixture.state.window.orientation == WindowOrientation.LANDSCAPE
            paparazzi.unsafeUpdateConfig(
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = if (landscape) 800 else 360,
                    screenHeight = if (landscape) 360 else 800,
                    orientation = if (landscape) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
                    nightMode = if (fixture.state.theme.mode == ThemeMode.DARK) {
                        NightMode.NIGHT
                    } else {
                        NightMode.NOTNIGHT
                    },
                    fontScale = fixture.state.theme.textScale,
                ),
            )
            val lifecycle = SnapshotLifecycle()
            val session = contract.target.open(
                ContributionContext(
                    contract.instanceId,
                    contract.codec.default,
                    SnapshotCancellation,
                    lifecycle.scope,
                ),
            )
            val slots = ScreenshotSlotRenderer(fixture.state.theme.foreground.value)
            val content = ScreenshotPreparedContentRenderer(fixture.state.theme.foreground.value)
            paparazzi.snapshot(name = fixture.screenshotId.value) {
                session.Render(
                    BlockRenderInput(
                        contract.instanceId,
                        fixture.state,
                        slots,
                        content,
                        ActionSink<BlockAction> { ActionDispatchResult.Accepted },
                    ),
                )
            }
            assertEquals(fixture.state.childSlots.map { it.id }, slots.rendered.map { it.id })
            assertEquals(fixture.state.content.items.map { it.id }, content.items.map { it.id })
            assertEquals(fixture.state.content.surfaces.map { it.key }, content.surfaces.map { it.key })
            session.close()
            lifecycle.close()
        }
    }
}

abstract class LayoutVisualAccessibilityContractSuite<C : Any> {
    protected abstract val contract: LayoutContractDeclaration<C>

    @get:Rule
    val paparazzi = Paparazzi(validateAccessibility = true, showSystemUi = false)

    @Test
    fun `layout render tree passes accessibility checks for every fixture`() {
        contract.fixtures.forEach { fixture ->
            updateAccessibilityDevice(
                paparazzi,
                fixture.state.window.orientation,
                fixture.state.theme.mode,
                fixture.state.theme.textScale,
            )
            val lifecycle = SnapshotLifecycle()
            val session = contract.target.open(
                ContributionContext(
                    contract.instanceId,
                    contract.codec.default,
                    SnapshotCancellation,
                    lifecycle.scope,
                ),
            )
            paparazzi.snapshot(name = "a11y-${fixture.screenshotId.value}") {
                session.Render(
                    LayoutRenderInput(
                        contract.instanceId,
                        fixture.state,
                        ScreenshotSlotRenderer(fixture.state.theme.foreground.value),
                        ActionSink<LayoutAction> { ActionDispatchResult.Accepted },
                    ),
                )
            }
            session.close()
            lifecycle.close()
        }
    }
}

abstract class BlockVisualAccessibilityContractSuite<C : Any> {
    protected abstract val contract: BlockContractDeclaration<C>

    @get:Rule
    val paparazzi = Paparazzi(validateAccessibility = true, showSystemUi = false)

    @Test
    fun `block render tree passes accessibility checks for every fixture`() {
        contract.fixtures.forEach { fixture ->
            updateAccessibilityDevice(
                paparazzi,
                fixture.state.window.orientation,
                fixture.state.theme.mode,
                fixture.state.theme.textScale,
            )
            val lifecycle = SnapshotLifecycle()
            val session = contract.target.open(
                ContributionContext(
                    contract.instanceId,
                    contract.codec.default,
                    SnapshotCancellation,
                    lifecycle.scope,
                ),
            )
            paparazzi.snapshot(name = "a11y-${fixture.screenshotId.value}") {
                session.Render(
                    BlockRenderInput(
                        contract.instanceId,
                        fixture.state,
                        ScreenshotSlotRenderer(fixture.state.theme.foreground.value),
                        ScreenshotPreparedContentRenderer(fixture.state.theme.foreground.value),
                        ActionSink<BlockAction> { ActionDispatchResult.Accepted },
                    ),
                )
            }
            session.close()
            lifecycle.close()
        }
    }
}

private fun updateAccessibilityDevice(
    paparazzi: Paparazzi,
    orientation: WindowOrientation,
    mode: ThemeMode,
    textScale: Float,
) {
    val landscape = orientation == WindowOrientation.LANDSCAPE
    paparazzi.unsafeUpdateConfig(
        DeviceConfig.PIXEL_5.copy(
            screenWidth = if (landscape) 800 else 360,
            screenHeight = if (landscape) 360 else 800,
            orientation = if (landscape) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
            nightMode = if (mode == ThemeMode.DARK) NightMode.NIGHT else NightMode.NOTNIGHT,
            fontScale = textScale,
        ),
    )
}

private object SnapshotCancellation : org.quicklauncher.contracts.contribution.CancellationSignal {
    override val isCancelled: Boolean = false
}

private class SnapshotLifecycle : AutoCloseable {
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Default)
    override fun close() = scope.cancel()
}

private class ScreenshotSlotRenderer(
    private val foreground: Long,
) : SlotRenderer {
    val rendered = mutableListOf<SlotRenderState>()

    @Composable
    override fun Render(slot: SlotRenderState, modifier: Modifier) {
        rendered += slot
        Box(modifier) {
            BasicText("Slot ${slot.id.value}", style = TextStyle(color = Color(foreground)))
        }
    }
}

private class ScreenshotPreparedContentRenderer(
    private val foreground: Long,
) : PreparedContentRenderer {
    val items = mutableListOf<PreparedContentItem>()
    val surfaces = mutableListOf<PreparedHostSurface>()

    @Composable
    override fun RenderItem(item: PreparedContentItem, modifier: Modifier) {
        items += item
        BasicText(item.label, modifier, style = TextStyle(color = Color(foreground)))
    }

    @Composable
    override fun RenderSurface(surface: PreparedHostSurface, modifier: Modifier) {
        surfaces += surface
        BasicText(
            "Surface ${surface.key.value}",
            modifier,
            style = TextStyle(color = Color(foreground)),
        )
    }
}

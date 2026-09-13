package org.quicklauncher.host.platform.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.quicklauncher.contracts.ui.PreparedContentItem
import org.quicklauncher.contracts.ui.PreparedContentRenderer
import org.quicklauncher.contracts.ui.PreparedHostSurface
import org.quicklauncher.contracts.ui.PreparedSurfaceKind
import org.quicklauncher.host.runtime.widgets.WidgetRenderToken

/** Routes opaque widget surfaces to their platform-owned host view and delegates other content. */
class AndroidWidgetPreparedContentRenderer(
    private val widgets: AndroidWidgetPlatform,
    private val fallback: PreparedContentRenderer,
) : PreparedContentRenderer {
    @Composable
    override fun RenderItem(item: PreparedContentItem, modifier: Modifier) {
        fallback.RenderItem(item, modifier)
    }

    @Composable
    override fun RenderSurface(surface: PreparedHostSurface, modifier: Modifier) {
        if (surface.kind != PreparedSurfaceKind.WIDGET) {
            fallback.RenderSurface(surface, modifier)
            return
        }
        val token = WidgetRenderToken.of(surface.key.value)
        if (widgets.ownsSurface(token)) {
            widgets.RenderSurface(token, modifier)
        } else {
            fallback.RenderSurface(surface, modifier)
        }
    }
}

package org.quicklauncher.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.ui.NotificationIndicator
import org.quicklauncher.contracts.ui.PreparedAvailability
import org.quicklauncher.contracts.ui.PreparedContentItem
import org.quicklauncher.contracts.ui.PreparedContentKind
import org.quicklauncher.contracts.ui.PreparedHostSurface
import org.quicklauncher.contracts.ui.PreparedProfileKind
import org.quicklauncher.contracts.ui.PreparedSurfaceKind
import org.quicklauncher.contracts.ui.RenderStatus
import org.quicklauncher.host.data.store.LauncherSnapshot
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.preferences.GestureMode
import org.quicklauncher.host.editor.LauncherEditor
import org.quicklauncher.host.runtime.LauncherRuntime
import org.quicklauncher.host.runtime.catalog.AppCatalogStatus
import org.quicklauncher.host.runtime.composition.CompositionIssue
import org.quicklauncher.host.runtime.composition.CompositionIssueKind
import org.quicklauncher.host.runtime.theme.BuiltInLauncherThemeResolver
import org.quicklauncher.host.runtime.theme.ThemeResolutionRequest

class ProductionPreparedContentVisualTest {
    @get:Rule
    val paparazzi = Paparazzi(
        validateAccessibility = true,
        showSystemUi = false,
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            fontScale = 2f,
            nightMode = NightMode.NOTNIGHT,
        ),
    )

    @Test
    fun `production fallback renderers remain visible at large text`() {
        val scope = CoroutineScope(SupervisorJob())
        val environment = productionEnvironment(
            apps = emptyList(),
            catalogStatus = AppCatalogStatus.READY,
            catalogErrorCode = null,
            snapshot = emptySnapshot,
            resolvedTheme = BuiltInLauncherThemeResolver.resolve(
                ThemeResolutionRequest(
                    systemDark = false,
                    textScale = 2f,
                    reducedMotion = true,
                ),
            ),
            widthDp = 393,
            heightDp = 851,
            destinationId = DESTINATION,
            runtime = unsupportedProxy(),
            editor = unsupportedProxy(),
            scope = scope,
        )
        try {
            paparazzi.snapshot(name = "prepared-fallbacks-large-text-light-portrait") {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(32.dp),
                        ) {
                            ProductionPreparedContentRenderer.RenderItem(
                                item = PreparedContentItem(
                                    id = ContentItemId.parse("org.quicklauncher.fixture/unavailable-app"),
                                    label = "Unavailable application with a deliberately long label",
                                    supportingText = null,
                                    kind = PreparedContentKind.APP,
                                    enabled = false,
                                    profile = PreparedProfileKind.WORK,
                                    availability = PreparedAvailability.UNAVAILABLE,
                                    indicator = NotificationIndicator.ApproximateCount(9),
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ProductionPreparedContentRenderer.RenderSurface(
                                surface = PreparedHostSurface(
                                    key = StableKey.parse("unavailable-widget"),
                                    kind = PreparedSurfaceKind.WIDGET,
                                    status = RenderStatus.Loading,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            environment.placeholderRenderer.Render(
                                issue = CompositionIssue(
                                    kind = CompositionIssueKind.MISSING_CONTRIBUTION,
                                    instanceId = ModuleInstanceId.parse("org.quicklauncher.fixture/missing"),
                                    message = "Fixture contribution is unavailable",
                                ),
                                modifier = Modifier.fillMaxWidth().height(280.dp),
                            )
                        }
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `production selected layout shell keeps chrome visible at large text`() {
        paparazzi.snapshot(name = "selected-layout-shell-large-text-light-portrait") {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ProductionSelectedLayoutShell(
                        destinations = destinations,
                        current = DESTINATION,
                        mode = GestureMode.CONTENT_HANDOFF,
                        reducedMotion = true,
                        onDestinationChanged = {},
                        onOpenMap = {},
                        onOpenSettings = {},
                    ) { destinationId ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (destinationId == DESTINATION) {
                                    "Selected destination frame"
                                } else {
                                    "Neighbor destination frame"
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private inline fun <reified T> unsupportedProxy(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "Unused ${T::class.java.simpleName} visual fixture"
            "hashCode" -> 0
            "equals" -> false
            else -> error("${method.name} must not be called while rendering production fallbacks")
        }
    } as T

    private companion object {
        val DESTINATION = DestinationId.parse("org.quicklauncher.destination/visual-fallback")
        val NEIGHBOR = DestinationId.parse("org.quicklauncher.destination/visual-neighbor")
        val destinations = listOf(
            DestinationRecord(DESTINATION, "Selected", DestinationCoordinate(0, 0)),
            DestinationRecord(NEIGHBOR, "Neighbor", DestinationCoordinate(1, 0)),
        )
        val emptySnapshot = LauncherSnapshot.restored(
            startDestinationId = null,
            destinations = emptyList(),
            destinationLayouts = emptyList(),
            moduleInstances = emptyList(),
            configurationDocuments = emptyList(),
            placements = emptyList(),
        )
    }
}

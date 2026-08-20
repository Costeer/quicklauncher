package org.quicklauncher.prototypes.nestedscroll

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class NavigationMode {
    HANDOFF,
    EDGE_ONLY,
}

internal enum class AppListSize(val count: Int, val label: String) {
    LONG(80, "80 apps"),
    SHORT(12, "12 apps"),
    TINY(2, "2 apps"),
}

@Stable
internal class PrototypeGestureState {
    private val machine = HandoffMachine()

    var previewOffsetPx by mutableFloatStateOf(0f)
        private set
    var currentDestination by mutableIntStateOf(0)
        private set
    var navigationCount by mutableIntStateOf(0)
        private set
    var listDragPx by mutableFloatStateOf(0f)
        private set
    var navigatorDragPx by mutableFloatStateOf(0f)
        private set
    var ignoredResidualFlings by mutableIntStateOf(0)
        private set
    var velocityOwner by mutableStateOf<String?>(null)
        private set
    var thresholdPx by mutableFloatStateOf(1f)
        private set
    var pageExtentPx by mutableFloatStateOf(1f)
        private set

    val reversalCount: Int
        get() = machine.reversalCount

    private var settling = false

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            val consumedY = machine.consumeBeforeList(available.y)
            syncPreview()
            return Offset(0f, consumedY)
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            listDragPx += abs(consumed.y)
            val consumedY = machine.consumeAfterList(available.y)
            navigatorDragPx += abs(consumedY)
            syncPreview()
            return Offset(0f, consumedY)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (machine.navigationOffsetPx == 0f) {
                velocityOwner = VelocityOwner.LIST.name
                return Velocity.Zero
            }

            settle(available.y)
            return Velocity(0f, available.y)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (abs(available.y) > 1f) {
                ignoredResidualFlings += 1
            }
            return Velocity.Zero
        }
    }

    fun updateViewport(thresholdPx: Float, pageExtentPx: Float) {
        this.thresholdPx = thresholdPx
        this.pageExtentPx = pageExtentPx
        machine.updateLimits(thresholdPx, pageExtentPx)
        syncPreview()
    }

    fun dragFromEdge(deltaY: Float) {
        val consumed = machine.consumeAfterList(deltaY)
        navigatorDragPx += abs(consumed)
        syncPreview()
    }

    suspend fun finishEdgeDrag() {
        settle(velocityY = 0f)
    }

    suspend fun cancelEdgeDrag() {
        animatePreviewTo(0f)
        machine.resetPreview()
        syncPreview()
    }

    fun reset() {
        machine.resetMeasurements()
        previewOffsetPx = 0f
        currentDestination = 0
        navigationCount = 0
        listDragPx = 0f
        navigatorDragPx = 0f
        ignoredResidualFlings = 0
        velocityOwner = null
        settling = false
    }

    private suspend fun settle(velocityY: Float) {
        if (settling) return
        settling = true
        val result = machine.finishGesture(velocityY)
        velocityOwner = result.velocityOwner.name
        animatePreviewTo(result.settleTargetPx)
        if (result.navigateBy != 0) {
            currentDestination += result.navigateBy
            navigationCount += 1
        }
        machine.resetPreview()
        syncPreview()
        settling = false
    }

    private suspend fun animatePreviewTo(targetPx: Float) {
        animate(
            initialValue = machine.navigationOffsetPx,
            targetValue = targetPx,
            animationSpec = tween(durationMillis = 160),
        ) { value, _ ->
            machine.setAnimatedOffset(value)
            syncPreview()
        }
    }

    private fun syncPreview() {
        previewOffsetPx = machine.navigationOffsetPx
    }
}

@Composable
fun NestedScrollPrototypeApp() {
    MaterialTheme {
        val defaultDensity = LocalDensity.current
        var textScale by remember { mutableFloatStateOf(1f) }
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = defaultDensity.density,
                fontScale = defaultDensity.fontScale * textScale,
            ),
        ) {
            PrototypeScreen(
                textScale = textScale,
                onToggleTextScale = { textScale = if (textScale == 1f) 2f else 1f },
            )
        }
    }
}

@Composable
private fun PrototypeScreen(
    textScale: Float,
    onToggleTextScale: () -> Unit,
) {
    val gestureState = remember { PrototypeGestureState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var navigationMode by remember { mutableStateOf(NavigationMode.EDGE_ONLY) }
    var listSize by remember { mutableStateOf(AppListSize.LONG) }

    LaunchedEffect(listSize) {
        listState.scrollToItem(0)
        gestureState.reset()
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp),
        ) {
            Text(
                text = "Nested scroll risk prototype",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = "Question: can a list hand one drag to vertical destination navigation and take it back safely?",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ControlRow {
                PrototypeButton(
                    label = "Handoff",
                    selected = navigationMode == NavigationMode.HANDOFF,
                    tag = "mode-handoff",
                ) { navigationMode = NavigationMode.HANDOFF }
                PrototypeButton(
                    label = "Edge only",
                    selected = navigationMode == NavigationMode.EDGE_ONLY,
                    tag = "mode-edge-only",
                ) { navigationMode = NavigationMode.EDGE_ONLY }
                PrototypeButton(
                    label = if (textScale == 1f) "Text 100%" else "Text 200%",
                    selected = textScale > 1f,
                    tag = "text-scale",
                    onClick = onToggleTextScale,
                )
                PrototypeButton(
                    label = "Reset",
                    selected = false,
                    tag = "reset",
                ) {
                    scope.launch { listState.scrollToItem(0) }
                    gestureState.reset()
                }
            }
            ControlRow {
                AppListSize.entries.forEach { option ->
                    PrototypeButton(
                        label = option.label,
                        selected = listSize == option,
                        tag = "list-${option.name.lowercase()}",
                    ) { listSize = option }
                }
            }

            val status = buildString {
                append("destination=${gestureState.currentDestination} ")
                append("nav=${gestureState.navigationCount} ")
                append("offset=${gestureState.previewOffsetPx.roundToInt()}px\n")
                append("listDrag=${gestureState.listDragPx.roundToInt()}px ")
                append("navDrag=${gestureState.navigatorDragPx.roundToInt()}px ")
                append("reversals=${gestureState.reversalCount}\n")
                append("velocity=${gestureState.velocityOwner ?: "NONE"} ")
                append("ignoredResidual=${gestureState.ignoredResidualFlings}\n")
                append("page=${gestureState.pageExtentPx.roundToInt()}px ")
                append("threshold=${gestureState.thresholdPx.roundToInt()}px edge=32dp")
            }
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE9EDF5))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("status"),
            )
            Text(
                text = if (navigationMode == NavigationMode.HANDOFF) {
                    "Rule: list first; boundary remainder navigates; reversal unwinds preview; one velocity owner."
                } else {
                    "Fallback default: the list scrolls everywhere; destination drags start in the 32 dp edge band."
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .testTag("destination-viewport"),
            ) {
                val density = LocalDensity.current
                val pageExtentPx = with(density) { maxHeight.toPx() }
                val thresholdPx = pageExtentPx * 0.25f
                LaunchedEffect(pageExtentPx, thresholdPx) {
                    gestureState.updateViewport(thresholdPx, pageExtentPx)
                }

                NeighborDestination(
                    label = if (gestureState.previewOffsetPx < 0f) {
                        "Destination ${gestureState.currentDestination + 1}"
                    } else {
                        "Destination ${gestureState.currentDestination - 1}"
                    },
                )

                val listModifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = gestureState.previewOffsetPx }
                    .background(Color(0xFFF9F9FC))
                    .testTag("app-list")
                    .let { base ->
                        if (navigationMode == NavigationMode.HANDOFF) {
                            base.nestedScroll(gestureState.nestedScrollConnection)
                        } else {
                            base
                        }
                    }

                LazyColumn(
                    state = listState,
                    modifier = listModifier,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    overscrollEffect = null,
                ) {
                    items(
                        items = (0 until listSize.count).toList(),
                        key = { it },
                    ) { index ->
                        AppRow(index)
                    }
                }

                if (navigationMode == NavigationMode.EDGE_ONLY) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Start)),
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .width(32.dp)
                                .fillMaxHeight()
                                .background(Color(0x223F51B5))
                                .testTag("edge-band")
                                .pointerInput(gestureState) {
                                    detectVerticalDragGestures(
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            gestureState.dragFromEdge(dragAmount)
                                        },
                                        onDragEnd = {
                                            scope.launch { gestureState.finishEdgeDrag() }
                                        },
                                        onDragCancel = {
                                            scope.launch { gestureState.cancelEdgeDrag() }
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun PrototypeButton(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF304FFE) else Color(0xFF5F6368),
        ),
    ) {
        Text(label)
    }
}

@Composable
private fun NeighborDestination(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF25324A)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
private fun AppRow(index: Int) {
    val initial = ('A'.code + (index % 26)).toChar()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp)
            .testTag("app-$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = initial.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF304FFE),
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = "$initial app ${index + 1}",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

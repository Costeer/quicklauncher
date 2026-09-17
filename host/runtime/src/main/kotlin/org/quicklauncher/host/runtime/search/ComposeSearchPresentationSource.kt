package org.quicklauncher.host.runtime.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.ui.SearchPresentation
import org.quicklauncher.host.runtime.composition.SearchPresentationSource

class ComposeSearchPresentationSource(
    private val engine: SearchEngine,
    private val dismiss: () -> Unit,
) : SearchPresentationSource {
    private val controllers = LinkedHashSet<SearchPresentationController>()
    private val mutableHasActiveQuery = MutableStateFlow(false)
    private val mutableHostActive = MutableStateFlow(true)
    override val hasActiveQuery: StateFlow<Boolean> = mutableHasActiveQuery

    @Composable
    override fun presentationFor(instanceId: ModuleInstanceId): SearchPresentation? {
        val hostActive by mutableHostActive.collectAsState()
        if (!hostActive) return null
        val scope = rememberCoroutineScope()
        val controller = remember(instanceId, engine) {
            SearchPresentationController(engine, scope, dismiss, ::refreshActivity)
        }
        val state by controller.state.collectAsState()
        DisposableEffect(controller) {
            synchronized(controllers) { controllers += controller }
            refreshActivity()
            onDispose {
                synchronized(controllers) { controllers -= controller }
                controller.close()
                refreshActivity()
            }
        }
        return SearchPresentation(state, org.quicklauncher.contracts.ui.ActionSink(controller::dispatch))
    }

    override fun dismissAll() {
        val visible = synchronized(controllers) { controllers.toList() }
        visible.forEach(SearchPresentationController::clearQuery)
        refreshActivity()
    }

    override fun setHostActive(active: Boolean) {
        mutableHostActive.value = active
    }

    override fun requestFocus(): Boolean {
        val visible = synchronized(controllers) { controllers.lastOrNull() } ?: return false
        visible.requestFocus()
        return true
    }

    private fun refreshActivity() {
        mutableHasActiveQuery.value = synchronized(controllers) {
            controllers.any(SearchPresentationController::hasActiveQuery)
        }
    }
}

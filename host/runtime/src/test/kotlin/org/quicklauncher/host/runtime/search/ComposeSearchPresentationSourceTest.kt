@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.quicklauncher.host.runtime.search

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.ui.SearchPresentation
import org.quicklauncher.contracts.ui.SearchPresentationAction
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ComposeSearchPresentationSourceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `one visible presentation owns one session and removal closes it`() {
        val engine = RecordingSearchEngine()
        val source = ComposeSearchPresentationSource(engine) {}
        val rootVisible = mutableStateOf(true)
        val visible = mutableStateOf(true)
        val unrelated = mutableStateOf(0)
        var presentation: SearchPresentation? = null

        compose.setContent {
            if (rootVisible.value) {
                unrelated.value
                presentation = if (visible.value) {
                    source.presentationFor(INSTANCE)
                } else {
                    null
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, engine.sessions.size)

        compose.runOnIdle { unrelated.value += 1 }
        compose.waitForIdle()
        assertEquals(1, engine.sessions.size)

        compose.runOnIdle {
            requireNotNull(presentation).actions.emit(SearchPresentationAction.QueryChanged("fixture"))
        }
        compose.waitForIdle()
        assertTrue(source.hasActiveQuery.value)
        assertEquals(listOf("fixture"), engine.sessions.single().queries)

        compose.runOnIdle { assertTrue(source.requestFocus()) }
        compose.waitForIdle()
        assertEquals(1L, requireNotNull(presentation).state.focusRequestId)

        compose.runOnIdle(source::dismissAll)
        compose.waitForIdle()
        assertFalse(source.hasActiveQuery.value)
        assertEquals(listOf("fixture", ""), engine.sessions.single().queries)

        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertEquals(1, engine.sessions.single().closeCalls)
        assertFalse(source.requestFocus())

        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertEquals(2, engine.sessions.size)
        assertEquals(0, engine.sessions.last().closeCalls)

        compose.runOnIdle { rootVisible.value = false }
        compose.waitForIdle()
        assertEquals(1, engine.sessions.last().closeCalls)
    }

    @Test
    fun `stopping the host closes the visible session and restart opens a fresh session`() {
        val engine = RecordingSearchEngine()
        val source = ComposeSearchPresentationSource(engine) {}

        compose.setContent { source.presentationFor(INSTANCE) }
        compose.waitForIdle()
        assertEquals(1, engine.sessions.size)

        compose.runOnIdle { source.setHostActive(false) }
        compose.waitForIdle()
        assertEquals(1, engine.sessions.single().closeCalls)

        compose.runOnIdle { source.setHostActive(true) }
        compose.waitForIdle()
        assertEquals(2, engine.sessions.size)
        assertEquals(0, engine.sessions.last().closeCalls)
    }

    private companion object {
        val INSTANCE = ModuleInstanceId.parse("org.quicklauncher.search.presentation/fixture")
    }
}

private class RecordingSearchEngine : SearchEngine {
    val sessions = mutableListOf<RecordingPresentationSession>()

    override fun open(): SearchSession = RecordingPresentationSession(sessions.size + 1L).also(sessions::add)
}

private class RecordingPresentationSession(id: Long) : SearchSession {
    override val state = kotlinx.coroutines.flow.MutableStateFlow(SearchSnapshot.initial(id, emptyList()))
    val queries = mutableListOf<String>()
    var closeCalls = 0

    override fun submit(rawQuery: String): QuerySubmissionResult {
        queries += rawQuery
        return QuerySubmissionResult.Accepted(queries.size.toLong())
    }

    override suspend fun activate(token: SearchActivationToken): SearchExecutionResult =
        SearchExecutionResult.Rejected

    override fun close() {
        closeCalls += 1
    }
}

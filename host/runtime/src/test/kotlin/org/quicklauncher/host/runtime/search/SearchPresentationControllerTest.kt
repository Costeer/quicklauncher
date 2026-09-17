package org.quicklauncher.host.runtime.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.ui.ActionDispatchResult
import org.quicklauncher.contracts.ui.SearchPresentationAction

class SearchPresentationControllerTest {
    @Test
    fun `controller owns one session clears dismissal state and closes without later actions`() = runTest {
        val session = RecordingSearchSession()
        var opened = 0
        var dismissed = 0
        var activityChanges = 0
        val controller = SearchPresentationController(
            engine = SearchEngine {
                opened += 1
                session
            },
            parentScope = this,
            dismiss = { dismissed += 1 },
            activityChanged = { activityChanges += 1 },
        )

        assertEquals(1, opened)
        assertEquals(
            ActionDispatchResult.Accepted,
            controller.dispatch(SearchPresentationAction.QueryChanged("synthetic")),
        )
        assertEquals(listOf("synthetic"), session.queries)
        assertTrue(controller.hasActiveQuery)
        controller.requestFocus()
        assertEquals(1L, controller.state.value.focusRequestId)

        assertEquals(ActionDispatchResult.Accepted, controller.dispatch(SearchPresentationAction.Dismiss))
        assertEquals(listOf("synthetic", ""), session.queries)
        assertEquals(1, dismissed)
        assertFalse(controller.hasActiveQuery)

        controller.close()
        controller.close()
        assertEquals(1, session.closeCalls)
        assertTrue(
            controller.dispatch(SearchPresentationAction.QueryChanged("late")) is
                ActionDispatchResult.Rejected,
        )
        assertEquals(listOf("synthetic", ""), session.queries)
        assertTrue(activityChanges >= 3)
    }
}

private class RecordingSearchSession : SearchSession {
    override val state = MutableStateFlow(SearchSnapshot.initial(1L, emptyList()))
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

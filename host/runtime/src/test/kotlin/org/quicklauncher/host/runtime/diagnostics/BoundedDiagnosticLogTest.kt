package org.quicklauncher.host.runtime.diagnostics

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ModuleInstanceId

class BoundedDiagnosticLogTest {
    @Test
    fun `records are durable through storage and retain only the newest bound`() = runBlocking {
        val storage = MemoryDiagnosticStorage()
        val clock = SequenceClock(10L, 20L, 30L)
        val log = BoundedDiagnosticLog(storage, clock, maximumRecords = 2)

        log.record(DiagnosticEvent(DiagnosticEventCode.HOME_ROLE_REQUESTED))
        log.record(DiagnosticEvent(DiagnosticEventCode.HOME_ROLE_FALLBACK_OPENED))
        log.record(DiagnosticEvent(DiagnosticEventCode.STARTUP_COMPLETED))

        assertEquals(
            listOf(DiagnosticEventCode.HOME_ROLE_FALLBACK_OPENED, DiagnosticEventCode.STARTUP_COMPLETED),
            BoundedDiagnosticLog(storage, clock, maximumRecords = 2).records().map { it.code },
        )
        assertEquals(listOf(20L, 30L), storage.records.map { it.timestampEpochMillis })
    }

    @Test
    fun `returned snapshots and stack frames are immutable copies`() {
        runBlocking {
            val mutableFrames = mutableListOf(
                DiagnosticStackFrame.parse("org.quicklauncher.Renderer", "render", 42),
            )
            val event = DiagnosticEvent(
                code = DiagnosticEventCode.RENDERER_FAILED,
                instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/failing"),
                errorClass = DiagnosticErrorClass.parse("java.lang.IllegalStateException"),
                stackFrames = mutableFrames,
            )
            mutableFrames.clear()
            val log = BoundedDiagnosticLog(MemoryDiagnosticStorage(), SequenceClock(1L), 4)

            log.record(event)
            val snapshot = log.records()

            assertEquals(1, snapshot.single().stackFrames.size)
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (snapshot as MutableList<DiagnosticRecord>).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (snapshot.single().stackFrames as MutableList<DiagnosticStackFrame>).clear()
            }
        }
    }

    @Test
    fun `diagnostic fields reject paths and unbounded or message-like data`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticErrorClass.parse("/data/user/0/org.quicklauncher/database")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticStackFrame.parse("org.quicklauncher.Renderer", "render\\secret", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticErrorClass.parse("x".repeat(161))
        }
        assertTrue(DiagnosticRecord::class.java.declaredFields.none { it.name.contains("message", true) })
        assertTrue(DiagnosticRecord::class.java.declaredFields.none { it.name.contains("path", true) })
    }

    @Test
    fun `renderer failures require typed instance and error identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticEvent(DiagnosticEventCode.RENDERER_FAILED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticEvent(
                DiagnosticEventCode.RENDERER_FAILED,
                instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/failing"),
                stackFrames = listOf(
                    DiagnosticStackFrame.parse("org.quicklauncher.Renderer", "render", 1),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticEvent(
                DiagnosticEventCode.RENDERER_FAILED,
                instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/failing"),
                errorClass = DiagnosticErrorClass.parse("java.lang.IllegalStateException"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticEvent(
                DiagnosticEventCode.STARTUP_COMPLETED,
                errorClass = DiagnosticErrorClass.parse("java.lang.IllegalStateException"),
            )
        }
    }

    @Test
    fun `durable event identifiers do not depend on enum names or ordinals`() {
        assertEquals("renderer.failed", DiagnosticEventCode.RENDERER_FAILED.persistedId)
        assertEquals(
            DiagnosticEventCode.RENDERER_FAILED,
            DiagnosticEventCode.fromPersistedId("renderer.failed"),
        )
        assertEquals(null, DiagnosticEventCode.fromPersistedId("future.event"))
    }

    @Test
    fun `cancelled durable replacement leaves the prior records observable`() = runBlocking {
        val storage = MemoryDiagnosticStorage()
        val log = BoundedDiagnosticLog(storage, SequenceClock(1L, 2L), 4)
        log.record(DiagnosticEvent(DiagnosticEventCode.STARTUP_COMPLETED))
        storage.cancelReplacement = true

        assertThrows(CancellationException::class.java) {
            runBlocking {
                log.record(DiagnosticEvent(DiagnosticEventCode.HOME_ROLE_REQUESTED))
            }
        }

        assertEquals(listOf(DiagnosticEventCode.STARTUP_COMPLETED), log.records().map { it.code })
    }
}

private class MemoryDiagnosticStorage : DiagnosticStorage {
    var records: List<DiagnosticRecord> = emptyList()
    var cancelReplacement: Boolean = false

    override suspend fun read(): List<DiagnosticRecord> = records

    override suspend fun replace(records: List<DiagnosticRecord>) {
        if (cancelReplacement) throw CancellationException("test cancellation")
        this.records = records.toList()
    }
}

private class SequenceClock(vararg values: Long) : DiagnosticClock {
    private val remaining = ArrayDeque(values.toList())

    override fun nowEpochMillis(): Long = remaining.removeFirst()
}

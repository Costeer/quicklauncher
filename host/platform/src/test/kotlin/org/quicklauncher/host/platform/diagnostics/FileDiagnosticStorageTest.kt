package org.quicklauncher.host.platform.diagnostics

import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.host.runtime.diagnostics.DiagnosticErrorClass
import org.quicklauncher.host.runtime.diagnostics.DiagnosticEventCode
import org.quicklauncher.host.runtime.diagnostics.DiagnosticRecord
import org.quicklauncher.host.runtime.diagnostics.DiagnosticStackFrame

class FileDiagnosticStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `records survive a new storage instance without messages or file paths`() = runBlocking {
        val file = File(temporaryFolder.root, "diagnostics.bin")
        val expected = listOf(rendererFailure())

        FileDiagnosticStorage(file).replace(expected)

        assertEquals(expected, FileDiagnosticStorage(file).read())
        val bytes = file.readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("secret message"))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("/data/user"))
    }

    @Test
    fun `corrupt durable data fails closed and can be replaced`() = runBlocking {
        val file = temporaryFolder.newFile("diagnostics.bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val storage = FileDiagnosticStorage(file)

        assertEquals(emptyList<DiagnosticRecord>(), storage.read())
        storage.replace(listOf(rendererFailure()))

        assertEquals(listOf(rendererFailure()), storage.read())
    }

    @Test
    fun `cancellation before atomic replacement preserves exact prior bytes`() = runBlocking {
        val file = File(temporaryFolder.root, "diagnostics.bin")
        FileDiagnosticStorage(file).replace(listOf(rendererFailure()))
        val priorBytes = file.readBytes()
        val cancelling = FileDiagnosticStorage.forTesting(file) {
            throw CancellationException("test cancellation")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                cancelling.replace(
                    listOf(
                        DiagnosticRecord(
                            timestampEpochMillis = 99L,
                            code = DiagnosticEventCode.HOME_ROLE_REQUESTED,
                            instanceId = null,
                            errorClass = null,
                            stackFrames = emptyList(),
                        ),
                    ),
                )
            }
        }

        assertArrayEquals(priorBytes, file.readBytes())
        assertEquals(emptyList<File>(), temporaryFolder.root.listFiles { child -> child.name.endsWith(".tmp") }?.toList())
    }

    @Test
    fun `Android factory confines diagnostics to one private file name`() = runBlocking {
        val factory = AndroidDiagnosticLogFactory.forTesting(temporaryFolder.root)

        factory.open("events.bin").record(
            org.quicklauncher.host.runtime.diagnostics.DiagnosticEvent(
                DiagnosticEventCode.HOME_ROLE_REQUESTED,
            ),
        )

        assertEquals(listOf("events.bin"), temporaryFolder.root.list()?.toList())
        assertThrows(IllegalArgumentException::class.java) { factory.open("../escaped.bin") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("nested/events.bin") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("..") }
        Unit
    }

    private fun rendererFailure() = DiagnosticRecord(
        timestampEpochMillis = 42L,
        code = DiagnosticEventCode.RENDERER_FAILED,
        instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/failing"),
        errorClass = DiagnosticErrorClass.parse("java.lang.IllegalStateException"),
        stackFrames = listOf(
            DiagnosticStackFrame.parse("org.quicklauncher.Renderer", "render", 12),
        ),
    )
}

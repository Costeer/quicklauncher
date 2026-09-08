package org.quicklauncher.host.platform.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.host.runtime.diagnostics.DiagnosticErrorClass
import org.quicklauncher.host.runtime.diagnostics.DiagnosticEventCode
import org.quicklauncher.host.runtime.diagnostics.DiagnosticRecord
import org.quicklauncher.host.runtime.diagnostics.DiagnosticStackFrame
import org.quicklauncher.host.runtime.diagnostics.DiagnosticStorage

/** Atomic, bounded persistence for structurally redacted local diagnostic records. */
internal class FileDiagnosticStorage private constructor(
    private val file: File,
    private val dispatcher: CoroutineDispatcher,
    private val beforeReplace: suspend () -> Unit,
) : DiagnosticStorage {
    private val mutex = Mutex()

    constructor(
        file: File,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(file, dispatcher, {})

    init {
        require(file.isAbsolute) { "Diagnostic file must use an absolute path" }
    }

    override suspend fun read(): List<DiagnosticRecord> = mutex.withLock {
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            readFile()
        }
    }

    override suspend fun replace(records: List<DiagnosticRecord>) {
        require(records.size <= MAXIMUM_RECORDS) {
            "Diagnostic storage accepts at most $MAXIMUM_RECORDS records"
        }
        val immutableInput = records.toList()
        mutex.withLock {
            withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                val bytes = encode(immutableInput)
                require(bytes.size <= MAXIMUM_FILE_BYTES) {
                    "Encoded diagnostics exceed the $MAXIMUM_FILE_BYTES byte storage limit"
                }
                beforeReplace()
                currentCoroutineContext().ensureActive()
                replaceFile(bytes)
            }
        }
    }

    private fun readFile(): List<DiagnosticRecord> {
        if (!file.exists() || file.length() > MAXIMUM_FILE_BYTES) return emptyList()
        return try {
            decode(file.readBytes())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        } catch (_: IndexOutOfBoundsException) {
            emptyList()
        }
    }

    private fun replaceFile(bytes: ByteArray) {
        val parent = checkNotNull(file.absoluteFile.parentFile) {
            "Diagnostic file must have a parent directory"
        }
        check(parent.exists() || parent.mkdirs()) {
            "Could not create diagnostic storage directory"
        }
        val temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            currentThreadCancellationCheck()
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun currentThreadCancellationCheck() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException("Diagnostic replacement thread was interrupted")
        }
    }

    private fun encode(records: List<DiagnosticRecord>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(FILE_MAGIC)
            output.writeInt(FILE_VERSION)
            output.writeInt(records.size)
            records.forEach { record ->
                output.writeLong(record.timestampEpochMillis)
                output.writeUTF(record.code.persistedId)
                output.writeOptional(record.instanceId?.value)
                output.writeOptional(record.errorClass?.value)
                output.writeInt(record.stackFrames.size)
                record.stackFrames.forEach { frame ->
                    output.writeUTF(frame.className)
                    output.writeUTF(frame.methodName)
                    output.writeInt(frame.lineNumber)
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): List<DiagnosticRecord> {
        require(bytes.size <= MAXIMUM_FILE_BYTES) { "Diagnostic file exceeds its size limit" }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == FILE_MAGIC) { "Diagnostic file has an invalid header" }
            require(input.readInt() == FILE_VERSION) { "Diagnostic file has an unsupported version" }
            val count = input.readInt()
            require(count in 0..MAXIMUM_RECORDS) { "Diagnostic record count is invalid" }
            val records = ArrayList<DiagnosticRecord>(count)
            repeat(count) {
                val timestamp = input.readLong()
                val persistedCode = input.readUTF()
                val code = DiagnosticEventCode.fromPersistedId(persistedCode)
                    ?: throw IllegalArgumentException("Diagnostic event code is invalid")
                val instanceId = input.readOptional()?.let(ModuleInstanceId::parse)
                val errorClass = input.readOptional()?.let(DiagnosticErrorClass::parse)
                val frameCount = input.readInt()
                require(frameCount in 0..MAXIMUM_STACK_FRAMES) {
                    "Diagnostic stack-frame count is invalid"
                }
                val frames = ArrayList<DiagnosticStackFrame>(frameCount)
                repeat(frameCount) {
                    frames += DiagnosticStackFrame.parse(
                        className = input.readUTF(),
                        methodName = input.readUTF(),
                        lineNumber = input.readInt(),
                    )
                }
                records += DiagnosticRecord(timestamp, code, instanceId, errorClass, frames)
            }
            require(input.available() == 0) { "Diagnostic file contains trailing data" }
            return records.toList()
        }
    }

    private fun DataOutputStream.writeOptional(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readOptional(): String? = if (readBoolean()) readUTF() else null

    companion object {
        private const val FILE_MAGIC = 0x514C4447
        private const val FILE_VERSION = 1
        private const val MAXIMUM_RECORDS = 1_024
        private const val MAXIMUM_STACK_FRAMES = 24
        private const val MAXIMUM_FILE_BYTES = 8 * 1024 * 1024

        internal fun forTesting(
            file: File,
            beforeReplace: suspend () -> Unit,
        ): FileDiagnosticStorage = FileDiagnosticStorage(file, Dispatchers.IO, beforeReplace)
    }
}

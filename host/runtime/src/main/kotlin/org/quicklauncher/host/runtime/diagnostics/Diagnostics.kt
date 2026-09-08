package org.quicklauncher.host.runtime.diagnostics

import java.util.Collections
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.quicklauncher.contracts.domain.ModuleInstanceId

enum class DiagnosticEventCode(val persistedId: String) {
    STARTUP_ATTEMPT("startup.attempt"),
    STARTUP_COMPLETED("startup.completed"),
    CRASH_LOOP_DETECTED("startup.crash-loop-detected"),
    RENDERER_FAILED("renderer.failed"),
    INSTANCE_QUARANTINED("instance.quarantined"),
    INSTANCE_RESET("instance.reset"),
    HOME_ROLE_REQUESTED("home-role.requested"),
    HOME_ROLE_FALLBACK_OPENED("home-role.fallback-opened"),
    ;

    companion object {
        fun fromPersistedId(value: String): DiagnosticEventCode? =
            entries.firstOrNull { it.persistedId == value }
    }
}

@JvmInline
value class DiagnosticErrorClass private constructor(val value: String) {
    companion object {
        fun parse(value: String): DiagnosticErrorClass {
            require(value.isNotBlank()) { "Diagnostic error class must not be blank" }
            require(value.length <= MAXIMUM_ERROR_CLASS_LENGTH) {
                "Diagnostic error class must not exceed $MAXIMUM_ERROR_CLASS_LENGTH characters"
            }
            require(value.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '$' }) {
                "Diagnostic error class must be a class name, not a path or message"
            }
            return DiagnosticErrorClass(value)
        }

        private const val MAXIMUM_ERROR_CLASS_LENGTH = 160
    }
}

@ConsistentCopyVisibility
data class DiagnosticStackFrame private constructor(
    val className: String,
    val methodName: String,
    val lineNumber: Int,
) {
    companion object {
        fun parse(
            className: String,
            methodName: String,
            lineNumber: Int,
        ): DiagnosticStackFrame {
            requireIdentifier(className, "class", MAXIMUM_CLASS_NAME_LENGTH)
            requireIdentifier(methodName, "method", MAXIMUM_METHOD_NAME_LENGTH)
            require(lineNumber >= -1) { "Diagnostic line number must be -1 or nonnegative" }
            return DiagnosticStackFrame(className, methodName, lineNumber)
        }

        private fun requireIdentifier(value: String, label: String, maximumLength: Int) {
            require(value.isNotBlank()) { "Diagnostic $label name must not be blank" }
            require(value.length <= maximumLength) {
                "Diagnostic $label name must not exceed $maximumLength characters"
            }
            require(value.none { it == '/' || it == '\\' || it == '\u0000' || it.isWhitespace() }) {
                "Diagnostic $label name must not contain a path or message"
            }
        }

        private const val MAXIMUM_CLASS_NAME_LENGTH = 160
        private const val MAXIMUM_METHOD_NAME_LENGTH = 120
    }
}

class DiagnosticEvent(
    val code: DiagnosticEventCode,
    val instanceId: ModuleInstanceId? = null,
    val errorClass: DiagnosticErrorClass? = null,
    stackFrames: Collection<DiagnosticStackFrame> = emptyList(),
) {
    val stackFrames: List<DiagnosticStackFrame> = immutableDiagnosticList(stackFrames)

    init {
        require(this.stackFrames.size <= MAXIMUM_STACK_FRAMES) {
            "A diagnostic event must not contain more than $MAXIMUM_STACK_FRAMES stack frames"
        }
        if (code.requiresInstance) {
            requireNotNull(instanceId) { "Diagnostic event $code requires a module instance" }
        }
        if (code == DiagnosticEventCode.RENDERER_FAILED) {
            requireNotNull(errorClass) { "Renderer failure diagnostics require an error class" }
            require(this.stackFrames.isNotEmpty()) {
                "Renderer failure diagnostics require at least one sanitized stack frame"
            }
        } else {
            require(errorClass == null && this.stackFrames.isEmpty()) {
                "Only renderer failure diagnostics may contain failure details"
            }
        }
        require(errorClass != null || this.stackFrames.isEmpty()) {
            "Diagnostic stack frames require an error class"
        }
    }

    private val DiagnosticEventCode.requiresInstance: Boolean
        get() = when (this) {
            DiagnosticEventCode.STARTUP_ATTEMPT,
            DiagnosticEventCode.RENDERER_FAILED,
            DiagnosticEventCode.CRASH_LOOP_DETECTED,
            DiagnosticEventCode.INSTANCE_QUARANTINED,
            DiagnosticEventCode.INSTANCE_RESET,
            -> true
            DiagnosticEventCode.STARTUP_COMPLETED,
            DiagnosticEventCode.HOME_ROLE_REQUESTED,
            DiagnosticEventCode.HOME_ROLE_FALLBACK_OPENED,
            -> false
        }

    private companion object {
        const val MAXIMUM_STACK_FRAMES = 24
    }
}

class DiagnosticRecord(
    val timestampEpochMillis: Long,
    val code: DiagnosticEventCode,
    val instanceId: ModuleInstanceId?,
    val errorClass: DiagnosticErrorClass?,
    stackFrames: Collection<DiagnosticStackFrame>,
) {
    val stackFrames: List<DiagnosticStackFrame> = immutableDiagnosticList(stackFrames)

    init {
        require(timestampEpochMillis >= 0L) { "Diagnostic timestamp must not be negative" }
        DiagnosticEvent(code, instanceId, errorClass, this.stackFrames)
    }

    override fun equals(other: Any?): Boolean = other is DiagnosticRecord &&
        timestampEpochMillis == other.timestampEpochMillis &&
        code == other.code &&
        instanceId == other.instanceId &&
        errorClass == other.errorClass &&
        stackFrames == other.stackFrames

    override fun hashCode(): Int {
        var result = timestampEpochMillis.hashCode()
        result = 31 * result + code.hashCode()
        result = 31 * result + (instanceId?.hashCode() ?: 0)
        result = 31 * result + (errorClass?.hashCode() ?: 0)
        return 31 * result + stackFrames.hashCode()
    }

    override fun toString(): String =
        "DiagnosticRecord(timestampEpochMillis=$timestampEpochMillis, code=$code, " +
            "instanceId=$instanceId, errorClass=$errorClass, stackFrames=$stackFrames)"
}

fun interface DiagnosticClock {
    fun nowEpochMillis(): Long
}

data object SystemDiagnosticClock : DiagnosticClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

interface DiagnosticStorage {
    suspend fun read(): List<DiagnosticRecord>

    /** Atomically replaces all records or leaves the prior durable records intact. */
    suspend fun replace(records: List<DiagnosticRecord>)
}

interface DiagnosticLog {
    suspend fun record(event: DiagnosticEvent): DiagnosticRecord

    suspend fun records(): List<DiagnosticRecord>
}

class BoundedDiagnosticLog(
    private val storage: DiagnosticStorage,
    private val clock: DiagnosticClock = SystemDiagnosticClock,
    private val maximumRecords: Int = DEFAULT_MAXIMUM_RECORDS,
) : DiagnosticLog {
    private val mutex = Mutex()

    init {
        require(maximumRecords in 1..MAXIMUM_ALLOWED_RECORDS) {
            "Maximum diagnostic records must be between 1 and $MAXIMUM_ALLOWED_RECORDS"
        }
    }

    override suspend fun record(event: DiagnosticEvent): DiagnosticRecord = mutex.withLock {
        val record = DiagnosticRecord(
            timestampEpochMillis = clock.nowEpochMillis(),
            code = event.code,
            instanceId = event.instanceId,
            errorClass = event.errorClass,
            stackFrames = event.stackFrames,
        )
        val retained = (storage.read() + record).takeLast(maximumRecords)
        storage.replace(immutableDiagnosticList(retained))
        record
    }

    override suspend fun records(): List<DiagnosticRecord> = mutex.withLock {
        immutableDiagnosticList(storage.read().takeLast(maximumRecords))
    }

    private companion object {
        const val DEFAULT_MAXIMUM_RECORDS = 128
        const val MAXIMUM_ALLOWED_RECORDS = 1_024
    }
}

private fun <T> immutableDiagnosticList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

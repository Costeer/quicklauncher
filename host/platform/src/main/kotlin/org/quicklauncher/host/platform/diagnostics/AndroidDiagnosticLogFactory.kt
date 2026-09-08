package org.quicklauncher.host.platform.diagnostics

import android.content.Context
import java.io.File
import org.quicklauncher.host.runtime.diagnostics.BoundedDiagnosticLog
import org.quicklauncher.host.runtime.diagnostics.DiagnosticLog

/** Resolves diagnostics into private, non-backed-up application storage. */
class AndroidDiagnosticLogFactory private constructor(
    private val directory: File,
) {
    constructor(context: Context) : this(context.applicationContext.noBackupFilesDir)

    fun open(fileName: String = DEFAULT_FILE_NAME): DiagnosticLog {
        require(fileName.isNotBlank()) { "Diagnostic file name must not be blank" }
        require(fileName.length <= 120) { "Diagnostic file name must not exceed 120 characters" }
        require('/' !in fileName && '\\' !in fileName) {
            "Diagnostic file name must not contain path separators"
        }
        require(fileName != "." && fileName != ".." && '\u0000' !in fileName) {
            "Diagnostic file name must identify one ordinary file"
        }
        return BoundedDiagnosticLog(FileDiagnosticStorage(File(directory, fileName)))
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "launcher-diagnostics.bin"

        internal fun forTesting(directory: File): AndroidDiagnosticLogFactory =
            AndroidDiagnosticLogFactory(directory)
    }
}

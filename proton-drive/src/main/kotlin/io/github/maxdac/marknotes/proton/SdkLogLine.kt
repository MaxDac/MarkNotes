package io.github.maxdac.marknotes.proton

import android.util.Log
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import me.proton.drive.sdk.LoggerProvider

/** A logcat priority and line for one Proton Drive SDK log event. */
internal data class SdkLogLine(val priority: Int, val text: String)

/**
 * Keeps only fixed warning/error text in every build. SDK categories and messages can contain user data.
 */
internal fun sdkLogLine(level: LoggerProvider.Level, diagnostics: DiagnosticReporter): SdkLogLine? {
    val priority = when (level) {
        LoggerProvider.Level.VERBOSE, LoggerProvider.Level.DEBUG, LoggerProvider.Level.INFO -> return null
        LoggerProvider.Level.WARN -> Log.WARN
        LoggerProvider.Level.ERROR -> Log.ERROR
    }
    diagnostics.report(DiagnosticCode.SDK_ERROR, null)
    val text = "Proton Drive SDK $level (details omitted)"
    return SdkLogLine(priority, text)
}

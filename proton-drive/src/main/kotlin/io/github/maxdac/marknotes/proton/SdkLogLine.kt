package io.github.maxdac.marknotes.proton

import android.util.Log
import me.proton.drive.sdk.LoggerProvider

/** A logcat priority and line for one Proton Drive SDK log event. */
internal data class SdkLogLine(val priority: Int, val text: String)

/**
 * Maps an SDK log event to a logcat line, or null to drop it. Debuggable builds ([verbose]) keep INFO and above
 * with the full message. Release builds keep only warnings and errors, without the message: SDK messages can
 * contain file names, paths and account or node IDs.
 */
internal fun sdkLogLine(level: LoggerProvider.Level, category: String, message: String, verbose: Boolean): SdkLogLine? {
    val priority = when (level) {
        LoggerProvider.Level.VERBOSE, LoggerProvider.Level.DEBUG -> return null
        LoggerProvider.Level.INFO -> if (verbose) Log.INFO else return null
        LoggerProvider.Level.WARN -> Log.WARN
        LoggerProvider.Level.ERROR -> Log.ERROR
    }
    val text = if (verbose) "[$category] $message" else "[$category] $level (details hidden in release builds)"
    return SdkLogLine(priority, text)
}

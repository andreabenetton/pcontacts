// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.logging

import android.util.Log
import io.pcontacts.core.logging.LogLevel
import io.pcontacts.core.logging.LogSink

/**
 * Bridges redacted log records to Android's logcat. Intended for debug
 * builds only — production builds wire `NoOpSink`. This class is the
 * single legitimate caller of `android.util.Log` in the codebase; the
 * `PcontactsSensitiveLog` Lint rule exempts the
 * `io.pcontacts.app.logging` package and fails the build elsewhere.
 *
 * Lives in `:app` (not `:core:logging`) because `:core:logging` is a
 * pure-JVM module per ADR-0011 — it cannot reference `android.util.Log`.
 */
class AndroidLogcatSink : LogSink {
    override fun emit(level: LogLevel, tag: String, msg: String, throwableInfo: String?) {
        val combined = if (throwableInfo != null) "$msg [$throwableInfo]" else msg
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, combined)
            LogLevel.INFO -> Log.i(tag, combined)
            LogLevel.WARN -> Log.w(tag, combined)
            LogLevel.ERROR -> Log.e(tag, combined)
        }
    }
}

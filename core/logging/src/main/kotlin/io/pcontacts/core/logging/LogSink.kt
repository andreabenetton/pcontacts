// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.logging

/**
 * Where redacted log records actually go. Keeps `RedactingLogger` pure-Kotlin
 * and testable without Android, while allowing a logcat sink in debug builds.
 *
 * Production builds wire `NoOpSink`; debug builds wire `AndroidLogcatSink`.
 */
fun interface LogSink {
    fun emit(level: LogLevel, tag: String, msg: String, throwableInfo: String?)
}

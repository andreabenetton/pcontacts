// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.logging

/**
 * The default `Logger` implementation. Pure Kotlin — applies `Redactor` to
 * every message and throwable, then forwards to a `LogSink`.
 *
 * Production app wires this with `NoOpSink` and `minLevel = LogLevel.WARN`
 * (so even WARN/ERROR records are silently dropped unless an operator-controlled
 * sink replaces NoOpSink). Debug builds wire `AndroidLogcatSink` and
 * `LogLevel.DEBUG`.
 */
class RedactingLogger(
    private val tag: String,
    private val sink: LogSink,
    private val minLevel: LogLevel = LogLevel.DEBUG
) : Logger {

    override fun debug(throwable: Throwable?, msg: () -> String) = log(LogLevel.DEBUG, throwable, msg)
    override fun info(throwable: Throwable?, msg: () -> String) = log(LogLevel.INFO, throwable, msg)
    override fun warn(throwable: Throwable?, msg: () -> String) = log(LogLevel.WARN, throwable, msg)
    override fun error(throwable: Throwable?, msg: () -> String) = log(LogLevel.ERROR, throwable, msg)

    private fun log(level: LogLevel, throwable: Throwable?, msg: () -> String) {
        if (level.ordinal < minLevel.ordinal) return
        val rendered = try {
            msg()
        } catch (t: Throwable) {
            "<log-render-error:${t.javaClass.simpleName}>"
        }
        val redactedMsg = Redactor.redact(rendered)
        val redactedThrowable = throwable?.let { Redactor.redactThrowable(it) }
        sink.emit(level, tag, redactedMsg, redactedThrowable)
    }
}

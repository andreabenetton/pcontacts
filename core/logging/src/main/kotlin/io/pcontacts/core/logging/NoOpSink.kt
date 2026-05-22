// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.logging

/**
 * Default production sink: swallow every record.
 *
 * ADR-0015 stance: information disclosure via logs is a one-way street;
 * the default is "log nothing externally". Operators who want diagnostics
 * replace this sink explicitly.
 */
object NoOpSink : LogSink {
    override fun emit(level: LogLevel, tag: String, msg: String, throwableInfo: String?) = Unit
}

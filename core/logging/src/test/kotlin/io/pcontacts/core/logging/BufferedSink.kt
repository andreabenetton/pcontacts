// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.logging

/** Test-only sink that captures emissions for assertions. */
class BufferedSink : LogSink {
    data class Emission(
        val level: LogLevel,
        val tag: String,
        val msg: String,
        val throwableInfo: String?
    )

    val emissions = mutableListOf<Emission>()

    override fun emit(level: LogLevel, tag: String, msg: String, throwableInfo: String?) {
        emissions += Emission(level, tag, msg, throwableInfo)
    }
}

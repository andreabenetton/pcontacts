// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.logging

/**
 * The only logging surface the rest of the codebase is allowed to use.
 * The custom `pcontacts.SensitiveLog` Lint rule (ADR-0015) fails the build
 * on direct `android.util.Log`, `println`, or `System.out.*` calls anywhere
 * outside `:core:logging` itself.
 *
 * Messages are passed as lazy lambdas so the rendering cost is only paid
 * when the log level is enabled. Redaction is applied unconditionally on
 * the rendered string, never trusting the call site.
 */
interface Logger {
    fun debug(throwable: Throwable? = null, msg: () -> String)
    fun info(throwable: Throwable? = null, msg: () -> String)
    fun warn(throwable: Throwable? = null, msg: () -> String)
    fun error(throwable: Throwable? = null, msg: () -> String)
}

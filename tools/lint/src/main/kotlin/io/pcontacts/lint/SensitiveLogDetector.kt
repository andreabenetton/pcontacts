// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.getContainingUFile

/**
 * Enforces ADR-0015: no `android.util.Log`, `println`, `print`,
 * `System.out.*`, `System.err.*`, or `Throwable.printStackTrace()` calls
 * anywhere outside the `:core:logging` package. The single legitimate caller
 * of `android.util.Log` lives in `io.pcontacts.core.logging.AndroidLogcatSink`;
 * every other module must go through the `Logger` / `RedactingLogger` /
 * `LogSink` surface so sensitive data is redacted before reaching logcat.
 *
 * Fires at severity ERROR — fails the build.
 */
class SensitiveLogDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> = listOf(
        "v", "d", "i", "w", "e", "wtf",       // android.util.Log
        "println", "print",                    // kotlin.io.* and java.io.PrintStream
        "printStackTrace"                      // java.lang.Throwable
    )

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {
            override fun visitCallExpression(node: UCallExpression) {
                val method = node.resolve() ?: return
                val owner = method.containingClass?.qualifiedName ?: return

                val isBanned = when (owner) {
                    "android.util.Log" -> true
                    "java.io.PrintStream" -> true   // System.out.println, System.err.print, etc.
                    "java.lang.Throwable" -> method.name == "printStackTrace"
                    // Kotlin top-level `println` / `print` resolve to ConsoleKt or IoKt.
                    "kotlin.io.ConsoleKt", "kotlin.io.IoKt" -> true
                    else -> false
                }
                if (!isBanned) return

                // Exempt the two packages that own the logging surface:
                //   io.pcontacts.core.logging   — pure-JVM Logger/Redactor/sinks
                //   io.pcontacts.app.logging    — Android logcat sink (single
                //                                  legitimate caller of Log.*)
                val pkg = node.getContainingUFile()?.packageName.orEmpty()
                if (pkg.startsWith("io.pcontacts.core.logging") ||
                    pkg.startsWith("io.pcontacts.app.logging")) {
                    return
                }

                context.report(
                    issue = ISSUE,
                    scope = node,
                    location = context.getLocation(node),
                    message = "Direct logging is forbidden outside :core:logging. " +
                        "Use the `Logger` interface from `io.pcontacts.core.logging` " +
                        "so sensitive data is redacted before reaching logcat (ADR-0015)."
                )
            }
        }
    }

    override fun getApplicableUastTypes(): List<Class<out org.jetbrains.uast.UElement>> =
        listOf(UCallExpression::class.java)

    companion object {
        val ISSUE: Issue = Issue.create(
            id = "PcontactsSensitiveLog",
            briefDescription = "Direct log call outside :core:logging",
            explanation = """
                ADR-0015 forbids `android.util.Log`, `println`, `print`,
                `System.out.*`, `System.err.*`, and `Throwable.printStackTrace()`
                anywhere except the `:core:logging` package, because the
                redacting logger is the only path that strips tokens,
                passphrases, private keys, and inline PGP blocks before they
                hit logcat.

                Replace the call with the `Logger` interface from
                `io.pcontacts.core.logging` — inject it via constructor or
                resolve it from a shared `LoggerFactory`.
            """.trimIndent(),
            category = Category.SECURITY,
            priority = 10,
            severity = Severity.ERROR,
            implementation = Implementation(
                SensitiveLogDetector::class.java,
                Scope.JAVA_FILE_SCOPE
            )
        )
    }
}

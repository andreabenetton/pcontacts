// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

/**
 * Note: `LintDetectorTest` extends JUnit 3 `TestCase`. Method names must
 * start with `test` to be discovered. `@Test` annotations are ignored.
 */
class SensitiveLogDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = SensitiveLogDetector()
    override fun getIssues(): List<Issue> = listOf(SensitiveLogDetector.ISSUE)

    // Stub of android.util.Log so the detector can resolve the symbol in tests.
    private val androidLogStub: TestFile = java(
        """
        package android.util;
        public final class Log {
            public static int v(String tag, String msg) { return 0; }
            public static int d(String tag, String msg) { return 0; }
            public static int i(String tag, String msg) { return 0; }
            public static int w(String tag, String msg) { return 0; }
            public static int e(String tag, String msg) { return 0; }
            public static int wtf(String tag, String msg) { return 0; }
        }
        """
    ).indented()

    fun testFlagsAndroidLogCallOutsideCoreLogging() {
        lint().files(
            androidLogStub,
            kotlin(
                """
                package io.pcontacts.feature.onboarding
                import android.util.Log
                class LoginVm {
                    fun onClick() {
                        Log.d("LoginVm", "user tapped login")
                    }
                }
                """
            ).indented()
        )
            .issues(SensitiveLogDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }

    fun testAllowsAndroidLogCallInsideCoreLogging() {
        lint().files(
            androidLogStub,
            kotlin(
                """
                package io.pcontacts.core.logging
                import android.util.Log
                class CoreSink {
                    fun emit(tag: String, msg: String) {
                        Log.d(tag, msg)
                    }
                }
                """
            ).indented()
        )
            .issues(SensitiveLogDetector.ISSUE)
            .run()
            .expectClean()
    }

    fun testAllowsAndroidLogCallInsideAppLogging() {
        lint().files(
            androidLogStub,
            kotlin(
                """
                package io.pcontacts.app.logging
                import android.util.Log
                class AndroidLogcatSink {
                    fun emit(tag: String, msg: String) {
                        Log.d(tag, msg)
                    }
                }
                """
            ).indented()
        )
            .issues(SensitiveLogDetector.ISSUE)
            .run()
            .expectClean()
    }

    fun testFlagsKotlinPrintlnOutsideCoreLogging() {
        lint().files(
            kotlin(
                """
                package io.pcontacts.core.sync
                class SyncEngine {
                    fun run() {
                        println("starting sync")
                    }
                }
                """
            ).indented()
        )
            .issues(SensitiveLogDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }

    fun testFlagsSystemOutPrintln() {
        lint().files(
            kotlin(
                """
                package io.pcontacts.core.sync
                class Boot {
                    fun start() {
                        System.out.println("boot")
                    }
                }
                """
            ).indented()
        )
            .issues(SensitiveLogDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }

    fun testFlagsThrowablePrintStackTrace() {
        lint().files(
            kotlin(
                """
                package io.pcontacts.feature.settings
                class Foo {
                    fun handle(t: Throwable) {
                        t.printStackTrace()
                    }
                }
                """
            ).indented()
        )
            .issues(SensitiveLogDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }

    fun testAllowsSafeMethodCalls() {
        lint().files(
            kotlin(
                """
                package io.pcontacts.core.sync
                class SyncEngine {
                    fun runOnce() {
                        val count = 0
                        val msg = count.toString()
                        msg.length
                    }
                }
                """
            ).indented()
        )
            .issues(SensitiveLogDetector.ISSUE)
            .run()
            .expectClean()
    }
}

// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Lint 31.11.x embeds a Kotlin 2.1 analysis; a newer kotlin-stdlib on the
    // test classpath breaks symbol resolution inside LintDetectorTest (calls
    // like kotlin.io.println stop resolving). Pin this module's core libraries
    // to the stdlib lint itself was built against, and cap apiVersion so the
    // 2.x compiler can't emit references to newer stdlib API.
    coreLibrariesVersion = "2.1.20"
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
}

dependencies {
    compileOnly(libs.android.lint.api)
    compileOnly(libs.android.lint.checks)

    testImplementation(libs.android.lint)
    testImplementation(libs.android.lint.tests)
    testImplementation(libs.junit)
}

tasks.jar {
    manifest {
        // Lint discovers our issues via this manifest attribute.
        attributes["Lint-Registry-v2"] = "io.pcontacts.lint.PcontactsIssueRegistry"
    }
}

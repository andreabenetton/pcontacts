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
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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

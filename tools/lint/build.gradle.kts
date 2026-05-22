// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
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

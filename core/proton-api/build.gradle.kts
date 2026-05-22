// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    // OkHttp + Retrofit + serialization land here in a later commit (ADR-0012).
    // implementation(libs.bundles.okhttp)
    // implementation(libs.retrofit)
    // implementation(libs.retrofit.kotlinx.serialization.converter)
    // implementation(libs.kotlinx.serialization.json)
    // implementation(project(":core:crypto"))
    // implementation(project(":core:logging"))
}

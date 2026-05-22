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
    // ez-vcard + Card split/merge land here in a later commit (ADR-0005).
    // implementation(libs.ezvcard)
    // implementation(libs.kotlinx.serialization.json)
    // implementation(project(":core:crypto"))
    // implementation(project(":core:logging"))
}

// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    testImplementation(libs.junit)
}

// Pure-JVM module per ADR-0011 (testable without an emulator). The Android
// logcat bridge lives in :app under io.pcontacts.app.logging; the
// PcontactsSensitiveLog rule exempts both that package and this one.
// Android Lint isn't run on pure-JVM modules; the detekt equivalent for
// this module lands when its source surface grows beyond the redacting
// logger.

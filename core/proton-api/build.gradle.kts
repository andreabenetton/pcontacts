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
    // 'api' because ProtonApiFactory's constructor exposes OkHttpClient as a
    // parameter type; downstream modules (e.g. :core:sync's AuthBootstrap)
    // need OkHttp on their compile classpath to call the constructor.
    api(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // ADR-0011: :core:proton-api stays Android-free. It uses the :core:logging
    // surface only — never android.util.Log directly.
    implementation(project(":core:logging"))

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register("verifyCertificatePins") {
    group = "verification"
    description = "Fails if proton_certificate_pins.txt is missing or contains no pins."
    val pinFile = file("src/main/resources/proton_certificate_pins.txt")
    doLast {
        require(pinFile.exists()) {
            "proton_certificate_pins.txt is missing — SPKI pins are required for release builds."
        }
        val pins = pinFile.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        require(pins.isNotEmpty()) {
            "proton_certificate_pins.txt contains no pins — at least one sha256/ pin is required."
        }
        logger.lifecycle("Certificate pin check passed — ${pins.size} pin(s) loaded.")
    }
}

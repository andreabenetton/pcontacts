// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // ADR-0005: vCard 4.0 parser/serializer. Apache 2.0 → GPL-3.0 compatible.
    implementation(libs.ezvcard)

    // ContactDto / ContactCardDto live here; pulling :core:proton-api in as
    // an api dep so downstream callers (e.g. a future contact-decrypt sync
    // engine) can name those types without re-declaring the dependency.
    api(project(":core:proton-api"))

    implementation(project(":core:logging"))

    testImplementation(libs.junit)
}

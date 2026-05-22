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
    // BouncyCastle + ported SRP / bcrypt-SHA512 land here in a later commit (ADR-0002).
    // implementation(libs.bundles.bouncycastle)
}

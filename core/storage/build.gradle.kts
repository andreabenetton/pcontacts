// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.pcontacts.core.storage"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.min.sdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    // ADR-0009: SecretStore — EncryptedSharedPreferences + Keystore AEAD.
    implementation(libs.androidx.security.crypto)

    // Room (ADR-0008) wired in the commit that lands the contact-mapping schema.
    // implementation(libs.bundles.room)
    // ksp(libs.androidx.room.compiler)

    implementation(project(":core:logging"))

    testImplementation(libs.junit)

    lintChecks(project(":tools:lint"))
}

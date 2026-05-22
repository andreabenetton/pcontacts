// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
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
    // Room + EncryptedSharedPreferences land here in a later commit (ADR-0008, ADR-0009).
    // implementation(libs.bundles.room)
    // ksp(libs.androidx.room.compiler)
    // implementation(libs.androidx.security.crypto)
    // implementation(project(":core:logging"))

    lintChecks(project(":tools:lint"))
}

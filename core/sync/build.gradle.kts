// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.pcontacts.core.sync"
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
    // SyncEngine orchestration lands here in a later commit.
    // implementation(libs.androidx.work.runtime.ktx)
    // implementation(libs.bundles.kotlinx.coroutines)
    // implementation(project(":core:proton-api"))
    // implementation(project(":core:proton-contacts"))
    // implementation(project(":core:contacts-writer"))
    // implementation(project(":core:storage"))
    // implementation(project(":core:logging"))

    lintChecks(project(":tools:lint"))
}

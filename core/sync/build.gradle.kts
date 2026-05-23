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
    implementation(libs.bundles.kotlinx.coroutines)
    implementation(project(":core:proton-api"))
    implementation(project(":core:crypto"))
    implementation(project(":core:storage"))
    implementation(project(":core:logging"))
    // implementation(project(":core:proton-contacts"))   — added when ContactSync lands.
    // implementation(project(":core:contacts-writer"))   — added when ContactSync lands.
    // implementation(libs.androidx.work.runtime.ktx)     — added when WorkManager scheduling lands.

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    lintChecks(project(":tools:lint"))
}

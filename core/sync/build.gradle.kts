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
    // HttpException for error classification in ContactWriteEngine.
    implementation(libs.retrofit)
    implementation(project(":core:crypto"))
    implementation(project(":core:storage"))
    implementation(project(":core:logging"))
    // ContactRow / RawContactOpIntent / ApplyResult appear in EmailSyncEngine's
    // public constructor surface; downstream :app callers (SyncBootstrap) need
    // them on their compile classpath → api scope.
    api(project(":core:contacts-writer"))
    // ContactProcessor / CardCryptoOp appear in the decrypt orchestrator's
    // public surface; same rationale as :core:contacts-writer above.
    api(project(":core:proton-contacts"))
    // implementation(libs.androidx.work.runtime.ktx)     — added when WorkManager scheduling lands.

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // ContactDecryptBootstrapTest builds real PGP keys inline so the
    // end-to-end test exercises actual BouncyCastle crypto.
    testImplementation(libs.bundles.bouncycastle)

    lintChecks(project(":tools:lint"))
}
